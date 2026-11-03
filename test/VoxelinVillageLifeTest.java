import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Села: нові професії, «mob a|b» у шаблонах, ріст від ліжок (основа + ліжка, раз на добу, заміна загиблих), збереження стану. */
public class VoxelinVillageLifeTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-vlife").toFile(), "saves"), null);
        professions(); template(); growth();
        System.out.println("VoxelinVillageLifeTest: " + n + " перевірок OK");
    }

    static void professions() {
        int v = 0;
        for (String k : new String[]{"farmer", "smith", "elder", "mason", "hunter", "healer"}) {
            MobType t = g.mobTypes.get("voxelin:villager_" + k);
            check(t != null && t.villager() && t.talkable(), "житель " + k);
            if (!k.equals("elder")) check(!t.trades.isEmpty(), "у " + k + " є угоди");
            for (voxelin.api.MobSettings.Trade tr : t.trades) check(g.items.get(tr.in) != null && g.items.get(tr.out) != null, "угода " + k + ": " + tr.in + " -> " + tr.out + " - справжні предмети");
            v++;
        }
        check(v == 6, "шість видів жителів");
    }

    static void template() throws Exception {
        String t = "place: manual\nlegend:\n  C = cobblestone\n  V = mob villager_mason|villager_hunter\nlayers:\nCV\n";
        StructureTemplate st = StructureTemplate.parse("voxelin:t", t, g.blocks);
        check("voxelin:villager_mason|voxelin:villager_hunter".equals(st.mobAt(1, 0, 0)), "mob a|b: обидва види з префіксом: " + st.mobAt(1, 0, 0));
        boolean bad = false;
        try { StructureTemplate.parse("voxelin:t", t.replace("villager_hunter", "Bad Name"), g.blocks); } catch (StructureTemplate.StructureException e) { bad = true; }
        check(bad, "погана назва в «mob a|b» - помилка шаблону");
        int farmerHouses = 0;
        for (StructureTemplate s : g.structures.templates()) for (int y = 0; y < s.sy; y++) for (int z = 0; z < s.sz; z++) for (int x = 0; x < s.sx; x++) {
            String m = s.mobAt(x, y, z);
            if (m == null) continue;
            for (String k : m.split("\\|")) check(g.mobTypes.get(k) != null, "моб у шаблоні " + s.name + " існує: " + k);
            if (m.contains("villager_hunter")) farmerHouses++;
        }
        check(farmerHouses > 0, "нові професії з'являються й у згенерованих селах");
    }

    static long day(World w) { return w.dayNumber(); }
    static void nextDay(World w) { w.setTime(w.info.time + w.dayTicks()); }

    static void growth() throws Exception {
        g.createWorld("vl", 7, WorldInfo.Type.FLAT);
        World w = g.openWorld("vl");
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int y = 0; for (int yy = 200; yy > 0; yy--) if (w.blockId(0, yy, 0) > 0 && w.blocks.get(w.blockId(0, yy, 0)).solid) { y = yy; break; }
        long cx = 0, cz = 0;
        Random r = new Random(4);
        check(VillageLife.grow(w, cx, cz, r) == null && w.info.villages.get("0_0") != null && w.info.villages.get("0_0")[0] == 0, "перший візит: основа = 0 жителів");
        nextDay(w);
        check(VillageLife.grow(w, cx, cz, r) == null, "без ліжок ніхто не приходить");
        BlockType bed = g.blocks.byName(Sleep.BED);
        w.setBlock(P, 5, y + 1, 5, bed); w.setBlock(P, -6, y + 1, 4, bed);
        Mob a = VillageLife.grow(w, cx, cz, r);
        check(a != null && a.type.villager() && !a.type.name.endsWith("_elder") && a.keep, "ліжко -> новий житель (не старійшина): " + (a == null ? null : a.type.name));
        check(Math.abs(a.pos.relX(0) - 5.5) <= 3 || Math.abs(a.pos.relX(0) + 5.5) <= 3, "житель з'являється біля ліжка");
        check(VillageLife.grow(w, cx, cz, r) == null, "за добу - не більше одного");
        nextDay(w);
        Mob b = VillageLife.grow(w, cx, cz, r);
        check(b != null, "наступної доби - другий");
        nextDay(w);
        check(VillageLife.grow(w, cx, cz, r) == null, "ліжок 2 - жителів не більше 2");
        a.damage(1000, 0, 0, null);
        for (int i = 0; i < 30; i++) w.tick();
        nextDay(w);
        check(VillageLife.grow(w, cx, cz, r) != null, "загиблого заміняє новий, поки є ліжко");
        // далеко від ліжок (за межами радіуса) - не рахуються
        w.setBlock(P, 40, y + 1, 0, bed);
        nextDay(w);
        check(VillageLife.grow(w, cx, cz, r) == null, "ліжко далі " + VillageLife.BED_RADIUS + " блоків від колодязя не рахується");
        // збереження
        File d = Files.createTempDirectory("vx-vlinfo").toFile();
        w.info.save(d);
        WorldInfo copy = WorldInfo.load(d);
        check(copy.villages.containsKey("0_0") && copy.villages.get("0_0")[1] == w.info.villages.get("0_0")[1], "стан села зберігається");
        w.close();
    }
}
