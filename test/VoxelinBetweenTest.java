import voxelin.core.*;
import voxelin.core.gen.*;
import voxelin.core.gen.StructureTemplate;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Міжсвіття: вимір-тека, генератор (острови, нитки, платформа з виходом), Розрив Шву й правила Ключа Порогу, перенесення стану. */
public class VoxelinBetweenTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;

    static void load(World w, int r) throws Exception {
        for (int i = 0; i < 3000; i++) { w.update(0, 0, r + 1); boolean all = true; for (int dx = -r; dx <= r && all; dx++) for (int dz = -r; dz <= r && all; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
    }

    static World between(String name) throws Exception {
        g.createWorld(name, 11, WorldInfo.Type.NORMAL);
        World w = g.openDimension(name, "between");
        load(w, 3);
        return w;
    }

    static String at(World w, long x, int y, long z) { return w.blocks.get(Math.max(0, w.blockId(x, y, z))).name; }

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("vx-between").toFile();
        g = new Game(new File(root, "saves"), null);
        dims(); generator(); world(); rift(); state(); house(); echo(); traces();
        System.out.println("VoxelinBetweenTest: " + n + " перевірок OK");
    }

    static void dims() {
        check(Dimensions.type("between") == WorldInfo.Type.BETWEEN && Dimensions.type("end") == WorldInfo.Type.END && Dimensions.type("overworld") == null && Dimensions.type("nether") == WorldInfo.Type.NETHER && Dimensions.type("sift") == null, "назви вимірів");
        check(Dimensions.of(WorldInfo.Type.BETWEEN).equals("between") && Dimensions.of(WorldInfo.Type.FLAT).equals("overworld"), "тип -> назва");
        check(Dimensions.portalTarget("overworld").equals("end") && Dimensions.portalTarget("end").equals("overworld") && Dimensions.portalTarget("between").equals("overworld"), "куди веде постійний портал");
        Pos p = Dimensions.arrival("between");
        check(p.blockX() == BetweenGenerator.PLATFORM_X && p.blockZ() == BetweenGenerator.ARRIVAL_Z, "прибуття на платформу");
        boolean bad = false; try { g.openDimension("x", "nether"); } catch (Exception e) { bad = true; }
        check(bad, "невідомий вимір відкидається");
        for (String b : new String[]{"threshold_stone", "seam_thread", "threshold_bloom", "seam_rift"}) check(g.blocks.byName("voxelin:" + b) != null, "блок " + b);
        check(g.blocks.byName(Rift.RIFT).hardness < 0 && !g.blocks.byName(Rift.RIFT).solid && "rift".equals(g.blocks.byName(Rift.RIFT).interact), "розрив: незламний, прохідний");
        check(g.items.get(Rift.KEY) != null && g.records.get("voxelin:between") != null, "ключ і запис");
    }

    static void generator() {
        BetweenGenerator a = new BetweenGenerator(5, g.blocks), b = new BetweenGenerator(5, g.blocks), c = new BetweenGenerator(6, g.blocks);
        int same = 0, diff = 0, islands = 0;
        for (long cx = -20; cx <= 20; cx++) for (long cz = -20; cz <= 20; cz++) {
            BetweenGenerator.Island i1 = a.island(cx, cz), i2 = b.island(cx, cz), i3 = c.island(cx, cz);
            if (Objects.equals(i1, i2)) same++;
            if (!Objects.equals(i1, i3)) diff++;
            if (i1 != null) {
                islands++;
                check(i1.x() - i1.r() - 2 >= BetweenGenerator.cellMin(cx) && i1.x() + i1.r() + 2 < BetweenGenerator.cellMin(cx + 1)
                        && i1.z() - i1.r() - 2 >= BetweenGenerator.cellMin(cz) && i1.z() + i1.r() + 2 < BetweenGenerator.cellMin(cz + 1), "острів у межах своєї клітинки: " + i1 + " клітинка " + cx + "," + cz);
            }
        }
        check(same == 41 * 41 && diff > 300, "детермінований від зерна і залежить від нього: " + diff);
        check(islands > 600 && islands < 1100, "приблизно половина клітинок має острів: " + islands);
        check(a.island(0, 0) != null && a.island(0, 0).x() == 0, "острів прибуття завжди є");
        long far = 1_000_000_000_000_000L;
        BetweenGenerator.Island fi = null;
        for (long k = 0; k < 50 && fi == null; k++) fi = a.island(far / BetweenGenerator.CELL + k, 7);
        check(fi != null && a.surfaceY(fi.x(), fi.z()) > 0, "острови на 1e18");
        check(a.surfaceY(BetweenGenerator.PLATFORM_X, BetweenGenerator.PLATFORM_Z) == BetweenGenerator.TOP, "поверхня платформи");
    }

    static void world() throws Exception {
        World w = between("w1");
        check(w.dimension().equals("between") && w.info.type == WorldInfo.Type.BETWEEN && w.dir.getPath().replace('\\', '/').endsWith("dimensions/between"), "окрема тека виміру");
        check(w.skyLevel() == World.BETWEEN_SKY, "стале небо");
        int ts = BetweenGenerator.TOP;
        check(at(w, 0, ts, 0).equals("voxelin:threshold_stone") && at(w, 0, ts + 1, 0).equals("voxelin:air"), "платформа");
        int portal = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (at(w, dx, ts, BetweenGenerator.EXIT_Z + dz).equals("voxelin:end_portal")) portal++;
        check(portal == 9, "вихід 3x3");
        check(!at(w, 0, ts, BetweenGenerator.ARRIVAL_Z).equals("voxelin:end_portal") && w.isSolid(0, ts, BetweenGenerator.ARRIVAL_Z), "прибуття не на порталі");
        long[] land = w.findLand(0, 0);
        check(!at(w, land[0], w.surfaceY(land[0], land[1]), land[1]).equals("voxelin:end_portal"), "спавн не на виході");
        Map<String, Integer> cnt = new HashMap<>();
        for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) {
            Chunk c = w.chunk(cx, cz);
            for (int y = 0; y < 140; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) { int id = c.get(x, y, z); if (id > 0) cnt.merge(w.blocks.get(id).name, 1, Integer::sum); }
        }
        check(cnt.getOrDefault("voxelin:seam_thread", 0) > 20, "є нитки: " + cnt.get("voxelin:seam_thread"));
        check(cnt.getOrDefault("voxelin:liminite_ore", 0) > 10, "є лімініт: " + cnt.get("voxelin:liminite_ore"));
        check(cnt.getOrDefault("voxelin:threshold_bloom", 0) > 0, "є Квітки Порогу: " + cnt.get("voxelin:threshold_bloom"));
        check(cnt.keySet().stream().filter(k -> k.matches("voxelin:(grass|stone|end_stone|threshold_stone)")).count() >= 3, "острови різних типів: " + cnt.keySet());
        w.close();
        World again = g.openDimension("w1", "between");
        check(again.info.type == WorldInfo.Type.BETWEEN, "відкривається знову");
        again.close();
        File saves = new File(root, "saves");
        check(!new File(saves, "between").exists() && g.listWorlds().stream().noneMatch(s -> s.equals("between")), "тека виміру не видна як світ");
    }

    static World over(String name, WorldInfo.Type t) throws Exception {
        g.createWorld(name, 3, t);
        World w = g.openWorld(name);
        load(w, 1);
        w.naturalSpawning = false;
        return w;
    }

    static Inventory key(World w, int n) { Inventory i = new Inventory(w.itemTypes); i.add(Rift.KEY, n); return i; }

    static void rift() throws Exception {
        World w = over("r1", WorldInfo.Type.FLAT);
        int y = w.surfaceY(0, 0);
        check(Rift.open(w, new Inventory(w.itemTypes), 0, y, 0, false) == null, "без ключа - нічого");
        Inventory k = key(w, 2);
        String m = Rift.open(w, k, 0, y, 0, false);
        check(m != null && at(w, 0, y + 1, 0).equals(Rift.RIFT) && at(w, 0, y + 2, 0).equals(Rift.RIFT), "розрив 1x2 над блоком: " + m);
        check(Trading.count(k, w.itemTypes, Rift.KEY) == 1 && w.info.rifts.size() == 1, "ключ витрачено, розрив записано");
        check(Rift.open(w, k, 0, y + 5, 0, false).contains("місця") && Trading.count(k, w.itemTypes, Rift.KEY) == 1, "у повітрі не відкривається, ключ цілий");
        check(Rift.open(w, k, 0, y, 0, false).contains("місця"), "на місці розриву вдруге не відкривається");
        w.setBlock(Sources.SYSTEM, 3, y + 1, 3, w.blocks.byName("voxelin:water"));
        check(Rift.open(w, k, 3, y, 3, false).contains("місця"), "у воді не відкривається");
        // зникає через 60 с
        for (int t = 0; t < Rift.LIFE_TICKS - 5; t++) w.tick();
        check(at(w, 0, y + 1, 0).equals(Rift.RIFT), "ще відкритий перед кінцем хвилини");
        for (int t = 0; t < 10; t++) w.tick();
        check(at(w, 0, y + 1, 0).equals("voxelin:air") && at(w, 0, y + 2, 0).equals("voxelin:air") && w.info.rifts.isEmpty(), "закрився через хвилину");
        // креатив: не витрачається
        Inventory c = key(w, 1);
        Rift.open(w, c, 5, y, 5, true);
        check(Trading.count(c, w.itemTypes, Rift.KEY) == 1, "у креативі ключ не витрачається");
        // збереження: після перезапуску розрив закривається вчасно
        w.close();
        World r = g.openWorld("r1");
        load(r, 1);
        check(r.info.rifts.size() == 1 && at(r, 5, y + 1, 5).equals(Rift.RIFT), "розрив пережив перезапуск");
        for (int t = 0; t < Rift.LIFE_TICKS + 5; t++) r.tick();
        check(at(r, 5, y + 1, 5).equals("voxelin:air") && r.info.rifts.isEmpty(), "і закрився вчасно");
        // правила за станом зараження
        r.info.infState = WorldInfo.InfState.CURED;
        Inventory cu = key(r, 1);
        check(Rift.open(r, cu, 7, y, 7, false).contains("лишився") && Trading.count(cu, r.itemTypes, Rift.KEY) == 1, "після лікування - постійний доступ");
        r.info.infState = WorldInfo.InfState.BURNED;
        check(Rift.open(r, cu, 9, y, 9, false).contains("випалено") && !at(r, 9, y + 1, 9).equals(Rift.RIFT), "після спалення не відкривається");
        r.close();
        World e = between("r2");
        check(Rift.open(e, key(e, 1), 0, BetweenGenerator.TOP, 0, false).contains("звичайному"), "у міжсвітті ключ не відкриває");
        e.close();
        // биті записи
        File dir = new File(new File(root, "saves"), "r1");
        File info = new File(dir, WorldInfo.FILE);
        if (info.isFile()) {
            String txt = Files.readString(info.toPath());
            Files.writeString(info.toPath(), txt.replaceAll("(?m)^rifts=.*$", "") + "\nrifts=1,2,3,4;сміття;1,999,3,4;5,6\n");
            World z = g.openWorld("r1");
            check(z.info.rifts.size() == 1, "биті записи розривів ігноруються: " + z.info.rifts.size());
            z.close();
        }
    }

    static void state() throws Exception {
        World o = over("s1", WorldInfo.Type.FLAT);
        o.info.infState = WorldInfo.InfState.ACTIVE;
        o.info.eggChoice = WorldInfo.EggChoice.INFECTION;
        o.close();
        World b = g.openDimension("s1", "between");
        b.info.copyPlayerFrom(WorldInfo.load(new File(new File(root, "saves"), "s1")));
        check(b.info.overInf == WorldInfo.InfState.ACTIVE && b.info.eggChoice == WorldInfo.EggChoice.INFECTION, "настрій міжсвіття = стан зараження світу");
        check(b.info.infState == WorldInfo.InfState.NONE, "саме зараження в міжсвіття не переноситься");
        b.close();
        World back = g.openDimension("s1", "between");
        check(back.info.overInf == WorldInfo.InfState.ACTIVE, "настрій зберігається");
        back.close();
    }

    static void house() throws Exception {
        StructureTemplate t = null;
        for (StructureTemplate x : g.structures.templates()) if (x.name.endsWith("keeper_home")) t = x;
        check(t != null && t.place == StructureTemplate.Place.MANUAL && t.chance == 0, "Дім Сторожа - ручний шаблон, у світі сам не з'являється");
        World w = between("h1");
        long ox = BetweenGenerator.HOUSE_X - t.sx / 2, oz = BetweenGenerator.HOUSE_Z - t.sz / 2;
        int y = BetweenGenerator.TOP + 1;
        check(at(w, ox, y, oz).equals("voxelin:threshold_stone") && at(w, ox, y + 4, oz).equals("voxelin:seam_thread"), "підлога й дах дому");
        check(at(w, ox + 4, y + 1, oz + 8).equals("voxelin:air") && at(w, ox + 4, y + 2, oz + 8).equals("voxelin:air"), "двері відчинені");
        for (int k = 0; k < 40; k++) { w.update(0, 0, 4); w.tick(); Thread.sleep(5); }
        ChestEntity ch = w.chestAt(ox + 6, y + 1, oz + 2);
        boolean full = false;
        if (ch != null) for (int i = 0; i < ChestEntity.SIZE; i++) if (ch.get(i) != null) full = true;
        check(ch != null && full, "у домі скриня зі здобиччю");
        int threads = 0;
        for (long x = 6; x < BetweenGenerator.HOUSE_X - 10; x++) for (int yy = BetweenGenerator.TOP - 2; yy <= BetweenGenerator.TOP + 2; yy++) for (long z = -2; z <= 2; z++) if (at(w, x, yy, z).equals("voxelin:seam_thread")) { threads++; break; }
        check(threads > 10, "нитка від прибуття до дому: " + threads);
        w.close();
        // у звичайному світі /locate не знає Дому
        g.createWorld("h2", 4, WorldInfo.Type.NORMAL);
        World o = g.openWorld("h2");
        check(o.generator().structureNames().stream().noneMatch(n -> n.endsWith("keeper_home")), "у звичайному світі Дому нема");
        o.close();
    }

    static final class Fake implements PlayerTarget {
        Pos pos; Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return true; }
        public void hurt(double d, double kx, double kz) {}
    }

    static void echo() throws Exception {
        MobType e = g.mobTypes.get("voxelin:echo");
        check(e != null && e.hostile() && e.dimension.equals("between") && g.mobTypes.get("voxelin:zombie").dimension.equals("overworld"), "Відлуння - ворог міжсвіття");
        World w = between("e1");
        check(w.naturalSpawning, "у міжсвітті природний спавн увімкнено");
        w.setPlayerTarget(new Fake(Dimensions.arrival("between")));
        for (int t = 0; t < 20 * 90; t++) { w.tick(); if (t % 40 == 0) w.update(0, 0, 5); }
        int echoes = 0, other = 0;
        for (voxelin.core.entity.Mob m : w.mobs()) { if (m.type == e) echoes++; else other++; }
        check(echoes > 0 && echoes <= MobSpawner.hostileCap(w) && other == 0, "з'являються лише Відлуння, у межах ліміту: " + echoes + " / інших " + other);
        check(MobSpawner.hostileCap(w) == 6, "звичайний ліміт 6");
        w.info.overInf = WorldInfo.InfState.ACTIVE;
        check(MobSpawner.hostileCap(w) == 12, "під час зараження - удвічі більше");
        w.info.overInf = WorldInfo.InfState.NONE; w.info.ritualStage = 2;
        check(MobSpawner.hostileCap(w) == 3, "після Ритуалу - удвічі менше");
        w.close();
        World end = g.openDimension("e1", "end");
        check(!end.naturalSpawning, "в Енді природного спавну як і раніше нема");
        end.close();
    }

    static void traces() {
        BetweenGenerator b = new BetweenGenerator(77, g.blocks, g.structures.templates());
        int onIsland = 0, inVoid = 0, checked = 0;
        for (long cx = -40; cx <= 40 && checked < 6; cx++) for (long cz = -40; cz <= 40 && checked < 6; cz++) {
            if (!b.trace(cx, cz)) continue;
            BetweenGenerator.Island is = b.island(cx, cz);
            if (is != null) {
                onIsland++;
                Chunk c = new Chunk(Math.floorDiv(is.x(), 16L), Math.floorDiv(is.z(), 16L));
                b.generate(c);
                check(c.get((int) Math.floorMod(is.x(), 16L), is.top() - 1, (int) Math.floorMod(is.z(), 16L)) == 0, "слід: тунель крізь острів");
            } else {
                inVoid++;
                long x = BetweenGenerator.cellMin(cx) + BetweenGenerator.HALF, z = BetweenGenerator.cellMin(cz) + BetweenGenerator.HALF;
                Chunk c = new Chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L));
                b.generate(c);
                boolean lamp = false;
                for (int y = 1; y < 200; y++) if (c.get((int) Math.floorMod(x, 16L), y, (int) Math.floorMod(z, 16L)) == g.blocks.byName("voxelin:lamp").id) lamp = true;
                check(lamp, "слід: лампа на стовпі в порожнечі");
            }
            checked++;
        }
        check(onIsland + inVoid > 0, "сліди трапляються");
        check(!b.trace(0, 0) && !b.trace(1, 0), "біля прибуття й дому слідів нема");
        check(g.records.all().stream().noneMatch(r -> r.id.contains("herobrine") || r.title.contains("Херобрин")), "про Херобрина в журналі ні слова");
    }
}
