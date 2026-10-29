import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Зараження: старт від яйця, фази, розростання біля гравця (темрява/світло/вода), зупинки (вилікувати, спалити, приручити), збереження. */
public class VoxelinInfectionTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    static final class Fake implements PlayerTarget {
        Pos pos; Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return false; }
        public void hurt(double d, double kx, double kz) {}
    }

    static World open(String name, WorldInfo.EggChoice choice) throws Exception {
        g.createWorld(name, 5, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        load(w);
        w.naturalSpawning = false;
        w.info.eggChoice = choice;
        return w;
    }

    static void load(World w) throws Exception {
        for (int i = 0; i < 600; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }
    static BlockType b(World w, String n) { return w.blocks.byName(n); }
    static String at(World w, long x, int y, long z) { return w.blocks.get(Math.max(0, w.blockId(x, y, z))).name; }

    /** Поставити яйце на поверхню в (0,0); повертає y ядра. */
    static int placeEgg(World w) {
        int y = surface(w, 0, 0) + 1;
        check(w.setBlock(P, 0, y, 0, b(w, Infection.EGG)), "яйце поставлено");
        return y;
    }

    static int growBlocks(World w, int r) {
        int id = b(w, Infection.GROWTH).id, c = 0;
        for (long x = -r; x <= r; x++) for (long z = -r; z <= r; z++) for (int y = 0; y < 120; y++) if (w.blockId(x, y, z) == id) c++;
        return c;
    }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-inf").toFile();
        g = new Game(new File(root, "saves"), null);
        content(); start(); phases(); cataclysms(); spread(); tame(); cure(); burn(); persistence();
        System.out.println("VoxelinInfectionTest: " + n + " перевірок OK");
    }

    static void content() {
        for (String s : new String[]{Infection.CORE, Infection.GROWTH, Infection.SCAR}) check(g.blocks.byName(s) != null, "блок " + s);
        check(g.blocks.byName(Infection.CORE).hardness < 0, "ядро незламне");
        for (String s : new String[]{Infection.CURE, Infection.FIRE}) check(g.items.get(s) != null, "предмет " + s);
        check(g.records.get("voxelin:infection_start") != null && g.records.get("voxelin:infection_end") != null, "записи зараження");
    }

    static void start() throws Exception {
        World w = open("s1", WorldInfo.EggChoice.NONE);
        int y = placeEgg(w);
        check(at(w, 0, y, 0).equals(Infection.EGG) && w.info.infState == WorldInfo.InfState.NONE, "без вибору «Зараження» яйце - просто блок");
        w.close();
        World r = open("s2", WorldInfo.EggChoice.RITUAL);
        y = placeEgg(r);
        check(at(r, 0, y, 0).equals(Infection.EGG) && r.info.infState == WorldInfo.InfState.NONE, "при Ритуалі яйце не заражає");
        r.close();
        World i = open("s3", WorldInfo.EggChoice.INFECTION);
        y = placeEgg(i);
        check(at(i, 0, y, 0).equals(Infection.CORE), "яйце стало ядром");
        check(i.info.infState == WorldInfo.InfState.ACTIVE && i.info.infX == 0 && i.info.infY == y && i.info.infZ == 0, "зараження почалось у точці ядра");
        check(i.info.records.contains("voxelin:infection_start"), "запис початку");
        check(i.setBlock(P, 3, y, 3, b(i, Infection.EGG)) && at(i, 3, y, 3).equals(Infection.EGG), "друге яйце вже не ядро");
        check(i.infection.isCore(0, y, 0) && !i.infection.isCore(3, y, 3), "isCore");
        i.close();
    }

    static void phases() throws Exception {
        World w = open("p1", WorldInfo.EggChoice.INFECTION);
        placeEgg(w);
        long d = w.dayTicks(), s = w.info.infStart;
        check(w.infection.phase() == Infection.Phase.QUIET && w.infection.day() == 0, "початок - тиха пора");
        w.info.time = s + d * 9 + d - 1; check(w.infection.phase() == Infection.Phase.QUIET, "день 9 - тиха");
        w.info.time = s + d * 10; check(w.infection.phase() == Infection.Phase.ACTIVE, "день 10 - активна");
        w.info.time = s + d * 20; check(w.infection.phase() == Infection.Phase.CRISIS, "день 20 - криза");
        w.info.time = s + d * 500; check(w.infection.phase() == Infection.Phase.CRISIS, "далі - криза");
        w.close();
    }

    static void cataclysms() throws Exception {
        g.createWorld("k0", 5, WorldInfo.Type.FLAT, WorldInfo.Cataclysms.MILD);
        check(WorldInfo.load(g.worldDir("k0")).cataclysms == WorldInfo.Cataclysms.MILD, "налаштування зберігається у світі");
        check(WorldInfo.load(g.worldDir("s1")).cataclysms == WorldInfo.Cataclysms.ON, "за замовчуванням - увімкнено");
        WorldInfo end = new WorldInfo(); end.type = WorldInfo.Type.END;
        end.copyPlayerFrom(WorldInfo.load(g.worldDir("k0")));
        check(end.cataclysms == WorldInfo.Cataclysms.MILD, "переходить у вимір (вибір робиться в Краї)");
        // помірні: без кризи й прориву
        World m = open("k1", WorldInfo.EggChoice.INFECTION);
        m.info.cataclysms = WorldInfo.Cataclysms.MILD;
        placeEgg(m);
        long d = m.dayTicks(), s = m.info.infStart;
        m.info.time = s + d * 15; check(m.infection.phase() == Infection.Phase.ACTIVE, "помірні: день 15 - активна");
        m.info.time = s + d * 25; check(m.infection.phase() == Infection.Phase.ACTIVE, "помірні: день 25 - все ще активна, не криза");
        m.info.time = s + d * 100; check(!m.infection.breached() && m.infection.radius() == Infection.RADIUS && m.infection.rate() == 2 / 40.0, "помірні: прориву нема");
        m.info.cataclysms = WorldInfo.Cataclysms.ON;
        check(m.infection.breached() && m.infection.radius() == Infection.BREACH_RADIUS, "увімкнені: день 100 - прорив");
        m.close();
        // вимкнені: Сторож не пропонує Зараження, яйце не заражає
        World o = open("k2", WorldInfo.EggChoice.NONE);
        o.info.cataclysms = WorldInfo.Cataclysms.OFF; o.info.dragonDead = true;
        Keeper.Dialog dl = Keeper.dialog(o, null);
        check(dl.choices().size() == 1 && dl.choices().get(0).action().equals("ritual"), "вимкнені: у розвилці лише Ритуал");
        check(Keeper.act(o, null, "infection") == null && o.info.eggChoice == WorldInfo.EggChoice.NONE, "вимкнені: дію «infection» не прийнято");
        o.info.eggChoice = WorldInfo.EggChoice.INFECTION; // навіть якщо вибір якось уже стоїть
        int y = placeEgg(o);
        check(at(o, 0, y, 0).equals(Infection.EGG) && o.info.infState == WorldInfo.InfState.NONE, "вимкнені: яйце - просто блок");
        o.close();
    }

    static void spread() throws Exception {
        World w = open("g1", WorldInfo.EggChoice.INFECTION);
        int y = placeEgg(w);
        for (int k = 0; k < 4000; k++) w.infection.spread();
        int c = w.infection.growthCount();
        check(c > 20, "біля гравця росте: " + c);
        check(growBlocks(w, 40) == c, "кожна клітинка списку - блок наросту: " + growBlocks(w, 40) + " vs " + c);
        check(at(w, 0, y, 0).equals(Infection.CORE), "ядро на місці");
        // що не поглинається
        check(!w.infection.consumable(0, y, 0) && !w.infection.consumable(0, 200, 0), "ядро й повітря не поглинаються");
        w.setBlock(P, 20, y - 1, 20, b(w, "voxelin:obsidian"));
        check(!w.infection.consumable(20, y - 1, 20), "обсидіан стримує");
        // темрява швидше за світло, вода гальмує
        int sy = surface(w, 30, 30);
        double lit = w.infection.chance(30, sy, 30), dark = w.infection.chance(30, Math.max(1, sy - 6), 30);
        check(dark > lit, "у темряві швидше: " + dark + " > " + lit);
        w.setBlock(P, 31, sy - 6, 30, b(w, "voxelin:water"));
        check(w.infection.chance(30, sy - 6, 30) < dark, "вода поруч гальмує");
        // тік світу: росте й без гравця (зараження росте завжди, поки йде відлік)
        int before = w.infection.growthCount();
        for (int k = 0; k < 2000; k++) w.tick();
        check(w.infection.growthCount() > before, "тік світу розрощує навіть без гравця");
        // зона: нічого за межею радіуса
        boolean inside = true;
        w.info.infOwed = 50_000;
        for (int k = 0; k < 400; k++) w.tick();
        check(w.info.infOwed < 50_000 - 1000, "накопичені спроби виконуються (доростає пропущене): " + w.info.infOwed);
        int gid0 = b(w, Infection.GROWTH).id;
        for (long x = -100; x <= 100 && inside; x += 1) for (long z = -100; z <= 100 && inside; z += 1) {
            if (Math.abs(x) <= Infection.RADIUS && Math.abs(z) <= Infection.RADIUS) continue;
            if (w.chunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) continue;
            for (int yy = 0; yy < 120; yy++) if (w.blockId(x, yy, z) == gid0) { inside = false; break; }
        }
        check(inside, "наріст не виходить за радіус 64 до прориву");
        check(w.infection.radius() == Infection.RADIUS, "радіус 64");
        w.info.time = w.info.infStart + w.dayTicks() * Infection.DAYS;
        check(w.infection.radius() == Infection.BREACH_RADIUS && w.infection.rate() == 2 * 4 / 12.0, "після 30-го дня: радіус 128, удвічі швидше");
        w.info.time = w.info.infStart;
        check(Math.abs(w.infection.rate() - 0.01) < 1e-12, "тиха пора: спроба на 100 тіків");
        // ділянка ядра не завантажена: борг накопичується, нічого не росте
        long cx = w.info.infX; w.info.infX = 10_000_000;
        double owed = w.info.infOwed;
        int cnt = w.infection.growthCount();
        check(w.infection.step() == Infection.Step.DEFERRED, "незавантажене ядро - відкладено");
        for (int k = 0; k < 300; k++) w.tick();
        check(w.info.infOwed > owed && w.infection.growthCount() <= cnt, "борг росте, поки ділянка не завантажена");
        w.info.infX = cx;
        // зламаний наріст забувається
        int gid = b(w, Infection.GROWTH).id;
        for (long x = -40; x <= 40; x++) for (long z = -40; z <= 40; z++) for (int yy = 0; yy < 120; yy++) if (w.blockId(x, yy, z) == gid) w.setBlock(P, x, yy, z, b(w, "voxelin:stone"));
        for (int k = 0; k < 6000; k++) w.infection.spread();
        check(growBlocks(w, 40) <= w.infection.growthCount(), "наріст рахується чесно після ламання");
        w.close();
    }

    static Inventory inv(World w, String item, int count) { Inventory i = new Inventory(w.itemTypes); if (item != null) i.add(item, count); return i; }

    static void tame() throws Exception {
        World w = open("t1", WorldInfo.EggChoice.INFECTION);
        int y = placeEgg(w);
        for (int k = 0; k < 3000; k++) w.infection.spread();
        String st = w.infection.use(inv(w, null, 0), 0, y, 0, false);
        check(st != null && st.contains("день 1") && st.contains("з 30"), "ПКМ по ядру - стан: " + st);
        check(w.infection.use(inv(w, null, 0), 3, y, 3, false) == null, "не ядро - null");
        Inventory five = inv(w, Infection.SHARD, 5);
        check(w.infection.use(five, 0, y, 0, false).contains("8") && w.info.infState == WorldInfo.InfState.ACTIVE && Trading.count(five, w.itemTypes, Infection.SHARD) == 5, "5 уламків замало, нічого не забрано");
        Inventory ten = inv(w, Infection.SHARD, 10);
        w.infection.use(ten, 0, y, 0, false);
        check(w.info.infState == WorldInfo.InfState.TAMED && Trading.count(ten, w.itemTypes, Infection.SHARD) == 2, "приручено, забрано рівно 8");
        int c = w.infection.growthCount();
        for (int k = 0; k < 3000; k++) w.infection.spread();
        w.info.infOwed = 5000;
        for (int k = 0; k < 500; k++) w.tick();
        check(w.infection.growthCount() == c, "приручене не росте");
        int gb = growBlocks(w, 40);
        for (int k = 0; k < 500; k++) w.tick();
        check(growBlocks(w, 40) == gb && gb > 0, "наріст лишився, але не росте");
        check(at(w, 0, y, 0).equals(Infection.CORE) && w.info.records.contains("voxelin:infection_end"), "ядро лишається; запис кінця");
        w.close();
    }

    static void cure() throws Exception {
        World w = open("c1", WorldInfo.EggChoice.INFECTION);
        int y = placeEgg(w);
        for (int k = 0; k < 3000; k++) w.infection.spread();
        check(growBlocks(w, 40) > 0, "є що лікувати");
        Inventory i = inv(w, Infection.CURE, 1);
        check(w.infection.use(i, 0, y, 0, false) != null && i.selectedStack() == null, "зілля витрачено");
        check(w.info.infState == WorldInfo.InfState.CURED && growBlocks(w, 40) == 0 && w.infection.growthCount() == 0, "наріст зник");
        check(at(w, 0, y, 0).equals(Infection.EGG), "ядро знову яйце");
        check(w.infection.use(inv(w, Infection.CURE, 1), 0, y, 0, false) == null, "вилікуване більше не ядро");
        check(w.setBlock(P, 2, y, 2, b(w, Infection.EGG)) && at(w, 2, y, 2).equals(Infection.EGG), "після лікування яйце не заражає знову");
        // наріст у незавантаженому чанку чекає
        w.infection.growthCount();
        w.close();
    }

    static void burn() throws Exception {
        World w = open("b1", WorldInfo.EggChoice.INFECTION);
        int y = placeEgg(w);
        for (int k = 0; k < 3000; k++) w.infection.spread();
        Inventory i = inv(w, Infection.FIRE, 1);
        w.infection.use(i, 0, y, 0, true); // креатив: не витрачається
        check(i.selectedStack() != null, "у креативі вогонь не витрачається");
        check(w.info.infState == WorldInfo.InfState.BURNED && w.blockId(0, y, 0) == 0 && w.blockId(0, y - 3, 0) == 0, "кратер на місці ядра");
        check(growBlocks(w, 40) == 0, "наріст випалено");
        int scar = b(w, Infection.SCAR).id, sc = 0;
        for (long x = -8; x <= 8; x++) for (long z = -8; z <= 8; z++) for (int yy = y - 8; yy <= y + 8; yy++) if (w.blockId(x, yy, z) == scar) sc++;
        check(sc > 20, "обпалена оболонка кратера: " + sc);
        check(w.infection.use(inv(w, null, 0), 0, y, 0, false) == null, "ядра більше нема");
        w.close();
    }

    static void persistence() throws Exception {
        World w = open("r1", WorldInfo.EggChoice.INFECTION);
        int y = placeEgg(w);
        for (int k = 0; k < 3000; k++) w.infection.spread();
        int c = w.infection.growthCount();
        long start = w.info.infStart;
        w.info.infOwed = 123.5;
        w.close();
        World r = g.openWorld("r1");
        check(r.info.infState == WorldInfo.InfState.ACTIVE && r.info.infY == y && r.info.infStart == start && r.infection.growthCount() == c && r.info.infOwed == 123.5, "стан, наріст і борг збережені");
        load(r);
        check(at(r, 0, y, 0).equals(Infection.CORE), "ядро в збереженому світі");
        Inventory i = inv(r, Infection.CURE, 1);
        r.infection.use(i, 0, y, 0, false);
        r.close();
        World q = g.openWorld("r1");
        check(q.info.infState == WorldInfo.InfState.CURED && q.infection.growthCount() == 0, "вилікуваний стан збережено");
        q.close();
        // биті дані
        File dir = new File(g.savesDir, "r1");
        Files.writeString(new File(dir, "infection.dat").toPath(), "1 2 3\nсміття\n1 999 3\n4 5\n");
        World z = g.openWorld("r1");
        check(z.infection.growthCount() == 1, "биті рядки ігноруються");
        z.close();
    }
}
