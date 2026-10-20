package voxelin.core;

import voxelin.api.MobSettings;

import java.util.*;

/** Реєстр видів істот (за назвою). Закривається разом з іншими реєстрами. */
public final class MobRegistry {
    private final Map<String, MobType> byName = new LinkedHashMap<>();
    private boolean frozen;

    public synchronized MobType register(String fullName, MobSettings s) {
        if (frozen) throw new IllegalStateException("реєстр істот уже закритий: " + fullName);
        if (!fullName.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва істоти: " + fullName + " (треба mod:name, малі латинські літери, цифри, _)");
        if (byName.containsKey(fullName)) throw new IllegalArgumentException("істота вже зареєстрована: " + fullName);
        MobType t = new MobType(fullName, s);
        byName.put(fullName, t);
        return t;
    }

    public void freeze() { frozen = true; }
    public MobType get(String fullName) { return fullName == null ? null : byName.get(fullName); }

    /** За повною або короткою назвою (без двокрапки - voxelin). */
    public MobType find(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        MobType t = byName.get(n);
        return t != null || n.indexOf(':') >= 0 ? t : byName.get("voxelin:" + n);
    }

    public Collection<MobType> all() { return Collections.unmodifiableCollection(byName.values()); }
}
