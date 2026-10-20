package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.Chunk;
import voxelin.core.WorldInfo;

/**
 * Рельєф етапу 1: пагорби з фрактального шуму Перліна, трава/земля/камінь, корінна порода внизу, дерева.
 * На етапі «Новий світ» його замінить генератор з біомами, печерами й рудами (той самий інтерфейс).
 */
public final class TerrainGenerator implements Generator {
    public static final int BASE = 64;
    private final Noise height, detail;
    private final long seed;
    private final WorldInfo.Type type;
    private final int grass, dirt, stone, bedrock, log, leaves;

    public TerrainGenerator(long seed, WorldInfo.Type type, BlockRegistry reg) {
        this.seed = seed; this.type = type;
        height = new Noise(seed); detail = new Noise(seed ^ 0x7A3F9C11D5E2B648L);
        grass = id(reg, "voxelin:grass"); dirt = id(reg, "voxelin:dirt"); stone = id(reg, "voxelin:stone");
        bedrock = id(reg, "voxelin:bedrock"); log = id(reg, "voxelin:oak_log"); leaves = id(reg, "voxelin:oak_leaves");
    }

    private static int id(BlockRegistry r, String n) {
        if (r.byName(n) == null) throw new IllegalStateException("генератору потрібен блок " + n + " (ванільний вміст не завантажено?)");
        return r.byName(n).id;
    }

    @Override public int surfaceY(long x, long z) {
        if (type == WorldInfo.Type.FLAT) return BASE;
        double h = height.fractal2(x, z, 96, 4) * 18 + detail.fractal2(x, z, 24, 2) * 3;
        return Math.max(4, Math.min(Chunk.HEIGHT - 20, BASE + (int) Math.round(h)));
    }

    @Override public void generate(Chunk c) {
        long bx = c.cx * 16, bz = c.cz * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = surfaceY(bx + x, bz + z);
            c.set(x, 0, z, bedrock);
            for (int y = 1; y <= top; y++) c.set(x, y, z, y == top ? grass : (y >= top - 3 ? dirt : stone));
        }
        if (type == WorldInfo.Type.FLAT) return;
        // дерева: детерміновано за зерном і координатами, не ближче 2 блоків до краю чанка (крона не вилазить у сусіда)
        for (int x = 2; x < 14; x++) for (int z = 2; z < 14; z++) {
            long h = hash(bx + x, bz + z);
            if ((h & 1023) >= 9) continue; // ~0.9% колонок
            int top = surfaceY(bx + x, bz + z);
            int trunk = 4 + (int) ((h >>> 10) & 2);
            for (int y = 1; y <= trunk; y++) c.set(x, top + y, z, log);
            int crown = top + trunk;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 2; dy++) {
                int r = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy);
                if (r > 3 || (dx == 0 && dz == 0 && dy <= 0)) continue;
                if (c.get(x + dx, crown + dy, z + dz) == 0) c.set(x + dx, crown + dy, z + dz, leaves);
            }
        }
    }

    private long hash(long x, long z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return h;
    }
}
