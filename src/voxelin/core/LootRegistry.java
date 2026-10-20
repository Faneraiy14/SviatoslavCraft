package voxelin.core;

import voxelin.api.LootSettings;

import java.util.*;

/** Реєстр таблиць здобичі. При закритті записи з невідомими предметами відкидаються, порожня таблиця зникає (гра не ламається). */
public final class LootRegistry {
    private final Map<String, LootSettings> raw = new LinkedHashMap<>();
    private final Map<String, LootTable> tables = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void add(String id, LootSettings s) {
        if (frozen) throw new IllegalStateException("реєстр здобичі уже закритий: " + id);
        if (!id.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва таблиці: " + id);
        if (raw.containsKey(id)) throw new IllegalArgumentException("таблиця вже є: " + id);
        if (s.entryList().isEmpty()) throw new IllegalArgumentException("таблиця " + id + " порожня");
        raw.put(id, s);
    }

    public synchronized void freeze(ItemRegistry items) {
        for (Map.Entry<String, LootSettings> e : raw.entrySet()) {
            List<LootSettings.Entry> ok = new ArrayList<>();
            for (LootSettings.Entry en : e.getValue().entryList()) {
                if (items.get(en.item) == null) System.out.println("[Voxelin] ⚠️ здобич " + e.getKey() + ": невідомий предмет " + en.item);
                else ok.add(en);
            }
            if (!ok.isEmpty()) tables.put(e.getKey(), new LootTable(e.getKey(), e.getValue(), ok));
        }
        frozen = true;
    }

    /** За повною або короткою назвою (без двокрапки - voxelin). */
    public LootTable get(String id) {
        if (id == null) return null;
        LootTable t = tables.get(id);
        return t != null || id.indexOf(':') >= 0 ? t : tables.get("voxelin:" + id);
    }

    public Collection<LootTable> all() { return Collections.unmodifiableCollection(tables.values()); }
}
