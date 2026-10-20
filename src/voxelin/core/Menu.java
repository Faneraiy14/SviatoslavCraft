package voxelin.core;

import java.util.*;

/**
 * Меню предметів: набір слотів + «курсорний» стек (те, що гравець тримає мишею). Уся логіка кліків тут, без вікна, тому тестується:
 * ліва кнопка - взяти/покласти/обміняти/злити, права - половина/по одному, shift - перекласти в іншу частину, подвійний клік - зібрати однакові.
 * Слоти інвентарю гравця мають ті самі номери, що в Inventory (0..8 - хотбар, 9..35 - сховище); додаткові слоти (сітка крафту, піч) йдуть далі.
 */
public class Menu {
    /** Доступ до слота: стек, правила (що можна класти, чи лише брати). */
    public interface Slot {
        Inventory.Stack get();
        void set(Inventory.Stack s);
        default boolean canPlace(Inventory.Stack s) { return true; }
        /** Лише брати (результат крафту, вихід печі). */
        default boolean takeOnly() { return false; }
        /** Гравець забрав стек з цього слота (крафт витрачає інгредієнти тощо). */
        default void onTake(Inventory.Stack taken) {}
    }

    protected final List<Slot> slots = new ArrayList<>();
    protected final ItemRegistry items;
    private Inventory.Stack cursor;

    public Menu(ItemRegistry items) { this.items = items; }

    /** Слоти 0..35 = інвентар гравця. */
    protected final void addInventory(Inventory inv) {
        for (int i = 0; i < Inventory.SIZE; i++) {
            final int idx = i;
            slots.add(new Slot() {
                public Inventory.Stack get() { return inv.get(idx); }
                public void set(Inventory.Stack s) { inv.set(idx, s); }
            });
        }
    }

    public int size() { return slots.size(); }
    public Inventory.Stack get(int i) { return i >= 0 && i < slots.size() ? slots.get(i).get() : null; }
    public Inventory.Stack cursor() { return cursor; }
    public boolean takeOnly(int i) { return i >= 0 && i < slots.size() && slots.get(i).takeOnly(); }

    protected int maxStack(String item) { return items == null ? Inventory.MAX_STACK : items.maxStack(item); }

    private static boolean same(Inventory.Stack a, Inventory.Stack b) { return a.item.equals(b.item) && a.damage == b.damage; }

    /** Діапазон слотів, куди shift-клік переносить стек зі слота i: {від, до} включно; null - нікуди. */
    protected int[] shiftTarget(int i) {
        if (i < Inventory.HOTBAR) return new int[]{Inventory.HOTBAR, Inventory.SIZE - 1};
        if (i < Inventory.SIZE) return new int[]{0, Inventory.HOTBAR - 1};
        return null;
    }

    /** Клік по слоту. button: 0 - ліва, 1 - права. */
    public void click(int i, int button, boolean shift) {
        if (i < 0 || i >= slots.size()) return;
        Slot s = slots.get(i);
        if (shift) { shiftClick(i); return; }
        Inventory.Stack st = s.get();
        if (s.takeOnly()) { takeOutput(s, st); return; }
        if (button == 0) leftClick(s, st); else rightClick(s, st);
    }

    private void takeOutput(Slot s, Inventory.Stack st) {
        if (st == null) return;
        if (cursor == null) { cursor = st; s.set(null); s.onTake(st); }
        else if (same(cursor, st) && cursor.count + st.count <= maxStack(cursor.item)) { cursor.count += st.count; s.set(null); s.onTake(st); }
    }

    private void leftClick(Slot s, Inventory.Stack st) {
        if (cursor == null) { if (st != null) { cursor = st; s.set(null); } return; }
        if (st == null) {
            if (!s.canPlace(cursor)) return;
            int max = maxStack(cursor.item);
            if (cursor.count <= max) { s.set(cursor); cursor = null; }
            else { s.set(new Inventory.Stack(cursor.item, max, cursor.damage)); cursor.count -= max; }
        } else if (same(st, cursor)) {
            int m = Math.min(maxStack(st.item) - st.count, cursor.count);
            if (m > 0) { st.count += m; cursor.count -= m; s.set(st); }
            if (cursor.count <= 0) cursor = null;
        } else if (s.canPlace(cursor)) { s.set(cursor); cursor = st; }
    }

