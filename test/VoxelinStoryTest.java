import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Сюжет: журнал записів, Сторож Шва, вибір Ритуал/Зараження (раз і остаточно), збереження. */
public class VoxelinStoryTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;

    static World end(String name) throws Exception {
        g.createWorld(name, 9, WorldInfo.Type.NORMAL);
        World w = g.openDimension(name, "end");
        for (int i = 0; i < 600; i++) { w.update(0, 0, 2); boolean all = true; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        w.naturalSpawning = false;
        return w;
    }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-story").toFile();
        g = new Game(new File(root, "saves"), null);
        records(); keeper(); choice(); persistence();
        System.out.println("VoxelinStoryTest: " + n + " перевірок OK");
    }

    static void records() {
        check(g.records.all().size() >= 11, "записів не менше 11: " + g.records.all().size());
        for (RecordRegistry.Record r : g.records.all()) check(!r.title.isBlank() && r.text.length() > 20, "запис має назву й текст: " + r.id);
        check(g.records.get("voxelin:nothing") == null, "невідомий запис");
        MobType k = g.mobTypes.get("voxelin:seam_keeper");
        check(k != null && k.invulnerable && k.talkable() && "keeper".equals(k.dialog), "Сторож: невразливий, говорить");
    }

    static void keeper() throws Exception {
        World w = end("k1");
        check(!w.spawnKeeperIfNeeded(), "поки дракон живий - Сторожа нема");
        check(Keeper.dialog(w, new Inventory()).choices().isEmpty(), "без смерті дракона вибору нема");
        check(Keeper.act(w, new Inventory(), "ritual") == null, "вибір до смерті дракона неможливий");
        w.info.dragonDead = true;
        check(w.spawnKeeperIfNeeded() && !w.spawnKeeperIfNeeded(), "Сторож створюється рівно один раз");
        Mob k = null; for (Mob m : w.mobs()) if (m.type.invulnerable) k = m;
        check(k != null, "Сторож у світі");
        double h = k.health;
        check(!k.damage(50, 0, 0, k.pos) && k.health == h, "Сторожа вбити не можна");
        check(w.unlockRecord("voxelin:gate") && !w.unlockRecord("voxelin:gate") && !w.unlockRecord("voxelin:nothing"), "запис відкривається раз; невідомий ігнорується");
        w.close();
    }

    static void choice() throws Exception {
        World w = end("k2");
        w.info.dragonDead = true;
        Inventory inv = new Inventory();
        Keeper.Dialog d = Keeper.dialog(w, inv);
        check(d.choices().size() == 2 && d.text().contains("остаточний"), "два варіанти, наслідки показані до вибору");
        check(Keeper.act(w, inv, "bogus") == null, "невідома дія");
        check(Keeper.act(w, inv, "infection") != null && w.info.eggChoice == WorldInfo.EggChoice.INFECTION, "обрано зараження");
        check(Keeper.act(w, inv, "ritual") == null && w.info.eggChoice == WorldInfo.EggChoice.INFECTION, "вибір остаточний");
        check(w.info.records.contains("voxelin:choice_infection") && !w.info.records.contains("voxelin:choice_ritual") && w.info.records.contains("voxelin:keeper"), "записи вибору");
        check(Keeper.dialog(w, inv).choices().isEmpty(), "після вибору кнопок нема");
        w.close();
        World r = end("k3");
        r.info.dragonDead = true;
        check(Keeper.act(r, new Inventory(), "ritual") != null && r.info.eggChoice == WorldInfo.EggChoice.RITUAL, "обрано ритуал");
        Keeper.Dialog rd = Keeper.dialog(r, new Inventory());
        check(rd.choices().size() == 1 && !rd.choices().get(0).enabled(), "без якорів віддати не можна");
        r.close();
    }

    static void persistence() throws Exception {
        World w = end("k4");
        w.info.dragonDead = true;
        w.unlockRecord("voxelin:elder");
        Keeper.act(w, new Inventory(), "ritual");
        w.close();
        World b = g.openDimension("k4", "end");
        check(b.info.eggChoice == WorldInfo.EggChoice.RITUAL && b.info.records.contains("voxelin:elder") && b.info.records.contains("voxelin:choice_ritual"), "вибір і записи збережені");
        b.close();
    }
}
