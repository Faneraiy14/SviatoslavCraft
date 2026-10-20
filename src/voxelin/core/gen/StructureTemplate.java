package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.BlockType;

import java.util.*;

/**
 * Шаблон структури (текстовий формат .vxs): заголовок (place, spacing, chance, depth, sink), легенда «символ = блок» і шари знизу вгору
 * (у шарі рядки - це z, символи - x). Символ '.' чи пропуск - не чіпати світ, 'air' - прорізати повітря, «chest loot=ID» - скриня зі здобиччю.
 * Розбір суворий: будь-яка помилка - виняток з номером рядка (невалідний шаблон гра пропускає з попередженням).
 */
public final class StructureTemplate {
    public enum Place { SURFACE, UNDERGROUND }

    public static final class StructureException extends Exception {
        public StructureException(int line, String msg) { super("рядок " + line + ": " + msg); }
    }

    public static final int MAX_XZ = 32, MAX_Y = 64;

    public final String name;
    public final Place place;
    public final int spacing, depthMin, depthMax, sink, sx, sy, sz;
    public final double chance;
    /** Блоки: -1 - не чіпати, 0 - повітря, інакше id. Індекс x + z*sx + y*sx*sz. */
    final short[] ids;
    /** Таблиця здобичі для клітинок-скринь (інакше null). */
    final String[] loot;
    final short foundation;

    private StructureTemplate(String name, Place place, int spacing, double chance, int depthMin, int depthMax, int sink, int sx, int sy, int sz, short[] ids, String[] loot) {
        this.name = name; this.place = place; this.spacing = spacing; this.chance = chance; this.depthMin = depthMin; this.depthMax = depthMax; this.sink = sink;
        this.sx = sx; this.sy = sy; this.sz = sz; this.ids = ids; this.loot = loot;
        short f = 1;
        for (int i = 0; i < sx * sz && i < ids.length; i++) if (ids[i] > 0 && loot[i] == null) { f = ids[i]; break; }
        foundation = f;
    }

    public int id(int x, int y, int z) { return ids[x + z * sx + y * sx * sz]; }
    public String lootAt(int x, int y, int z) { return loot[x + z * sx + y * sx * sz]; }

    /** Розміри габариту після повороту (rot 0..3): непарний - x і z міняються місцями. */
    public int width(int rot) { return (rot & 1) == 0 ? sx : sz; }
    public int depth(int rot) { return (rot & 1) == 0 ? sz : sx; }

    /** Зсув клітинки шаблона (x,z) у повернутому габариті: rot 0 - як є, 1 - на 90° за годинниковою, 2 - 180°, 3 - 270°. */
    public int rx(int rot, int x, int z) {
        switch (rot & 3) { case 0: return x; case 1: return sz - 1 - z; case 2: return sx - 1 - x; default: return z; }
    }
    public int rz(int rot, int x, int z) {
        switch (rot & 3) { case 0: return z; case 1: return x; case 2: return sz - 1 - z; default: return sx - 1 - x; }
    }

