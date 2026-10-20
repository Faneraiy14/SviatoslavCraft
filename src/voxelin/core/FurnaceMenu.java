package voxelin.core;

import java.util.List;

/** Меню печі: слоти 0..35 інвентар, 36 вхід, 37 паливо, 38 вихід (лише брати). Слоти напряму читають стан печі. */
public final class FurnaceMenu extends Menu {
    public static final int INPUT = 36, FUEL = 37, OUTPUT = 38;
    public final FurnaceEntity furnace;
    private final RecipeRegistry recipes;

    public FurnaceMenu(ItemRegistry items, RecipeRegistry recipes, Inventory inv, FurnaceEntity f) {
        super(items);
        this.furnace = f; this.recipes = recipes;
        addInventory(inv);
        slots.add(new Slot() {
            public Inventory.Stack get() { return f.input; }
            public void set(Inventory.Stack s) { f.input = s == null || s.count <= 0 ? null : s; }
            public boolean canPlace(Inventory.Stack s) { return recipes.smeltFor(s.item) != null; }
        });
        slots.add(new Slot() {
            public Inventory.Stack get() { return f.fuel; }
            public void set(Inventory.Stack s) { f.fuel = s == null || s.count <= 0 ? null : s; }
            public boolean canPlace(Inventory.Stack s) { return recipes.fuelTicks(s.item) > 0; }
        });
        slots.add(new Slot() {
            public Inventory.Stack get() { return f.output; }
            public void set(Inventory.Stack s) { f.output = s == null || s.count <= 0 ? null : s; }
            public boolean canPlace(Inventory.Stack s) { return false; }
            public boolean takeOnly() { return true; }
        });
    }

    @Override protected int[] shiftTarget(int i, Inventory.Stack st) {
        if (i >= INPUT) return new int[]{0, Inventory.SIZE - 1};
        if (st != null && recipes.smeltFor(st.item) != null) return new int[]{INPUT, INPUT};
        if (st != null && recipes.fuelTicks(st.item) > 0) return new int[]{FUEL, FUEL};
        return super.shiftTarget(i, st);
    }

    /** Вміст печі лишається в печі при закритті вікна. */
    @Override public List<Inventory.Stack> drainExtra() { return List.of(); }
}
