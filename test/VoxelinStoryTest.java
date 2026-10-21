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
        records(); keeper(); choice(); persistence(); ritual(); ritualPersistence();
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

    static Mob find(World w, String id) { for (Mob m : w.mobs()) if (m.type.name.equals(id) && !m.dying) return m; return null; }

    static void ritual() throws Exception {
        check(g.items.get(Keeper.ANCHOR) != null && g.items.get("voxelin:seam_heart") != null, "якір і серце існують");
        World w = end("r1");
        w.info.dragonDead = true;
        Inventory inv = new Inventory(w.itemTypes);
        check(Keeper.act(w, inv, "give_anchors") == null, "без вибору Ритуалу якорі не беруться");
        Keeper.act(w, inv, "ritual");
        inv.add(Keeper.ANCHOR, 2);
        check(Keeper.act(w, inv, "give_anchors") == null && w.info.ritualStage == 0, "двох якорів замало");
        check(w.spawnRitualBossesIfNeeded() == 0, "до віддачі якорів босів нема");
        inv.add(Keeper.ANCHOR, 2);
        check(Keeper.dialog(w, inv).choices().get(0).enabled(), "з 4 якорями кнопка активна");
        check(Keeper.act(w, inv, "give_anchors") != null && w.info.ritualStage == 1, "якорі віддано");
        check(Trading.count(inv, w.itemTypes, Keeper.ANCHOR) == 1, "забрано рівно 3");
        Mob a = find(w, World.WEAVER), b = find(w, World.UNRAVELER);
        check(a != null && b != null && a.type.boss && a.type.flying && b.keep, "Ткач і Розплітач прилетіли");
        check(w.spawnRitualBossesIfNeeded() == 0, "удруге не створюються");
        check(Keeper.act(w, inv, "give_anchors") == null, "удруге якорі не беруться");
        check(w.info.records.contains("voxelin:ritual_twins"), "запис про двох");
        // перший падає - другий лютує
        w.hurtMob(a, 10_000, 0, 0, a.pos);
        check(w.info.ritualKills == 1 && w.info.ritualStage == 1 && b.enraged && !a.enraged, "Розплітач лютує після смерті Ткача");
        check(w.spawnRitualBossesIfNeeded() == 0, "вбитий не відроджується");
        w.hurtMob(b, 10_000, 0, 0, b.pos);
        check(w.info.ritualKills == 3 && w.info.ritualStage == 2 && w.info.records.contains("voxelin:ritual_done"), "Ритуал звершено");
        boolean heart = false, wings = false;
        for (voxelin.core.entity.ItemEntity e : w.itemEntities()) { if (e.item.equals("voxelin:seam_heart")) heart = true; if (e.item.equals(Inventory.WINGS)) wings = true; }
        check(heart && wings, "Серце Шву й Крила Шву випали");
        check(Keeper.dialog(w, inv).text().contains("звершено"), "Сторож дякує");
        // лютий б'є сильніше
        w.close();
    }

    static void ritualPersistence() throws Exception {
        World w = end("r2");
        w.info.dragonDead = true;
        Inventory inv = new Inventory(w.itemTypes);
        Keeper.act(w, inv, "ritual");
        inv.add(Keeper.ANCHOR, 3);
        Keeper.act(w, inv, "give_anchors");
        Mob a = find(w, World.WEAVER);
        w.hurtMob(a, 10_000, 0, 0, a.pos);
        for (int k = 0; k < 40; k++) w.tick();
        w.close();
        World r = g.openDimension("r2", "end");
        check(r.info.ritualStage == 1 && r.info.ritualKills == 1, "стан ритуалу збережено");
        check(find(r, World.WEAVER) == null && find(r, World.UNRAVELER) != null, "живий бос збережений, вбитий - ні");
        r.killAll(null);
        check(r.spawnRitualBossesIfNeeded() == 1 && find(r, World.UNRAVELER) != null && find(r, World.WEAVER) == null, "зниклий живий бос повертається, вбитий - ні");
        r.close();
    }
}
