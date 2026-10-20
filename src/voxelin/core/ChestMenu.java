package voxelin.core;

import java.util.List;

/** Меню скрині: слоти 0..35 інвентар, 36..62 скриня. Shift: з інвентарю в скриню й назад. Вміст лишається в скрині при закритті. */
public final class ChestMenu extends Menu {
    public static final int CHEST = 36;
    public final ChestEntity chest;

    public ChestMenu(ItemRegistry items, Inventory inv, ChestEntity c) {
        super(items);
        this.chest = c;
        addInventory(inv);
        for (int k = 0; k < ChestEntity.SIZE; k++) {
            final int idx = k;
            slots.add(new Slot() {
                public Inventory.Stack get() { return c.get(idx); }
                public void set(Inventory.Stack s) { c.set(idx, s); }
            });
        }
    }

    @Override protected int[] shiftTarget(int i) {
        if (i < CHEST) return new int[]{CHEST, CHEST + ChestEntity.SIZE - 1};
        return new int[]{0, Inventory.SIZE - 1};
    }

    @Override public List<Inventory.Stack> drainExtra() { return List.of(); }
}
