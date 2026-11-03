import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/** Скелет-стрілець: ворожі стріли б'ють гравця, не підбираються, не б'ють свого стрільця; стрілець тримає дистанцію, не стріляє крізь стіну, горить на сонці; кістки й кістяне борошно. */
public class VoxelinSkeletonTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");
    static final String SK = "voxelin:skeleton";

    static final class Fake implements PlayerTarget {
        Pos pos; boolean creative; int hits; double total;
        Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return creative; }
        public void hurt(double d, double kx, double kz) { hits++; total += d; }
    }

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-skel").toFile(), "saves"), null);
        registry(); shooting(); wall(); retreat(); ownArrow(); creative(); sun(); boneMeal();
        System.out.println("VoxelinSkeletonTest: " + n + " перевірок OK");
    }

    static int seq;
    static World open() throws Exception {
        String name = "s" + (seq++);
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }
    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }
    static void night(World w) { w.setTime((long) (w.dayTicks() * 0.78)); }
    static Fake player(World w, double x, double z) { Fake f = new Fake(new Pos(0, 0, x, surface(w, (long) Math.floor(x), (long) Math.floor(z)) + 1 + 0.9, z)); w.setPlayerTarget(f); return f; }
    static Mob skeleton(World w, double x, double z) { return w.spawnMob(g.mobTypes.get(SK), new Pos(0, 0, x, surface(w, (long) Math.floor(x), (long) Math.floor(z)) + 1, z)); }
    static double dist(Mob m, Fake f) { return Math.hypot(m.pos.relX(0) - f.pos.x, m.pos.relZ(0) - f.pos.z); }

    static void registry() {
        MobType t = g.mobTypes.get(SK);
        check(t != null && t.hostile() && t.ranged() && t.burnsInSun && t.rangedRange == 15 && t.rangedCooldownTicks == 40, "скелет: ворожий стрілець, горить на сонці");
        boolean arrow = false, bone = false;
        for (BlockSettings.Drop d : t.drops) { if (d.item.equals(Arrows.ITEM)) arrow = true; if (d.item.equals("voxelin:bone")) bone = true; }
        check(arrow && bone, "зі скелета - стріли й кістки");
        Inventory.Stack[] gr = new Inventory.Stack[9];
        gr[4] = new Inventory.Stack("voxelin:bone", 1);
        Inventory.Stack m = Crafting.match(g.recipes, g.items, gr, 3);
        check(m != null && m.item.equals(Growth.BONE_MEAL) && m.count == 3, "кістка -> 3 кістяного борошна");
        check(new MobSettings().ranged(Float.NaN, -1, 99).rangedRangeValue() == 0 && new MobSettings().ranged(500, 0, 99).rangedSpeedValue() == 4, "ranged(): межі й NaN");
    }

    static void shooting() throws Exception {
        World w = open();
        night(w);
        Fake f = player(w, 0.5, 0.5);
        Mob s = skeleton(w, 10.5, 0.5);
        int shot = 0;
        for (int i = 0; i < 20 * 12; i++) { night(w); int before = w.arrows.all().size(); w.tick(); if (w.arrows.all().size() > before) shot++; }
        check(shot >= 4, "стріляє раз на ~2 с: " + shot);
        check(f.hits >= 2, "стріли влучають у гравця на 10 блоках: " + f.hits + " з " + shot);
        check(f.total / Math.max(1, f.hits) >= 2 && f.total / Math.max(1, f.hits) <= 6, "шкода стріли 2..6: " + f.total / Math.max(1, f.hits));
        double d = dist(s, f);
        check(d >= Mob.KEEP_AWAY - 1 && d <= 16, "тримає дистанцію: " + d);
        check(s.health == 20, "свої стріли скелета не ранять: " + s.health);
        for (Arrows.Arrow a : w.arrows.all()) check(a.hostile() && !a.pickup, "ворожа стріла не підбирається");
        // застрягла ворожа стріла не підбирається
        Arrows.Arrow down = w.arrows.shootFrom(s, new Pos(0, 0, 30.5, surface(w, 30, 30) + 3, 30.5), 0, -1, 0, 1);
        for (int i = 0; i < 40 && !down.stuck; i++) w.tick();
        check(down.stuck, "ворожа стріла застрягла в землі");
        Inventory inv = new Inventory();
        for (int i = 0; i < 20; i++) w.tick();
        check(w.arrows.collect(down.pos.copy(), inv) == 0 && !Arrows.hasArrow(inv), "стрілу скелета не підібрати");
    }

    static void wall() throws Exception {
        World w = open();
        night(w);
        int y0 = surface(w, 5, 0) + 1;
        BlockType stone = g.blocks.byName("voxelin:stone");
        for (int z = -6; z <= 6; z++) for (int y = y0; y < y0 + 6; y++) w.setBlock(P, 5, y, z, stone);
        Fake f = player(w, 0.5, 0.5);
        Mob s = skeleton(w, 11.5, 0.5);
        for (int i = 0; i < 20 * 6; i++) { night(w); w.tick(); }
        check(!s.seesTarget, "за стіною гравця не видно");
        check(f.hits == 0, "крізь стіну не влучає: " + f.hits);
        int arrows = 0; for (Arrows.Arrow a : w.arrows.all()) if (a.hostile()) arrows++;
        check(arrows == 0, "за стіною не стріляє: " + arrows);
    }

    static void retreat() throws Exception {
        World w = open();
        night(w);
        Fake f = player(w, 0.5, 0.5);
        Mob s = skeleton(w, 3.5, 0.5);
        double d0 = dist(s, f);
        for (int i = 0; i < 30; i++) { night(w); s.tick(w, f, new Random(1)); }
        check(dist(s, f) > d0 + 1, "ближче 6 блоків - відходить: " + d0 + " -> " + dist(s, f));
        double fx = f.pos.x - s.pos.relX(0), fz = f.pos.z - s.pos.relZ(0);
        double faceYaw = Math.toDegrees(Math.atan2(fx, -fz)), dy = Math.abs(((s.yaw - faceYaw) % 360 + 540) % 360 - 180);
        check(dy < 20, "відходячи, дивиться на гравця: " + dy);
    }

    static void ownArrow() throws Exception {
        World w = open();
        night(w);
        Mob s = skeleton(w, 0.5, 0.5);
        Mob z = w.spawnMob(g.mobTypes.get("voxelin:zombie"), new Pos(0, 0, 4.5, s.pos.y, 0.5));
        w.arrows.shootFrom(s, s.eye(), 1, 0, 0, 1.6); // зсередини себе - у бік зомбі
        for (int i = 0; i < 20; i++) { night(w); w.tick(); }
        check(s.health == 20, "своя стріла не б'є стрільця");
        check(z.health < 20, "стріла скелета б'є інших істот: " + z.health);
    }

    static void creative() throws Exception {
        World w = open();
        night(w);
        Fake f = player(w, 0.5, 0.5); f.creative = true;
        Mob s = skeleton(w, 10.5, 0.5);
        w.arrows.shootFrom(s, s.eye(), -1, 0, 0, 1.6);
        for (int i = 0; i < 20 * 4; i++) { night(w); w.tick(); }
        check(f.hits == 0, "у творчому стріли не б'ють");
    }

    static void sun() throws Exception {
        World w = open();
        w.setTime((long) (w.dayTicks() * 0.1));
        Mob s = skeleton(w, 0.5, 0.5);
        for (int i = 0; i < 100; i++) s.tick(w, null, new Random(2));
        check(s.burning && s.health <= 16, "на сонці горить: " + s.health);
    }

    static void boneMeal() throws Exception {
        World w = open();
        Random r = new Random(5);
        int y = surface(w, 20, 20);
        w.setBlock(P, 20, y, 20, g.blocks.byName("voxelin:dirt"));
        check(Growth.apply(w, 20, y, 20, r) && g.blocks.get(w.blockId(20, y, 20)).name.equals("voxelin:grass"), "борошно: земля -> дерен");
        w.setBlock(P, 22, y, 20, g.blocks.byName("voxelin:dirt"));
        w.setBlock(P, 22, y + 1, 20, g.blocks.byName("voxelin:stone"));
        check(!Growth.apply(w, 22, y, 20, r), "під блоком земля не заростає");
        check(!Growth.apply(w, 22, y + 1, 20, r), "на камені нічого");
        int tries = 0;
        while (w.blockId(20, y + 1, 20) == 0 && tries < 30) { check(Growth.apply(w, 20, y, 20, r), "на дерені борошно витрачається"); tries++; }
        check(g.blocks.get(w.blockId(20, y + 1, 20)).name.equals("voxelin:oak_log"), "з дерену виростає дуб за " + tries + " спроб");
        check(g.blocks.get(w.blockId(21, y + 4, 20)).name.equals("voxelin:oak_leaves") || g.blocks.get(w.blockId(21, y + 5, 20)).name.equals("voxelin:oak_leaves"), "у дуба є крона");
        w.setBlock(P, 30, y + 3, 30, g.blocks.byName("voxelin:stone"));
        check(!Growth.tree(w, 30, y + 1, 30, 5) && w.blockId(30, y + 1, 30) == 0, "немає місця - дерево не росте");
    }
}
