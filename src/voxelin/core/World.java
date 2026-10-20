package voxelin.core;

import voxelin.api.*;
import voxelin.api.event.*;
import voxelin.core.entity.ItemEntity;
import voxelin.core.entity.Vitals;
import voxelin.core.gen.Generator;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Світ ядра: чанки, генерація/завантаження у фонових потоках, зміни блоків з джерелом (права + ліміт за тік), тік, збереження.
 * Нічого не знає про вікно й рендер. Карту чанків змінює ЛИШЕ головний потік (у update()); фонові потоки лише читають.
 */
public final class World implements WorldAccess {
    /** Що цікавить рендер. Викликається на головному потоці. */
    public interface Listener {
        void chunkLoaded(Chunk c);
        void chunkUnloaded(Chunk c);
        void blockChanged(long x, int y, long z);
        /** Світло в секції (чанк, sy) змінилось - меш застарів. */
        default void lightChanged(Chunk c, int sy) {}
    }

    /**
     * Захист від переповнення long, а НЕ ігрова межа: чанки існують для |cx|,|cz| <= MAX_CHUNK (~2.9e17 чанків = ~4.6e18 блоків від початку).
     * Це математичний край усіх координат long; на швидкості 10 блоків/с до нього йти ~15 мільярдів років (див. docs/DESIGN.md).
     */
    public static final long MAX_CHUNK = Long.MAX_VALUE >> 5;
    public static boolean inRange(long cx, long cz) { return cx >= -MAX_CHUNK && cx <= MAX_CHUNK && cz >= -MAX_CHUNK && cz <= MAX_CHUNK; }

    public final File dir;
    public final WorldInfo info;
    public final BlockRegistry blocks;
    public final ItemRegistry itemTypes;
    public final RecipeRegistry recipes;
    private final EventBus events;
    private final Generator generator;
    private final RegionStorage storage;
    private final int[] rt2p, p2rt;
    private final ConcurrentHashMap<ChunkPos, Chunk> chunks = new ConcurrentHashMap<>();
    private final Set<ChunkPos> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Chunk> ready = new ConcurrentLinkedQueue<>();
    /** Знімки чанків, чергованих на запис: повторне завантаження бере звідси, а не зі ще не оновленого файлу. */
    private final ConcurrentHashMap<ChunkPos, Chunk> saving = new ConcurrentHashMap<>();
    private final ExecutorService genPool, io;
    private final Map<Source, int[]> budgets = new IdentityHashMap<>();
    private Listener listener;
    private final LightEngine light;
    private int batchDepth;
    private final Set<Chunk> batchChunks = Collections.newSetFromMap(new IdentityHashMap<>());
    private long tick, lastAutosave = System.currentTimeMillis();
    private volatile boolean closed;
    /** Статистика для F3 і --bench. */
    public volatile long genNanos, genCount;

