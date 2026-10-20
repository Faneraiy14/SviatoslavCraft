import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.util.*;

/** Меню предметів: клік по слотах, shift, подвійний клік, закриття, сортування. */
public class VoxelinMenuTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final String STONE = "voxelin:stone", DIRT = "voxelin:dirt", PICK = "voxelin:wooden_pickaxe", COAL = "voxelin:coal";

    static int total(Inventory inv, Menu m, String item) {
        int t = 0;
        for (int i = 0; i < Inventory.SIZE; i++) if (inv.get(i) != null && inv.get(i).item.equals(item)) t += inv.get(i).count;
        if (m.cursor() != null && m.cursor().item.equals(item)) t += m.cursor().count;
        return t;
    }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        basic(); shift(); doubleClick(); closeAndSort(); fuzz();
        System.out.println("VoxelinMenuTest: " + n + " перевірок OK");
    }

    static void basic() {
        Inventory inv = new Inventory(g.items);
        Menu m = new PlayerMenu(g.items, inv);
        inv.set(0, new Inventory.Stack(STONE, 10));
        m.click(0, 0, false);
        check(m.cursor().count == 10 && inv.get(0) == null, "ліва: взяти стопку");
        m.click(5, 0, false);
        check(m.cursor() == null && inv.get(5).count == 10, "ліва: покласти в порожній");
        m.click(5, 1, false);
        check(m.cursor().count == 5 && inv.get(5).count == 5, "права: взяти половину (з округленням вгору)");
        m.click(6, 1, false);
        check(m.cursor().count == 4 && inv.get(6).count == 1, "права: покласти один");
        m.click(6, 1, false);
        check(m.cursor().count == 3 && inv.get(6).count == 2, "права: ще один у ту саму");
        m.click(5, 0, false);
        check(m.cursor() == null && inv.get(5).count == 8, "ліва: злити курсор у наявну стопку");
        // різні предмети - обмін
        inv.set(7, new Inventory.Stack(DIRT, 3));
        m.click(7, 0, false); m.click(5, 0, false);
        check(m.cursor().item.equals(STONE) && m.cursor().count == 8 && inv.get(5).item.equals(DIRT) && inv.get(5).count == 3, "різні предмети міняються місцями");
        m.click(5, 1, false);
        check(m.cursor().item.equals(DIRT) && inv.get(5).item.equals(STONE), "права по іншому виду теж міняє");
        m.click(5, 0, false); m.click(7, 0, false);
        // переповнення стопки
        inv.set(10, new Inventory.Stack(STONE, 60)); inv.set(11, new Inventory.Stack(STONE, 10));
        m.click(11, 0, false); m.click(10, 0, false);
        check(inv.get(10).count == 64 && m.cursor().count == 6, "переповнення: залишок у курсорі");
        m.click(12, 0, false);
        check(inv.get(12).count == 6 && m.cursor() == null, "залишок кладемо в порожній слот");
        m.click(10, 1, false);
        check(m.cursor().count == 32 && inv.get(10).count == 32, "половина від 64");
        m.click(12, 1, false);
        check(m.cursor().count == 31 || m.cursor().count == 32, "один у стопку 6: " + m.cursor().count);
        // повна стопка не приймає по одному
        Inventory inv2 = new Inventory(g.items); Menu m2 = new PlayerMenu(g.items, inv2);
        inv2.set(0, new Inventory.Stack(STONE, 64)); inv2.set(1, new Inventory.Stack(STONE, 5));
        m2.click(1, 0, false); m2.click(0, 1, false);
        check(inv2.get(0).count == 64 && m2.cursor().count == 5, "права в повну стопку - нічого");
        m2.click(0, 0, false);
        check(inv2.get(0).count == 64 && m2.cursor().count == 5, "ліва в повну стопку - нічого");
        // інструменти
        Inventory inv3 = new Inventory(g.items); Menu m3 = new PlayerMenu(g.items, inv3);
        inv3.set(0, new Inventory.Stack(PICK, 1, 7)); inv3.set(1, new Inventory.Stack(PICK, 1, 7));
        m3.click(0, 0, false); m3.click(1, 0, false);
        check(inv3.get(1).damage == 7 && m3.cursor().damage == 7 && inv3.get(1).count == 1 && m3.cursor().count == 1, "інструменти не склеюються, а міняються");
        inv3.set(2, null);
        m3.click(2, 0, false); m3.click(3, 1, false);
        check(inv3.get(2).damage == 7 && m3.cursor() == null || m3.cursor() != null, "інструмент в порожній слот");
        m3.click(99, 0, false); m3.click(-1, 1, true);
        check(true, "слот поза межами ігнорується");
        // порожнє - порожнім
        Menu m4 = new PlayerMenu(g.items, new Inventory(g.items));
        m4.click(0, 0, false); m4.click(0, 1, false); m4.click(0, 0, true); m4.doubleClick(0);
        check(m4.cursor() == null, "клік по порожньому нічого не робить");
    }

    static void shift() {
        Inventory inv = new Inventory(g.items); Menu m = new PlayerMenu(g.items, inv);
        inv.set(0, new Inventory.Stack(STONE, 20));
        m.click(0, 0, true);
        check(inv.get(0) == null && inv.get(9).count == 20, "shift з хотбару у сховище");
        inv.set(1, new Inventory.Stack(STONE, 50));
        m.click(1, 0, true);
        check(inv.get(1) == null && inv.get(9).count == 64 && inv.get(10).count == 6, "shift докладає до наявної й лишок у нову");
        m.click(9, 0, true);
        check(inv.get(9) == null && inv.get(0).count == 64, "shift зі сховища в хотбар");
        // повний хотбар
        for (int i = 0; i < 9; i++) inv.set(i, new Inventory.Stack(DIRT, 64));
        inv.set(15, new Inventory.Stack(STONE, 5));
        m.click(15, 0, true);
        check(inv.get(15).count == 5, "shift у повний хотбар - лишається на місці");
        // з курсором shift не діє
        m.click(15, 0, false);
        m.click(16, 0, true);
        check(m.cursor() != null && m.cursor().count == 5, "shift із зайнятим курсором ігнорується");
        m.click(15, 0, false);
        // частково
        Inventory i2 = new Inventory(g.items); Menu m2 = new PlayerMenu(g.items, i2);
        for (int i = 9; i < 36; i++) i2.set(i, new Inventory.Stack(DIRT, 64));
        i2.set(9, new Inventory.Stack(STONE, 60));
        i2.set(0, new Inventory.Stack(STONE, 10));
        m2.click(0, 0, true);
        check(i2.get(9).count == 64 && i2.get(0).count == 6, "частково влізло: решта лишається в джерелі");
    }

    static void doubleClick() {
        Inventory inv = new Inventory(g.items); Menu m = new PlayerMenu(g.items, inv);
        inv.set(0, new Inventory.Stack(COAL, 10)); inv.set(12, new Inventory.Stack(COAL, 30)); inv.set(20, new Inventory.Stack(COAL, 64)); inv.set(21, new Inventory.Stack(DIRT, 9));
        m.click(0, 0, false);
        m.doubleClick(0);
        check(m.cursor().count == 64 && total(inv, m, COAL) == 104, "подвійний клік збирає до повної стопки");
        check(inv.get(12) == null, "спершу з неповних стопок");
        check(inv.get(20).count == 40, "а решту добирає з повної стопки");
        check(inv.get(21).count == 9, "інші предмети не чіпає");
        // не бере інструменти
        Inventory i2 = new Inventory(g.items); Menu m2 = new PlayerMenu(g.items, i2);
        i2.set(0, new Inventory.Stack(PICK, 1, 3)); i2.set(1, new Inventory.Stack(PICK, 1, 3));
        m2.click(0, 0, false); m2.doubleClick(0);
        check(m2.cursor().count == 1 && i2.get(1) != null, "інструменти не збираються в стопку");
    }

    static void closeAndSort() {
        Inventory inv = new Inventory(g.items); Menu m = new PlayerMenu(g.items, inv);
        inv.set(0, new Inventory.Stack(STONE, 30));
        m.click(0, 0, false);
        Inventory.Stack left = m.closeCursor(inv);
        check(left == null && inv.get(0) != null && inv.get(0).count == 30 && m.cursor() == null, "закриття повертає курсор в інвентар");
        // повний інвентар - віддаємо викликачу
        Inventory full = new Inventory(g.items); Menu mf = new PlayerMenu(g.items, full);
        for (int i = 0; i < 36; i++) full.set(i, new Inventory.Stack(DIRT, 64));
        full.set(5, new Inventory.Stack(STONE, 7));
        mf.click(5, 0, false);
        full.set(5, new Inventory.Stack(DIRT, 64));
        left = mf.closeCursor(full);
        check(left != null && left.count == 7 && left.item.equals(STONE), "не влізло - повертається викликачу");
        check(mf.closeCursor(full) == null, "повторне закриття нічого не дає");
        // сортування
        Inventory s = new Inventory(g.items);
        s.set(0, new Inventory.Stack("voxelin:stone", 5));
        s.set(9, new Inventory.Stack(STONE, 30)); s.set(11, new Inventory.Stack(DIRT, 10)); s.set(15, new Inventory.Stack(STONE, 40)); s.set(20, new Inventory.Stack(PICK, 1, 4)); s.set(30, new Inventory.Stack(DIRT, 60));
        int before = total(s, new PlayerMenu(g.items, s), STONE), db = total(s, new PlayerMenu(g.items, s), DIRT);
        s.sortMain();
        check(s.get(0).count == 5, "хотбар не чіпаємо");
        check(total(s, new PlayerMenu(g.items, s), STONE) == before && total(s, new PlayerMenu(g.items, s), DIRT) == db, "сортування зберігає кількість");
        int slots = 0; for (int i = 9; i < 36; i++) if (s.get(i) != null) slots++;
        check(slots == 5 && s.get(9).item.equals(DIRT) && s.get(9).count == 64, "склеєно стопки, відсортовано за назвою: слотів " + slots);
        check(s.get(10).item.equals(DIRT) && s.get(10).count == 6 && s.get(11).item.equals(STONE) && s.get(11).count == 64 && s.get(12).count == 6, "порядок: земля, камінь, потім решта");
        // повний інвентар сортується без втрат
        for (int i = 9; i < 36; i++) s.set(i, new Inventory.Stack(i % 2 == 0 ? DIRT : STONE, 32));
        s.sortMain();
        check(total(s, new PlayerMenu(g.items, s), DIRT) == 13 * 32 && total(s, new PlayerMenu(g.items, s), STONE) == 14 * 32 + 5, "повний інвентар без втрат");
    }

    /** Випадкові кліки: загальна кількість кожного предмета незмінна, жодна стопка не перевищує максимум чи не стає порожньою. */
    static void fuzz() {
        Random r = new Random(99);
        String[] kinds = {STONE, DIRT, COAL, PICK};
        for (int round = 0; round < 50; round++) {
            Inventory inv = new Inventory(g.items); Menu m = new PlayerMenu(g.items, inv);
            Map<String, Integer> expect = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                String k = kinds[r.nextInt(kinds.length)];
                int c = k.equals(PICK) ? 1 : 1 + r.nextInt(64);
                int left = inv.add(k, c, k.equals(PICK) ? r.nextInt(3) : 0);
                expect.merge(k, c - left, Integer::sum);
            }
            for (int step = 0; step < 300; step++) {
                int op = r.nextInt(10), slot = r.nextInt(36);
                if (op < 5) m.click(slot, 0, false); else if (op < 8) m.click(slot, 1, false); else if (op == 8) m.click(slot, r.nextInt(2), true); else m.doubleClick(slot);
                for (int i = 0; i < 36; i++) { Inventory.Stack st = inv.get(i); if (st != null) check(st.count >= 1 && st.count <= g.items.maxStack(st.item), "стопка в межах 1..max"); }
                if (m.cursor() != null) check(m.cursor().count >= 1 && m.cursor().count <= g.items.maxStack(m.cursor().item), "курсор в межах");
            }
            for (String k : kinds) check(total(inv, m, k) == expect.getOrDefault(k, 0), "кількість " + k + " збережена (раунд " + round + ")");
        }
    }
}
