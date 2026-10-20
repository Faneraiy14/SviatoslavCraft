import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Жителі: торгівля (атомарність), дім і радіус блукання, збереження, відсутність природного спавну. */
public class VoxelinTradeTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final String APPLE = "voxelin:apple", GOLD = "voxelin:gold_ingot", DIRT = "voxelin:dirt";

    static MobSettings.Trade tr(String in, int ic, String out, int oc) { return new MobSettings.Trade(in, ic, out, oc); }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        registry(); trading(); home(); persistence();
        System.out.println("VoxelinTradeTest: " + n + " перевірок OK");
    }

    static void registry() {
        for (String v : new String[]{"villager_farmer", "villager_smith", "villager_elder"}) {
            MobType t = g.mobTypes.get("voxelin:" + v);
            check(t != null && t.persistent && t.homeRadius == 24 && t.passive() && t.talkable(), "житель " + v);
            check(!t.greetings.isEmpty(), "репліки " + v);
        }
        MobType f = g.mobTypes.find("villager_farmer"), s = g.mobTypes.find("villager_smith"), e = g.mobTypes.find("villager_elder");
        check(Trading.offers(f, g.items).size() == 5 && Trading.offers(s, g.items).size() == 4 && Trading.offers(e, g.items).isEmpty(), "угоди: " + Trading.offers(f, g.items).size() + "/" + Trading.offers(s, g.items).size());
        for (MobType t : g.mobTypes.all()) for (MobSettings.Trade tt : t.trades) check(g.items.get(tt.in) != null && g.items.get(tt.out) != null, "предмети угоди існують: " + t.name);
        check(!g.mobTypes.find("zombie").talkable() && !g.mobTypes.find("cow").talkable(), "звичайні істоти не говорять");
        check(tr("a", 0, "b", 999).inCount == 1 && tr("a", 999, "b", -3).inCount == 64 && tr("a", 1, "b", -3).outCount == 1, "межі угоди");
        // невідомі предмети відкидаються
        MobRegistry reg = new MobRegistry();
        MobType ghost = reg.register("x:ghost", new MobSettings().trade("x:nothing", 1, "voxelin:gold_ingot", 1).trade("voxelin:apple", 1, "voxelin:gold_ingot", 1));
        check(Trading.offers(ghost, g.items).size() == 1, "угоди з невідомими предметами відкидаються");
    }

    static void trading() {
        Inventory inv = new Inventory(g.items);
        MobSettings.Trade t = tr(APPLE, 8, GOLD, 1);
        check(!Trading.canTrade(inv, g.items, t) && !Trading.execute(inv, g.items, t), "порожній інвентар - угоди нема");
        inv.add(APPLE, 7);
        check(!Trading.canTrade(inv, g.items, t), "7 з 8 - мало");
        inv.add(APPLE, 1);
        check(Trading.canTrade(inv, g.items, t) && Trading.execute(inv, g.items, t), "рівно 8 - вистачає");
        check(Trading.count(inv, g.items, APPLE) == 0 && Trading.count(inv, g.items, GOLD) == 1, "обмін відбувся");
        // кілька стопок
        Inventory m = new Inventory(g.items);
        m.set(0, new Inventory.Stack(APPLE, 3)); m.set(5, new Inventory.Stack(APPLE, 3)); m.set(20, new Inventory.Stack(APPLE, 5));
        check(Trading.execute(m, g.items, t) && Trading.count(m, g.items, APPLE) == 3 && Trading.count(m, g.items, GOLD) == 1, "вхід береться з кількох стопок");
        check(m.get(0) == null && m.get(5) == null || m.get(0) == null || m.get(5) == null, "порожні стопки прибрано");
        // повний інвентар: результат не влізає
        Inventory full = new Inventory(g.items);
        for (int i = 0; i < 36; i++) full.set(i, new Inventory.Stack(DIRT, 64));
        full.set(4, new Inventory.Stack(APPLE, 5)); full.set(9, new Inventory.Stack(APPLE, 3));
        // забрати 8 яблук звільняє два слоти -> золото влізе
        check(Trading.execute(full, g.items, t) && Trading.count(full, g.items, GOLD) == 1, "вхід звільняє місце для результату");
        Inventory full2 = new Inventory(g.items);
        for (int i = 0; i < 36; i++) full2.set(i, new Inventory.Stack(DIRT, 64));
        full2.set(0, new Inventory.Stack(APPLE, 64));
        String before = String.join(",", Arrays.stream(full2.encode()).map(x -> x == null ? "-" : x).toArray(String[]::new));
        check(Trading.execute(full2, g.items, tr(APPLE, 1, DIRT, 64)) == false && String.join(",", Arrays.stream(full2.encode()).map(x -> x == null ? "-" : x).toArray(String[]::new)).equals(before), "результат не влізає - інвентар не змінюється (атомарність)");
        // інструменти: зношений не приймається як вхід
        Inventory tools = new Inventory(g.items);
        tools.add("voxelin:iron_pickaxe", 1, 10);
        MobSettings.Trade tt = tr("voxelin:iron_pickaxe", 1, GOLD, 1);
        check(!Trading.canTrade(tools, g.items, tt), "зношену кирку не приймають");
        tools.add("voxelin:iron_pickaxe", 1, 0);
        check(Trading.execute(tools, g.items, tt) && Trading.count(tools, g.items, "voxelin:iron_pickaxe") == 0, "цілу приймають");
        check(tools.get(0) != null && tools.get(0).damage == 10 || tools.get(1) != null && tools.get(1).damage == 10, "зношена лишилась у гравця");
        // невідомі предмети
        check(!Trading.canTrade(inv, g.items, tr("x:none", 1, GOLD, 1)) && !Trading.canTrade(inv, g.items, tr(GOLD, 1, "x:none", 1)), "невідомі предмети - не торгуємо");
        // обмін туди-назад і серія
        Inventory s = new Inventory(g.items);
        s.add(GOLD, 20);
        MobSettings.Trade buy = tr(GOLD, 1, "voxelin:steak", 3);
        int ok = 0; while (Trading.execute(s, g.items, buy)) ok++;
        check(ok == 20 && Trading.count(s, g.items, "voxelin:steak") == 60 && Trading.count(s, g.items, GOLD) == 0, "серія угод: " + ok);
        // сума предметів зберігається в обміні 1 на 1
        Inventory c = new Inventory(g.items);
        c.add("voxelin:coal", 64);
        check(Trading.execute(c, g.items, tr("voxelin:coal", 64, GOLD, 64)) && Trading.count(c, g.items, GOLD) == 64, "максимальні кількості");
    }

    static World world(String name, long seed) throws Exception {
        g.createWorld(name, seed, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        return w;
    }

    static void load(World w, int r) throws Exception {
        for (int i = 0; i < 600; i++) { w.update(0, 0, r); boolean all = true; for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r + r && w.chunk(dx, dz) == null) all = false; if (all) return; Thread.sleep(10); }
        throw new IllegalStateException("чанки не завантажились");
    }

    static void home() throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-trade").toFile(), "saves"), null);
        World w = world("home", 1);
        load(w, 4);
        int top = w.surfaceY(0, 0);
        MobType vt = g.mobTypes.find("villager_farmer");
        Mob v = w.spawnMob(vt, Pos.ofBlock(0, top + 1, 0));
        check(v.home != null && Math.hypot(v.home.relX(0), v.home.relZ(0)) < 1, "дім = місце появи");
        Random rng = new Random(4);
        double maxD = 0;
        for (int i = 0; i < 4000; i++) { v.tick(w, null, rng); maxD = Math.max(maxD, Math.hypot(v.pos.relX(0) - v.home.relX(0), v.pos.relZ(0) - v.home.relZ(0))); }
        check(maxD <= 24 + 4, "житель не йде далі за радіус дому: " + maxD);
        // відвели далеко - повертається
        v.pos.add(40, 0, 0); v.pos.y = top + 1;
        double d0 = Math.hypot(v.pos.relX(0) - v.home.relX(0), v.pos.relZ(0) - v.home.relZ(0));
        for (int i = 0; i < 600; i++) v.tick(w, null, rng);
        double d1 = Math.hypot(v.pos.relX(0) - v.home.relX(0), v.pos.relZ(0) - v.home.relZ(0));
        check(d1 < d0 - 10, "далеко від дому - повертається: " + d0 + " -> " + d1);
        // розмова: стоїть
        v.talkTicks = 100;
        double x0 = v.pos.relX(0), z0 = v.pos.relZ(0);
        for (int i = 0; i < 80; i++) v.tick(w, null, rng);
        check(Math.hypot(v.pos.relX(0) - x0, v.pos.relZ(0) - z0) < 1.2 && v.talkTicks == 20, "під час розмови стоїть");
        // бити можна: тікає й не помирає від першого удару
        v.talkTicks = 0; v.invuln = 0;
        w.hurtMob(v, 1, 1, 0, Pos.ofBlock(0, top + 1, 0));
        check(v.alive() && v.fleeTicks > 0, "удар - втеча");
        // природний спавн не створює жителів
        w.naturalSpawning = true;
        w.killAll(null);
        w.setTime((long) (w.dayTicks() * 0.1));
        class Tgt implements PlayerTarget { public Pos body() { return Pos.ofBlock(0, top + 1, 0); } public boolean alive() { return true; } public boolean creative() { return false; } public void hurt(double d, double kx, double kz) {} }
        w.setPlayerTarget(new Tgt());
        for (int i = 0; i < 20 * 120; i++) w.tick();
        boolean villager = false; int passives = 0;
        for (Mob m : w.mobs()) { if (m.type.persistent) villager = true; if (m.type.passive()) passives++; }
        check(!villager && passives > 0, "природний спавн не створює жителів (мирних: " + passives + ")");
        w.close();
    }

    static void persistence() throws Exception {
        File root = Files.createTempDirectory("vx-trade2").toFile();
        g = new Game(new File(root, "saves"), null);
        World w = world("p", 2);
        load(w, 2);
        int top = w.surfaceY(0, 0);
        Mob v = w.spawnMob(g.mobTypes.find("villager_smith"), Pos.ofBlock(3, top + 1, 3));
        v.home = Pos.ofBlock(7, top + 1, 7);
        Mob c = w.spawnMob(g.mobTypes.find("cow"), Pos.ofBlock(5, top + 1, 5));
        w.spawnMob(g.mobTypes.find("zombie"), Pos.ofBlock(6, top + 1, 6));
        w.close();
        World w2 = new Game(new File(root, "saves"), null).openWorld("p");
        check(w2.mobs().size() == 2, "жителя й корову збережено, зомбі - ні: " + w2.mobs().size());
        Mob lv = null, lc = null;
        for (Mob m : w2.mobs()) { if (m.type.persistent) lv = m; else lc = m; }
        check(lv != null && lv.home != null && Math.abs(lv.home.x - 7.5) < 1e-9 && Math.abs(lv.home.z - 7.5) < 1e-9, "дім жителя збережено");
        check(lc != null && lc.home == null, "у корови дому нема");
        w2.close();
        // старий формат (8 полів) читається, дім = позиція; биті дім-поля відкидають запис
        Files.write(new File(root, "saves/p/mobs.dat").toPath(), ("voxelin:villager_farmer 0 0 3.5 70 3.5 20 0\nvoxelin:villager_farmer 0 0 3.5 70 3.5 20 0 0 0 99 70 3.5\nvoxelin:villager_farmer 0 0 3.5 70 3.5 20 0 0 0 NaN 70 3.5\nvoxelin:villager_smith 0 0 4.5 70 4.5 20 0 0 0 8.5 70 8.5\nvoxelin:cow 0 0 1 2 3\n").getBytes());
        World w3 = new Game(new File(root, "saves"), null).openWorld("p");
        check(w3.mobs().size() == 2, "старий формат і хороший 13-польовий читаються, биті - ні: " + w3.mobs().size());
        for (Mob m : w3.mobs()) check(m.home != null, "дім виставлено");
        w3.close();
    }
}
