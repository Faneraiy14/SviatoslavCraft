package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.Chunk;

/**
 * Енд: порожнеча з головним островом (радіус ~46, нерівний край, знизу звужується), вісьмома обсидіановими вежами й малими острівцями далеко.
 * Платформа прибуття 7x7 з постійним виходом (3x3 порталу) на південному краю головного острова. Детерміновано від зерна, long-координати.
 */
public final class EndGenerator implements Generator {
    public static final int TOP = 64, MAIN_RADIUS = 46, PILLAR_RING = 28;
    /** Місце прибуття (ноги гравця) і центр платформи з виходом. */
    public static final int PLATFORM_X = 0, PLATFORM_Z = 38, ARRIVAL_Z = 41;

    private final long seed;
    private final int endStone, obsidian, portal;
    private final Noise edge, thick, small;

    public EndGenerator(long seed, BlockRegistry reg) {
        this.seed = seed;
        endStone = reg.byName("voxelin:end_stone").id; obsidian = reg.byName("voxelin:obsidian").id; portal = reg.byName("voxelin:end_portal").id;
        edge = new Noise(seed ^ 0xE0D1L); thick = new Noise(seed ^ 0xE0D2L); small = new Noise(seed ^ 0xE0D3L);
    }

    /** Висота верху суцільного каменю в колонці або -1, якщо там порожнеча (лише основа, без веж). */
    private int topAt(long x, long z) {
        double d = Math.sqrt((double) x * x + (double) z * z);
        double ang = Math.atan2(z, x);
        double r = MAIN_RADIUS + 5 * edge.fractal2(Math.round(Math.cos(ang) * 64) + 1000, Math.round(Math.sin(ang) * 64) + 1000, 8, 2);
        if (d < r) return TOP + (int) Math.round(thick.fractal2(x, z, 14, 2) * 1.6);
        // малі острівці: у клітинці 160x160 один, з імовірністю 0.6, далі за 150 від початку
        if (d < 150) return -1;
        long cx = Math.floorDiv(x, 160L), cz = Math.floorDiv(z, 160L);
        long h = Noise.mix(seed ^ 0x15AAL ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL));
        if (((h >>> 11) / (double) (1L << 53)) >= 0.6) return -1;
        long ix = cx * 160 + 30 + (h >>> 8) % 100, iz = cz * 160 + 30 + (h >>> 24) % 100;
        double ir = 6 + ((h >>> 40) & 7), dd = Math.hypot(x - ix, z - iz);
        return dd < ir ? 58 + (int) ((h >>> 50) & 15) : -1;
    }

    @Override public int surfaceY(long x, long z) { int t = topAt(x, z); return t < 0 ? 0 : t; }
    @Override public String biomeAt(long x, long z) { return "Енд"; }
    @Override public int seaLevel() { return 0; }

    private int depthBelow(long x, long z, int top) {
        double d = Math.sqrt((double) x * x + (double) z * z);
        if (d >= 150) return 6; // острівці тонкі
        double f = Math.max(0, 1 - d / (MAIN_RADIUS + 4));
        return 3 + (int) Math.round(12 * Math.pow(f, 0.6));
    }

    @Override public void generate(Chunk c) {
        final long bx = c.cx * 16, bz = c.cz * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            long wx = bx + x, wz = bz + z;
            int top = topAt(wx, wz);
            if (top < 0) continue;
            int bottom = Math.max(1, top - depthBelow(wx, wz, top));
            for (int y = bottom; y <= top; y++) c.set(x, y, z, endStone);
        }
        // обсидіанові вежі
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + 0.2;
            long px = Math.round(Math.cos(a) * PILLAR_RING), pz = Math.round(Math.sin(a) * PILLAR_RING);
            if (px + 3 < bx || px - 3 >= bx + 16 || pz + 3 < bz || pz - 3 >= bz + 16) continue;
            int h = 14 + (int) ((Noise.mix(seed + k * 77L) >>> 20) % 13), base = surfaceY(px, pz);
            for (long x = px - 2; x <= px + 2; x++) for (long z = pz - 2; z <= pz + 2; z++) {
                if ((x - px) * (x - px) + (z - pz) * (z - pz) > 5 || x < bx || x >= bx + 16 || z < bz || z >= bz + 16) continue;
                for (int y = base - 2; y <= base + h; y++) c.set((int) (x - bx), y, (int) (z - bz), obsidian);
            }
        }
        // платформа прибуття: 7x7 обсидіану на рівні верху острова, у центрі 3x3 порталу-виходу
        for (long x = PLATFORM_X - 3; x <= PLATFORM_X + 3; x++) for (long z = PLATFORM_Z - 3; z <= PLATFORM_Z + 3; z++) {
            if (x < bx || x >= bx + 16 || z < bz || z >= bz + 16) continue;
            int lx = (int) (x - bx), lz = (int) (z - bz);
            for (int y = TOP - 2; y <= TOP; y++) c.set(lx, y, lz, obsidian);
            for (int y = TOP + 1; y <= TOP + 4; y++) c.set(lx, y, lz, 0);
            if (Math.abs(x - PLATFORM_X) <= 1 && Math.abs(z - PLATFORM_Z) <= 1) c.set(lx, TOP, lz, portal);
        }
    }
}
