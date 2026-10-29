import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Міф (той, кого гра не називає): лише сліди поза очима, без істот і записів; «допомога» - справжня, трохи не та й раз за світ. */
public class VoxelinMythTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g; static File saves;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        saves = new File(Files.createTempDirectory("vx-myth").toFile(), "saves"); g = new Game(saves, null);
        traces(); helps(); chest(); quiet();
        System.out.println("VoxelinMythTest: " + n + " перевірок OK");
    }

    static World open(String name, WorldInfo.Type t) throws Exception {
        g.createWorld(name, 21, t);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 4); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }

    static int count(World w, String block, long cx, long cz, int r) {
        int id = w.blocks.byName("voxelin:" + block).id, c = 0;
        for (long x = cx - r; x <= cx + r; x++) for (long z = cz - r; z <= cz + r; z++) for (int y = 1; y < 140; y++) if (w.blockId(x, y, z) == id) c++;
        return c;
    }

    static void traces() throws Exception {
        World w = open("t", WorldInfo.Type.FLAT);
        Pos me = Pos.ofBlock(0, surface(w, 0, 0) + 1, 0);
        int mobs = w.mobs().size(), recs = w.info.records.size();
        int sand0 = count(w, "sand", 0, 0, 46);
        check(Herobrine.trace(w, Herobrine.Trace.PYRAMID, me, new Random(1)), "піраміда");
        check(count(w, "sand", 0, 0, 46) - sand0 == 25 + 9 + 1, "піраміда 5x5-3x3-1");
        boolean far = true;
        for (long x = -27; x <= 27; x++) for (long z = -27; z <= 27; z++) if (Math.hypot(x, z) < 27 && w.blockId(x, surface(w, 0, 0) + 3, z) == w.blocks.byName("voxelin:sand").id) far = false;
        check(far, "слід - не ближче 28 блоків");
        int leaves0 = count(w, "oak_leaves", 0, 0, 50) + count(w, "birch_leaves", 0, 0, 50);
        boolean lf = Herobrine.trace(w, Herobrine.Trace.LEAFLESS, me, new Random(2));
        check(!lf || count(w, "oak_leaves", 0, 0, 50) + count(w, "birch_leaves", 0, 0, 50) < leaves0, "дерево без листя");
        check(w.mobs().size() == mobs && w.info.records.size() == recs, "жодних істот і записів");
        w.close();
        World s = open("t2", WorldInfo.Type.NORMAL);
        Pos me2 = Pos.ofBlock(0, surface(s, 0, 0) + 1, 0);
        int air0 = 0;
        boolean tun = false;
        for (int k = 0; k < 6 && !tun; k++) tun = Herobrine.trace(s, Herobrine.Trace.TUNNEL, me2, new Random(10 + k));
        check(tun, "тунель у камені");
        int lamps0 = count(s, "lamp", 0, 0, 46);
        boolean lp = false;
        for (int k = 0; k < 6 && !lp; k++) lp = Herobrine.trace(s, Herobrine.Trace.LAMP, me2, new Random(30 + k));
        check(!lp || count(s, "lamp", 0, 0, 46) == lamps0 + 1, "лампа в темряві");
        s.close();
    }

    static void helps() throws Exception {
        World w = open("h", WorldInfo.Type.FLAT);
        int y = surface(w, 0, 0) + 1;
        Pos me = Pos.ofBlock(0, y, 0);
        w.setBlock(P, 2, y, 2, w.blocks.byName("voxelin:torch"));
        Mob z = w.spawnMob(w.mobTypes.find("zombie"), Pos.ofBlock(4, y, 0));
        Herobrine.scare(w, me);
        check(z.scared > 0 && w.blockId(2, y, 2) == 0, "вороги тікають, але смолоскип погас");
        check(w.info.hbUsed.contains(Herobrine.HELP_SCARE), "запам'ятано - раз за світ");
        double x0 = z.pos.relX(0);
        for (int i = 0; i < 40; i++) w.tick();
        check(z.pos.relX(0) > x0 + 1, "зомбі біжить геть: " + x0 + " -> " + z.pos.relX(0));
        w.close();
        World s = open("h2", WorldInfo.Type.NORMAL);
        // печера: лампи до точки появи світу (0,0), а не до гравця/ліжка
        long px = 40; int py = 20; long pz = 0;
        for (long x = px - 30; x <= px + 2; x++) for (int yy = py; yy <= py + 2; yy++) s.setBlock(P, x, yy, pz, s.blocks.air());
        s.setBlock(P, px - 30, py + 3, pz, s.blocks.byName("voxelin:stone"));
        for (long x = px - 30; x <= px + 2; x++) s.setBlock(P, x, py + 3, pz, s.blocks.byName("voxelin:stone"));
        int put = Herobrine.lamps(s, Pos.ofBlock(px, py + 0.5, pz));
        check(put >= 3, "лампи під стелею: " + put);
        boolean towardSpawn = true;
        for (long x = px + 1; x <= px + 10; x++) if (s.blockId(x, py + 3, pz) == s.blocks.byName("voxelin:lamp").id) towardSpawn = false;
        check(towardSpawn && s.blockId(px - 3, py + 3, pz) == s.blocks.byName("voxelin:lamp").id, "лампи ведуть до точки появи");
        s.close();
    }

    static void chest() throws Exception {
        World w = open("c", WorldInfo.Type.FLAT);
        int y = surface(w, 0, 0) + 1;
        List<Inventory.Stack> drops = new ArrayList<>(List.of(new Inventory.Stack("voxelin:diamond", 3), new Inventory.Stack("voxelin:stick", 10), new Inventory.Stack("voxelin:apple", 2)));
        boolean done = false; int tries = 0;
        while (!done && tries++ < 40) done = Herobrine.deathChest(w, Pos.ofBlock(5, y + 0.2, 5), drops, new Random(tries));
        check(done, "скриня на місці смерті");
        ChestEntity ce = w.chestAt(5, y, 5);
        int stacks = 0;
        for (int k = 0; k < ChestEntity.SIZE; k++) if (ce.get(k) != null) stacks++;
        check(stacks == 2, "однієї стопки бракує: " + stacks);
        check(!Herobrine.deathChest(w, Pos.ofBlock(9, y + 0.2, 9), drops, new Random(1)) && !Herobrine.deathChest(w, Pos.ofBlock(9, y + 0.2, 9), drops, new Random(2)) && !Herobrine.deathChest(w, Pos.ofBlock(9, y + 0.2, 9), drops, new Random(3)), "удруге - ніколи");
        w.close();
        WorldInfo li = WorldInfo.load(new File(saves, "c"));
        check(li.hbUsed.contains(Herobrine.HELP_CHEST), "пам'ять міфу зберігається");
    }

    static void quiet() throws Exception {
        World w = open("q", WorldInfo.Type.FLAT);
        int mobs = w.mobs().size();
        w.setPlayerTarget(new PlayerTarget() {
            public Pos body() { return Pos.ofBlock(0, 70, 0); }
            public boolean alive() { return true; }
            public boolean creative() { return false; }
            public void hurt(double d, double kx, double kz) {}
        });
        for (int i = 0; i < 4000; i++) w.tick();
        check(w.mobs().size() == mobs && !w.info.records.stream().anyMatch(r -> r.contains("hero") || r.contains("myth")), "жодного запису в журналі про нього");
        for (RecordRegistry.Record r : g.records.all()) check(!r.title.toLowerCase().contains("херобр") && !r.text.toLowerCase().contains("херобр"), "гра його не називає: " + r.title);
        w.close();
    }
}
