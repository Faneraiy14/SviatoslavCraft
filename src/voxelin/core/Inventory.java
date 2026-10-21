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
    /** Надягнута броня: 0 шолом, 1 нагрудник, 2 поножі, 3 черевики. */
    private final Stack[] armor = new Stack[4];
    private final ItemRegistry items;
    private int selected;

    public Inventory() { this(null); }
    public Inventory(ItemRegistry items) { this.items = items; }

    private int maxStack(String item) { return items == null ? MAX_STACK : items.maxStack(item); }

    public Stack get(int i) { return i >= 0 && i < SIZE ? slots[i] : null; }
    public void set(int i, Stack s) { if (i >= 0 && i < SIZE) slots[i] = s == null || s.count <= 0 ? null : s; }
    public Stack armor(int slot) { return slot >= 0 && slot < 4 ? armor[slot] : null; }
    public void setArmor(int slot, Stack s) { if (slot >= 0 && slot < 4) armor[slot] = s == null || s.count <= 0 ? null : s; }

    public static final double ARMOR_PER_POINT = 0.04, ARMOR_CAP = 0.8;

    /** Шкода після броні: кожне очко - 4% менше, не більше 80%. */
    public static double reduce(double damage, int points) { return reduce(damage, points, 0); }

    /** Те саме, але удар пробиває частку pierce (0..1) захисту броні. */
    public static double reduce(double damage, int points, double pierce) {
        double p = Double.isNaN(pierce) ? 0 : Math.max(0, Math.min(1, pierce));
        return damage * (1.0 - Math.min(ARMOR_CAP, Math.max(0, points) * ARMOR_PER_POINT) * (1 - p));
    }

    /** Сума очок захисту надягнутої броні. */
    public int armorPoints(ItemRegistry reg) {
        int p = 0;
        for (Stack s : armor) { if (s == null) continue; ItemType t = reg == null ? null : reg.get(s.item); if (t != null && t.armorSlot != null) p += t.armorPoints; }
        return p;
    }

    /** Чи повний комплект броні рівня не нижче tier у всіх 4 слотах (правило входу в Енд: tier 4). */
    public boolean fullArmor(ItemRegistry reg, int tier) {
        for (int i = 0; i < 4; i++) {
            Stack s = armor[i]; ItemType t = s == null || reg == null ? null : reg.get(s.item);
            if (t == null || t.armorSlot == null || t.armorSlot.ordinal() != i || t.armorTier < tier) return false;
        }
        return true;
    }

    /** Надіти броню з вибраного слота руки: якщо слот броні зайнятий - міняються місцями. true - надягнуто. */
    public boolean equipSelected(ItemRegistry reg) {
        Stack st = slots[selected];
        ItemType t = st == null || reg == null ? null : reg.get(st.item);
        if (t == null || t.armorSlot == null) return false;
        int k = t.armorSlot.ordinal();
        Stack old = armor[k];
        armor[k] = st;
        slots[selected] = old;
        return true;
    }

    /** Знос броні від удару: випадковий надягнутий шматок втрачає max(1, шкода/4) міцності; 0 - предмет зникає. */
    public void wearArmor(ItemRegistry reg, double damage, java.util.Random rng) {
        java.util.List<Integer> worn = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) if (armor[i] != null) worn.add(i);
        if (worn.isEmpty()) return;
        int k = worn.get(rng.nextInt(worn.size()));
        ItemType t = reg == null ? null : reg.get(armor[k].item);
        if (t == null || t.durability <= 0) return;
        armor[k].damage += Math.max(1, (int) (damage / 4));
        if (armor[k].item.equals(WINGS)) { armor[k].damage = Math.min(armor[k].damage, t.durability - 1); return; } // крила не ламаються - лише перестають нести
        if (armor[k].damage >= t.durability) armor[k] = null;
    }

    /** Крила Шву (нагорода Ритуалу): надягаються замість нагрудника; планують, поки не зношені до останньої одиниці. */
    public static final String WINGS = "voxelin:seam_wings";

    public boolean canGlide(ItemRegistry reg) {
        Stack s = armor[1]; ItemType t = s == null || reg == null ? null : reg.get(s.item);
        return t != null && s.item.equals(WINGS) && (t.durability <= 0 || s.damage < t.durability - 1);
    }

    /** Зношення крил польотом (1 за секунду). */
    public void wearWings(ItemRegistry reg) {
        Stack s = armor[1]; ItemType t = s == null || reg == null ? null : reg.get(s.item);
        if (t != null && s.item.equals(WINGS) && t.durability > 0) s.damage = Math.min(t.durability - 1, s.damage + 1);
    }

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

    @Override public void clear() { Arrays.fill(slots, null); Arrays.fill(armor, null); }

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
        for (int i = 0; i < 4; i++) if (armor[i] != null) { out.add(armor[i]); armor[i] = null; }
        return out;
    }

    /** Упорядкувати сховище (слоти 9..35): склеїти однакові стопки й відсортувати за назвою. Хотбар не чіпаємо. */
    public void sortMain() {
        List<Stack> all = new ArrayList<>();
        for (int i = HOTBAR; i < SIZE; i++) if (slots[i] != null) { all.add(slots[i]); slots[i] = null; }
        all.sort(Comparator.comparing((Stack x) -> x.item).thenComparingInt(x -> x.damage).thenComparingInt(x -> -x.count));
        List<Stack> out = new ArrayList<>();
        for (Stack x : all) {
            int max = maxStack(x.item);
            while (x.count > 0) {
                Stack last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && last.item.equals(x.item) && last.damage == x.damage && last.count < max) { int m = Math.min(max - last.count, x.count); last.count += m; x.count -= m; }
                else { out.add(x); break; }
            }
        }
        for (int i = 0; i < out.size(); i++) slots[HOTBAR + i] = out.get(i);
    }

    public boolean isEmpty() { for (Stack s : slots) if (s != null) return false; for (Stack s : armor) if (s != null) return false; return true; }

    // ------------------------------------------------------------------ збереження

    /** Слоти як рядки «назва|кількість|зношеність» (null - порожній слот). */
    public String[] encode() {
        String[] r = new String[SIZE];
        for (int i = 0; i < SIZE; i++) if (slots[i] != null) r[i] = slots[i].item + "|" + slots[i].count + "|" + slots[i].damage;
        return r;
    }

    /** Броня як рядки «назва|кількість|зношеність» (null - порожньо). */
    public String[] encodeArmor() {
        String[] r = new String[4];
        for (int i = 0; i < 4; i++) if (armor[i] != null) r[i] = armor[i].item + "|" + armor[i].count + "|" + armor[i].damage;
        return r;
    }

    /** Прочитати броню; биті записи й предмети не для цього слота ігноруються. */
    public void decodeArmor(String[] lines) {
        Arrays.fill(armor, null);
        for (int i = 0; i < 4 && lines != null && i < lines.length; i++) {
            if (lines[i] == null) continue;
            String[] p = lines[i].split("\\|");
            if (p.length != 3 || !p[0].matches("[a-z0-9_]+:[a-z0-9_/]+")) continue;
            try {
                int d = Integer.parseInt(p[2].trim());
                ItemType t = items == null ? null : items.get(p[0]);
                if (d < 0 || Integer.parseInt(p[1].trim()) != 1 || t == null || t.armorSlot == null || t.armorSlot.ordinal() != i) continue;
                armor[i] = new Stack(p[0], 1, d);
            } catch (NumberFormatException e) { /* бита клітинка */ }
        }
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
