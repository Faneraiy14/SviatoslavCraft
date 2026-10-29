package voxelin.client.look;

import java.io.File;
import java.util.*;

/** Уся доступна косметика: вбудована + теки в cosmetics/ (перечитуються на вимогу - коли відкривається екран скіна). */
public final class CosmeticLibrary {
    private final File dir;
    private final Map<String, Cosmetic> byId = new LinkedHashMap<>();
    private final List<Cosmetic> builtin = BuiltinCosmetics.all();

    public CosmeticLibrary(File dir) { this.dir = dir; reload(null); }

    /** Перечитати теку; renderer (якщо є) забуває старі текстури файлової косметики. */
    public void reload(CosmeticRenderer renderer) {
        if (renderer != null) for (String id : byId.keySet()) if (id.startsWith("file:")) renderer.forget(id);
        byId.clear();
        for (Cosmetic c : builtin) byId.put(c.id, c);
        for (Cosmetic c : Cosmetic.loadAll(dir)) byId.put(c.id, c);
    }

    public Cosmetic get(String id) { return id == null ? null : byId.get(id); }

    /** Ідентифікатори для слота: спершу "none". */
    public List<String> forSlot(String slot) {
        List<String> out = new ArrayList<>(List.of("none"));
        for (Cosmetic c : byId.values()) if (c.slot.equals(slot)) out.add(c.id);
        return out;
    }

    public String title(String id) { Cosmetic c = get(id); return c != null ? c.title : id.equals("none") ? "нічого" : id + " (нема)"; }
}
