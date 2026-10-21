package voxelin.core;

/** Меню з сіткою крафту n x n (2 - в інвентарі, 3 - верстак): слоти 0..35 інвентар, 36 результат (лише брати), 37.. сітка по рядках. */
public final class CraftMenu extends Menu {
    public static final int RESULT = 36, GRID = 37;
    public final int n;
    /** Індекс першого слота броні (є лише в інвентарі гравця, n == 2); -1 - броні нема. */
    public final int armorBase;
    private final Inventory inv;
    private final Inventory.Stack[] grid;
    private final RecipeRegistry recipes;
    private Inventory.Stack result;
    private boolean took;

    public CraftMenu(ItemRegistry items, RecipeRegistry recipes, Inventory inv, int n) {
        super(items);
        this.n = n; this.recipes = recipes; this.inv = inv; grid = new Inventory.Stack[n * n];
        armorBase = n == 2 ? GRID + n * n : -1;
        addInventory(inv);
        slots.add(new Slot() {
            public Inventory.Stack get() { return result; }
            public void set(Inventory.Stack s) { /* результат задає лише співставлення */ }
            public boolean canPlace(Inventory.Stack s) { return false; }
            public boolean takeOnly() { return true; }
            public void onTake(Inventory.Stack taken) { consume(); }
        });
        for (int k = 0; k < n * n; k++) {
            final int idx = k;
            slots.add(new Slot() {
                public Inventory.Stack get() { return grid[idx]; }
                public void set(Inventory.Stack s) { grid[idx] = s == null || s.count <= 0 ? null : s; }
            });
        }
        if (armorBase >= 0) for (int k = 0; k < 4; k++) {
            final int slot = k;
            slots.add(new Slot() {
                public Inventory.Stack get() { return inv.armor(slot); }
                public void set(Inventory.Stack s) { inv.setArmor(slot, s); }
                public boolean canPlace(Inventory.Stack s) { ItemType t = items == null ? null : items.get(s.item); return t != null && t.armorSlot != null && t.armorSlot.ordinal() == slot && s.count == 1; }
            });
        }
    }

    @Override protected int[] shiftTarget(int i, Inventory.Stack st) {
        if (armorBase >= 0 && i >= armorBase) return new int[]{0, Inventory.SIZE - 1};
        if (armorBase >= 0 && i < Inventory.SIZE && st != null) {
            ItemType t = items == null ? null : items.get(st.item);
            if (t != null && t.armorSlot != null && inv.armor(t.armorSlot.ordinal()) == null) return new int[]{armorBase + t.armorSlot.ordinal(), armorBase + t.armorSlot.ordinal()};
        }
        return super.shiftTarget(i, st);
    }

    @Override protected int[] shiftTarget(int i) {
        if (i >= RESULT) return new int[]{0, Inventory.SIZE - 1};
        return super.shiftTarget(i);
    }

    private void consume() {
        took = true;
        for (int k = 0; k < grid.length; k++) if (grid[k] != null && --grid[k].count <= 0) grid[k] = null;
        refresh();
    }

    public void refresh() { result = Crafting.match(recipes, items, grid, n); }

    @Override public void click(int i, int button, boolean shift) {
        if (i == RESULT && shift) { // shift по результату - крафтимо, доки є з чого й куди класти
            for (int guard = 0; guard < 512 && result != null; guard++) { took = false; super.click(i, 0, true); if (!took) break; }
            refresh();
            return;
        }
        super.click(i, button, shift);
        refresh();
    }

    @Override public void doubleClick(int i) { super.doubleClick(i); refresh(); }

    /** Закриття вікна повертає лише вміст сітки; броня лишається надягнутою. */
    @Override public java.util.List<Inventory.Stack> drainExtra() {
        java.util.List<Inventory.Stack> out = new java.util.ArrayList<>();
        for (int k = 0; k < grid.length; k++) if (grid[k] != null) { out.add(grid[k]); grid[k] = null; }
        return out;
    }

    /** Поточний результат (null - нема). */
    public Inventory.Stack result() { return result; }
    public Inventory.Stack gridStack(int k) { return k >= 0 && k < grid.length ? grid[k] : null; }
}
