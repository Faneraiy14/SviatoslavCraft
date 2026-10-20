package voxelin.core;

import voxelin.core.gen.StructureTemplate;

import java.util.*;

/** Реєстр шаблонів структур: сирий текст від модів/файлів, потім (після закриття блоків) розбір з перевіркою. Шаблон з помилкою пропускається з попередженням. */
public final class StructureRegistry {
    private final Map<String, String> raw = new LinkedHashMap<>();
    private final List<StructureTemplate> compiled = new ArrayList<>();
    private boolean frozen;

    public synchronized void add(String name, String text) {
        if (frozen) throw new IllegalStateException("реєстр структур уже закритий: " + name);
        if (!name.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва структури: " + name);
        raw.put(name, text); // повтор назви перекриває (файл гравця перекриває ванільну)
    }

    public synchronized void freeze(BlockRegistry blocks) {
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try { compiled.add(StructureTemplate.parse(e.getKey(), e.getValue(), blocks)); }
            catch (StructureTemplate.StructureException ex) { System.out.println("[Voxelin] ⚠️ структуру " + e.getKey() + " пропущено: " + ex.getMessage()); }
        }
        frozen = true;
    }

    public List<StructureTemplate> templates() { return Collections.unmodifiableList(compiled); }

    public StructureTemplate find(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (StructureTemplate t : compiled) if (t.name.equals(n) || t.name.equals("voxelin:" + n)) return t;
        return null;
    }
}
