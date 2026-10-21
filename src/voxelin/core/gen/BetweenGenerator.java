package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.BlockType;
import voxelin.core.Chunk;

/**
 * Міжсвіття: сіра порожнеча з островами-«уламками» інших світів (трава, камінь із рудами, камінь Краю, пороговий камінь) і «нитками» Шву
 * між сусідніми островами. Острови лежать у клітинках 48x48 (не більше одного на клітинку, цілком усередині неї), тож чанк дивиться лише
 * на кілька клітинок навколо. Клітинка (0,0) - острів прибуття з платформою 9x9 і постійним виходом 3x3. Детерміновано від зерна, long-координати.
 */
public final class BetweenGenerator implements Generator {
    public static final int TOP = 64, CELL = 48, HALF = CELL / 2;

    /** Клітинка ґратки (зсунута на півклітинки, щоб клітинка (0,0) мала центр у початку координат) і її мінімальна координата. */
    public static long cellOf(long v) { return Math.floorDiv(v + HALF, CELL); }
    public static long cellMin(long c) { return c * CELL - HALF; }
    /** Платформа прибуття (центр), вихід 3x3 (центр) і місце появи гравця. */
    public static final int PLATFORM_X = 0, PLATFORM_Z = 0, EXIT_Z = -3, ARRIVAL_Z = 2;

    public enum Kind { GRASS, STONE, END, THRESHOLD }
    public record Island(long x, long z, int top, int r, Kind kind, boolean flat) {}
    /** Дім Сторожа: острів у клітинці (1,0), центр і кут шаблона. */
    public static final long HOUSE_X = CELL, HOUSE_Z = 0;

    private final long seed;
    private final int grass, dirt, stone, coal, iron, liminite, endStone, obsidian, threshold, thread, portal, bloom, lamp;
    private final Noise edge;
    private final int cobble;
    private final StructureTemplate house;
    private final StructurePlacer stamper;

    public BetweenGenerator(long seed, BlockRegistry reg) { this(seed, reg, java.util.List.of()); }

    public BetweenGenerator(long seed, BlockRegistry reg, java.util.List<StructureTemplate> templates) {
        this.seed = seed;
        grass = id(reg, "grass"); dirt = id(reg, "dirt"); stone = id(reg, "stone"); coal = id(reg, "coal_ore"); iron = id(reg, "iron_ore"); liminite = id(reg, "liminite_ore");
        endStone = id(reg, "end_stone"); obsidian = id(reg, "obsidian"); threshold = id(reg, "threshold_stone"); thread = id(reg, "seam_thread"); portal = id(reg, "end_portal");
        bloom = id(reg, "threshold_bloom"); lamp = id(reg, "lamp");
        edge = new Noise(seed ^ 0xBE7AL);
        cobble = id(reg, "cobblestone");
        StructureTemplate h = null;
        for (StructureTemplate t : templates) if (t.name.endsWith("keeper_home")) h = t;
        house = h;
        stamper = new StructurePlacer(seed, java.util.List.of(), (x, z) -> 0, 0);
    }

    private static int id(BlockRegistry reg, String n) { BlockType b = reg.byName("voxelin:" + n); return b == null ? 0 : b.id; }

