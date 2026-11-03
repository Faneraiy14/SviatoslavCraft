package voxelin.core;

import java.util.Random;

/**
 * Кістяне борошно. Саджанців і грядок у грі ще нема, тому поки що воно:
 * земля під відкритим повітрям -> дерен; дерен -> з шансом 1/3 виростає дуб (стовбур 4-6 + крона), якщо над ним вільно.
 * Борошно витрачається при кожному вдалому застосуванні (і коли дерево не виросло - як у Minecraft).
 */
public final class Growth {
    public static final String BONE_MEAL = "voxelin:bone_meal";
    static final String DIRT = "voxelin:dirt", GRASS = "voxelin:grass", LOG = "voxelin:oak_log", LEAVES = "voxelin:oak_leaves";
    /** Скільки вільних блоків треба над дереном для дерева. */
    public static final int NEED_AIR = 7;

    private Growth() {}

    /** Застосувати борошно до блока (x,y,z). true - щось сталося, борошно витрачається. */
    public static boolean apply(World w, long x, int y, long z, Random rng) {
        if (y < 0 || y >= Chunk.HEIGHT - 1) return false;
        BlockType b = w.blocks.get(Math.max(0, w.blockId(x, y, z)));
        if (b.name.equals(DIRT)) {
            if (w.blockId(x, y + 1, z) != 0) return false; // під блоком дерен не росте
            return w.setBlock(Sources.SYSTEM, x, y, z, w.blocks.byName(GRASS));
        }
        if (!b.name.equals(GRASS)) return false;
        if (rng.nextInt(3) == 0) tree(w, x, y + 1, z, 4 + rng.nextInt(3));
        return true;
    }

    /** Чи можна виростити дерево з підніжжям на (x,y,z): стовбур і крона мають бути в повітрі. */
    static boolean room(World w, long x, int y, long z) {
        if (y + NEED_AIR >= Chunk.HEIGHT) return false;
        for (int dy = 0; dy < NEED_AIR; dy++) if (w.blockId(x, y + dy, z) != 0) return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk((x + dx) >> 4, (z + dz) >> 4) == null) return false;
        return true;
    }

    /** Дуб: стовбур trunk блоків, крона 5x5 у двох шарах під верхівкою, 3x3 над ними (листя лише в повітря). */
    public static boolean tree(World w, long x, int y, long z, int trunk) {
        if (!room(w, x, y, z)) return false;
        BlockType log = w.blocks.byName(LOG), leaves = w.blocks.byName(LEAVES);
        if (log == null || leaves == null) return false;
        w.batch(() -> {
            int top = y + trunk;
            for (int ly = top - 2; ly <= top + 1; ly++) {
                int r = ly >= top ? 1 : 2;
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // зрізані кути
                    if (w.blockId(x + dx, ly, z + dz) == 0) w.setBlock(Sources.SYSTEM, x + dx, ly, z + dz, leaves);
                }
            }
            for (int i = 0; i < trunk; i++) w.setBlock(Sources.SYSTEM, x, y + i, z, log);
        });
        return true;
    }
}
