package voxelin.core;

import voxelin.api.MobSettings;

import java.util.ArrayList;
import java.util.List;

/** Торгівля з жителями: угоди «віддай - отримай» виконуються атомарно (або повністю, або нічого). Чисті функції над Inventory. */
public final class Trading {
    private Trading() {}

    /** Угоди виду, у яких усі предмети відомі (невідомі - мод зник - відкидаються). */
    public static List<MobSettings.Trade> offers(MobType t, ItemRegistry items) {
        List<MobSettings.Trade> r = new ArrayList<>();
        for (MobSettings.Trade tr : t.trades) if (items.get(tr.in) != null && items.get(tr.out) != null) r.add(tr);
        return r;
    }

    /** Скільки цілих (незношених) предметів item у гравця. */
    public static int count(Inventory inv, ItemRegistry items, String item) {
        ItemType it = items.get(item);
        int n = 0;
        for (int i = 0; i < Inventory.SIZE; i++) {
            Inventory.Stack s = inv.get(i);
            if (s != null && s.item.equals(item) && (it == null || it.durability <= 0 || s.damage == 0)) n += s.count;
        }
        return n;
    }

    /** Чи можна виконати угоду: є що віддати й є куди покласти результат. */
    public static boolean canTrade(Inventory inv, ItemRegistry items, MobSettings.Trade t) {
        if (items.get(t.in) == null || items.get(t.out) == null) return false;
        if (count(inv, items, t.in) < t.inCount) return false;
        Inventory copy = copyOf(inv, items);
        take(copy, items, t.in, t.inCount);
        return copy.add(t.out, t.outCount) == 0;
    }

    /** Виконати: true - угоду здійснено. Інвентар не змінюється, якщо не вдалося. */
    public static boolean execute(Inventory inv, ItemRegistry items, MobSettings.Trade t) {
        if (!canTrade(inv, items, t)) return false;
        take(inv, items, t.in, t.inCount);
        inv.add(t.out, t.outCount, 0);
        return true;
    }

    private static Inventory copyOf(Inventory inv, ItemRegistry items) {
        Inventory c = new Inventory(items);
        c.decode(inv.encode(), inv.selected());
        return c;
    }

    private static void take(Inventory inv, ItemRegistry items, String item, int count) {
        ItemType it = items.get(item);
        int left = count;
        for (int i = 0; i < Inventory.SIZE && left > 0; i++) {
            Inventory.Stack s = inv.get(i);
            if (s == null || !s.item.equals(item) || (it != null && it.durability > 0 && s.damage != 0)) continue;
            int m = Math.min(left, s.count);
            s.count -= m; left -= m;
            if (s.count <= 0) inv.set(i, null);
        }
    }
}
