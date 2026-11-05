package voxelin.core;

import voxelin.api.*;
import voxelin.api.event.*;
import voxelin.core.entity.ItemEntity;
import voxelin.core.entity.Mob;
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
    public final MobRegistry mobTypes;
    public final LootRegistry loot;
    public final RecordRegistry records;
    private final EventBus events;
    private final Generator generator;
    public Generator generator() { return generator; }
    private final RegionStorage storage;
    private final int[] rt2p, p2rt;
    private final ConcurrentHashMap<ChunkPos, Chunk> chunks = new ConcurrentHashMap<>();
    /**
     * Замок світу. Клієнт тікає світ в окремому потоці (PlayScreen.worldLoop), а гравець, ввід і малювання істот - у головному; хто міняє
     * чи читає стан світу (крім блоків чанків - їх і так читають потоки мешингу), тримає цей замок. Справедливий: головний потік не голодує.
     * Тести й однопотокові програми можуть його не брати.
     */
    public final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock(true);
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

    public World(File dir, WorldInfo info, BlockRegistry blocks, ItemRegistry itemTypes, RecipeRegistry recipes, MobRegistry mobTypes, LootRegistry loot, RecordRegistry records, EventBus events, Generator generator) throws IOException {
        this.dir = dir; this.info = info; this.blocks = blocks; this.itemTypes = itemTypes; this.recipes = recipes; this.mobTypes = mobTypes; this.loot = loot; this.records = records; this.events = events; this.generator = generator;
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
        loadMobs();
        infection = new Infection(this);
        boards = new Boards(this);
        arrows = new Arrows(this);
        loadMarks();
        if (info.type == WorldInfo.Type.END) naturalSpawning = false;
        // генерація детермінована й швидка (~2 мс/чанк): вистачає кількох потоків; -Dvoxelin.genThreads=N перевизначає
        int threads = Integer.getInteger("voxelin.genThreads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
        genPool = Executors.newFixedThreadPool(threads, daemon("voxelin-gen"));
        io = Executors.newSingleThreadExecutor(daemon("voxelin-io"));
    }

    static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; };
    }

    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------ мережа: дзеркало світу на гості, спостерігачі й центри гостей на хості

    /** Звідки дзеркало (світ гостя) бере чанки: замість диска й генератора - запит хосту. false - зараз не просити (спробувати пізніше). */
    public interface ChunkFeed { boolean request(long cx, long cz); }
    /** Хтось (сервер мережевої гри) хоче знати про кожну зміну блока. Головний потік. */
    public interface BlockWatcher { void changed(long x, int y, long z); }
    private volatile ChunkFeed feed;
    private final List<BlockWatcher> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Додаткові центри завантаження {cx, cz, радіус} - гості мережевої гри; світ тримає чанки й довкола них. */
    private volatile List<long[]> extraCenters = List.of();

    /** Зробити світ дзеркалом: чанки лише з feed (хост), без генерації й диска. */
    public void setFeed(ChunkFeed f) { feed = f; }
    /** Дзеркало чужого світу (гра по мережі): не тікає, не зберігається, блоки міняє хост. */
    public boolean remote() { return feed != null; }
    public void addWatcher(BlockWatcher w) { watchers.add(w); }
    public void removeWatcher(BlockWatcher w) { watchers.remove(w); }
    public void setExtraCenters(List<long[]> centers) { extraCenters = List.copyOf(centers); }

    /** Дані чанка від хоста (формат RegionStorage, номери палітри хоста = палітра цього світу). Розпаковка й світло - у фоні. */
    public void deliver(long cx, long cz, byte[] data) {
        ChunkPos k = new ChunkPos(cx, cz);
        if (closed || !pending.contains(k)) return; // не просили (чи вже вивантажили) - ігноруємо
        genPool.execute(() -> {
            try {
                Chunk c = RegionStorage.decode(cx, cz, data, p2rt);
                light.initLocal(c);
                ready.add(c);
            } catch (Throwable t) {
                System.out.println("[Voxelin] ⚠️ чанк від хоста " + cx + "," + cz + ": " + t);
                pending.remove(k);
            }
        });
    }

    /** Хост не дав чанк (далеко) - можна попросити знову. */
    public void undeliver(long cx, long cz) { pending.remove(new ChunkPos(cx, cz)); }

    /** Блок від хоста (номер палітри): без подій, бюджетів і реакцій - лише стан, світло й перемальовування. */
    public void setRemote(long x, int y, long z, int pal) {
        if (closed || y < 0 || y >= Chunk.HEIGHT) return;
        Chunk c = chunks.get(new ChunkPos(x >> 4, z >> 4));
        if (c == null) return;
        int id = pal >= 0 && pal < p2rt.length ? p2rt[pal] : blocks.unknown().id;
        int old = c.get((int) (x & 15), y, (int) (z & 15));
        if (old == id) return;
        c.set((int) (x & 15), y, (int) (z & 15), id);
        c.modified = false;
        light.blockChanged(c, (int) (x & 15), y, (int) (z & 15), old, id);
        if (listener != null) listener.blockChanged(x, y, z);
    }

    /** Чанк у мережевий формат (номери палітри цього світу). Головний потік. */
    public byte[] encodeChunk(Chunk c) { return RegionStorage.encode(c, rt2p); }
    /** Номер палітри світу для id блока цього запуску. */
    public int paletteOf(int id) { return id >= 0 && id < rt2p.length ? rt2p[id] : 0; }

    private void notifyWatchers(long x, int y, long z) { for (BlockWatcher w : watchers) w.changed(x, y, z); }

    // ------------------------------------------------------------------ час доби й здоров'я гравця

    @Override public long time() { return info.time; }
    @Override public void setTime(long t) { info.time = Math.max(0, t); }
    @Override public long dayTicks() { return DayCycle.dayTicks(info.dayMinutes); }
    /** Час доби = ігровий час + зсув: коли зміну дня вимкнено, зсув тримає сонце на місці, а ігровий час (зараження, визрівання) іде далі. */
    public double dayPhase() { return DayCycle.phase(info.time + info.dayShift, dayTicks()); }
    public long dayNumber() { return DayCycle.day(info.time + info.dayShift, dayTicks()); }
    public boolean rule(String id) { return info.rule(id); }
    /** Яскравість неба 0.12..1 за часом доби; в Енді стала. */
    public double skyLevel() { return info.type == WorldInfo.Type.END ? END_SKY : info.type == WorldInfo.Type.BETWEEN ? BETWEEN_SKY : info.type == WorldInfo.Type.NETHER ? 0 : DayCycle.sky(dayPhase()); }
    public static final double END_SKY = 0.35, BETWEEN_SKY = 0.6;
    /** "overworld", "end" або "between". */
    public String dimension() { return Dimensions.of(info.type); }

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
            if (mainInventory == null && info.hasInv) { inv.decode(info.inv, info.invSelected); inv.decodeArmor(info.armor); }
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
        int boardCount = boards.isBoards(b) ? Long.bitCount(boards.mask(x, y, z)) : 0;
        if (!setBlock(src, x, y, z, blocks.air())) return false;
        if (dropItems && boardCount > 0) spawnItem(Pos.ofBlock(x, y + 0.5, z), boardItem(b), boardCount, 0, true); // уся клітинка дошок - усі дошки
        if (dropItems) for (Inventory.Stack st : Mining.drops(b, tool, itemRng)) spawnItem(Pos.ofBlock(x, y + 0.5, z), st.item, st.count, st.damage, true);
        return true;
    }

    private void tickItems() {
        for (Iterator<ItemEntity> it = items.iterator(); it.hasNext(); ) {
            ItemEntity e = it.next();
            if (e.expired()) { it.remove(); continue; }
            e.tick(solidView, this::isLiquid);
            if (e.pos.y < -30 || (isLava(e.pos.blockX(), e.pos.blockY(), e.pos.blockZ()) && !fireproof(e.item))) it.remove(); // випав за межі світу чи згорів у лаві (незерит не горить)
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

    // ------------------------------------------------------------------ істоти

    public static final int MAX_MOBS = 256;
    private final List<Mob> mobs = new ArrayList<>();
    private final Random mobRng = new Random();
    private final MobSpawner spawner = new MobSpawner();
    private PlayerTarget target;
    /** Зараження (гілка розвилки яйця). */
    public Infection infection;
    /** Стріли в польоті й застряглі. */
    public Arrows arrows;
    /** Справжні дошки: що саме зайнято в клітинках «*_boards». */
    public Boards boards;

    /** Суцільність для фізики (гравець, істоти, предмети): клітинки й коробки неповних блоків. */
    public final voxelin.core.entity.PlayerEntity.Solid solidView = new voxelin.core.entity.PlayerEntity.Solid() {
        public boolean at(long x, int y, long z) { return isSolid(x, y, z); }
        public double[] boxes(long x, int y, long z) { return World.this.boxes(x, y, z); }
    };

    /** Колізійні коробки клітинки в частках блока (по 6 чисел) або null - повний куб (якщо блок суцільний). */
    public double[] boxes(long x, int y, long z) {
        int id = blockId(x, y, z);
        if (id <= 0) return null;
        BlockType b = blocks.get(id);
        if (b.shape == null) return null;
        if (boards != null && boards.isBoards(b)) return Boards.boxes(boards.mask(x, y, z));
        return SHAPES.get(b.shape);
    }

    /** Коробки фігурних блоків (частки блока): смолоскип - паличка, стіл - стільниця й чотири ніжки. */
    public static final Map<String, double[]> SHAPES = Map.of(
            "torch", new double[]{7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 10 / 16.0, 9 / 16.0},
            "table", new double[]{0, 12 / 16.0, 0, 1, 1, 1,
                    1 / 16.0, 0, 1 / 16.0, 4 / 16.0, 12 / 16.0, 4 / 16.0, 12 / 16.0, 0, 1 / 16.0, 15 / 16.0, 12 / 16.0, 4 / 16.0,
                    1 / 16.0, 0, 12 / 16.0, 4 / 16.0, 12 / 16.0, 15 / 16.0, 12 / 16.0, 0, 12 / 16.0, 15 / 16.0, 12 / 16.0, 15 / 16.0});

    /** Вміст клітинки змінився без зміни блока (дошки): перебудувати геометрію. */
    void changed(long x, int y, long z) { Chunk c = chunks.get(new ChunkPos(x >> 4, z >> 4)); if (c != null) c.modified = true; if (listener != null) listener.blockChanged(x, y, z); if (!watchers.isEmpty()) notifyWatchers(x, y, z); }

    /** Предмет дошки для блока-клітинки дошок: voxelin:oak_boards -> voxelin:oak_board. */
    public static String boardItem(BlockType b) { return b.name.endsWith("_boards") ? b.name.substring(0, b.name.length() - 1) : b.name; }

    /** Зламати одну дошку (біт маски) і, якщо треба, кинути її предметом. */
    public boolean harvestBoard(Source src, long x, int y, long z, int bit, boolean dropItems) {
        int id = blockId(x, y, z);
        if (id <= 0 || !boards.isBoards(blocks.get(id))) return false;
        BlockType b = blocks.get(id);
        double[] bx = Boards.boxes(boards.mask(x, y, z) & 1L << bit);
        if (bx.length == 0 || !boards.remove(src, x, y, z, bit)) return false;
        if (dropItems) spawnItem(new Pos(0, 0, x + (bx[0] + bx[3]) / 2, y + (bx[1] + bx[4]) / 2, z + (bx[2] + bx[5]) / 2), boardItem(b), 1, 0, true);
        return true;
    }
    /** Вимкнути природний спавн (тести, /gamerule пізніше). */
    public boolean naturalSpawning = true;
    /** Куди йдуть звукові події (клієнт ставить свій рушій; на сервері й у тестах - тиша). */
    public SoundSink sound = SoundSink.NONE;

    /** Звук істоти: voxelin:zombie + "hurt" -> voxelin:mob.zombie.hurt (клієнт сам підставляє загальний, якщо такого нема). */
    public void mobSound(Mob m, String what, float volume) {
        String n = m.type.name; int c = n.indexOf(':');
        sound.play(n.substring(0, c + 1) + "mob." + n.substring(c + 1) + "." + what, new Pos(m.pos.cx, m.pos.cz, m.pos.x, m.pos.y + m.type.height * 0.7, m.pos.z), volume, 0.9f + mobRng.nextFloat() * 0.2f);
    }

    public List<Mob> mobs() { return Collections.unmodifiableList(mobs); }
    public void setPlayerTarget(PlayerTarget t) { target = t; }
    /** Гравець для ШІ, стріл і спавнера (може бути null). */
    public PlayerTarget playerTarget() { return target; }

    /** Інші гравці-цілі (гості мережевої гри у виживанні): істоти й стріли б'ють найближчого. */
    private final List<PlayerTarget> extraTargets = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addTarget(PlayerTarget t) { if (t != null && !extraTargets.contains(t)) extraTargets.add(t); }
    public void removeTarget(PlayerTarget t) { extraTargets.remove(t); }

    /** Усі гравці: хост (якщо є) і гості. */
    public List<PlayerTarget> targets() {
        if (extraTargets.isEmpty()) return target == null ? List.of() : List.of(target);
        List<PlayerTarget> l = new ArrayList<>(1 + extraTargets.size());
        if (target != null) l.add(target);
        l.addAll(extraTargets);
        return l;
    }

    /** Ціль для істоти в точці at: найближчий живий гравець не в творчому; нікого - головний гравець (як в одиночній грі). */
    public PlayerTarget targetFor(Pos at) {
        if (extraTargets.isEmpty()) return target;
        PlayerTarget best = null;
        double bd = Double.MAX_VALUE;
        for (PlayerTarget t : targets()) {
            if (!t.alive() || t.creative()) continue;
            double d = dist2(at, t.body());
            if (d < bd) { bd = d; best = t; }
        }
        return best != null ? best : target;
    }

    /** Квадрат горизонтальної відстані (далекі чанки - «дуже далеко», без переповнення). */
    private static double dist2(Pos a, Pos b) {
        if (Math.abs(a.cx - b.cx) > 1024 || Math.abs(a.cz - b.cz) > 1024) return Double.MAX_VALUE;
        double dx = a.relX(b.cx) - b.x, dz = a.relZ(b.cz) - b.z;
        return dx * dx + dz * dz;
    }
    public Random mobRandom() { return mobRng; }
    public MobSpawner spawner() { return spawner; }

    /** Створити істоту; null - забагато істот у світі. */
    public Mob spawnMob(MobType type, Pos at) {
        if (mobs.size() >= MAX_MOBS) return null;
        Mob m = new Mob(type, at);
        m.yaw = mobRng.nextFloat() * 360f;
        if (type.homeRadius > 0) m.home = at.copy();
        mobs.add(m);
        return m;
    }

    /** Відкрити запис журналу (повна назва, напр. voxelin:dragon). true - щойно відкрито (гравець бачить повідомлення). */
    public void notifyPlayer(String message) { if (target != null) target.notify(message); }

    public boolean unlockRecord(String id) {
        RecordRegistry.Record r = records.get(id);
        if (r == null || !info.records.add(id)) return false;
        if (target != null) target.notify("Новий запис у журналі: " + r.title + "  (J)");
        sound.play("voxelin:record.unlock", null, 0.8f, 1f);
        return true;
    }

    /** Сторож Шва: у Енді після смерті дракона біля яйця, один (невразливий). true - створено. */
    public boolean spawnKeeperIfNeeded() {
        if (info.type != WorldInfo.Type.END || !info.dragonDead) return false;
        MobType t = mobTypes.get("voxelin:seam_keeper");
        if (t == null) return false;
        for (Mob m : mobs) if (m.type == t) return false;
        Mob k = spawnMob(t, Pos.ofBlock(4, voxelin.core.gen.EndGenerator.TOP + 1, 0));
        if (k == null) return false;
        k.keep = true;
        return true;
    }

    /** Наповнити Енд при вході: дракон (якщо ще живий) або Сторож (якщо дракона вбито). */
    public void populateEnd() { if (!spawnDragonIfNeeded()) spawnKeeperIfNeeded(); spawnRitualBossesIfNeeded(); }

    public static final String WEAVER = "voxelin:seam_weaver", UNRAVELER = "voxelin:seam_unraveler";

    /** Ритуал (стадія 1): Ткач і Розплітач над островом - ті, хто ще не впав і кого ще нема. Повертає, скільки створено. */
    public int spawnRitualBossesIfNeeded() {
        if (info.type != WorldInfo.Type.END || info.ritualStage != 1) return 0;
        int n = 0;
        String[] ids = {WEAVER, UNRAVELER};
        for (int k = 0; k < 2; k++) {
            if ((info.ritualKills & (1 << k)) != 0) continue;
            MobType t = mobTypes.get(ids[k]);
            if (t == null) continue;
            boolean present = false;
            for (Mob m : mobs) if (m.type == t && !m.dying) present = true;
            if (present) continue;
            Mob b = spawnMob(t, Pos.ofBlock(k == 0 ? -20 : 20, voxelin.core.gen.EndGenerator.TOP + 20, 0));
            if (b == null) continue;
            b.home = Pos.ofBlock(0, voxelin.core.gen.EndGenerator.TOP, 0);
            b.keep = true;
            b.orbitOffset(k * Math.PI);
            n++;
        }
        return n;
    }

    /** Смерть боса Ритуалу: напарник лютує; обидва впали - Ритуал звершено. */
    private void ritualBossDied(Mob dead) {
        int bit = dead.type.name.equals(WEAVER) ? 1 : 2;
        info.ritualKills |= bit;
        if (info.ritualKills == 3) {
            if (info.ritualStage == 1) {
                info.ritualStage = 2;
                unlockRecord("voxelin:ritual_done");
                Pos at = dead.home != null ? new Pos(dead.home.cx, dead.home.cz, dead.home.x, dead.home.y + 2, dead.home.z) : dead.pos.copy();
                spawnItem(at, "voxelin:seam_heart", 1, 0, true);
                spawnItem(at, Inventory.WINGS, 1, 0, true); // крила - нагорода Ритуалу
                if (target != null) target.notify("Ритуал звершено. Шов запечатано.");
            }
            return;
        }
        for (Mob m : mobs) if (!m.dying && m != dead && (m.type.name.equals(WEAVER) || m.type.name.equals(UNRAVELER))) {
            m.enraged = true;
            if (target != null) target.notify(m.type.title + " лютує!");
        }
    }

    /** Дракон Краю: створити над островом, якщо ще не вбитий і його немає. true - створено. */
    public boolean spawnDragonIfNeeded() {
        if (info.type != WorldInfo.Type.END || info.dragonDead) return false;
        MobType t = mobTypes.get("voxelin:ender_dragon");
        if (t == null) return false;
        for (Mob m : mobs) if (m.type == t && !m.dying) return false;
        Mob d = spawnMob(t, Pos.ofBlock(0, voxelin.core.gen.EndGenerator.TOP + 24, 0));
        if (d == null) return false;
        d.home = Pos.ofBlock(0, voxelin.core.gen.EndGenerator.TOP, 0);
        d.keep = true;
        return true;
    }

    /** Для команд: поставити істоту біля блока (x,y,z), піднявши її, якщо там твердо (до 5 блоків). */
    public Mob summon(MobType type, long x, int y, long z) {
        int yy = y;
        for (int i = 0; i < 6 && (isSolid(x, yy, z) || isSolid(x, yy + 1, z)); i++) yy++;
        if (blockId(x, yy, z) < 0) return null; // чанк не завантажено
        return spawnMob(type, Pos.ofBlock(x, yy, z));
    }

    /** Прибрати всіх істот (type == null) або лише цього виду, без дропу. Повертає, скільки. */
    /** Прибрати істоту без смерті й дропу (дзеркало мережевої гри: хост її більше не показує). */
    public boolean removeMob(Mob m) { return mobs.remove(m); }

    public int killAll(MobType type) {
        int n = 0;
        for (Iterator<Mob> it = mobs.iterator(); it.hasNext(); ) { Mob m = it.next(); if (type == null || m.type == type) { it.remove(); n++; } }
        return n;
    }

    /** Удар по істоті (шкода, поштовх від (kx,kz)); дроп - у момент смерті. */
    public boolean hurtMob(Mob m, double damage, double kx, double kz, Pos from) {
        boolean ok = m.damage(damage, kx, kz, from);
        if (ok) mobSound(m, "hurt", 1f);
        if (ok && m.dying) dropLoot(m);
        return ok;
    }

    private void dropLoot(Mob m) {
        if (m.lootDropped) return;
        m.lootDropped = true;
        mobSound(m, "death", m.type.boss ? 1.5f : 0.8f);
        if (m.type.boss && m.type.name.equals("voxelin:ender_dragon")) { info.dragonDead = true; unlockRecord("voxelin:dragon"); keeperPending = true; } // смерть дракона записується у світ (не відроджується)
        if (m.type.name.equals(WEAVER) || m.type.name.equals(UNRAVELER)) ritualBossDied(m);
        for (voxelin.api.BlockSettings.Drop d : m.type.drops) {
            if (!m.type.boss && !info.rule(GameRules.MOB_LOOT)) break; // сюжетна здобич босів падає завжди - інакше гру не пройти
            if (d.chance < 1f && mobRng.nextFloat() >= d.chance) continue;
            int n = d.min + (d.max > d.min ? mobRng.nextInt(d.max - d.min + 1) : 0);
            Pos at = m.type.boss && m.home != null ? new Pos(m.home.cx, m.home.cz, m.home.x, m.home.y + 2, m.home.z) : new Pos(m.pos.cx, m.pos.cz, m.pos.x, m.pos.y + m.type.height / 2, m.pos.z); // здобич боса - на землі біля дому, а не в повітрі
            if (n > 0) spawnItem(at, d.item, n, 0, true);
        }
    }

    private boolean keeperPending;

    private void tickMobs() {
        if (naturalSpawning && info.rule(GameRules.MOB_SPAWNING)) {
            spawner.tick(this, target, mobRng, tick);
            for (PlayerTarget t : extraTargets) if (t.alive() && !t.creative()) spawner.tick(this, t, mobRng, tick); // і довкола гостей
        }
        Pos pc = target == null ? null : target.body();
        List<Pos> others = new ArrayList<>();
        for (PlayerTarget t : extraTargets) others.add(t.body());
        for (Iterator<Mob> it = mobs.iterator(); it.hasNext(); ) {
            Mob m = it.next();
            if (Math.floorMod(tick + m.hashCode(), 10) == 0) pickPrey(m);
            m.tick(this, targetFor(m.pos), mobRng);
            if (!m.dying && !m.type.fireproof && tick % 10 == 0 && isLava(m.pos.blockX(), m.pos.blockY(), m.pos.blockZ())) hurtMob(m, 4, 0, 0, m.pos); // лава
            if (!m.dying && mobRng.nextInt(m.type.boss ? 160 : 480) == 0) mobSound(m, "idle", m.type.boss ? 1.5f : 0.8f); // голоси - зрідка, у випадковий момент
            if (!m.dying && m.type.flying && Math.floorMod(tick + m.hashCode(), 30) == 0) mobSound(m, "flap", 1f);
            if (m.dying && !m.lootDropped) dropLoot(m);
            if (m.removable()) { it.remove(); continue; }
            if (pc != null) { // далекі зникають (вороги ближче, ніж мирні)
                double d2 = dist2(m.pos, pc);
                for (Pos o : others) d2 = Math.min(d2, dist2(m.pos, o)); // біля гостя теж не зникають
                if (naturalSpawning && !m.keep && !m.type.persistent && ((m.type.hostile() && d2 > 80 * 80) || d2 > 128 * 128)) it.remove();
            }
        }
        if (keeperPending) { keeperPending = false; spawnKeeperIfNeeded(); }
    }

    /**
     * Цілі між істотами: Вартовий - найближчий ворог у 16 блоках; ворог - житель чи Вартовий у межах виявлення; житель бачить ворога
     * в 8 блоках - тікає. Бос і літуни в цьому не беруть участі.
     */
    private void pickPrey(Mob m) {
        if (m.dying || m.type.boss || m.type.flying) return;
        boolean guard = m.type.guard(), hostile = m.type.hostile(), villager = m.type.villager();
        if (!guard && !hostile && !villager) return;
        double range = guard ? 16 : villager ? 8 : m.type.detect, best = range * range;
        Mob pick = null;
        for (Mob o : mobs) {
            if (o == m || o.dying || o.type.boss || o.type.flying) continue;
            boolean fit = guard || villager ? o.type.hostile() : o.type.villager() || o.type.guard();
            if (!fit) continue;
            double dx = o.pos.relX(m.pos.cx) - m.pos.x, dz = o.pos.relZ(m.pos.cz) - m.pos.z, dy = o.pos.y - m.pos.y, d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < best) { best = d2; pick = o; }
        }
        if (villager) { if (pick != null) m.fleeFrom(pick.pos, 40); }
        else m.prey = pick;
    }

    /** Відмітки вже створених мобів зі структур (щоб повторна генерація чанка не відроджувала жителів). */
    private final Set<Long> marks = new HashSet<>();

    private File marksFile() { return new File(dir, "marks.dat"); }

    private void saveMarks() {
        try {
            StringBuilder sb = new StringBuilder();
            for (long k : marks) sb.append(k).append('\n');
            writeLater(marksFile(), sb.toString());
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати відмітки: " + e); }
    }

    private void loadMarks() {
        File f = marksFile();
        if (!f.isFile()) return;
        try { for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) try { marks.add(Long.parseLong(line.trim())); } catch (NumberFormatException e) { /* бита клітинка */ } }
        catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати відмітки: " + e); }
    }

    private File mobsFile() { return new File(dir, "mobs.dat"); }

    private void saveMobs() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Mob m : mobs) if ((m.type.passive() || m.type.persistent || m.keep) && !m.dying) {
                sb.append(m.type.name).append(' ').append(m.pos.cx).append(' ').append(m.pos.cz).append(' ').append(m.pos.x).append(' ').append(m.pos.y).append(' ').append(m.pos.z).append(' ').append(m.health).append(' ').append(m.yaw);
                if (m.home != null) sb.append(' ').append(m.home.cx).append(' ').append(m.home.cz).append(' ').append(m.home.x).append(' ').append(m.home.y).append(' ').append(m.home.z);
                if (m.keep) sb.append(' ').append(1);
                sb.append('\n');
            }
            writeLater(mobsFile(), sb.toString());
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати істот: " + e); }
    }

    private void loadMobs() {
        File f = mobsFile();
        if (!f.isFile()) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 8 && p.length != 9 && p.length != 13 && p.length != 14) continue;
                MobType t = mobTypes.get(p[0]);
                if (t == null || mobs.size() >= 200) continue;
                try {
                    long cx = Long.parseLong(p[1]), cz = Long.parseLong(p[2]);
                    double x = Double.parseDouble(p[3]), y = Double.parseDouble(p[4]), z = Double.parseDouble(p[5]), hp = Double.parseDouble(p[6]);
                    float yaw = Float.parseFloat(p[7]);
                    if (!inRange(cx, cz) || Double.isNaN(x + y + z + hp + yaw) || Double.isInfinite(x + y + z + hp + yaw) || x < 0 || x >= 16 || z < 0 || z >= 16 || y < -30 || y > 400 || hp <= 0) continue;
                    Mob m = new Mob(t, new Pos(cx, cz, x, y, z));
                    m.health = Math.min(hp, t.health); m.yaw = yaw;
                    boolean hasHome = p.length >= 13;
                    if (p.length == 9 || p.length == 14) m.keep = "1".equals(p[p.length - 1]);
                    if (hasHome) {
                        long hcx = Long.parseLong(p[8]), hcz = Long.parseLong(p[9]);
                        double hx = Double.parseDouble(p[10]), hy = Double.parseDouble(p[11]), hz = Double.parseDouble(p[12]);
                        if (!inRange(hcx, hcz) || Double.isNaN(hx + hy + hz) || Double.isInfinite(hx + hy + hz) || hx < 0 || hx >= 16 || hz < 0 || hz >= 16 || hy < -30 || hy > 400) continue;
                        m.home = new Pos(hcx, hcz, hx, hy, hz);
                    } else if (t.homeRadius > 0) m.home = m.pos.copy();
                    mobs.add(m);
                } catch (NumberFormatException e) { /* бита клітинка */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати істот: " + e); }
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

    /** Скриня в цій точці (створюється порожньою, якщо блок - скриня, а стану ще нема). */
    public ChestEntity chestAt(long x, int y, long z) {
        int id = blockId(x, y, z);
        if (id <= 0 || !"chest".equals(blocks.get(id).interact)) return null;
        BlockEntity e = blockEntities.computeIfAbsent(new BlockPos(x, y, z), k -> new ChestEntity(x, y, z));
        return e instanceof ChestEntity ? (ChestEntity) e : null;
    }

    /** Забрати блок-сутність зі світу (напр. перед тим, як гість зламає скриню й отримає вміст сам); null - нема. */
    public BlockEntity detachBlockEntity(long x, int y, long z) { return blockEntities.remove(new BlockPos(x, y, z)); }

    /** Повернути забрану сутність (злам не вдався). */
    public void attachBlockEntity(BlockEntity e) { blockEntities.putIfAbsent(new BlockPos(e.x, e.y, e.z), e); }

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
            writeLater(blockEntitiesFile(), sb.toString());
        } catch (IOException ex) { System.out.println("[Voxelin] ⚠️ не вдалось записати блок-сутності: " + ex); }
    }

    private void loadBlockEntities() {
        File f = blockEntitiesFile();
        if (!f.isFile()) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ", 5);
                if (p.length != 5 || !(p[3].equals("furnace") || p[3].equals("chest"))) continue;
                try {
                    long x = Long.parseLong(p[0]), z = Long.parseLong(p[2]);
                    int y = Integer.parseInt(p[1]);
                    if (y < 0 || y >= Chunk.HEIGHT || !inRange(x >> 4, z >> 4)) continue;
                    BlockEntity be = p[3].equals("furnace") ? new FurnaceEntity(x, y, z) : new ChestEntity(x, y, z);
                    be.decode(p[4]);
                    blockEntities.put(new BlockPos(x, y, z), be);
                } catch (IllegalArgumentException e) { /* бита клітинка - пропускаємо */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати блок-сутності: " + e); }
    }

    private File itemsFile() { return new File(dir, "items.dat"); }

    private void saveItems() {
        try {
            StringBuilder sb = new StringBuilder();
            for (ItemEntity e : items) sb.append(e.pos.cx).append(' ').append(e.pos.cz).append(' ').append(e.pos.x).append(' ').append(e.pos.y).append(' ').append(e.pos.z).append(' ').append(e.item).append(' ').append(e.count).append(' ').append(e.damage).append('\n');
            writeLater(itemsFile(), sb.toString());
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
    /** Чи нема суцільних блоків на прямій від a до b (крок 0.25 блока; клітинки самих кінців не рахуються). Для стрільців. */
    public boolean lineOfSight(Pos a, Pos b) {
        double dx = b.relX(a.cx) - a.x, dy = b.y - a.y, dz = b.relZ(a.cz) - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len < 256)) return false;
        int steps = (int) Math.ceil(len / 0.25);
        Pos p = a.copy();
        long ax = a.blockX(), az = a.blockZ(), bx = b.blockX(), bz = b.blockZ(); int ay = a.blockY(), by = b.blockY();
        for (int i = 1; i < steps; i++) {
            p.add(dx / steps, dy / steps, dz / steps);
            long x = p.blockX(), z = p.blockZ(); int y = p.blockY();
            if ((x == ax && y == ay && z == az) || (x == bx && y == by && z == bz)) continue;
            if (isSolid(x, y, z)) return false;
        }
        return true;
    }

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
        boolean infect = infection != null && infection.shouldStart(nb) && blocks.byName(Infection.CORE) != null;
        if (infect) nb = blocks.byName(Infection.CORE); // яйце пускає корені
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
        if (!watchers.isEmpty()) notifyWatchers(x, y, z);
        if (boards != null && old != nb.id && boards.isBoards(blocks.get(old))) boards.cleared(x, y, z);
        if (infect) infection.start(x, y, z);
        if (nb.name.equals(Guard.HEAD) || nb.name.equals(Guard.IRON)) Guard.tryBuild(this, x, y, z); // Т із залізних блоків + лампа = Вартовий
        NetherPortal.changed(this, x, y, z, blocks.get(old), nb); // зламали рамку чи портал - гасне весь
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
            processPending(c);
            if (listener != null) listener.chunkLoaded(c);
        }
        // замовлення від найближчих до дальніх, не більше 2 черг на потік; центри: гравець і гості мережевої гри
        List<long[]> centers = new ArrayList<>(1 + extraCenters.size());
        centers.add(new long[]{centerCx, centerCz, radius});
        centers.addAll(extraCenters);
        if (pending.size() < 16) {
            List<long[]> want = new ArrayList<>();
            Set<ChunkPos> seen = new HashSet<>();
            for (long[] ce : centers) {
                int r = (int) ce[2];
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + r || !inRange(ce[0] + dx, ce[1] + dz)) continue;
                    ChunkPos k = new ChunkPos(ce[0] + dx, ce[1] + dz);
                    if (!chunks.containsKey(k) && !pending.contains(k) && seen.add(k)) want.add(new long[]{dx * dx + dz * dz, ce[0] + dx, ce[1] + dz});
                }
            }
            want.sort(Comparator.comparingLong(a -> a[0]));
            for (long[] w : want) { if (pending.size() >= 16) break; request(w[1], w[2]); }
        }
        for (Iterator<Chunk> it = chunks.values().iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            boolean near = false;
            for (long[] ce : centers) if (Math.abs(c.cx - ce[0]) <= ce[2] + 2 && Math.abs(c.cz - ce[1]) <= ce[2] + 2) { near = true; break; }
            if (near) continue;
            it.remove();
            if (listener != null) listener.chunkUnloaded(c);
            LightEngine.disconnect(c);
            batchChunks.remove(c);
            if (c.modified) saveAsync(c);
        }
    }

    private void request(long cx, long cz) {
        ChunkPos k = new ChunkPos(cx, cz);
        ChunkFeed f = feed;
        if (f != null) { if (!pending.contains(k) && f.request(cx, cz)) pending.add(k); return; } // дзеркало: чанк надішле хост
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
        pathBudget = Pathfinder.BUDGET_PER_TICK;
        info.time++;
        if (!info.rule(GameRules.DAYLIGHT)) info.dayShift--;
        long[] tp = PROF ? new long[12] : null; int pi = 0;
        if (PROF) tp[pi++] = System.nanoTime();
        tickItems(); if (PROF) tp[pi++] = System.nanoTime();
        arrows.tick(); if (PROF) tp[pi++] = System.nanoTime();
        tickBlockEntities(); if (PROF) tp[pi++] = System.nanoTime();
        tickMobs(); if (PROF) tp[pi++] = System.nanoTime();
        Herobrine.tick(this, target, mobRng, tick); if (PROF) tp[pi++] = System.nanoTime();
        VillageLife.tick(this, target, tick); if (PROF) tp[pi++] = System.nanoTime();
        infection.tick(tick, target); if (PROF) tp[pi++] = System.nanoTime();
        Rift.tick(this); if (PROF) tp[pi++] = System.nanoTime();
        budgets.clear();
        events.post(new TickEvent(this, tick)); if (PROF) tp[pi++] = System.nanoTime();
        if (System.currentTimeMillis() - lastAutosave > 30_000) { lastAutosave = System.currentTimeMillis(); startAutosave(); }
        autosaveStep();
        if (PROF) { tp[pi++] = System.nanoTime(); if (tp[pi - 1] - tp[0] > 5_000_000) { StringBuilder sb = new StringBuilder("[tickprof] " + (tp[pi - 1] - tp[0]) / 1_000_000 + " мс: items arrows be mobs hero village infect rift events save ="); for (int i = 1; i < pi; i++) sb.append(' ').append((tp[i] - tp[i - 1]) / 100_000 / 10.0); System.out.println(sb); } }
    }

    public long tickCount() { return tick; }
    private static final boolean PROF = Boolean.getBoolean("voxelin.tickprof");

    private int pathBudget = Pathfinder.BUDGET_PER_TICK;
    /** Чи можна ще шукати шлях у цьому тіку (усім істотам разом - не більше Pathfinder.BUDGET_PER_TICK). */
    public boolean takePathBudget() { if (pathBudget <= 0) return false; pathBudget--; return true; }
    /** Скільки пошуків шляху лишилось у цьому тіку (для тестів). */
    public int pathBudgetLeft() { return pathBudget; }

    /**
     * Записати файл світу атомарно (tmp -> заміна). Поки світ відкритий - на потоці диска (порядок записів зберігається: один потік),
     * щоб тік світу не чекав на диск; під час закриття - одразу. Текст збирається тут, у викликача, - фон бачить лише готові байти.
     */
    void writeLater(File target, String text) throws IOException {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (closed || io.isShutdown()) { writeNow(target, data); return; }
        io.execute(() -> { try { writeNow(target, data); } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати " + target.getName() + ": " + e); } });
    }

    private static void writeNow(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), data);
        Files2.replace(tmp, target);
    }

    /** Автозбереження частинами: копія чанка - на потоці світу, тож усі змінені за один тік тримали б світ десятки мілісекунд. */
    private final ArrayDeque<ChunkPos> autosaveQueue = new ArrayDeque<>();
    static final int AUTOSAVE_PER_TICK = 24;

    private void startAutosave() {
        syncPlayerData();
        autosaveQueue.clear();
        for (Chunk c : chunks.values()) if (c.modified) autosaveQueue.add(c.pos());
        if (autosaveQueue.isEmpty()) io.execute(this::flushQuiet);
    }

    private void autosaveStep() {
        if (autosaveQueue.isEmpty()) return;
        for (int n = 0; n < AUTOSAVE_PER_TICK && !autosaveQueue.isEmpty(); n++) {
            Chunk c = chunks.get(autosaveQueue.poll());
            if (c != null && c.modified) saveAsync(c); // вивантажений уже збережено при вивантаженні
        }
        if (autosaveQueue.isEmpty()) io.execute(this::flushQuiet);
    }

    /** Зберегти все одразу (не частинами). */
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
            String[] arm = mainInventory.encodeArmor();
            System.arraycopy(arm, 0, info.armor, 0, 4);
            info.invSelected = mainInventory.selected(); info.hasInv = true;
        }
        saveItems();
        saveBlockEntities();
        saveMobs();
        saveMarks();
        infection.save();
        boards.save();
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

    @Override public long[] locate(String structure, long x, long z) { return generator.locate(structure, x, z); }
    public java.util.List<String> structureNames() { return generator.structureNames(); }

    @Override public boolean teleport(Source source, long x, int y, long z) {
        if (target == null || y < 0 || y >= Chunk.HEIGHT || !inRange(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L))) return false;
        target.teleport(x, y, z);
        return true;
    }

    /** Відкладена робота з генерації чанка (скрині структур): сутність створюється один раз, уже збережена/розграбована не відновлюється. */
    private void processPending(Chunk c) {
        if (c.pending.isEmpty()) return;
        for (Chunk.Pending p : c.pending) {
            if ("mob".equals(p.kind())) { // житель/тварина зі структури: створюється один раз (відмітка в marks.dat)
                if (marks.contains(p.seed())) continue;
                String[] kinds = p.loot().split("\\|"); // «mob a|b» - вибір за зерном клітинки
                MobType mt = mobTypes.get(kinds[(int) Math.floorMod(p.seed(), (long) kinds.length)]);
                if (mt == null) continue;
                Mob m = spawnMob(mt, Pos.ofBlock(p.x(), p.y(), p.z()));
                if (m != null) { m.keep = true; marks.add(p.seed()); }
                continue;
            }
            if (!"chest".equals(p.kind())) continue;
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (blockEntities.containsKey(pos)) continue;
            ChestEntity e = new ChestEntity(p.x(), p.y(), p.z());
            LootTable t = loot.get(p.loot());
            if (t != null) t.fill(e, new Random(p.seed()), itemTypes);
            blockEntities.put(pos, e);
        }
        c.pending.clear();
    }
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
    private boolean fireproof(String item) { ItemType t = itemTypes.get(item); return t != null && t.fireproof; }
    public boolean isLava(long x, int y, long z) { int id = blockId(x, y, z); return id > 0 && blocks.get(id).name.equals(LAVA); }
    public static final String LAVA = "voxelin:lava";

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
