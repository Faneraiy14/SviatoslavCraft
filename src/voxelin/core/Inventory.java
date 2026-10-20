package voxelin.core;

import voxelin.api.ItemHolder;

import java.util.*;

/**
 * Інвентар гравця як дані: слоти з назвою предмета, кількістю й зношеністю (damage - скільки міцності витрачено). Розмір стопки бере з реєстру предметів
 * (інструменти - по 1). Зберігається у світі (encode/decode), тож перезапуск не губить речі.
 */
public final class Inventory implements ItemHolder {
    public static final int HOTBAR = 9, SIZE = 36, MAX_STACK = 64;

    public static final class Stack {
        public final String item; public int count, damage;
        public Stack(String item, int count) { this(item, count, 0); }
        public Stack(String item, int count, int damage) { this.item = item; this.count = count; this.damage = damage; }
    }

    private final Stack[] slots = new Stack[SIZE];
    private final ItemRegistry items;
    private int selected;

    public Inventory() { this(null); }
    public Inventory(ItemRegistry items) { this.items = items; }

    private int maxStack(String item) { return items == null ? MAX_STACK : items.maxStack(item); }

    public Stack get(int i) { return i >= 0 && i < SIZE ? slots[i] : null; }
    public void set(int i, Stack s) { if (i >= 0 && i < SIZE) slots[i] = s == null || s.count <= 0 ? null : s; }
    public int selected() { return selected; }
    public void select(int i) { if (i >= 0 && i < HOTBAR) selected = i; }
    public void scroll(int d) { selected = Math.floorMod(selected + d, HOTBAR); }
    public Stack selectedStack() { return slots[selected]; }

    public int add(String item, int count) { return add(item, count, 0); }

    /** Додати предмет: спершу в наявні стопки (з тією ж зношеністю), потім у порожні слоти. Повертає, скільки НЕ влізло. */
    public int add(String item, int count, int damage) {
        int max = maxStack(item);
        for (int i = 0; i < SIZE && count > 0; i++) if (slots[i] != null && slots[i].item.equals(item) && slots[i].damage == damage && slots[i].count < max) {
            int m = Math.min(count, max - slots[i].count); slots[i].count += m; count -= m;
        }
        for (int i = 0; i < SIZE && count > 0; i++) if (slots[i] == null) { int m = Math.min(count, max); slots[i] = new Stack(item, m, damage); count -= m; }
        return count;
    }

    /** Чи влізе (повністю) цей стек - для підбору з землі. */
    public boolean canFit(String item, int count, int damage) {
        int max = maxStack(item), room = 0;
        for (Stack s : slots) { if (s == null) room += max; else if (s.item.equals(item) && s.damage == damage && s.count < max) room += max - s.count; if (room >= count) return true; }
        return room >= count;
    }

    @Override public int give(String item, int count) { return count <= 0 ? 0 : add(item, count, 0); }

    @Override public void clear() { Arrays.fill(slots, null); }

    /** Забрати один предмет із вибраного слота. */
    public void takeSelected() {
        Stack s = slots[selected];
        if (s != null && --s.count <= 0) slots[selected] = null;
    }

    /** Зношення вибраного предмета на 1 удар; повертає true, якщо він зламався (зник). durability <= 0 - не ламається. */
    public boolean wearSelected(int durability) {
        Stack s = slots[selected];
        if (s == null || durability <= 0) return false;
        if (++s.damage >= durability) { slots[selected] = null; return true; }
        return false;
    }

    /** Забрати все з інвентаря (смерть): повертає стеки, слоти порожні. */
    public List<Stack> drain() {
        List<Stack> out = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) if (slots[i] != null) { out.add(slots[i]); slots[i] = null; }
        return out;
    }

    public boolean isEmpty() { for (Stack s : slots) if (s != null) return false; return true; }

    // ------------------------------------------------------------------ збереження

    /** Слоти як рядки «назва|кількість|зношеність» (null - порожній слот). */
    public String[] encode() {
        String[] r = new String[SIZE];
        for (int i = 0; i < SIZE; i++) if (slots[i] != null) r[i] = slots[i].item + "|" + slots[i].count + "|" + slots[i].damage;
        return r;
    }

    /** Прочитати збережене; биті записи (невідомий формат, кількість поза 1..максимум) ігноруються. Невідомі предмети (мод зник) лишаються як є. */
    public void decode(String[] lines, int selectedSlot) {
        Arrays.fill(slots, null);
        for (int i = 0; i < SIZE && lines != null && i < lines.length; i++) {
            if (lines[i] == null) continue;
            String[] p = lines[i].split("\\|");
            if (p.length != 3 || !p[0].matches("[a-z0-9_]+:[a-z0-9_/]+")) continue;
            try {
                int c = Integer.parseInt(p[1].trim()), d = Integer.parseInt(p[2].trim());
                if (c < 1 || c > maxStack(p[0]) || d < 0) continue;
                slots[i] = new Stack(p[0], c, d);
            } catch (NumberFormatException e) { /* бита клітинка - пропускаємо */ }
        }
        select(selectedSlot);
    }
}
