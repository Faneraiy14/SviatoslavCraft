import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;

/** Лук і стріли: рецепти, натяг, політ із гравітацією, влучання в істоту, застрягання в блоці, підбір, падіння після зламу блока. */
public class VoxelinBowTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-bow").toFile(), "saves"), null);
        recipes(); flight(); hitMob(); stick();
        System.out.println("VoxelinBowTest: " + n + " перевірок OK");
    }

    static World open(String name) throws Exception {
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }

    static Inventory.Stack craft(String... rows) {
        Inventory.Stack[] gr = new Inventory.Stack[9];
        for (int y = 0; y < rows.length; y++) { String[] c = rows[y].split(","); for (int x = 0; x < c.length; x++) if (!c[x].equals("-")) gr[x + y * 3] = new Inventory.Stack("voxelin:" + c[x], 1); }
        return Crafting.match(g.recipes, g.items, gr, 3);
    }

    static void recipes() {
        Inventory.Stack b = craft("-,stick,string", "stick,-,string", "-,stick,string");
        check(b != null && b.item.equals(Arrows.BOW) && g.items.get(Arrows.BOW).durability > 0, "лук: 3 палиці + 3 нитки");
        Inventory.Stack ar = craft("copper_ingot,-,-", "stick,-,-", "feather,-,-");
        check(ar != null && ar.item.equals(Arrows.ITEM) && ar.count == 4, "стріли: мідь + палиця + пір'я = 4");
        check(Arrows.power(0) == 0 && Arrows.power(1) == 1 && Arrows.power(0.5) < 0.5 + 1e-9 && Arrows.power(2) == 1, "натяг: 0..1, з насиченням");
        check(Arrows.damage(Arrows.FULL_SPEED, true) == 8 && Arrows.damage(1, false) == 2, "шкода від швидкості (повний постріл +2)");
        Inventory inv = new Inventory();
        check(!Arrows.hasArrow(inv) && !Arrows.takeArrow(inv), "без стріл - не стріляє");
        inv.add(Arrows.ITEM, 2);
        check(Arrows.takeArrow(inv) && Arrows.takeArrow(inv) && !Arrows.hasArrow(inv), "стріли витрачаються по одній");
    }

    static void flight() throws Exception {
        World w = open("f");
        int y = surface(w, 0, 0) + 2;
        Arrows.Arrow a = w.arrows.shoot(new Pos(0, 0, 8.5, y, 8.5), 0, -30, Arrows.FULL_SPEED, true); // угору й на -Z
        double top = y;
        for (int i = 0; i < 400 && !a.stuck; i++) { w.tick(); top = Math.max(top, a.pos.y); }
        check(a.stuck, "стріла впала й застрягла");
        check(top > y + 5, "летить дугою вгору: " + top);
        check(a.pos.relZ(0) < -20 && w.isSolid(a.pos.blockX(), a.pos.blockY(), a.pos.blockZ()), "застрягла в блоці далеко попереду: z=" + a.pos.relZ(0) + " y=" + a.pos.y);
    }

    static void hitMob() throws Exception {
        World w = open("m");
        int y = surface(w, 0, 0) + 1;
        Mob cow = w.spawnMob(w.mobTypes.find("cow"), Pos.ofBlock(0, y, -10));
        double hp = cow.health;
        w.arrows.shoot(new Pos(0, 0, 0.5, y + 0.8, 0.5), 0, 0, Arrows.FULL_SPEED, true);
        for (int i = 0; i < 20; i++) w.tick();
        check(cow.health <= hp - 6 + 1e-6, "повний постріл - 6..8 шкоди: " + hp + " -> " + cow.health);
        check(w.arrows.all().isEmpty(), "стріла, що влучила, зникла");
    }

    static void stick() throws Exception {
        World w = open("s");
        int y = surface(w, 0, 0) + 1;
        BlockType stone = w.blocks.byName("voxelin:stone");
        for (int dy = 0; dy < 3; dy++) for (int x = -2; x <= 2; x++) w.setBlock(P, x, y + dy, -6, stone);
        Arrows.Arrow a = w.arrows.shoot(new Pos(0, 0, 0.5, y + 1.5, 0.5), 0, 0, 2, true);
        for (int i = 0; i < 20; i++) w.tick();
        check(a.stuck && a.pos.relZ(0) > -5.2 && a.pos.relZ(0) < -4.8, "застрягла в стіні: z=" + a.pos.relZ(0));
        Inventory inv = new Inventory();
        check(w.arrows.collect(new Pos(0, 0, 0.5, y + 1, 0.5), inv) == 0, "далеко - не підібрати");
        check(w.arrows.collect(new Pos(0, 0, 0.5, y + 1, -4.3), inv) == 1 && Arrows.hasArrow(inv), "поруч - підібрали");
        Arrows.Arrow c = w.arrows.shoot(new Pos(0, 0, 0.5, y + 1.5, 0.5), 0, 0, 2, false);
        for (int i = 0; i < 20; i++) w.tick();
        check(c.stuck && w.arrows.collect(new Pos(0, 0, 0.5, y + 1, -4.3), inv) == 0, "з творчого - не підбирається");
        w.setBlock(P, 0, y + 1, -6, w.blocks.air());
        for (int i = 0; i < 40; i++) w.tick();
        check(!c.stuck || c.pos.y < y + 1, "блок зламано - стріла впала: y=" + c.pos.y);
    }
}
