package voxelin.api;

import java.util.*;

/** Таблиця здобичі для скринь: скільки разів кидаємо жереб (rolls) і що може випасти (предмет, кількість min..max, вага). */
public final class LootSettings {
    public static final class Entry {
        public final String item; public final int min, max, weight;
        public Entry(String item, int min, int max, int weight) { this.item = item; this.min = Math.max(1, min); this.max = Math.max(this.min, max); this.weight = Math.max(1, weight); }
    }

    int minRolls = 1, maxRolls = 3;
    final List<Entry> entries = new ArrayList<>();

    /** Скільки разів кидати жереб для однієї скрині (min..max). */
    public LootSettings rolls(int min, int max) { minRolls = Math.max(1, Math.min(27, min)); maxRolls = Math.max(minRolls, Math.min(27, max)); return this; }
    public LootSettings entry(String item, int min, int max, int weight) { entries.add(new Entry(item, min, max, weight)); return this; }

    public LootSettings qualified(String modId) {
        LootSettings s = new LootSettings();
        s.minRolls = minRolls; s.maxRolls = maxRolls;
        for (Entry e : entries) s.entries.add(new Entry(e.item.indexOf(':') >= 0 ? e.item : modId + ":" + e.item, e.min, e.max, e.weight));
        return s;
    }

    public int minRollsValue() { return minRolls; }
    public int maxRollsValue() { return maxRolls; }
    public List<Entry> entryList() { return Collections.unmodifiableList(entries); }
}
