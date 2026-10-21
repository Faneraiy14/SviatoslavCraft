package voxelin.core;

import voxelin.api.Source;

/**
 * Портал в Енд: кільце 5x5 без кутів із 12 рамок навколо отвору 3x3. Око в рамку ставить гравець; якщо воно 12-те в кільці, портал відчиняється
 * лише коли той, хто його ставить, у повній алмазній (рівень 4) або незеритовій броні - інакше око повертається в інвентар, рамка лишається порожньою.
 */
public final class EndPortal {
    private EndPortal() {}

    public static final int REQUIRED_TIER = 4;
    public static final String EYE = "voxelin:end_eye", FRAME = "voxelin:end_frame", FRAME_EYE = "voxelin:end_frame_eye", PORTAL = "voxelin:end_portal";

    private static boolean ringCell(int dx, int dz) { return Math.max(Math.abs(dx), Math.abs(dz)) == 2 && !(Math.abs(dx) == 2 && Math.abs(dz) == 2); }

    /** Центр отвору (cx,cz) повного кільця з 12 заповнених рамок, що містить (x,z), або null. */
    public static long[] filledRing(World w, long x, int y, long z) {
        int eye = w.blocks.byName(FRAME_EYE).id;
        for (long cx = x - 2; cx <= x + 2; cx++) for (long cz = z - 2; cz <= z + 2; cz++) {
            if (!ringCell((int) (x - cx), (int) (z - cz))) continue;
            boolean all = true;
            for (int dx = -2; dx <= 2 && all; dx++) for (int dz = -2; dz <= 2 && all; dz++) if (ringCell(dx, dz) && w.blockId(cx + dx, y, cz + dz) != eye) all = false;
            if (all) return new long[]{cx, cz};
        }
        return null;
    }

    /**
     * Поставити Око в рамку (x,y,z). Повертає повідомлення для гравця або null, якщо дія неможлива (не та рамка/нема ока).
     * creative - око не витрачається.
     */
    public static String insertEye(World w, Source src, long x, int y, long z, Inventory inv, boolean creative) {
        BlockType frame = w.blocks.byName(FRAME), withEye = w.blocks.byName(FRAME_EYE), portal = w.blocks.byName(PORTAL);
        if (frame == null || withEye == null || portal == null || w.blockId(x, y, z) != frame.id) return null;
        Inventory.Stack st = inv.selectedStack();
        if (st == null || !st.item.equals(EYE)) return null;
        if (!creative) inv.takeSelected();
        w.setBlock(src, x, y, z, withEye);
        long[] ring = filledRing(w, x, y, z);
        if (ring == null) return "Око вставлено";
        if (!inv.fullArmor(w.itemTypes, REQUIRED_TIER)) { // правило входу: повна алмазна/незеритова броня
            w.setBlock(src, x, y, z, frame);
            if (!creative && inv.add(EYE, 1) > 0) w.spawnItem(Pos.ofBlock(x, y + 1, z), EYE, 1, 0, true);
            return "Шов не відчиняється: потрібна повна алмазна або незеритова броня на тому, хто вставляє останнє око. Око повернуто.";
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.setBlock(src, ring[0] + dx, y, ring[1] + dz, portal);
        return "Шов відчинився.";
    }
}