    private static long h(long seed, long a, long b, long salt) { return Noise.mix(seed ^ salt ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL)); }
    private static double frac(long h) { return (h >>> 11) / (double) (1L << 53); }

    /** Острів клітинки або null (порожня клітинка). */
    public Island island(long cx, long cz) {
        if (cx == 0 && cz == 0) return new Island(PLATFORM_X, PLATFORM_Z, TOP, 11, Kind.THRESHOLD, true);
        if (cx == 1 && cz == 0) return new Island(HOUSE_X, HOUSE_Z, TOP, 10, Kind.GRASS, true); // острів Дому Сторожа
        long hh = h(seed, cx, cz, 0xB1L);
        if (frac(hh) >= 0.55) return null;
        long x = cellMin(cx) + 14 + (hh >>> 8) % 20, z = cellMin(cz) + 14 + (hh >>> 16) % 20;
        int r = 5 + (int) ((hh >>> 40) % 7), top = TOP - 14 + (int) ((hh >>> 48) % 29);
        return new Island(x, z, top, r, Kind.values()[(int) ((hh >>> 24) % 4)], false);
    }

    /** Верх острова в колонці або -1. */
    private int topAt(Island is, long x, long z) {
        double d = Math.hypot(x - is.x, z - is.z);
        double rr = is.r + 1.6 * edge.fractal2(x, z, 6, 2);
        if (d >= rr) return -1;
        if (is.flat && Math.abs(x - is.x) <= 6 && Math.abs(z - is.z) <= 6) return is.top; // рівний майданчик (прибуття, дім)
        return is.top + (int) Math.round(edge.fractal2(x + 7000, z, 9, 1));
    }

    private int depth(Island is, long x, long z) {
        double f = Math.max(0, 1 - Math.hypot(x - is.x, z - is.z) / (is.r + 2));
        return 2 + (int) Math.round(is.r * 0.9 * Math.pow(f, 0.6));
    }

    @Override public int surfaceY(long x, long z) {
        Island is = island(cellOf(x), cellOf(z));
        if (is == null) return 0;
        int t = topAt(is, x, z);
        return t < 0 ? 0 : t;
    }

    @Override public String biomeAt(long x, long z) { return "Міжсвіття"; }
    @Override public int seaLevel() { return 0; }

    private static boolean thread(long seed, long cx, long cz, int dir) { return (cx == 0 && cz == 0 && dir == 0) || frac(h(seed, cx * 2 + dir, cz, 0x7E4DL)) < 0.6; } // до дому нитка є завжди

    /** Слід Херобрина в клітинці (рідко; ні моба, ні запису - лише слід). */
    public boolean trace(long cx, long cz) { return !(Math.abs(cx) <= 1 && cz == 0) && frac(h(seed, cx, cz, 0x4E7BL)) < 0.03; }

    @Override public void generate(Chunk c) {
        final long bx = c.cx * 16, bz = c.cz * 16;
        long c0x = cellOf(bx), c1x = cellOf(bx + 15), c0z = cellOf(bz), c1z = cellOf(bz + 15);
        for (long cx = c0x; cx <= c1x; cx++) for (long cz = c0z; cz <= c1z; cz++) {
            Island is = island(cx, cz);
            if (is != null) fillIsland(c, is, bx, bz);
        }
        // нитки Шву: від острова до сусіда праворуч і знизу (лінія лежить у межах двох клітинок)
        for (long cx = c0x - 1; cx <= c1x; cx++) for (long cz = c0z - 1; cz <= c1z; cz++) {
            Island a = island(cx, cz);
            if (a == null) continue;
            for (int dir = 0; dir < 2; dir++) {
                Island b = dir == 0 ? island(cx + 1, cz) : island(cx, cz + 1);
                if (b != null && thread(seed, cx, cz, dir)) drawThread(c, a, b, bx, bz);
            }
        }
        for (long cx = c0x; cx <= c1x; cx++) for (long cz = c0z; cz <= c1z; cz++) if (trace(cx, cz)) drawTrace(c, cx, cz, bx, bz);
        if (house != null) stamper.stampAt(c, house, HOUSE_X - house.sx / 2, TOP + 1, HOUSE_Z - house.sz / 2, 0, seed ^ 0x40E5L);
        // платформа прибуття з постійним виходом
        for (long x = PLATFORM_X - 4; x <= PLATFORM_X + 4; x++) for (long z = PLATFORM_Z - 4; z <= PLATFORM_Z + 4; z++) {
            if (x < bx || x >= bx + 16 || z < bz || z >= bz + 16) continue;
            int lx = (int) (x - bx), lz = (int) (z - bz);
            for (int y = TOP - 2; y <= TOP; y++) c.set(lx, y, lz, threshold);
            for (int y = TOP + 1; y <= TOP + 5; y++) c.set(lx, y, lz, 0);
            if (Math.abs(x - PLATFORM_X) <= 1 && Math.abs(z - EXIT_Z) <= 1) c.set(lx, TOP, lz, portal);
        }
    }

    private void fillIsland(Chunk c, Island is, long bx, long bz) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            long wx = bx + x, wz = bz + z;
            int top = topAt(is, wx, wz);
            if (top < 0) continue;
            int bottom = Math.max(1, top - depth(is, wx, wz));
            for (int y = bottom; y <= top; y++) c.set(x, y, z, block(is.kind, wx, y, wz, top));
            if ((is.kind == Kind.GRASS || is.kind == Kind.THRESHOLD) && bloom != 0 && top + 1 < Chunk.HEIGHT && (h(seed, wx, wz, 0xF10L) & 63) == 0
                    && !(is.x == PLATFORM_X && is.z == PLATFORM_Z && Math.abs(wx) <= 5 && Math.abs(wz) <= 5))
                c.set(x, top + 1, z, bloom); // Квітка Порогу
        }
    }

    private int block(Kind k, long x, int y, long z, int top) {
        long r = h(seed, x * 257 + y, z, 0x0BEL) & 1023;
        switch (k) {
            case GRASS: return y == top ? grass : y >= top - 3 ? dirt : stone;
            case STONE: return r < 26 ? coal : r < 38 ? iron : r < 46 ? liminite : stone;
            case END: return r < 34 ? obsidian : endStone;
            default: return r < 40 ? liminite : threshold;
        }
    }

    /** Слід: на острові - прорізаний наскрізь тунель 1x2, у порожній клітинці - самотня лампа на стовпі. */
    private void drawTrace(Chunk c, long cx, long cz, long bx, long bz) {
        Island is = island(cx, cz);
        long hh = h(seed, cx, cz, 0x4E7CL);
        if (is != null) {
            for (long x = is.x - is.r - 3; x <= is.x + is.r + 3; x++) {
                if (x < bx || x >= bx + 16 || is.z < bz || is.z >= bz + 16) continue;
                for (int y = is.top - 2; y <= is.top - 1; y++) if (y > 0) c.set((int) (x - bx), y, (int) (is.z - bz), 0);
            }
            return;
        }
        long x = cellMin(cx) + HALF, z = cellMin(cz) + HALF;
        if (x < bx || x >= bx + 16 || z < bz || z >= bz + 16 || lamp == 0) return;
        int base = TOP - 10 + (int) ((hh >>> 8) % 20);
        for (int y = base; y < base + 4; y++) c.set((int) (x - bx), y, (int) (z - bz), cobble);
        c.set((int) (x - bx), base + 4, (int) (z - bz), lamp);
    }

    private void drawThread(Chunk c, Island a, Island b, long bx, long bz) {
        if (thread == 0) return;
        double dx = b.x - a.x, dy = b.top - a.top, dz = b.z - a.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = (int) Math.ceil(len * 2);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            long x = Math.round(a.x + dx * t), z = Math.round(a.z + dz * t); int y = (int) Math.round(a.top + dy * t);
            if (x < bx || x >= bx + 16 || z < bz || z >= bz + 16 || y < 1 || y >= Chunk.HEIGHT) continue;
            int lx = (int) (x - bx), lz = (int) (z - bz);
            if (c.get(lx, y, lz) == 0) c.set(lx, y, lz, thread);
        }
    }
}
