import voxelin.core.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Храми: лісове святилище (ліси, схованка під позначеною плитою, павуки-вартові) і затонулі руїни (place: seabed - на дні під водою). */
public class VoxelinTempleTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-temple").toFile(), "saves"), null);
        parse(); shrine(); sunken();
        System.out.println("VoxelinTempleTest: " + n + " перевірок OK");
    }

    static StructureTemplate t(String name) { for (StructureTemplate s : g.structures.templates()) if (s.name.equals(name)) return s; return null; }

    static void parse() throws Exception {
        StructureTemplate f = t("voxelin:forest_shrine"), s = t("voxelin:sunken_ruin");
        check(f != null && f.place == StructureTemplate.Place.SURFACE && f.biomes.containsAll(List.of("forest", "birch", "taiga")), "святилище: поверхня, ліси");
        check(s != null && s.place == StructureTemplate.Place.SEABED && s.biomes.contains("ocean"), "руїни: дно, океан");
        int spiders = 0, hidden = -1, mark = -1;
        for (int y = 0; y < f.sy; y++) for (int z = 0; z < f.sz; z++) for (int x = 0; x < f.sx; x++) {
            if ("voxelin:spider".equals(f.mobAt(x, y, z))) spiders++;
            if ("forest_shrine_hidden".equals(f.lootAt(x, y, z))) { hidden = y; check(f.id(x, y + 1, z) == g.blocks.byName("voxelin:cobblestone").id, "над схованкою - плита з бруківки (позначка)"); }
        }
        check(spiders == 2, "двоє павуків-вартових: " + spiders);
        check(hidden >= 0 && hidden < 2, "схованка під землею (sink 2): шар " + hidden);
        for (String l : new String[]{"forest_shrine", "forest_shrine_hidden", "sunken_ruin"}) check(g.loot.get("voxelin:" + l) != null, "таблиця " + l);
        boolean bad = false;
        try { StructureTemplate.parse("voxelin:x", "place: lake\nlayers:\nS\n", g.blocks); } catch (StructureTemplate.StructureException e) { bad = true; }
        check(bad, "невідоме place - помилка");
    }

    static Chunk gen(TerrainGenerator tg, Map<Long, Chunk> m, long cx, long cz) { return m.computeIfAbsent(cx * 1_000_003L + cz, k -> { Chunk c = new Chunk(cx, cz); tg.generate(c); return c; }); }
    static int at(TerrainGenerator tg, Map<Long, Chunk> m, long x, int y, long z) { return gen(tg, m, Math.floorDiv(x, 16L), Math.floorDiv(z, 16L)).get((int) Math.floorMod(x, 16L), y, (int) Math.floorMod(z, 16L)); }

    static void shrine() {
        TerrainGenerator tg = new TerrainGenerator(12345, WorldInfo.Type.NORMAL, g.blocks, g.structures.templates());
        long[] loc = tg.locate("forest_shrine", 0, 0);
        check(loc != null, "святилище існує у світі");
        String b = tg.biomeAt(loc[0], loc[2]);
        check(b.equals("Ліс") || b.equals("Березовий гай") || b.equals("Тайга"), "святилище в лісі: " + b);
        Map<Long, Chunk> m = new HashMap<>();
        int mono = g.blocks.byName("voxelin:monolith").id, chest = g.blocks.byName("voxelin:chest").id, mo = 0, ch = 0;
        for (long x = loc[0] - 12; x <= loc[0] + 12; x++) for (long z = loc[2] - 12; z <= loc[2] + 12; z++) for (int y = 40; y < 140; y++) { int id = at(tg, m, x, y, z); if (id == mono) mo++; if (id == chest) ch++; }
        check(mo >= 7 && ch >= 2, "вівтар і дві скрині на місці: моноліт " + mo + ", скринь " + ch);
    }

    static void sunken() {
        TerrainGenerator tg = new TerrainGenerator(12345, WorldInfo.Type.NORMAL, g.blocks, g.structures.templates());
        long[] loc = tg.locate("sunken_ruin", 0, 0);
        check(loc != null, "затонулі руїни існують у світі");
        check(tg.biomeAt(loc[0], loc[2]).equals("Океан"), "руїни в океані: " + tg.biomeAt(loc[0], loc[2]));
        Map<Long, Chunk> m = new HashMap<>();
        int water = g.blocks.byName("voxelin:water").id, chest = g.blocks.byName("voxelin:chest").id, ch = 0, chestY = -1;
        for (long x = loc[0] - 10; x <= loc[0] + 10; x++) for (long z = loc[2] - 10; z <= loc[2] + 10; z++) for (int y = 2; y < 70; y++) if (at(tg, m, x, y, z) == chest) { ch++; chestY = y; check(at(tg, m, x, y + 4, z) == water, "над скринею вода"); }
        check(ch == 1, "одна скриня руїн: " + ch);
        check(chestY > 0 && chestY < 62 - 3, "скриня глибоко під водою: y=" + chestY);
        long[] loc2 = tg.locate("sunken_ruin", loc[0] + 1500, loc[2]);
        check(loc2 != null && loc2[0] != loc[0], "руїн у світі кілька");
    }
}
