package voxelin.core;

/** Меню з сіткою крафту n x n (2 - в інвентарі, 3 - верстак): слоти 0..35 інвентар, 36 результат (лише брати), 37.. сітка по рядках. */
public final class CraftMenu extends Menu {
    public static final int RESULT = 36, GRID = 37;
    public final int n;
    private final Inventory.Stack[] grid;
    private final RecipeRegistry recipes;
    private Inventory.Stack result;
    private boolean took;

    public CraftMenu(ItemRegistry items, RecipeRegistry recipes, Inventory inv, int n) {
        super(items);
        this.n = n; this.recipes = recipes; grid = new Inventory.Stack[n * n];
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

    /** Поточний результат (null - нема). */
    public Inventory.Stack result() { return result; }
    public Inventory.Stack gridStack(int k) { return k >= 0 && k < grid.length ? grid[k] : null; }
}
