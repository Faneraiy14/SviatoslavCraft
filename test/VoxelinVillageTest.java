import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Села: ролі шаблонів, розкладка, склеювання через межі чанків, жителі (один раз, не зникають), стежки, /locate village. */
public class VoxelinVillageTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;
    static final int[] DX = {0, -1, 0, 1}, DZ = {1, 0, -1, 0};

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("vx-village").toFile();
        g = new Game(new File(root, "saves"), null);
        parsing(); layout(); stitching(); world();
        System.out.println("VoxelinVillageTest: " + n + " перевірок OK");
    }

    static StructureTemplate parse(String text) throws Exception { return StructureTemplate.parse("voxelin:t", text, g.blocks); }

    static void bad(String text, String why) {
        try { parse(text); check(false, "має бути помилка: " + why); }
        catch (StructureTemplate.StructureException e) { check(true, why); }
        catch (Exception e) { check(false, "неочікуваний виняток (" + why + "): " + e); }
    }

    static void parsing() throws Exception {
        Map<String, Integer> roles = new HashMap<>();
        for (StructureTemplate t : g.structures.templates()) if (t.role != null) roles.merge(t.role, 1, Integer::sum);
        check(roles.get("house") == 2 && roles.get("well") == 1 && roles.get("hall") == 1 && roles.get("farm") == 1 && roles.get("lamp") == 1, "ролі шаблонів села: " + roles);
        StructureTemplate small = g.structures.find("village_house_small"), farm = g.structures.find("village_farm"), well = g.structures.find("village_well");
        check(small.sx == 7 && small.sz == 7 && small.role.equals("house") && small.weight == 3, "малий будинок 7x7, вага 3");
        int mobs = 0, chests = 0;
        for (int y = 0; y < small.sy; y++) for (int z = 0; z < small.sz; z++) for (int x = 0; x < small.sx; x++) { if (small.mobAt(x, y, z) != null) { mobs++; check(small.mobAt(x, y, z).equals("voxelin:villager_farmer") && small.id(x, y, z) == 0, "мітка моба = повітря"); } if (small.lootAt(x, y, z) != null) chests++; }
        check(mobs == 1 && chests == 1, "у будинку один житель і одна скриня: " + mobs + "/" + chests);
        int animals = 0; for (int y = 0; y < farm.sy; y++) for (int z = 0; z < farm.sz; z++) for (int x = 0; x < farm.sx; x++) if (farm.mobAt(x, y, z) != null) animals++;
        check(animals == 5, "у загоні 5 тварин: " + animals);
        check(well.sink == 2 && well.sx == 5, "колодязь заглиблений");
        check(g.loot.get("village") != null, "таблиця здобичі села");
        String base = "village: house 2\nlegend:\n  S = stone\n  V = mob villager_farmer\nlayers:\nSV\n";
        StructureTemplate ok = parse(base);
        check(ok.role.equals("house") && ok.weight == 2 && ok.place == StructureTemplate.Place.SURFACE && ok.mobAt(1, 0, 0).equals("voxelin:villager_farmer"), "мінімальний шаблон села без place/spacing/chance");
        bad(base.replace("house", "castle"), "невідома роль");
        bad(base.replace("house 2", "house x"), "вага не число");
        bad(base.replace("house 2", "house 0"), "вага 0");
        bad(base.replace("house 2", "house 500"), "вага 500");
        bad(base.replace("mob villager_farmer", "mob Bad Name"), "погана назва виду");
        bad(base.replace("V = mob villager_farmer", "V = mob"), "mob без виду");
        // мітки не плутаються з «chest»
        check(parse(base.replace("mob villager_farmer", "mob zombie")).mobAt(1, 0, 0).equals("voxelin:zombie"), "коротка назва моба -> voxelin:");
        check(parse(base.replace("mob villager_farmer", "mob mymod:ghost")).mobAt(1, 0, 0).equals("mymod:ghost"), "повна назва моба зберігається");
    }

    static StructurePlacer placer(long seed, StructurePlacer.Surface s) {
        return new StructurePlacer(seed, g.structures.templates(), s, 62).pathBlock(g.blocks.byName("voxelin:gravel").id);
    }

    static void layout() throws Exception {
        StructurePlacer.Surface flat = (x, z) -> 70;
        StructurePlacer p = placer(42, flat), p2 = placer(42, flat), other = placer(43, flat);
        check(p.hasVillages(), "розкладка бачить ролі");
        int villages = 0, cells = 0, same = 0, diff = 0, houses = 0, doorsOk = 0;
        for (long cx = -12; cx < 12; cx++) for (long cz = -12; cz < 12; cz++) {
            cells++;
            StructurePlacer.Village v = p.village(cx, cz), v2 = p2.village(cx, cz), vo = other.village(cx, cz);
            check((v == null) == (v2 == null) && (v == null || (v.cx == v2.cx && v.cz == v2.cz && v.buildings.size() == v2.buildings.size() && v.path.size() == v2.path.size())), "розкладка детермінована");
            if (v != null || vo != null) { if ((vo == null) != (v == null) || (v.cx != vo.cx || v.cz != vo.cz)) diff++; else same++; }
            if (v == null) continue;
            villages++;
            long cx0 = cx * StructurePlacer.VILLAGE_CELL, cz0 = cz * StructurePlacer.VILLAGE_CELL;
            check(v.cx >= cx0 + StructurePlacer.VILLAGE_MARGIN && v.cx < cx0 + StructurePlacer.VILLAGE_CELL - StructurePlacer.VILLAGE_MARGIN && v.cz >= cz0 + StructurePlacer.VILLAGE_MARGIN && v.cz < cz0 + StructurePlacer.VILLAGE_CELL - StructurePlacer.VILLAGE_MARGIN, "центр у полі клітинки");
            int house = 0;
            for (int i = 0; i < v.buildings.size(); i++) {
                StructurePlacer.Placement b = v.buildings.get(i);
                check(b.ox >= cx0 && b.ox + b.w <= cx0 + StructurePlacer.VILLAGE_CELL && b.oz >= cz0 && b.oz + b.d <= cz0 + StructurePlacer.VILLAGE_CELL, "будівля всередині клітинки села");
                for (int j = i + 1; j < v.buildings.size(); j++) {
                    StructurePlacer.Placement o = v.buildings.get(j);
                    check(b.ox + b.w <= o.ox || o.ox + o.w <= b.ox || b.oz + b.d <= o.oz || o.oz + o.d <= b.oz, "будівлі не перетинаються");
                }
                if (b.t.role.equals("house") || b.t.role.equals("hall")) {
                    house++; houses++;
                    double tx = v.cx - (b.ox + b.w / 2.0), tz = v.cz - (b.oz + b.d / 2.0);
                    if (DX[b.rot] * tx + DZ[b.rot] * tz > 0) doorsOk++;
                }
            }
            check(house >= 2 && house <= 9, "жилих будівель 2..9: " + house);
            StructurePlacer.Placement w = null; for (StructurePlacer.Placement b : v.buildings) if (b.t.role.equals("well")) w = b;
            check(w != null && Math.abs(w.centerX() - v.cx) <= 3 && Math.abs(w.centerZ() - v.cz) <= 3, "колодязь у центрі");
            check(!v.path.isEmpty(), "стежки є");
            long[] last = v.path.get(v.path.size() - 1);
            check(Math.abs(last[0] - v.cx) <= 1 && Math.abs(last[1] - v.cz) <= 1, "стежка закінчується біля колодязя");
        }
        double rate = villages / (double) cells;
        check(rate > 0.12 && rate < 0.5, "частота сіл біля 0.3: " + rate);
        check(doorsOk >= houses * 0.95, "двері повернуті до центру: " + doorsOk + " з " + houses);
        check(diff > same * 2, "інше зерно - інші села");
        // океан: сіл нема; нерівність: значно менше
        StructurePlacer ocean = placer(42, (x, z) -> 50), hilly = placer(42, (x, z) -> 70 + (int) Math.floorMod(x * 7 + z * 13, 12L));
        int o = 0, h = 0;
        for (long cx = -12; cx < 12; cx++) for (long cz = -12; cz < 12; cz++) { if (ocean.village(cx, cz) != null) o++; if (hilly.village(cx, cz) != null) h++; }
        check(o == 0, "в океані сіл нема");
        check(h < villages / 2 + 1, "на нерівному сіл значно менше: " + h + " проти " + villages);
        long[] near = p.locateVillage(0, 0, 12);
        check(near != null, "locateVillage знайшов");
        double best = Double.MAX_VALUE;
        for (long cx = -4; cx <= 4; cx++) for (long cz = -4; cz <= 4; cz++) { StructurePlacer.Village v = p.village(cx, cz); if (v != null) best = Math.min(best, Math.hypot(v.cx, v.cz)); }
        check(Math.abs(Math.hypot(near[0], near[1]) - best) < 1.0, "найближче село: " + best);
    }

    static Chunk gen(TerrainGenerator tg, long cx, long cz) { Chunk c = new Chunk(cx, cz); tg.generate(c); return c; }

    static void stitching() throws Exception {
        long seed = 31;
        TerrainGenerator tg = new TerrainGenerator(seed, WorldInfo.Type.FLAT, g.blocks, g.structures.templates());
        StructurePlacer pl = new StructurePlacer(seed, g.structures.templates(), tg::surfaceY, tg.seaLevel()).pathBlock(g.blocks.byName("voxelin:gravel").id);
        StructurePlacer.Village v = null;
        for (long cx = 0; cx < 20 && v == null; cx++) for (long cz = 0; cz < 20 && v == null; cz++) v = pl.village(cx, cz);
        check(v != null, "село знайдено на плоскому світі");
        Map<String, Chunk> m = new HashMap<>();
        java.util.function.BiFunction<Long, Long, Chunk> get = (x, z) -> m.computeIfAbsent(x + "," + z, k -> gen(tg, x, z));
        // усі клітинки будівель на місці (у прямому порядку генерації)
        int checked = 0;
        for (StructurePlacer.Placement p : v.buildings) {
            StructureTemplate t = p.t;
            for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
                int id = t.id(x, y, z);
                if (id < 0) continue;
                long wx = p.ox + t.rx(p.rot, x, z), wz = p.oz + t.rz(p.rot, x, z);
                Chunk c = get.apply(Math.floorDiv(wx, 16L), Math.floorDiv(wz, 16L));
                check(c.get((int) Math.floorMod(wx, 16L), p.oy + y, (int) Math.floorMod(wz, 16L)) == id, t.name + ": клітинка на місці");
                checked++;
            }
        }
        check(checked > 500, "перевірено клітинок: " + checked);
        // стежки: гравій на поверхні (поза будівлями)
        int gravel = g.blocks.byName("voxelin:gravel").id, pathCells = 0, onGravel = 0;
        for (long[] pc : v.path) {
            boolean inside = false;
            for (StructurePlacer.Placement p : v.buildings) if (pc[0] >= p.ox - 1 && pc[0] < p.ox + p.w + 1 && pc[1] >= p.oz - 1 && pc[1] < p.oz + p.d + 1 && !p.t.role.equals("lamp")) inside = true;
            if (inside) continue;
            pathCells++;
            Chunk c = get.apply(Math.floorDiv(pc[0], 16L), Math.floorDiv(pc[1], 16L));
            if (c.get((int) Math.floorMod(pc[0], 16L), 64, (int) Math.floorMod(pc[1], 16L)) == gravel) onGravel++;
        }
        check(pathCells > 20 && onGravel >= pathCells * 0.9, "стежки з гравію: " + onGravel + " з " + pathCells);
        // зворотний порядок генерації: ті самі блоки
        Map<String, Chunk> rev = new HashMap<>();
        long minX = Long.MAX_VALUE, maxX = Long.MIN_VALUE, minZ = Long.MAX_VALUE, maxZ = Long.MIN_VALUE;
        for (StructurePlacer.Placement p : v.buildings) { minX = Math.min(minX, p.ox); maxX = Math.max(maxX, p.ox + p.w); minZ = Math.min(minZ, p.oz); maxZ = Math.max(maxZ, p.oz + p.d); }
        for (long x = Math.floorDiv(maxX, 16L); x >= Math.floorDiv(minX, 16L); x--) for (long z = Math.floorDiv(maxZ, 16L); z >= Math.floorDiv(minZ, 16L); z--) {
            Chunk a2 = gen(tg, x, z), b2 = get.apply(x, z);
            for (int y = 0; y < 256; y++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) check(a2.get(xx, y, zz) == b2.get(xx, y, zz), "порядок генерації не впливає на село");
            check(a2.pending.size() == b2.pending.size(), "відкладені однакові");
        }
        // відкладені: скрині й мобі
        int chests = 0, mobsP = 0;
        for (Chunk c : m.values()) for (Chunk.Pending pe : c.pending) { if (pe.kind().equals("chest")) chests++; if (pe.kind().equals("mob")) mobsP++; }
        check(chests >= 2 && mobsP >= 2, "відкладені скрині й мобі: " + chests + "/" + mobsP);
    }

    static void world() throws Exception {
        Game g2 = new Game(new File(root, "saves2"), null);
        g2.createWorld("V", 31, WorldInfo.Type.FLAT);
        World w = g2.openWorld("V");
        w.naturalSpawning = false;
        long[] r = w.locate("village", 0, 0);
        check(r != null && w.structureNames().contains("village"), "/locate village знаходить, назва в переліку");
        long cx = Math.floorDiv(r[0], 16L), cz = Math.floorDiv(r[2], 16L);
        for (int i = 0; i < 900; i++) { w.update(cx, cz, 4); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 20 && w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int villagers = 0, kept = 0;
        for (Mob m : w.mobs()) { if (m.type.persistent) villagers++; if (m.keep) kept++; }
        check(villagers >= 2 && kept >= villagers, "жителі з'явились: " + villagers + ", із ознакою keep: " + kept);
        int total = w.mobs().size();
        // ходимо далеко й назад: чанки перегенеруються, нових жителів нема
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 400; i++) { w.update(cx + 200, cz + 200, 4); w.tick(); }
            for (int i = 0; i < 900; i++) { w.update(cx, cz, 4); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 20 && w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        }
        check(w.mobs().size() == total, "після повторної генерації жителів не побільшало: " + total + " -> " + w.mobs().size());
        // не зникають від відстані
        w.naturalSpawning = true;
        class Far implements PlayerTarget { public Pos body() { return Pos.ofBlock(r[0] + 300, 70, r[2] + 300); } public boolean alive() { return true; } public boolean creative() { return true; } public void hurt(double d, double kx, double kz) {} }
        w.setPlayerTarget(new Far());
        for (int i = 0; i < 400; i++) w.tick();
        int still = 0; for (Mob m : w.mobs()) if (m.keep) still++;
        check(still >= kept, "істоти зі структур не зникають від відстані: " + still + " з " + kept);
        w.setPlayerTarget(null);
        w.naturalSpawning = false;
        w.close();
        check(new File(root, "saves2/V/marks.dat").isFile() && new File(root, "saves2/V/mobs.dat").isFile(), "marks.dat і mobs.dat записано");
        World w2 = new Game(new File(root, "saves2"), null).openWorld("V");
        w2.naturalSpawning = false;
        int v2 = 0, k2 = 0; for (Mob m : w2.mobs()) { if (m.type.persistent) v2++; if (m.keep) k2++; }
        check(v2 == villagers && k2 >= kept, "після перезапуску жителі ті самі, keep збережено: " + v2 + "/" + k2);
        for (int i = 0; i < 900; i++) { w2.update(cx, cz, 4); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 20 && w2.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int v3 = 0; for (Mob m : w2.mobs()) if (m.type.persistent) v3++;
        check(v3 == villagers, "після перезапуску й генерації чанків жителів не побільшало: " + v3);
        // убитий житель не відроджується
        Mob victim = null; for (Mob m : w2.mobs()) if (m.type.persistent) { victim = m; break; }
        victim.invuln = 0; w2.hurtMob(victim, 1000, 1, 0, Pos.ofBlock(r[0], 70, r[2]));
        for (int i = 0; i < 30; i++) w2.tick();
        for (int i = 0; i < 400; i++) { w2.update(cx + 200, cz + 200, 4); w2.tick(); }
        for (int i = 0; i < 900; i++) { w2.update(cx, cz, 4); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 20 && w2.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int v4 = 0; for (Mob m : w2.mobs()) if (m.type.persistent) v4++;
        check(v4 == villagers - 1, "убитий житель не відроджується: " + v4 + " з " + villagers);
        // /locate командою
        List<String> out = g2.commands.execute(Sources.player("P"), w2, new long[]{0, 70, 0}, "/locate village");
        check(out.get(0).startsWith("village: x "), "/locate village: " + out);
        // биті marks
        w2.close();
        Files.write(new File(root, "saves2/V/marks.dat").toPath(), "abc\n\n123\n-5\n99999999999999999999\n".getBytes());
        World w3 = new Game(new File(root, "saves2"), null).openWorld("V");
        check(w3.mobs() != null, "биті marks.dat не ламають завантаження");
        w3.close();
    }
}