    private void rightClick(Slot s, Inventory.Stack st) {
        if (cursor == null) {
            if (st == null) return;
            int half = (st.count + 1) / 2;
            cursor = new Inventory.Stack(st.item, half, st.damage);
            st.count -= half;
            s.set(st.count <= 0 ? null : st);
            return;
        }
        if (st == null) {
            if (!s.canPlace(cursor)) return;
            s.set(new Inventory.Stack(cursor.item, 1, cursor.damage));
            if (--cursor.count <= 0) cursor = null;
        } else if (same(st, cursor)) {
            if (st.count < maxStack(st.item)) { st.count++; s.set(st); if (--cursor.count <= 0) cursor = null; }
        } else if (s.canPlace(cursor)) { s.set(cursor); cursor = st; }
    }

    private void shiftClick(int i) {
        Slot s = slots.get(i);
        Inventory.Stack st = s.get();
        if (st == null || cursor != null) return;
        int[] range = shiftTarget(i);
        if (range == null) return;
        if (s.takeOnly()) { // результат: переносимо, лише якщо влізе повністю (інакше крафт «з'їв» би речі)
            if (!fits(st, range[0], range[1])) return;
            s.set(null); s.onTake(st);
            moveInto(st, range[0], range[1]);
            return;
        }
        moveInto(st, range[0], range[1]);
        s.set(st.count <= 0 ? null : st);
    }

    private boolean fits(Inventory.Stack st, int from, int to) {
        int room = 0, max = maxStack(st.item);
        for (int j = from; j <= to; j++) {
            Slot t = slots.get(j);
            if (t.takeOnly() || !t.canPlace(st)) continue;
            Inventory.Stack x = t.get();
            if (x == null) room += max; else if (same(x, st)) room += max - x.count;
        }
        return room >= st.count;
    }

    /** Перекласти стек у слоти [from,to]: спершу докладаємо до наявних, потім у порожні. Те, що не влізло, лишається в st. */
    private void moveInto(Inventory.Stack st, int from, int to) {
        int max = maxStack(st.item);
        for (int j = from; j <= to && st.count > 0; j++) {
            Slot t = slots.get(j);
            Inventory.Stack x = t.get();
            if (t.takeOnly() || x == null || !same(x, st) || x.count >= max || !t.canPlace(st)) continue;
            int m = Math.min(max - x.count, st.count);
            x.count += m; st.count -= m; t.set(x);
        }
        for (int j = from; j <= to && st.count > 0; j++) {
            Slot t = slots.get(j);
            if (t.takeOnly() || t.get() != null || !t.canPlace(st)) continue;
            int m = Math.min(max, st.count);
            t.set(new Inventory.Stack(st.item, m, st.damage)); st.count -= m;
        }
    }

    /** Подвійний клік: зібрати в курсор усі однакові предмети з усіх слотів (спершу з неповних стопок), до розміру стопки. */
    public void doubleClick(int i) {
        if (cursor == null) return;
        int max = maxStack(cursor.item);
        for (int pass = 0; pass < 2 && cursor.count < max; pass++) {
            for (Slot s : slots) {
                if (cursor.count >= max) break;
                Inventory.Stack x = s.get();
                if (s.takeOnly() || x == null || !same(x, cursor)) continue;
                boolean full = x.count >= max;
                if ((pass == 0) == full) continue;
                int m = Math.min(max - cursor.count, x.count);
                cursor.count += m; x.count -= m;
                s.set(x.count <= 0 ? null : x);
            }
        }
    }

    /** Забрати стек з курсора (клік поза вікном: викинути). one=true - лише один предмет. null - курсор порожній. */
    public Inventory.Stack takeCursor(boolean one) {
        if (cursor == null) return null;
        if (!one || cursor.count == 1) { Inventory.Stack c = cursor; cursor = null; return c; }
        cursor.count--;
        return new Inventory.Stack(cursor.item, 1, cursor.damage);
    }

    /** Закриття вікна: курсорний стек повертається в інвентар; те, що не влізло, віддається викликачу (викинути на землю). */
    public Inventory.Stack closeCursor(Inventory inv) {
        Inventory.Stack c = cursor;
        cursor = null;
        if (c == null) return null;
        int left = inv.add(c.item, c.count, c.damage);
        return left > 0 ? new Inventory.Stack(c.item, left, c.damage) : null;
    }

    /** Усе, що лежить у додаткових слотах (не інвентар) - для повернення гравцю при закритті (сітка крафту). */
    public List<Inventory.Stack> drainExtra() {
        List<Inventory.Stack> out = new ArrayList<>();
        for (int i = Inventory.SIZE; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.takeOnly()) continue;
            Inventory.Stack x = s.get();
            if (x != null) { out.add(x); s.set(null); }
        }
        return out;
    }
}