    public static StructureTemplate parse(String name, String text, BlockRegistry blocks) throws StructureException {
        String[] lines = text.split("\\R", -1);
        Map<String, String> head = new HashMap<>();
        Map<Character, String> legend = new HashMap<>();
        List<List<String>> layers = new ArrayList<>();
        int mode = 0; // 0 заголовок, 1 легенда, 2 шари
        List<String> cur = null;
        for (int i = 0; i < lines.length; i++) {
            int ln = i + 1;
            String raw = lines[i], s = raw.strip();
            if (s.isEmpty() || s.startsWith("//")) continue;
            if (mode < 2 && s.equals("legend:")) { mode = 1; continue; }
            if (s.equals("layers:")) { if (mode != 1 && !legend.isEmpty()) throw new StructureException(ln, "layers: має йти після legend:"); mode = 2; cur = new ArrayList<>(); layers.add(cur); continue; }
            if (mode == 0) {
                int c = s.indexOf(':');
                if (c <= 0) throw new StructureException(ln, "очікується «ключ: значення»: " + s);
                head.put(s.substring(0, c).strip(), s.substring(c + 1).strip());
            } else if (mode == 1) {
                char ch = s.charAt(0);
                String rest = s.substring(1).strip();
                if (!rest.startsWith("=")) throw new StructureException(ln, "очікується «символ = значення»: " + s);
                if (legend.containsKey(ch)) throw new StructureException(ln, "символ '" + ch + "' вже визначено");
                legend.put(ch, rest.substring(1).strip());
            } else {
                if (s.equals("---")) { cur = new ArrayList<>(); layers.add(cur); continue; }
                cur.add(s);
            }
        }
        if (mode != 2) throw new StructureException(lines.length, "нема розділу layers:");
        // заголовок
        Place place;
        String pl = head.getOrDefault("place", "");
        if (pl.equals("surface")) place = Place.SURFACE; else if (pl.equals("underground")) place = Place.UNDERGROUND; else throw new StructureException(1, "place: має бути surface або underground");
        int spacing = intOf(head, "spacing", 16, 4096);
        double chance;
        try { chance = Double.parseDouble(head.getOrDefault("chance", "")); } catch (NumberFormatException e) { throw new StructureException(1, "chance: не число"); }
        if (!(chance >= 0 && chance <= 1)) throw new StructureException(1, "chance: від 0 до 1");
        int sink = place == Place.SURFACE && head.containsKey("sink") ? intOf(head, "sink", 0, 8) : 0, dmin = 0, dmax = 0;
        if (place == Place.UNDERGROUND) {
            String[] d = head.getOrDefault("depth", "").split("\\s+");
            try { dmin = Integer.parseInt(d[0]); dmax = Integer.parseInt(d[1]); } catch (RuntimeException e) { throw new StructureException(1, "depth: потрібні два числа «мін макс»"); }
            if (dmin < 3 || dmax > 250 || dmax < dmin) throw new StructureException(1, "depth: 3 <= мін <= макс <= 250");
        }
        // шари
        layers.removeIf(List::isEmpty);
        if (layers.isEmpty()) throw new StructureException(lines.length, "нема жодного шару");
        int sy = layers.size(), sz = layers.get(0).size(), sx = layers.get(0).get(0).length();
        if (sx > MAX_XZ || sz > MAX_XZ || sy > MAX_Y) throw new StructureException(1, "розмір " + sx + "x" + sz + "x" + sy + " більший за " + MAX_XZ + "x" + MAX_XZ + "x" + MAX_Y);
        if (spacing < Math.max(sx, sz) + 8) throw new StructureException(1, "spacing має бути не менше " + (Math.max(sx, sz) + 8) + " (габарит + 8)");
        // легенда -> id
        Map<Character, Integer> idOf = new HashMap<>();
        Map<Character, String> lootOf = new HashMap<>();
        for (Map.Entry<Character, String> e : legend.entrySet()) {
            String v = e.getValue();
            if (v.equals("air")) idOf.put(e.getKey(), 0);
            else if (v.equals("keep")) idOf.put(e.getKey(), -1);
            else if (v.startsWith("chest")) {
                String[] p = v.split("\\s+");
                if (p.length != 2 || !p[1].startsWith("loot=") || p[1].length() < 6) throw new StructureException(1, "скриня: «chest loot=ID»: " + v);
                BlockType b = blocks.byName("voxelin:chest");
                if (b == null) throw new StructureException(1, "нема блока voxelin:chest");
                idOf.put(e.getKey(), b.id); lootOf.put(e.getKey(), p[1].substring(5));
            } else {
                BlockType b = blocks.byName(v.indexOf(':') >= 0 ? v : "voxelin:" + v);
                if (b == null) throw new StructureException(1, "невідомий блок у легенді: " + v);
                idOf.put(e.getKey(), b.id);
            }
        }
        short[] ids = new short[sx * sy * sz];
        String[] loot = new String[sx * sy * sz];
        Arrays.fill(ids, (short) -1);
        for (int y = 0; y < sy; y++) {
            List<String> rows = layers.get(y);
            if (rows.size() != sz) throw new StructureException(1, "шар " + (y + 1) + ": " + rows.size() + " рядків, у першому " + sz);
            for (int z = 0; z < sz; z++) {
                String row = rows.get(z);
                if (row.length() != sx) throw new StructureException(1, "шар " + (y + 1) + ", рядок " + (z + 1) + ": ширина " + row.length() + ", у першому " + sx);
                for (int x = 0; x < sx; x++) {
                    char ch = row.charAt(x);
                    if (ch == '.' || ch == ' ') continue;
                    Integer id = idOf.get(ch);
                    if (id == null) throw new StructureException(1, "шар " + (y + 1) + ", рядок " + (z + 1) + ": символ '" + ch + "' не визначено в легенді");
                    ids[x + z * sx + y * sx * sz] = (short) (int) id;
                    loot[x + z * sx + y * sx * sz] = lootOf.get(ch);
                }
            }
        }
        boolean any = false; for (short v : ids) if (v != -1) any = true;
        if (!any) throw new StructureException(1, "шаблон порожній (усі клітинки «не чіпати»)");
        return new StructureTemplate(name, place, spacing, chance, dmin, dmax, sink, sx, sy, sz, ids, loot);
    }

    private static int intOf(Map<String, String> head, String key, int lo, int hi) throws StructureException {
        try {
            int v = Integer.parseInt(head.getOrDefault(key, ""));
            if (v < lo || v > hi) throw new StructureException(1, key + ": від " + lo + " до " + hi);
            return v;
        } catch (NumberFormatException e) { throw new StructureException(1, key + ": потрібне ціле число"); }
    }
}
