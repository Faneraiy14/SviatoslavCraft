package voxelin.core;

/**
 * Збудувати Вартового села (як залізного голема в Minecraft): Т із чотирьох залізних блоків (два стовпом, по одному з боків верхнього)
 * і лампа зверху. Коли ставиться лампа (або останній блок) і форма склалась, блоки зникають, а на їхньому місці постає Вартовий,
 * чий дім - місце побудови.
 */
public final class Guard {
    private Guard() {}

    public static final String IRON = "voxelin:iron_block", HEAD = "voxelin:lamp", MOB = "voxelin:village_guard";

    /** Після зміни блока (x, y, z): чи склалась форма з лампою в будь-якій із сусідніх позицій. Повертає істоту або null. */
    static voxelin.core.entity.Mob tryBuild(World w, long x, int y, long z) {
        BlockType placed = w.blocks.get(Math.max(0, w.blockId(x, y, z)));
        if (!placed.name.equals(HEAD) && !placed.name.equals(IRON)) return null;
        for (int dy = 0; dy <= 2; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { // лампа могла бути поставлена раніше
            voxelin.core.entity.Mob m = at(w, x + dx, y + dy, z + dz);
            if (m != null) return m;
        }
        return null;
    }

    /** Форма з лампою в (x, y, z): тіло y-1 і y-2, руки на y-1 уздовж X або Z. */
    private static voxelin.core.entity.Mob at(World w, long x, int y, long z) {
        if (y < 2 || !is(w, x, y, z, HEAD) || !is(w, x, y - 1, z, IRON) || !is(w, x, y - 2, z, IRON)) return null;
        int ax;
        if (is(w, x - 1, y - 1, z, IRON) && is(w, x + 1, y - 1, z, IRON)) ax = 0;
        else if (is(w, x, y - 1, z - 1, IRON) && is(w, x, y - 1, z + 1, IRON)) ax = 1;
        else return null;
        MobType t = w.mobTypes.find(MOB);
        if (t == null) return null;
        BlockType air = w.blocks.get(0);
        voxelin.api.Source src = Sources.SYSTEM;
        w.setBlock(src, x, y, z, air); w.setBlock(src, x, y - 1, z, air); w.setBlock(src, x, y - 2, z, air);
        if (ax == 0) { w.setBlock(src, x - 1, y - 1, z, air); w.setBlock(src, x + 1, y - 1, z, air); }
        else { w.setBlock(src, x, y - 1, z - 1, air); w.setBlock(src, x, y - 1, z + 1, air); }
        return w.spawnMob(t, Pos.ofBlock(x, y - 2, z));
    }

    private static boolean is(World w, long x, int y, long z, String name) {
        int id = w.blockId(x, y, z);
        return id > 0 && w.blocks.get(id).name.equals(name);
    }
}
