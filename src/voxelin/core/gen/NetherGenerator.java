package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.BlockType;
import voxelin.core.Chunk;

/**
 * Незер - нижній бік Шву (рішення Святослава 29.10.2026: свій, пов'язаний зі Швом). Замкнений вимір висотою 128: корінна порода знизу й
 * зверху, між ними - печерні зали в Згарищі (3D-шум, найширші посередині), море лави до y=31, попіл на підлогах, жарові жили на стінах,
 * шрами Шву прожилками крізь камінь і глибоко біля лави - жаровий лімініт (матеріал для якоря Незеру). Детерміновано від зерна, long-координати.
 */
public final class NetherGenerator implements Generator {
    public static final int CEIL = 127, LAVA = 31, ORE_MAX_Y = 26;
    /** Фортеці: одна на клітинку 128x128 з імовірністю 0.5; зала на висоті FORT_Y, від неї хрест мостів по BRIDGE блоків. */
    public static final int FORT_CELL = 128, FORT_Y = 64, BRIDGE = 36, HALL = 11;

    private final long seed;
    private final int bedrock, cinder, ash, lava, emberVein, emberOre, scar, bricks;
    private final Noise big, small, dress;
    private final StructureTemplate hall;
    private final StructurePlacer stamper;

    public NetherGenerator(long seed, BlockRegistry reg) { this(seed, reg, java.util.List.of()); }

    public NetherGenerator(long seed, BlockRegistry reg, java.util.List<StructureTemplate> templates) {
        this.seed = seed;
        bedrock = id(reg, "bedrock"); cinder = id(reg, "cinder"); ash = id(reg, "ash"); lava = id(reg, "lava");
        emberVein = id(reg, "ember_vein"); emberOre = id(reg, "ember_ore"); scar = id(reg, "seam_scar");
        bricks = id(reg, "fortress_bricks");
        StructureTemplate h = null;
        for (StructureTemplate t : templates) if (t.name.endsWith("nether_fortress")) h = t;
        hall = h;
        stamper = new StructurePlacer(seed, java.util.List.of(), (x, z) -> 0, 0);
        big = new Noise(seed ^ 0x4E7E1L); small = new Noise(seed ^ 0x4E7E2L); dress = new Noise(seed ^ 0x4E7E3L);
    }

    private static int id(BlockRegistry reg, String n) { BlockType b = reg.byName("voxelin:" + n); return b == null ? 0 : b.id; }

    @Override public int surfaceY(long x, long z) { return 64; }
    @Override public String biomeAt(long x, long z) { return "Незер"; }
    @Override public int seaLevel() { return LAVA; }
    @Override public java.util.List<String> structureNames() { return java.util.List.of("nether_fortress"); }

    /** Центр фортеці клітинки або null. */
    public long[] fortress(long cx, long cz) {
        long hh = h(seed ^ 0xF0E7L, cx, 0, cz);
        if (frac(hh) >= 0.5) return null;
        return new long[]{cx * FORT_CELL + 40 + (hh >>> 8) % 48, cz * FORT_CELL + 40 + (hh >>> 20) % 48};
    }

