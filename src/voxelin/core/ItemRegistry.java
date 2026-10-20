package voxelin.core;

import voxelin.api.ItemSettings;

import java.util.*;

/** Реєстр предметів (за назвою). Закривається разом із реєстром блоків. */
public final class ItemRegistry {
    private final Map<String, ItemType> byName = new LinkedHashMap<>();
    private final Map<String, Set<String>> tags = new HashMap<>();
    private boolean frozen;

    public synchronized ItemType register(String fullName, ItemSettings s) {
        if (frozen) throw new IllegalStateException("реєстр предметів уже закритий: " + fullName);
        if (!fullName.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва предмета: " + fullName + " (треба mod:name, малі латинські літери, цифри, _)");
        if (byName.containsKey(fullName)) throw new IllegalArgumentException("предмет уже зареєстровано: " + fullName);
        ItemType t = new ItemType(fullName, s);
        byName.put(fullName, t);
        return t;
    }

    public synchronized void tag(String tag, String... names) {
        if (frozen) throw new IllegalStateException("реєстр предметів уже закритий (мітка " + tag + ")");
        if (!tag.matches("[a-z0-9_]+")) throw new IllegalArgumentException("погана назва мітки: " + tag);
        tags.computeIfAbsent(tag, k -> new LinkedHashSet<>()).addAll(Arrays.asList(names));
    }

    public boolean inTag(String tag, String item) { Set<String> s = tags.get(tag); return s != null && s.contains(item); }
    public Set<String> tagMembers(String tag) { Set<String> s = tags.get(tag); return s == null ? Set.of() : Collections.unmodifiableSet(s); }
    public boolean hasTag(String tag) { return tags.containsKey(tag); }

    public void freeze() { frozen = true; }
    public boolean isFrozen() { return frozen; }
    public ItemType get(String fullName) { return fullName == null ? null : byName.get(fullName); }

    /** Знайти за повною назвою або за короткою (без двокрапки - у просторі voxelin). */
    public ItemType find(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        ItemType t = byName.get(n);
        return t != null || n.indexOf(':') >= 0 ? t : byName.get("voxelin:" + n);
    }

    public int maxStack(String name) { ItemType t = byName.get(name); return t == null ? 64 : t.maxStack; }
    public Collection<ItemType> all() { return Collections.unmodifiableCollection(byName.values()); }
}
