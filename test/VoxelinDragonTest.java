import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Дракон Краю: політ, пікірування, удар, смерть, збереження. */
public class VoxelinDragonTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;

    static final class Fake implements PlayerTarget {
        Pos pos; boolean creative; int hits; double total;
        Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return creative; }
        public void hurt(double d, double kx, double kz) { hits++; total += d; }
    }

    static World end(String name) throws Exception {
        g.createWorld(name, 9, WorldInfo.Type.NORMAL);
        World w = g.openDimension(name, "end");
        for (int i = 0; i < 600; i++) { w.update(0, 0, 2); boolean all = true; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }

    static Mob dragon(World w) { for (Mob m : w.mobs()) if (m.type.boss) return m; return null; }

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("vx-dragon").toFile();
        g = new Game(new File(root, "saves"), null);
        type(); flight(); combat(); death(); persistence();
        System.out.println("VoxelinDragonTest: " + n + " перевірок OK");
    }

    static void type() throws Exception {
        MobType t = g.mobTypes.get("voxelin:ender_dragon");
        check(t != null && t.flying && t.boss && t.persistent && t.health == 200 && t.attackDamage == 10 && t.armorPierce == 0.5 && t.hostile(), "дракон: політ, бос, 200 здоров'я, шкода 10, пробиття 0.5");
        check(t.parts.stream().anyMatch(p -> p.swing == 3) && t.parts.stream().anyMatch(p -> p.swing == 4), "модель має крила");
        check(t.drops.stream().anyMatch(d -> d.item.equals("voxelin:dragon_egg")), "дропає яйце");
        check(g.items.get("voxelin:dragon_egg") != null && g.items.get("voxelin:dragon_egg").placesBlock != null, "яйце - блок-предмет");
        g.createWorld("O", 1, WorldInfo.Type.FLAT);
        World over = g.openWorld("O");
        check(!over.spawnDragonIfNeeded(), "у звичайному світі дракона нема");
        over.close();
        check(new MobSettings().flying(true).boss(true).wingPart(1, 1, 1, 1, 1, 1, 0, true).partList().get(0).swing == 3, "wingPart");
    }

    static void flight() throws Exception {
        World w = end("flight");
        w.naturalSpawning = false;
        check(w.spawnDragonIfNeeded(), "дракон створюється при першому вході");
        check(!w.spawnDragonIfNeeded(), "удруге не створюється");
        Mob d = dragon(w);
        check(d != null && d.keep && d.home != null && d.pos.y > EndGenerator.TOP + 15, "дракон над островом, дім заданий");
        double minR = 1e9, maxR = 0, minY = 1e9, maxY = 0;
        for (int i = 0; i < 1800; i++) {
            w.tick();
            if (i > 300) { double r = Math.hypot(d.pos.relX(0), d.pos.relZ(0)), y = d.pos.y; minR = Math.min(minR, r); maxR = Math.max(maxR, r); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        check(minR > 15 && maxR < 55, "кружляє на радіусі ~35: " + minR + ".." + maxR);
        check(minY > EndGenerator.TOP + 10 && maxY < EndGenerator.TOP + 40, "тримає висоту ~+22: " + minY + ".." + maxY);
        check(d.alive() && !d.dying, "живий");
        w.close();
    }

    static void combat() throws Exception {
        World w = end("combat");
        w.naturalSpawning = false;
        w.spawnDragonIfNeeded();
        Mob d = dragon(w);
        Fake p = new Fake(Pos.ofBlock(0, EndGenerator.TOP + 1.9, EndGenerator.ARRIVAL_Z));
        w.setPlayerTarget(p);
        double minY = 1e9, maxY = 0;
        int ticks = 3000;
        for (int i = 0; i < ticks; i++) { w.tick(); if (i > 200) { minY = Math.min(minY, d.pos.y); maxY = Math.max(maxY, d.pos.y); } }
        check(p.hits >= 3, "дракон пікірує й б'є: " + p.hits);
        check(Math.abs(p.total - 10.0 * p.hits) < 1e-9, "кожен удар 10");
        check(p.hits * 50 <= ticks + 100, "перезарядка 2.5 с дотримується: " + p.hits);
        check(minY < EndGenerator.TOP + 8 && maxY > EndGenerator.TOP + 18, "пікірує вниз і злітає назад: " + minY + ".." + maxY);
        // творчий гравець - не б'є
        p.creative = true; int before = p.hits;
        for (int i = 0; i < 1500; i++) w.tick();
        check(p.hits == before, "творчого не атакує");
        // не відкидається, але отримує шкоду
        d.invuln = 0;
        double vx = d.vx;
        check(w.hurtMob(d, 10, 1, 0, Pos.ofBlock(0, 70, 0)) && d.health == 190, "удар по дракону знімає здоров'я");
        check(d.vx == vx || Math.abs(d.vx - vx) < 1e-9, "бос не відкидається");
        w.close();
    }

    static void death() throws Exception {
        World w = end("death");
        w.naturalSpawning = false;
        w.spawnDragonIfNeeded();
        Mob d = dragon(w);
        check(!w.info.dragonDead, "до смерті прапорець вимкнено");
        d.invuln = 0;
        check(w.hurtMob(d, 1000, 1, 0, Pos.ofBlock(0, 70, 0)) && d.dying, "смертельний удар");
        check(w.info.dragonDead, "смерть записана у світ");
        for (int i = 0; i < 40; i++) w.tick();
        boolean egg = false;
        for (var it : w.itemEntities()) if (it.item.equals("voxelin:dragon_egg")) { egg = true; check(it.pos.y > 55 && Math.abs(it.pos.relX(0)) < 12 && Math.abs(it.pos.relZ(0)) < 12, "яйце впало біля центру острова, а не в порожнечу: " + it.pos); }
        check(egg, "яйце дракона випало");
        for (int i = 0; i < 100; i++) w.tick();
        check(dragon(w) == null, "після анімації смерті дракон зникає");
        check(!w.spawnDragonIfNeeded(), "убитий дракон не відроджується");
        w.close();
        WorldInfo li = WorldInfo.load(new File(root, "saves/death/dimensions/end"));
        check(li.dragonDead, "прапорець збережено на диску");
        World again = g.openDimension("death", "end");
        check(!again.spawnDragonIfNeeded(), "після перезапуску дракон не відроджується");
        again.close();
    }

    static void persistence() throws Exception {
        World w = end("pers");
        w.naturalSpawning = false;
        w.spawnDragonIfNeeded();
        Mob d = dragon(w);
        d.invuln = 0; w.hurtMob(d, 100, 1, 0, Pos.ofBlock(0, 70, 0));
        check(d.health == 100, "здоров'я знято");
        w.close();
        World w2 = g.openDimension("pers", "end");
        w2.naturalSpawning = false;
        Mob d2 = dragon(w2);
        check(d2 != null && d2.health == 100 && d2.home != null && d2.keep, "дракон відновлений зі здоров'ям і домом");
        check(!w2.spawnDragonIfNeeded(), "другого дракона не створюється");
        // далеко від гравця не зникає
        w2.naturalSpawning = true;
        class Far implements PlayerTarget { public Pos body() { return Pos.ofBlock(3000, 70, 3000); } public boolean alive() { return true; } public boolean creative() { return true; } public void hurt(double a, double b, double c) {} }
        w2.setPlayerTarget(new Far());
        for (int i = 0; i < 300; i++) w2.tick();
        check(dragon(w2) != null, "бос не зникає від відстані");
        w2.close();
    }
}
