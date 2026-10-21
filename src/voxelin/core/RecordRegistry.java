package voxelin.core;

import java.util.*;

/** Реєстр записів журналу (id -> назва й текст). Записи відкриваються подіями гри й зберігаються у світі. */
public final class RecordRegistry {
    public static final class Record {
        public final String id, title, text;
        Record(String id, String title, String text) { this.id = id; this.title = title; this.text = text; }
    }

    private final Map<String, Record> byId = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized void add(String id, String title, String text) {
        if (frozen) throw new IllegalStateException("реєстр записів уже закритий: " + id);
        if (!id.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва запису: " + id);
        if (byId.containsKey(id)) throw new IllegalArgumentException("запис уже є: " + id);
        if (title == null || title.isBlank() || text == null) throw new IllegalArgumentException("запис без назви: " + id);
        byId.put(id, new Record(id, title, text));
    }

    public void freeze() { frozen = true; }
    public Record get(String id) { return id == null ? null : byId.get(id); }
    public Collection<Record> all() { return Collections.unmodifiableCollection(byId.values()); }
}