    public World(File dir, WorldInfo info, BlockRegistry blocks, ItemRegistry itemTypes, RecipeRegistry recipes, EventBus events, Generator generator) throws IOException {
        this.dir = dir; this.info = info; this.blocks = blocks; this.itemTypes = itemTypes; this.recipes = recipes; this.events = events; this.generator = generator;
        storage = new RegionStorage(dir);
        light = new LightEngine(blocks, (c, sy) -> { if (listener != null) listener.lightChanged(c, sy); });
        rt2p = new int[blocks.size()];
        for (BlockType t : blocks.all()) rt2p[t.id] = info.paletteId(t.name);
        p2rt = new int[info.palette.size()];
        for (int p = 0; p < p2rt.length; p++) {
            BlockType t = blocks.byName(info.palette.get(p));
            p2rt[p] = t != null ? t.id : blocks.unknown().id;
        }
        info.save(dir); // палітра могла дописатись новими блоками
        loadItems();
        loadBlockEntities();
        // генерація детермінована й швидка (~2 мс/чанк): вистачає кількох потоків; -Dvoxelin.genThreads=N перевизначає
        int threads = Integer.getInteger("voxelin.genThreads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
        genPool = Executors.newFixedThreadPool(threads, daemon("voxelin-gen"));
        io = Executors.newSingleThreadExecutor(daemon("voxelin-io"));
    }

    static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; };
    }

    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------ час доби й здоров'я гравця

    @Override public long time() { return info.time; }
    @Override public void setTime(long t) { info.time = Math.max(0, t); }
    @Override public long dayTicks() { return DayCycle.dayTicks(info.dayMinutes); }
    public double dayPhase() { return DayCycle.phase(info.time, dayTicks()); }
    public long dayNumber() { return DayCycle.day(info.time, dayTicks()); }
    /** Яскравість неба 0.12..1 за часом доби. */
    public double skyLevel() { return DayCycle.sky(dayPhase()); }

    private final Map<Source, Vitals> vitals = new IdentityHashMap<>();
    private Vitals mainVitals;

    /** Здоров'я/голод джерела (створюється при першому зверненні; перше джерело - гравець одиночної гри - відновлюється зі збереження). */
    public Vitals vitals(Source s) {
        Vitals v = vitals.get(s);
        if (v == null) {
            v = mainVitals == null && info.hasVitals ? new Vitals(info.health, info.hunger, info.saturation) : new Vitals();
            if (v.dead()) v = new Vitals();
            v.setGod(info.mode == WorldInfo.Mode.CREATIVE);
            vitals.put(s, v);
            if (mainVitals == null) mainVitals = v;
        }
        return v;
    }

    @Override public Vitality vitality(Source s) { return s.kind() == Source.Kind.PLAYER ? vitals(s) : null; }

    // ------------------------------------------------------------------ режим гри, інвентар, предмети на землі

    @Override public String gameMode() { return info.mode.name().toLowerCase(Locale.ROOT); }

    @Override public boolean setGameMode(String mode) {
        WorldInfo.Mode m;
        switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "survival": case "виживання": case "s": case "0": m = WorldInfo.Mode.SURVIVAL; break;
            case "creative": case "творчий": case "креатив": case "c": case "1": m = WorldInfo.Mode.CREATIVE; break;
            default: return false;
        }
        info.mode = m;
        for (Vitals v : vitals.values()) v.setGod(m == WorldInfo.Mode.CREATIVE);
        return true;
    }

    public boolean creative() { return info.mode == WorldInfo.Mode.CREATIVE; }

    private final Map<Source, Inventory> inventories = new IdentityHashMap<>();
    private Inventory mainInventory;

    /** Інвентар джерела (створюється при першому зверненні; перший - гравця одиночної гри - відновлюється зі збереження). */
    public Inventory inventory(Source s) {
        Inventory inv = inventories.get(s);
        if (inv == null) {
            inv = new Inventory(itemTypes);
            if (mainInventory == null && info.hasInv) inv.decode(info.inv, info.invSelected);
            inventories.put(s, inv);
            if (mainInventory == null) mainInventory = inv;
        }
        return inv;
    }

    @Override public ItemHolder items(Source s) { return s.kind() == Source.Kind.PLAYER ? inventory(s) : null; }

    public static final int MAX_ITEMS = 512;
    private final List<ItemEntity> items = new ArrayList<>();
    private final Random itemRng = new Random();

    public List<ItemEntity> itemEntities() { return Collections.unmodifiableList(items); }

    /** Випустити предмет на землю в точці (блоки) із малим випадковим поштовхом. Понад MAX_ITEMS найстаріший зникає. */
    public ItemEntity spawnItem(Pos at, String item, int count, int damage, boolean toss) {
        if (count <= 0) return null;
        ItemEntity e = new ItemEntity(at, item, count, damage);
        if (toss) { e.vx = (itemRng.nextDouble() - 0.5) * 3; e.vz = (itemRng.nextDouble() - 0.5) * 3; e.vy = 3 + itemRng.nextDouble(); }
        items.add(e);
        if (items.size() > MAX_ITEMS) items.remove(0);
        return e;
    }

    /**
     * Зламати блок: замінити повітрям і викинути дроп (якщо dropItems). tool - предмет у руці (або null). true - блок зламано.
     * Міцність інструмента знімає той, хто викликає (вона в його інвентарі).
     */
    public boolean harvest(Source src, long x, int y, long z, ItemType tool, boolean dropItems) {
        int id = blockId(x, y, z);
        if (id <= 0 || blocks.get(id).hardness < 0) return false;
        BlockType b = blocks.get(id);
        if (!setBlock(src, x, y, z, blocks.air())) return false;
        if (dropItems) for (Inventory.Stack st : Mining.drops(b, tool, itemRng)) spawnItem(Pos.ofBlock(x, y + 0.5, z), st.item, st.count, st.damage, true);
        return true;
    }

    private void tickItems() {
        for (Iterator<ItemEntity> it = items.iterator(); it.hasNext(); ) {
            ItemEntity e = it.next();
            if (e.expired()) { it.remove(); continue; }
            e.tick(this::isSolid, this::isLiquid);
            if (e.pos.y < -30) it.remove(); // випав за межі світу
        }
        if (tick % 10 == 0 && items.size() > 1) { // злиття (раз на півсекунди)
            for (int i = 0; i < items.size(); i++) {
                ItemEntity a = items.get(i);
                for (int j = i + 1; j < items.size(); j++) {
                    ItemEntity b = items.get(j);
                    if (a.canMerge(b, itemTypes.maxStack(a.item))) { a.count += b.count; a.age = Math.min(a.age, b.age); items.remove(j--); }
                }
            }
        }
    }

    /** Підбір: усе, що лежить у радіусі від гравця (центр тіла at) і влізає в інвентар, переходить до нього. Повертає, скільки предметів підібрано. */
    public int collectItems(Pos at, Inventory inv) {
        int n = 0;
        for (Iterator<ItemEntity> it = items.iterator(); it.hasNext(); ) {
            ItemEntity e = it.next();
            if (e.pickupDelay > 0) continue;
            double dx = e.pos.relX(at.cx) - at.x, dz = e.pos.relZ(at.cz) - at.z, dy = e.pos.y - at.y;
            if (dx * dx + dz * dz + dy * dy > ItemEntity.PICKUP_RADIUS * ItemEntity.PICKUP_RADIUS) continue;
            if (!inv.canFit(e.item, e.count, e.damage)) continue;
            inv.add(e.item, e.count, e.damage);
            n += e.count;
            it.remove();
        }
        return n;
    }

    // ------------------------------------------------------------------ блок-сутності (піч)

    private final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();

    public Collection<BlockEntity> blockEntities() { return Collections.unmodifiableCollection(blockEntities.values()); }
    public BlockEntity blockEntityAt(long x, int y, long z) { return blockEntities.get(new BlockPos(x, y, z)); }

    /** Піч у цій точці: якщо блок - піч, а стану ще нема, створюється порожній. Інакше null. */
    public FurnaceEntity furnaceAt(long x, int y, long z) {
        int id = blockId(x, y, z);
        if (id <= 0 || !"furnace".equals(blocks.get(id).interact)) return null;
        BlockEntity e = blockEntities.computeIfAbsent(new BlockPos(x, y, z), k -> new FurnaceEntity(x, y, z));
        return e instanceof FurnaceEntity ? (FurnaceEntity) e : null;
    }

    private void tickBlockEntities() {
        for (Iterator<BlockEntity> it = blockEntities.values().iterator(); it.hasNext(); ) {
            BlockEntity e = it.next();
            if (!chunks.containsKey(new ChunkPos(e.x >> 4, e.z >> 4))) continue;
            int id = blockId(e.x, e.y, e.z);
            if (id < 0) continue;
            if (!e.kind().equals(blocks.get(id).interact)) { // блок зник без нашого відома (світ правили ззовні) - вміст на землю
                it.remove();
                for (Inventory.Stack st : e.contents()) spawnItem(Pos.ofBlock(e.x, e.y + 0.5, e.z), st.item, st.count, st.damage, true);
                continue;
            }
            e.tick(this);
        }
    }

    /** Блок замінено: якщо там була блок-сутність іншого виду - її вміст випадає предметами, сутність зникає. */
    private void blockEntityRemovedCheck(long x, int y, long z, BlockType now) {
        BlockEntity e = blockEntities.get(new BlockPos(x, y, z));
        if (e == null || e.kind().equals(now.interact)) return;
        blockEntities.remove(new BlockPos(x, y, z));
        for (Inventory.Stack st : e.contents()) spawnItem(Pos.ofBlock(x, y + 0.5, z), st.item, st.count, st.damage, true);
    }

    private File blockEntitiesFile() { return new File(dir, "blockentities.dat"); }

    private void saveBlockEntities() {
        try {
            StringBuilder sb = new StringBuilder();
            for (BlockEntity e : blockEntities.values()) sb.append(e.x).append(' ').append(e.y).append(' ').append(e.z).append(' ').append(e.kind()).append(' ').append(e.encode()).append('\n');
            File tmp = new File(dir, "blockentities.dat.tmp");
            java.nio.file.Files.write(tmp.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files2.replace(tmp, blockEntitiesFile());
        } catch (IOException ex) { System.out.println("[Voxelin] ⚠️ не вдалось записати блок-сутності: " + ex); }
    }

    private void loadBlockEntities() {
        File f = blockEntitiesFile();
        if (!f.isFile()) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ", 5);
                if (p.length != 5 || !p[3].equals("furnace")) continue;
                try {
                    long x = Long.parseLong(p[0]), z = Long.parseLong(p[2]);
                    int y = Integer.parseInt(p[1]);
                    if (y < 0 || y >= Chunk.HEIGHT || !inRange(x >> 4, z >> 4)) continue;
                    FurnaceEntity fe = new FurnaceEntity(x, y, z);
                    fe.decode(p[4]);
                    blockEntities.put(new BlockPos(x, y, z), fe);
                } catch (IllegalArgumentException e) { /* бита клітинка - пропускаємо */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати блок-сутності: " + e); }
    }

    private File itemsFile() { return new File(dir, "items.dat"); }

    private void saveItems() {
        try {
            StringBuilder sb = new StringBuilder();
            for (ItemEntity e : items) sb.append(e.pos.cx).append(' ').append(e.pos.cz).append(' ').append(e.pos.x).append(' ').append(e.pos.y).append(' ').append(e.pos.z).append(' ').append(e.item).append(' ').append(e.count).append(' ').append(e.damage).append('\n');
            File tmp = new File(dir, "items.dat.tmp");
            java.nio.file.Files.write(tmp.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files2.replace(tmp, itemsFile());
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати предмети: " + e); }
    }

    private void loadItems() {
        File f = itemsFile();
        if (!f.isFile()) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 8 || !p[5].matches("[a-z0-9_]+:[a-z0-9_/]+")) continue;
                try {
                    int c = Integer.parseInt(p[6]), d = Integer.parseInt(p[7]);
                    double x = Double.parseDouble(p[2]), y = Double.parseDouble(p[3]), z = Double.parseDouble(p[4]);
                    if (c < 1 || c > 64 || d < 0 || Double.isNaN(x + y + z) || Double.isInfinite(x + y + z) || x < 0 || x >= 16 || z < 0 || z >= 16 || y < -30 || y > 400) continue;
                    long cx = Long.parseLong(p[0]), cz = Long.parseLong(p[1]);
                    if (!inRange(cx, cz) || items.size() >= MAX_ITEMS) continue;
                    items.add(new ItemEntity(new Pos(cx, cz, x, y, z), p[5], c, d));
                } catch (NumberFormatException e) { /* бита клітинка */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати предмети: " + e); }
    }

    // ------------------------------------------------------------------ читання

    public Chunk chunk(long cx, long cz) { return chunks.get(new ChunkPos(cx, cz)); }
    public Collection<Chunk> loadedChunks() { return chunks.values(); }

    /** id блока; незавантажений чанк -> -1. Безпечно з будь-якого потоку. */
    public int blockId(long x, int y, long z) {
        if (y < 0 || y >= Chunk.HEIGHT) return 0;
        Chunk c = chunks.get(new ChunkPos(x >> 4, z >> 4));
        return c == null ? -1 : c.get((int) (x & 15), y, (int) (z & 15));
    }

    /** Чи є тут суцільний блок (незавантажений чанк вважається суцільним - гравець не провалиться крізь ще не готовий світ). */
    public boolean isSolid(long x, int y, long z) {
        int id = blockId(x, y, z);
        return id < 0 || blocks.get(id).solid;
    }

    @Override public int height() { return Chunk.HEIGHT; }
    @Override public BlockRef getBlock(long x, int y, long z) { int id = blockId(x, y, z); return id < 0 ? null : blocks.get(id); }
    @Override public long seed() { return info.seed; }
    @Override public String name() { return info.name; }

    // ------------------------------------------------------------------ зміни

    @Override public boolean setBlock(Source src, long x, int y, long z, BlockRef ref) {
        if (closed || y < 0 || y >= Chunk.HEIGHT || ref == null) return false;
        Chunk c = chunks.get(new ChunkPos(x >> 4, z >> 4));
        if (c == null) return false;
        BlockType nb = blocks.get(ref.id());
        if (nb != ref && blocks.byName(ref.name()) != nb) return false; // чужий BlockRef
        int[] b = budgets.computeIfAbsent(src, k -> new int[1]);
        if (b[0] >= src.blockBudgetPerTick()) return false;
        int old = c.get((int) (x & 15), y, (int) (z & 15));
        if (old == nb.id) return true;
        if (events.has(BlockChangeEvent.class)) {
            BlockChangeEvent e = events.post(new BlockChangeEvent(this, src, x, y, z, blocks.get(old), nb));
            if (e.isCancelled()) return false;
        }
        b[0]++;
        c.set((int) (x & 15), y, (int) (z & 15), nb.id);
        if (!blockEntities.isEmpty()) blockEntityRemovedCheck(x, y, z, nb);
        if (batchDepth > 0) batchChunks.add(c); else light.blockChanged(c, (int) (x & 15), y, (int) (z & 15), old, nb.id);
        if (listener != null) listener.blockChanged(x, y, z);
        return true;
    }

    private void linkNeighbors(Chunk c) {
        Chunk n;
        if ((n = chunk(c.cx + 1, c.cz)) != null) LightEngine.connect(c, 0, n);
        if ((n = chunk(c.cx - 1, c.cz)) != null) LightEngine.connect(c, 1, n);
        if ((n = chunk(c.cx, c.cz + 1)) != null) LightEngine.connect(c, 2, n);
        if ((n = chunk(c.cx, c.cz - 1)) != null) LightEngine.connect(c, 3, n);
    }

    /** Багато змін підряд без окремих оновлень світла: у кінці зачеплені чанки (і їхні сусіди) перераховуються один раз. Головний потік. */
    @Override public void batch(Runnable changes) {
        batchDepth++;
        try { changes.run(); }
        finally {
            if (--batchDepth == 0 && !batchChunks.isEmpty()) {
                List<Chunk> list = new ArrayList<>(batchChunks);
                batchChunks.clear();
                light.recompute(list);
            }
        }
    }

    // ------------------------------------------------------------------ життєвий цикл (головний потік)

    /** Довантажити чанки навколо (cx,cz) у радіусі, вивантажити дальші. Готові з фону потрапляють у світ саме тут. */
    public void update(long centerCx, long centerCz, int radius) {
        for (Chunk c; (c = ready.poll()) != null; ) {
            pending.remove(c.pos());
            if (closed) continue;
            chunks.put(c.pos(), c);
            linkNeighbors(c);
            light.reconcile(c);
            if (listener != null) listener.chunkLoaded(c);
        }
        // замовлення від найближчих до дальніх, не більше 2 черг на потік
        if (pending.size() < 16) {
            List<long[]> want = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius || !inRange(centerCx + dx, centerCz + dz)) continue;
                ChunkPos k = new ChunkPos(centerCx + dx, centerCz + dz);
                if (!chunks.containsKey(k) && !pending.contains(k)) want.add(new long[]{dx * dx + dz * dz, centerCx + dx, centerCz + dz});
            }
            want.sort(Comparator.comparingLong(a -> a[0]));
            for (long[] w : want) { if (pending.size() >= 16) break; request(w[1], w[2]); }
        }
        int keep = radius + 2;
        for (Iterator<Chunk> it = chunks.values().iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            if (Math.abs(c.cx - centerCx) <= keep && Math.abs(c.cz - centerCz) <= keep) continue;
            it.remove();
            if (listener != null) listener.chunkUnloaded(c);
            LightEngine.disconnect(c);
            batchChunks.remove(c);
            if (c.modified) saveAsync(c);
        }
    }

    private void request(long cx, long cz) {
        ChunkPos k = new ChunkPos(cx, cz);
        if (!pending.add(k)) return;
        genPool.execute(() -> {
            try {
                Chunk q = saving.get(k);
                Chunk c = q != null ? snapshot(q) : storage.load(cx, cz, p2rt);
                if (c == null) {
                    long t0 = System.nanoTime();
                    c = new Chunk(cx, cz);
                    generator.generate(c);
                    if (events.has(ChunkGeneratedEvent.class)) events.post(new ChunkGeneratedEvent(new GenAccess(c), info.seed));
                    c.modified = false; // згенерований без змін гравця - на диск не пишемо, згенерується так само
                    genNanos += System.nanoTime() - t0; genCount++;
                }
                light.initLocal(c);
                ready.add(c);
            } catch (Throwable t) {
                System.out.println("[Voxelin] ⚠️ чанк " + cx + "," + cz + ": " + t);
                pending.remove(k);
            }
        });
    }

    /** Тік гри (20/с). */
    public void tick() {
        tick++;
        info.time++;
        tickItems();
        tickBlockEntities();
        budgets.clear();
        events.post(new TickEvent(this, tick));
        if (System.currentTimeMillis() - lastAutosave > 30_000) { lastAutosave = System.currentTimeMillis(); autosave(); }
    }

    public long tickCount() { return tick; }

    public void autosave() {
        syncPlayerData();
        for (Chunk c : chunks.values()) if (c.modified) saveAsync(c);
        io.execute(this::flushQuiet);
    }

    /** Інвентар і предмети на землі -> у файли світу (автозбереження й закриття). */
    private void syncPlayerData() {
        if (mainInventory != null) {
            String[] enc = mainInventory.encode();
            System.arraycopy(enc, 0, info.inv, 0, enc.length);
            info.invSelected = mainInventory.selected(); info.hasInv = true;
        }
        saveItems();
        saveBlockEntities();
        try { info.save(dir); } catch (IOException e) { System.out.println("[Voxelin] ⚠️ " + e); }
    }

    private void saveAsync(Chunk c) {
        c.modified = false;
        Chunk copy = snapshot(c); // копія на головному потоці - фон не бачить напівзмінених масивів
        ChunkPos k = c.pos();
        saving.put(k, copy);
        io.execute(() -> { storage.save(copy, rt2p); saving.remove(k, copy); });
    }

    private static Chunk snapshot(Chunk c) {
        Chunk s = new Chunk(c.cx, c.cz);
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            Section src = c.section(sy);
            if (src == null || src.isEmpty()) continue;
            Section d = new Section();
            System.arraycopy(src.raw(), 0, d.blocks, 0, Section.VOLUME);
            d.recount();
            s.setSection(sy, d);
        }
        return s;
    }

    private void flushQuiet() {
        try { storage.flush(); } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати світ: " + e); }
    }

    /** Зберегти все й зупинити потоки. Повторний виклик нічого не робить. */
    public void close() {
        if (closed) return;
        for (Chunk c : chunks.values()) if (c.modified) saveAsync(c);
        closed = true;
        genPool.shutdownNow();
        io.execute(this::flushQuiet);
        io.shutdown();
        try { if (!io.awaitTermination(15, TimeUnit.SECONDS)) System.out.println("[Voxelin] ⚠️ збереження не завершилось за 15 с"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        syncPlayerData();
        if (mainVitals != null) { Vitals mv = mainVitals; info.hasVitals = true; info.health = mv.dead() ? 20 : mv.health(); info.hunger = mv.dead() ? 20 : mv.hunger(); info.saturation = mv.dead() ? 5 : mv.saturation(); }
        try { info.save(dir); } catch (IOException e) { System.out.println("[Voxelin] ⚠️ " + e); }
        chunks.clear();
    }

    public int surfaceY(long x, long z) { return generator.surfaceY(x, z); }
    public String biomeAt(long x, long z) { return generator.biomeAt(x, z); }

    /**
     * Найближча до (x,z) колонка суші (поверхня вище моря, не пляж/вода) - кільцями по 16 блоків до 2048; не знайдено - сама (x,z). Лише генератор,
     * чанки не потрібні, тож можна викликати до завантаження світу (визначити, що завантажувати для місця появи).
     */
    public long[] findLand(long x, long z) {
        int sea = generator.seaLevel();
        if (generator.surfaceY(x, z) > sea + 1) return new long[]{x, z};
        for (int r = 16; r <= 2048; r += 16) {
            for (int i = -r; i <= r; i += 16) {
                long[][] cand = {{x + i, z - r}, {x + i, z + r}, {x - r, z + i}, {x + r, z + i}};
                for (long[] c : cand) if (generator.surfaceY(c[0], c[1]) > sea + 1) return c;
            }
        }
        return new long[]{x, z};
    }
    public int seaLevel() { return generator.seaLevel(); }

    /** Чи рідина в точці (вода): гравець у ній пливе. Незавантажене - ні. */
    public boolean isLiquid(long x, int y, long z) { int id = blockId(x, y, z); return id > 0 && blocks.get(id).liquid; }

    /** Доступ до чанка під час генерації для модів (ChunkGeneratedEvent). */
    private final class GenAccess implements ChunkData {
        final Chunk c;
        GenAccess(Chunk c) { this.c = c; }
        public long chunkX() { return c.cx; }
        public long chunkZ() { return c.cz; }
        public int height() { return Chunk.HEIGHT; }
        public BlockRef get(int x, int y, int z) { return blocks.get(c.get(x & 15, y, z & 15)); }
        public void set(int x, int y, int z, BlockRef b) { if (x >= 0 && x < 16 && z >= 0 && z < 16 && b != null) c.set(x, y, z, blocks.get(b.id()).id); }
    }
}
