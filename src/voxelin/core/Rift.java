package voxelin.core;

/**
 * Розрив Шву: прохід 1x2 у міжсвіття, який відкриває Ключ Порогу (ПКМ по верху твердого блока у звичайному світі). Живе 60 с і зникає.
 * Ключ витрачається; після вилікування зараження - ні (постійний доступ); після спалення розрив не відкривається зовсім.
 */
public final class Rift {
    private Rift() {}

    public static final String KEY = "voxelin:threshold_key", RIFT = "voxelin:seam_rift";
    public static final int LIFE_TICKS = 60 * 20;

    /** ПКМ ключем по блоку (x,y,z). Повертає повідомлення або null, якщо в руці не ключ. */
    public static String open(World w, Inventory inv, long x, int y, long z, boolean creative) {
        Inventory.Stack st = inv.selectedStack();
        if (st == null || !st.item.equals(KEY)) return null;
        if (w.info.otherDim()) return "Розрив відкривається лише у звичайному світі.";
        if (w.info.infState == WorldInfo.InfState.BURNED) return "Шов випалено: розрив тут більше не відкривається.";
        BlockType rift = w.blocks.byName(RIFT);
        if (rift == null) return null;
        int base = w.blockId(x, y, z);
        if (base <= 0 || !w.blocks.get(base).solid || y + 2 >= Chunk.HEIGHT || w.blockId(x, y + 1, z) != 0 || w.blockId(x, y + 2, z) != 0)
            return "Тут нема місця для розриву: потрібні два блоки повітря над твердим блоком.";
        w.setBlock(Sources.SYSTEM, x, y + 1, z, rift);
        w.setBlock(Sources.SYSTEM, x, y + 2, z, rift);
        w.info.rifts.add(new long[]{x, y + 1, z, w.info.time + LIFE_TICKS});
        boolean keep = creative || w.info.infState == WorldInfo.InfState.CURED;
        if (!keep) inv.takeSelected();
        return keep ? "Розрив відчинено (ключ лишився: Шов тобі довіряє). Він закриється за хвилину." : "Розрив відчинено. Він закриється за хвилину.";
    }

    /** Закрити розриви, чий час минув (лише в завантажених чанках; решта - коли завантажаться). */
    static void tick(World w) {
        if (w.info.rifts.isEmpty()) return;
        BlockType rift = w.blocks.byName(RIFT), air = w.blocks.get(0);
        for (java.util.Iterator<long[]> it = w.info.rifts.iterator(); it.hasNext(); ) {
            long[] r = it.next();
            if (w.info.time < r[3]) continue;
            if (w.blockId(r[0], (int) r[1], r[2]) < 0) continue;
            for (int dy = 0; dy < 2; dy++) if (rift != null && w.blockId(r[0], (int) r[1] + dy, r[2]) == rift.id) w.setBlock(Sources.SYSTEM, r[0], (int) r[1] + dy, r[2], air);
            it.remove();
        }
    }
}
