package voxelin.core;

import voxelin.api.*;
import voxelin.api.event.*;
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
        void blockChanged(int x, int y, int z);
    }

    public final File dir;
    public final WorldInfo info;
    public final BlockRegistry blocks;
    private final EventBus events;
    private final Generator generator;
    private final RegionStorage storage;
    private final int[] rt2p, p2rt;
    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Chunk> ready = new ConcurrentLinkedQueue<>();
    /** Знімки чанків, чергованих на запис: повторне завантаження бере звідси, а не зі ще не оновленого файлу. */
    private final ConcurrentHashMap<Long, Chunk> saving = new ConcurrentHashMap<>();
    private final ExecutorService genPool, io;
    private final Map<Source, int[]> budgets = new IdentityHashMap<>();
    private Listener listener;
    private long tick, lastAutosave = System.currentTimeMillis();
    private volatile boolean closed;
    /** Статистика для F3 і --bench. */
    public volatile long genNanos, genCount;

    public World(File dir, WorldInfo info, BlockRegistry blocks, EventBus events, Generator generator) throws IOException {
        this.dir = dir; this.info = info; this.blocks = blocks; this.events = events; this.generator = generator;
        storage = new RegionStorage(dir);
        rt2p = new int[blocks.size()];
        for (BlockType t : blocks.all()) rt2p[t.id] = info.paletteId(t.name);
        p2rt = new int[info.palette.size()];
        for (int p = 0; p < p2rt.length; p++) {
            BlockType t = blocks.byName(info.palette.get(p));
            p2rt[p] = t != null ? t.id : blocks.unknown().id;
        }
        info.save(dir); // палітра могла дописатись новими блоками
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        genPool = Executors.newFixedThreadPool(threads, daemon("voxelin-gen"));
        io = Executors.newSingleThreadExecutor(daemon("voxelin-io"));
    }

    static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; };
    }

    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------ читання

    public Chunk chunk(int cx, int cz) { return chunks.get(Chunk.key(cx, cz)); }
    public Collection<Chunk> loadedChunks() { return chunks.values(); }

    /** id блока; незавантажений чанк -> -1. Безпечно з будь-якого потоку. */
    public int blockId(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return 0;
        Chunk c = chunks.get(Chunk.key(x >> 4, z >> 4));
        return c == null ? -1 : c.get(x & 15, y, z & 15);
    }

    /** Чи є тут суцільний блок (незавантажений чанк вважається суцільним - гравець не провалиться крізь ще не готовий світ). */
    public boolean isSolid(int x, int y, int z) {
        int id = blockId(x, y, z);
        return id < 0 || blocks.get(id).solid;
    }

    @Override public int height() { return Chunk.HEIGHT; }
    @Override public BlockRef getBlock(int x, int y, int z) { int id = blockId(x, y, z); return id < 0 ? null : blocks.get(id); }
    @Override public long seed() { return info.seed; }
    @Override public String name() { return info.name; }

    // ------------------------------------------------------------------ зміни

    @Override public boolean setBlock(Source src, int x, int y, int z, BlockRef ref) {
        if (closed || y < 0 || y >= Chunk.HEIGHT || ref == null) return false;
        Chunk c = chunks.get(Chunk.key(x >> 4, z >> 4));
        if (c == null) return false;
        BlockType nb = blocks.get(ref.id());
        if (nb != ref && blocks.byName(ref.name()) != nb) return false; // чужий BlockRef
        int[] b = budgets.computeIfAbsent(src, k -> new int[1]);
        if (b[0] >= src.blockBudgetPerTick()) return false;
        int old = c.get(x & 15, y, z & 15);
        if (old == nb.id) return true;
        if (events.has(BlockChangeEvent.class)) {
            BlockChangeEvent e = events.post(new BlockChangeEvent(this, src, x, y, z, blocks.get(old), nb));
            if (e.isCancelled()) return false;
        }
        b[0]++;
        c.set(x & 15, y, z & 15, nb.id);
        if (listener != null) listener.blockChanged(x, y, z);
        return true;
    }

    // ------------------------------------------------------------------ життєвий цикл (головний потік)

    /** Довантажити чанки навколо (cx,cz) у радіусі, вивантажити дальші. Готові з фону потрапляють у світ саме тут. */
    public void update(int centerCx, int centerCz, int radius) {
        for (Chunk c; (c = ready.poll()) != null; ) {
            pending.remove(Chunk.key(c.cx, c.cz));
            if (closed) continue;
            chunks.put(Chunk.key(c.cx, c.cz), c);
            if (listener != null) listener.chunkLoaded(c);
        }
        // замовлення від найближчих до дальніх, не більше 2 черг на потік
        if (pending.size() < 16) {
            List<long[]> want = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + radius) continue;
                long k = Chunk.key(centerCx + dx, centerCz + dz);
                if (!chunks.containsKey(k) && !pending.contains(k)) want.add(new long[]{dx * dx + dz * dz, centerCx + dx, centerCz + dz});
            }
            want.sort(Comparator.comparingLong(a -> a[0]));
            for (long[] w : want) { if (pending.size() >= 16) break; request((int) w[1], (int) w[2]); }
        }
        int keep = radius + 2;
        for (Iterator<Chunk> it = chunks.values().iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            if (Math.abs(c.cx - centerCx) <= keep && Math.abs(c.cz - centerCz) <= keep) continue;
            it.remove();
            if (listener != null) listener.chunkUnloaded(c);
            if (c.modified) saveAsync(c);
        }
    }

    private void request(int cx, int cz) {
        long k = Chunk.key(cx, cz);
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
        budgets.clear();
        events.post(new TickEvent(this, tick));
        if (System.currentTimeMillis() - lastAutosave > 30_000) { lastAutosave = System.currentTimeMillis(); autosave(); }
    }

    public long tickCount() { return tick; }

    public void autosave() {
        for (Chunk c : chunks.values()) if (c.modified) saveAsync(c);
        io.execute(this::flushQuiet);
    }

    private void saveAsync(Chunk c) {
        c.modified = false;
        Chunk copy = snapshot(c); // копія на головному потоці - фон не бачить напівзмінених масивів
        long k = Chunk.key(c.cx, c.cz);
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
        try { info.save(dir); } catch (IOException e) { System.out.println("[Voxelin] ⚠️ " + e); }
        chunks.clear();
    }

    public int surfaceY(int x, int z) { return generator.surfaceY(x, z); }

    /** Доступ до чанка під час генерації для модів (ChunkGeneratedEvent). */
    private final class GenAccess implements ChunkData {
        final Chunk c;
        GenAccess(Chunk c) { this.c = c; }
        public int chunkX() { return c.cx; }
        public int chunkZ() { return c.cz; }
        public int height() { return Chunk.HEIGHT; }
        public BlockRef get(int x, int y, int z) { return blocks.get(c.get(x & 15, y, z & 15)); }
        public void set(int x, int y, int z, BlockRef b) { if (x >= 0 && x < 16 && z >= 0 && z < 16 && b != null) c.set(x, y, z, blocks.get(b.id()).id); }
    }
}
