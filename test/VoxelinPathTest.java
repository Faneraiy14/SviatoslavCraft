import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;

/** Пошук шляху: обхід стіни, сходи, яма, замкнена кімната, урвище при блуканні, ліміт пошуків за тік, швидкість. */
public class VoxelinPathTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");
    static BlockType stone;

    static final class Fake implements PlayerTarget {
        Pos pos; int hits;
        Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return false; }
        public void hurt(double d, double kx, double kz) { hits++; }
    }

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-path").toFile(), "saves"), null);
        stone = g.blocks.byName("voxelin:stone");
        unit(); wall(); asyncWall(); stairs(); pit(); sealed(); cliff(); budget(); speed();
        System.out.println("VoxelinPathTest: " + n + " перевірок OK (" + info + ")");
    }

    static String info = "";
    static int seq, S;
    static World open() throws Exception {
        String name = "p" + (seq++);
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        for (int y = 200; y > 0; y--) if (w.blockId(0, y, 0) > 0) { S = y; break; } // поверхня
        return w;
    }
    static void night(World w) { w.setTime((long) (w.dayTicks() * 0.78)); }
    static void fill(World w, long x0, int y0, long z0, long x1, int y1, long z1, BlockType b) {
        w.batch(() -> { for (long x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (long z = z0; z <= z1; z++) w.setBlock(P, x, y, z, b == null ? g.blocks.byName("voxelin:air") : b); });
    }
    static Fake player(World w, double x, int feet, double z) { Fake f = new Fake(new Pos(0, 0, x, feet + 0.9, z)); w.setPlayerTarget(f); return f; }
    static Mob mob(World w, String k, double x, int feet, double z) { return w.spawnMob(g.mobTypes.get("voxelin:" + k), new Pos(0, 0, x, feet, z)); }
    static double hd(Mob m, Fake f) { return Math.hypot(m.pos.relX(0) - f.pos.x, m.pos.relZ(0) - f.pos.z); }
    /** Тікати, поки зомбі не вдарить (або ticks). */
    static int chase(World w, Mob m, Fake f, int ticks) { for (int i = 0; i < ticks; i++) { night(w); w.tick(); if (f.hits > 0) return i; } return -1; }

    static void unit() throws Exception {
        World w = open();
        long[] p = Pathfinder.find(w, 0, S + 1, 0, 6, S + 1, 0, 2, 600);
        check(p != null && p.length == 18 && p[15] == 6 && p[16] == S + 1, "рівний шлях - 6 клітинок по прямій");
        fill(w, 3, S + 1, -10, 3, S + 3, 10, stone);
        fill(w, 3, S + 1, 4, 3, S + 2, 4, null); // прохід
        long[] r = Pathfinder.find(w, 0, S + 1, 0, 6, S + 1, 0, 2, 600);
        check(r != null && r[r.length - 3] == 6 && r[r.length - 1] == 0, "з проходом - шлях до цілі");
        boolean through = false; for (int i = 0; i < r.length; i += 3) { check(!w.isSolid(r[i], (int) r[i + 1], r[i + 2]), "шлях не крізь блоки"); if (r[i] == 3) through = r[i + 2] == 4; }
        check(through, "шлях іде через прохід (3,4)");
        // діагональ не зрізає кут
        fill(w, 3, S + 1, -10, 3, S + 3, 10, null);
        fill(w, 1, S + 1, 0, 1, S + 2, 0, stone); fill(w, 0, S + 1, 1, 0, S + 2, 1, stone);
        long[] d = Pathfinder.find(w, 0, S + 1, 0, 1, S + 1, 1, 2, 600);
        check(d == null || !(d[0] == 1 && d[2] == 1), "діагональ між двома блоками не проходить");
        w.close();
    }

    /** Те саме, але пошук у фоновому потоці (як у грі й на сервері): зомбі все одно обходить стіну, тік його не чекає. */
    static void asyncWall() throws Exception {
        World w = open();
        w.asyncPaths = true;
        fill(w, -8, S + 1, 4, 8, S + 3, 4, stone);
        Fake f = player(w, 0.5, S + 1, 9.5);
        Mob z = mob(w, "zombie", 0.5, S + 1, -1.5);
        long[] bg = w.findPath(0, S + 1, -2, 0, S + 1, 9, 2, 600).get();
        check(bg != null && bg[bg.length - 3] == 0 && bg[bg.length - 1] == 9, "фоновий findPath знаходить обхід стіни");
        int t = -1;
        for (int i = 0; i < 20 * 25 && t < 0; i++) { night(w); w.tick(); Thread.sleep(1); if (f.hits > 0) t = i; } // фон встигає між тіками, як у грі (50 мс на тік)
        check(t >= 0, "фоновий пошук: зомбі обходить стіну й дістає гравця: " + hd(z, f));
        info += ", у фоні " + t / 20.0 + " с";
        w.close();
        check(w.findPath(0, S + 1, 0, 3, S + 1, 0, 2, 600).get() == null || true, "після закриття світу findPath не падає");
    }

    static void wall() throws Exception {
        World w = open();
        fill(w, -8, S + 1, 4, 8, S + 3, 4, stone); // стіна 17 х 3 між зомбі й гравцем
        Fake f = player(w, 0.5, S + 1, 9.5);
        Mob z = mob(w, "zombie", 0.5, S + 1, -1.5);
        int t = chase(w, z, f, 20 * 25);
        check(t >= 0, "зомбі обходить стіну й дістає гравця: " + hd(z, f));
        info += "стіна " + t / 20.0 + " с";
        w.close();
    }

    static void stairs() throws Exception {
        World w = open();
        fill(w, 8, S + 1, -3, 13, S + 4, 3, stone); // помост висотою 4
        for (int i = 0; i < 3; i++) fill(w, 10, S + 1, 4 + i, 11, S + 3 - i, 4 + i, stone); // сходи з боку +z
        Fake f = player(w, 10.5, S + 5, 0.5);
        Mob z = mob(w, "zombie", 0.5, S + 1, 0.5);
        int t = chase(w, z, f, 20 * 25);
        check(t >= 0 && z.pos.y > S + 4.5, "зомбі піднімається сходами на помост: y=" + z.pos.y + " (" + (S + 5) + ")");
        info += ", сходи " + t / 20.0 + " с";
        w.close();
    }

    static void pit() throws Exception {
        World w = open();
        fill(w, 4, S - 4, -5, 6, S, 5, null); // яма 3 х 11, глибина 5
        Fake f = player(w, 10.5, S + 1, 0.5);
        Mob z = mob(w, "zombie", 0.5, S + 1, 0.5);
        double minY = 1e9;
        int t = -1;
        for (int i = 0; i < 20 * 25; i++) { night(w); w.tick(); minY = Math.min(minY, z.pos.y); if (f.hits > 0) { t = i; break; } }
        check(minY > S + 0.5, "зомбі не стрибає в яму глибиною 5: найнижче y=" + minY);
        check(t >= 0, "і обходить її до гравця");
        w.close();
    }

    static void sealed() throws Exception {
        World w = open();
        fill(w, -2, S + 1, -2, 2, S + 4, 2, stone);
        fill(w, -1, S + 1, -1, 1, S + 2, 1, null); // кімната 3 х 3 х 2, усе замкнено
        Fake f = player(w, 0.5, S + 1, 8.5);
        Mob z = mob(w, "zombie", 0.5, S + 1, 0.5);
        for (int i = 0; i < 100; i++) { night(w); w.tick(); }
        double x0 = z.pos.relX(0), z0 = z.pos.relZ(0), w0 = z.walk;
        int jumps = 0;
        for (int i = 0; i < 100; i++) { night(w); w.tick(); if (z.vy > 1) jumps++; }
        check(z.walk - w0 < 0.5 && Math.hypot(z.pos.relX(0) - x0, z.pos.relZ(0) - z0) < 0.3, "у замкненій кімнаті стоїть: пройшов " + (z.walk - w0));
        check(jumps == 0, "і не стрибає об стіну: " + jumps);
        check(f.hits == 0, "крізь стіну не б'є");
        w.close();
    }

    static void cliff() throws Exception {
        World w = open();
        fill(w, -6, S - 4, -6, 6, S, 6, null);
        fill(w, -1, S - 4, -1, 1, S, 1, stone); // стовп 3 х 3 посеред прірви глибиною 5
        Mob c = mob(w, "cow", 0.5, S + 1, 0.5);
        Mob v = mob(w, "villager_farmer", -0.5, S + 1, -0.5);
        w.setTime(w.dayTicks() / 4);
        for (int i = 0; i < 20 * 60; i++) w.tick();
        check(c.pos.y > S + 0.5, "корова блукає 60 с і не падає з урвища: y=" + c.pos.y);
        check(v.pos.y > S + 0.5, "житель теж: y=" + v.pos.y);
        check(c.walk > 2, "але таки ходить: " + c.walk);
        w.close();
    }

    static void budget() throws Exception {
        World w = open();
        fill(w, -12, S + 1, 6, 12, S + 3, 6, stone);
        Fake f = player(w, 0.5, S + 1, 10.5);
        Mob[] zs = new Mob[40];
        for (int i = 0; i < zs.length; i++) zs[i] = mob(w, "zombie", -9.5 + (i % 20), S + 1, -1.5 - i / 20);
        int maxPer = 0;
        for (int i = 0; i < 60; i++) { long b = Pathfinder.searches; night(w); w.tick(); maxPer = Math.max(maxPer, (int) (Pathfinder.searches - b)); }
        check(maxPer <= Pathfinder.BUDGET_PER_TICK, "не більше " + Pathfinder.BUDGET_PER_TICK + " пошуків за тік: " + maxPer);
        int with = 0; for (Mob z : zs) if (z.path() != null) with++;
        check(with >= 36, "за 3 с шлях отримали майже всі 40 зомбі: " + with);
        w.close();
    }

    static void speed() throws Exception {
        World w = open();
        fill(w, -12, S + 1, 6, 12, S + 3, 6, stone);
        fill(w, -12, S + 1, 6, -12, S + 2, 6, null);
        Fake f = player(w, 0.5, S + 1, 9.5);
        for (int i = 0; i < 15; i++) mob(w, "zombie", -7.5 + i, S + 1, -1.5);
        for (int i = 0; i < 100; i++) { night(w); w.tick(); } // розігрів JIT
        long t0 = System.nanoTime(), s0 = Pathfinder.searches;
        int T = 400;
        for (int i = 0; i < T; i++) { night(w); f.pos.x = 0.5 + 8 * Math.sin(i * 0.05); w.tick(); } // гравець ходить уздовж стіни - шляхи перераховуються
        double ms = (System.nanoTime() - t0) / 1e6 / T;
        long found = Pathfinder.searches - s0;
        check(found >= 60, "зомбі справді шукають шлях: " + found + " пошуків");
        info += ", пошуків " + found;
        check(ms <= 2.0, "15 зомбі з пошуком шляху: тік світу " + String.format("%.3f", ms) + " мс (<= 2)");
        info += ", тік з 15 зомбі " + String.format("%.3f", ms) + " мс";
        w.close();
    }
}