    @Override public long[] locate(String name, long x, long z) {
        if (!name.endsWith("nether_fortress")) return null;
        long ccx = Math.floorDiv(x, (long) FORT_CELL), ccz = Math.floorDiv(z, (long) FORT_CELL);
        long[] best = null; double bd = Double.MAX_VALUE;
        for (int r = 0; r <= 8; r++) for (long cx = ccx - r; cx <= ccx + r; cx++) for (long cz = ccz - r; cz <= ccz + r; cz++) {
            if (Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) != r) continue;
            long[] f = fortress(cx, cz);
            if (f == null) continue;
            double d = Math.hypot(f[0] - x, f[1] - z);
            if (d < bd) { bd = d; best = new long[]{f[0], FORT_Y, f[1], (long) d}; }
        }
        return best;
    }

    /** Щільність у точці: > 0 - камінь. Зали найширші на висоті ~70, до підлоги й стелі камінь густішає. */
    private double density(long x, int y, long z) {
        double v = big.sample3(0, x, y, z, 40) + 0.45 * small.sample3(1, x, y, z, 14);
        double t = (y - 70) / 56.0;
        return v + t * t * (y < 70 ? 0.6 : 1.1) - 0.32; // донизу м'якше - широке море лави
    }

    private static long h(long seed, long x, long y, long z) { return Noise.mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0x632BE59BD9B4E019L) ^ (z * 0xC2B2AE3D27D4EB4FL)); }
    private static double frac(long h) { return (h >>> 11) / (double) (1L << 53); }

    @Override public void generate(Chunk c) {
        final long bx = c.cx * 16, bz = c.cz * 16;
        final int gy = CEIL / 4 + 2;
        double[][][] g = new double[5][5][gy];
        for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) for (int y = 0; y < gy; y++) g[i][j][y] = density(bx + i * 4, y * 4, bz + j * 4);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            long wx = bx + x, wz = bz + z;
            for (int y = 0; y <= CEIL; y++) {
                int id;
                if (y == 0 || y == CEIL) id = bedrock;
                else if ((y < 4 && frac(h(seed, wx, y, wz)) < 0.6 - y * 0.15) || (y > CEIL - 4 && frac(h(seed, wx, y, wz)) < 0.6 - (CEIL - y) * 0.15)) id = bedrock;
                else {
                    double d = tri(g, x >> 2, z >> 2, y >> 2, (x & 3) / 4.0, (y & 3) / 4.0, (z & 3) / 4.0);
                    id = d > 0 ? cinder : (y <= LAVA ? lava : 0);
                }
                c.set(x, y, z, id);
            }
        }
        // оздоблення: попіл на підлогах, жарові жили на відкритих стінах, шрами Шву, жаровий лімініт біля лави
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            long wx = bx + x, wz = bz + z;
            boolean ashy = dress.sample2(2, wx, wz, 24) > 0.15;
            for (int y = 4; y < CEIL - 3; y++) {
                if (c.get(x, y, z) != cinder) continue;
                int above = c.get(x, y + 1, z);
                long hh = h(seed ^ 0xD7L, wx, y, wz);
                if (above == 0 && ashy && y > LAVA) { c.set(x, y, z, ash); continue; }
                if (Math.abs(dress.sample3(3, wx, y, wz, 18)) < 0.035) { c.set(x, y, z, scar); continue; } // прожилки Шву
                boolean open = above == 0 || nb(c, x - 1, y, z) == 0 || nb(c, x + 1, y, z) == 0 || nb(c, x, y, z - 1) == 0 || nb(c, x, y, z + 1) == 0 || c.get(x, y - 1, z) == 0;
                if (open && frac(hh) < 0.025) { c.set(x, y, z, emberVein); continue; }
                if (y <= ORE_MAX_Y && frac(hh) > 0.996) c.set(x, y, z, emberOre);
            }
        }
        fortresses(c, bx, bz);
    }

    /** Фортеці, що зачіпають чанк: мости (підлога, поручні, прорубаний прохід, опори до лави) і зала з шаблона. */
    private void fortresses(Chunk c, long bx, long bz) {
        if (bricks <= 0) return;
        long ccx = Math.floorDiv(bx, (long) FORT_CELL), ccz = Math.floorDiv(bz, (long) FORT_CELL);
        for (long cx = ccx - 1; cx <= ccx + 1; cx++) for (long cz = ccz - 1; cz <= ccz + 1; cz++) {
            long[] f = fortress(cx, cz);
            if (f == null) continue;
            long fx = f[0], fz = f[1];
            int reach = HALL / 2 + BRIDGE;
            if (fx + reach < bx || fx - reach >= bx + 16 || fz + reach < bz || fz - reach >= bz + 16) continue;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                long wx = bx + x, wz = bz + z, dx = wx - fx, dz = wz - fz;
                boolean alongX = Math.abs(dz) <= 2 && Math.abs(dx) <= reach, alongZ = Math.abs(dx) <= 2 && Math.abs(dz) <= reach;
                if (!alongX && !alongZ) continue;
                long side = alongX ? Math.abs(dz) : Math.abs(dx), along = alongX ? Math.abs(dx) : Math.abs(dz);
                if (alongX && alongZ) side = Math.min(Math.abs(dx), Math.abs(dz));
                c.set(x, FORT_Y, z, bricks);
                for (int y = FORT_Y + 1; y <= FORT_Y + 4; y++) c.set(x, y, z, side == 2 && y == FORT_Y + 1 ? bricks : 0); // поручні по краях, прохід прорубано
                if (side == 0 && along % 8 == 4) for (int y = FORT_Y - 1; y > 1 && c.get(x, y, z) != cinder && c.get(x, y, z) != bedrock; y--) c.set(x, y, z, bricks); // опора
            }
            if (hall != null) stamper.stampAt(c, hall, fx - hall.sx / 2, FORT_Y, fz - hall.sz / 2, 0, seed ^ (cx * 31 + cz));
        }
    }

    private static int nb(Chunk c, int x, int y, int z) { return x < 0 || z < 0 || x > 15 || z > 15 ? -1 : c.get(x, y, z); }

    private static double tri(double[][][] g, int i, int j, int y, double fx, double fy, double fz) {
        double c00 = g[i][j][y] * (1 - fx) + g[i + 1][j][y] * fx, c10 = g[i][j + 1][y] * (1 - fx) + g[i + 1][j + 1][y] * fx;
        double c01 = g[i][j][y + 1] * (1 - fx) + g[i + 1][j][y + 1] * fx, c11 = g[i][j + 1][y + 1] * (1 - fx) + g[i + 1][j + 1][y + 1] * fx;
        double lo = c00 * (1 - fz) + c10 * fz, hi = c01 * (1 - fz) + c11 * fz;
        return lo * (1 - fy) + hi * fy;
    }
}
