package voxelin.core;

/**
 * Інвентар як дані: слоти з назвою предмета й кількістю (поки предмет = блок). Етап «Виживання» перероблює інвентар і крафт
 * разом зі Святославом - ця модель лише прибирає старе обмеження «5 слотів = 5 типів блоків».
 */
public final class Inventory {
    public static final int HOTBAR = 9, SIZE = 36, MAX_STACK = 64;

    public static final class Stack {
        public final String item; public int count;
        public Stack(String item, int count) { this.item = item; this.count = count; }
    }

    private final Stack[] slots = new Stack[SIZE];
    private int selected;

    public Stack get(int i) { return i >= 0 && i < SIZE ? slots[i] : null; }
    public void set(int i, Stack s) { if (i >= 0 && i < SIZE) slots[i] = s == null || s.count <= 0 ? null : s; }
    public int selected() { return selected; }
    public void select(int i) { if (i >= 0 && i < HOTBAR) selected = i; }
    public void scroll(int d) { selected = Math.floorMod(selected + d, HOTBAR); }
    public Stack selectedStack() { return slots[selected]; }

    /** Додати предмет: спершу в наявні стопки, потім у порожні слоти. Повертає, скільки НЕ влізло. */
    public int add(String item, int count) {
        for (int i = 0; i < SIZE && count > 0; i++) if (slots[i] != null && slots[i].item.equals(item) && slots[i].count < MAX_STACK) {
            int m = Math.min(count, MAX_STACK - slots[i].count); slots[i].count += m; count -= m;
        }
        for (int i = 0; i < SIZE && count > 0; i++) if (slots[i] == null) { int m = Math.min(count, MAX_STACK); slots[i] = new Stack(item, m); count -= m; }
        return count;
    }

    /** Забрати один предмет із вибраного слота. */
    public void takeSelected() {
        Stack s = slots[selected];
        if (s != null && --s.count <= 0) slots[selected] = null;
    }
}
