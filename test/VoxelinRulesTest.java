import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Чити й правила гри: без читів команди гравцю недоступні (крім /help і /seed), /gamerule, збереження, дія правил; зерно з поля. */
public class VoxelinRulesTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-rules").toFile(), "saves"), null);
        cheats(); rules(); effects(); seeds();
        System.out.println("VoxelinRulesTest: " + n + " перевірок OK");
    }

    static String run(World w, String line) { return String.join("\n", g.commands.execute(P, w, new long[]{0, 70, 0}, line)); }

    static void cheats() throws Exception {
        g.createWorld("nc", 5, WorldInfo.Type.FLAT, WorldInfo.Cataclysms.ON, WorldInfo.Mode.SURVIVAL, false, Map.of());
        World w = g.openWorld("nc");
        check(!w.info.cheats, "світ без читів");
        for (String c : new String[]{"/give diamond", "/gamemode creative", "/gamerule keepInventory true", "/time set ніч", "/tp 0 80 0"})
            check(run(w, c).contains("Чити в цьому світі вимкнено"), "без читів відмовлено: " + c);
        check(!w.info.rule(GameRules.KEEP_INVENTORY) && w.info.mode == WorldInfo.Mode.SURVIVAL, "нічого не змінилось");
        check(run(w, "/seed").contains("5") && run(w, "/help").contains("/gamerule"), "/seed і /help - без читів");
        // система (не гравець) - не обмежена
        check(!String.join("", g.commands.execute(Sources.SYSTEM, w, new long[3], "/gamerule fallDamage false")).contains("вимкнено") && !w.info.rule(GameRules.FALL_DAMAGE), "системне джерело не обмежене читами");
        w.close();
        check(WorldInfo.load(g.worldDir("nc")).cheats == false, "«чити: ні» зберігається");
        g.createWorld("ch", 5, WorldInfo.Type.FLAT, WorldInfo.Cataclysms.ON, WorldInfo.Mode.CREATIVE, true, Map.of(GameRules.KEEP_INVENTORY, true));
        World c = g.openWorld("ch");
        check(c.info.cheats && c.info.mode == WorldInfo.Mode.CREATIVE && c.info.rule(GameRules.KEEP_INVENTORY), "налаштування з екрана створення застосовано");
        check(!run(c, "/gamemode survival").contains("вимкнено") && c.info.mode == WorldInfo.Mode.SURVIVAL, "з читами команди працюють");
        c.close();
        // старі світи без поля - з читами
        WorldInfo old = WorldInfo.load(g.worldDir("ch"));
        File f = new File(g.worldDir("ch"), "voxelin.world");
        Files.writeString(f.toPath(), Files.readString(f.toPath()).replaceAll("(?m)^cheats=.*\\R", ""));
        check(!Files.readString(f.toPath()).contains("cheats="), "поле прибрано");
        check(WorldInfo.load(g.worldDir("ch")).cheats, "старий світ без поля «cheats» - з читами");
    }

    static void rules() throws Exception {
        g.createWorld("r", 5, WorldInfo.Type.FLAT);
        World w = g.openWorld("r");
        check(w.info.cheats, "dev-створення (автосвіт, тести) - з читами");
        String list = run(w, "/gamerule");
        for (GameRules.Rule r : GameRules.ALL) check(list.contains(r.id()), "у списку " + r.id());
        check(run(w, "/gamerule doMobLoot").contains("doMobLoot = true"), "читання правила");
        check(run(w, "/gamerule domobloot false").contains("doMobLoot = false") && !w.info.rule(GameRules.MOB_LOOT), "зміна (без урахування регістру)");
        check(run(w, "/gamerule nope true").contains("Невідоме правило") && run(w, "/gamerule doMobLoot maybe").contains("true або false"), "помилки");
        run(w, "/gamerule doDaylightCycle false");
        w.close();
        WorldInfo i = WorldInfo.load(g.worldDir("r"));
        check(!i.rule(GameRules.MOB_LOOT) && !i.rule(GameRules.DAYLIGHT) && i.rule(GameRules.FALL_DAMAGE), "правила збережено");
        i.setRule(GameRules.MOB_LOOT, true);
        check(!i.rules.containsKey(GameRules.MOB_LOOT), "типове значення не пишеться у файл");
        WorldInfo end = new WorldInfo(); end.type = WorldInfo.Type.END; end.copyPlayerFrom(i);
        check(!end.rule(GameRules.DAYLIGHT) && end.cheats == i.cheats, "правила й чити спільні для вимірів");
    }

    static void effects() throws Exception {
        g.createWorld("e", 5, WorldInfo.Type.FLAT);
        World w = g.openWorld("e");
        w.naturalSpawning = false;
        for (int k = 0; k < 600; k++) { w.update(0, 0, 2); if (w.chunk(0, 0) != null && w.chunk(1, 1) != null && w.chunk(-1, -1) != null) break; Thread.sleep(10); }
        // зміна дня вимкнена: сонце стоїть, ігровий час (зараження) іде
        w.info.setRule(GameRules.DAYLIGHT, false);
        double ph = w.dayPhase(); long t = w.info.time;
        for (int k = 0; k < 500; k++) w.tick();
        check(w.dayPhase() == ph && w.info.time == t + 500, "сонце стоїть, а час іде");
        w.info.setRule(GameRules.DAYLIGHT, true);
        for (int k = 0; k < 500; k++) w.tick();
        check(w.dayPhase() > ph, "знову рухається");
        // здобич з істот
        int y = 0; for (int yy = 200; yy > 0; yy--) if (w.blockId(0, yy, 0) > 0) { y = yy + 1; break; }
        w.info.setRule(GameRules.MOB_LOOT, false);
        int items0 = w.itemEntities().size();
        for (int k = 0; k < 5; k++) { Mob cow = w.spawnMob(w.mobTypes.find("cow"), Pos.ofBlock(k * 2, y, 3)); cow.damage(100, 0, 0, null); }
        for (int k = 0; k < 40; k++) w.tick();
        check(w.itemEntities().size() == items0, "без здобичі: " + w.itemEntities().size());
        w.info.setRule(GameRules.MOB_LOOT, true);
        for (int k = 0; k < 5; k++) { Mob cow = w.spawnMob(w.mobTypes.find("cow"), Pos.ofBlock(k * 2, y, 6)); cow.damage(100, 0, 0, null); }
        for (int k = 0; k < 40; k++) w.tick();
        check(w.itemEntities().size() > items0, "зі здобиччю: " + w.itemEntities().size());
        // природне відновлення
        Vitals v = new Vitals(10, 20, 5);
        v.regen = false;
        for (int k = 0; k < 400; k++) v.tick(false);
        check(v.health() == 10, "без відновлення здоров'я не росте");
        v.regen = true;
        for (int k = 0; k < 400; k++) v.tick(false);
        check(v.health() > 10, "з відновленням росте");
        w.close();
    }

    static void seeds() throws Exception {
        java.lang.reflect.Method m = Class.forName("voxelin.client.Menus").getDeclaredMethod("parseSeed", String.class);
        m.setAccessible(true);
        check((long) m.invoke(null, "12345") == 12345L && (long) m.invoke(null, "-7") == -7L, "число - як є");
        check((long) m.invoke(null, "voxelin") == "voxelin".hashCode() && (long) m.invoke(null, " voxelin ") == "voxelin".hashCode(), "текст - хеш");
        check((long) m.invoke(null, "") != (long) m.invoke(null, ""), "порожнє - випадкове");
        g.createWorld("s", 42, WorldInfo.Type.NORMAL);
        g.createWorld("s2", 42, WorldInfo.Type.NORMAL);
        World a = g.openWorld("s"), b = g.openWorld("s2");
        check(a.biomeAt(1000, -300).equals(b.biomeAt(1000, -300)) && a.biomeAt(-5000, 777).equals(b.biomeAt(-5000, 777)), "те саме зерно - той самий світ");
        a.close(); b.close();
    }
}
