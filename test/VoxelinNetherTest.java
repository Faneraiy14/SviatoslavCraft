import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/** Незер: генерація (корінна порода, море лави, зали, жили, жаровий лімініт), портал з обсидіану й кресала, прибуття, лава, лава в печерах звичайного світу. */
public class VoxelinNetherTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-nether").toFile(), "saves"), null);
        gen(); portal(); arrive(); lava(); overworld();
        System.out.println("VoxelinNetherTest: " + n + " перевірок OK");
    }

    static void load(World w, long cx, long cz) throws Exception {
        for (int i = 0; i < 900; i++) { w.update(cx, cz, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
    }

    static int id(String n) { return g.blocks.byName("voxelin:" + n).id; }

    static void gen() {
        NetherGenerator gen = new NetherGenerator(42, g.blocks);
        int bed = 0, lava = 0, cinder = 0, air = 0, vein = 0, ore = 0, oreHigh = 0, scar = 0, ash = 0, open = 0;
        for (int cx = -3; cx < 3; cx++) for (int cz = -3; cz < 3; cz++) {
            Chunk c = new Chunk(cx, cz);
            gen.generate(c);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                if (c.get(x, 0, z) == id("bedrock") && c.get(x, NetherGenerator.CEIL, z) == id("bedrock")) bed++;
                for (int y = 1; y < NetherGenerator.CEIL; y++) {
                    int b = c.get(x, y, z);
                    if (b == id("lava")) { lava++; check(y <= NetherGenerator.LAVA, "лава лише до y=31"); }
                    else if (b == id("cinder")) cinder++;
                    else if (b == 0) { air++; check(y > NetherGenerator.LAVA, "нижче рівня лави - не повітря"); }
                    else if (b == id("ember_vein")) vein++;
                    else if (b == id("ember_ore")) { ore++; if (y > NetherGenerator.ORE_MAX_Y) oreHigh++; }
                    else if (b == id("seam_scar")) scar++;
                    else if (b == id("ash")) ash++;
                }
                if (c.get(x, 70, z) == 0) open++;
            }
        }
        int cols = 36 * 256;
        check(bed == cols, "корінна порода знизу й зверху всюди");
        check(lava > cols * 3 && cinder > cols * 30 && air > cols * 15, "лава, камінь і зали: " + lava + " " + cinder + " " + air);
        check(open > cols / 5, "на висоті 70 переважно зали: " + open);
        check(vein > 100 && ore > 3 && oreHigh == 0 && scar > 50 && ash > 100, "жили " + vein + ", лімініт " + ore + " (глибоко), шрами " + scar + ", попіл " + ash);
        Chunk a = new Chunk(5, -7), b = new Chunk(5, -7);
        gen.generate(a); new NetherGenerator(42, g.blocks).generate(b);
        boolean same = true;
        for (int y = 0; y < 128; y += 3) for (int x = 0; x < 16; x++) if (a.get(x, y, 9) != b.get(x, y, 9)) same = false;
        check(same, "детерміновано");
        BlockType ore1 = g.blocks.byName("voxelin:ember_ore");
        check(ore1.minTier == 4 && Mining.drops(ore1, g.items.get("voxelin:diamond_pickaxe"), new Random(1)).get(0).item.equals("voxelin:ember_crystal"), "жаровий лімініт - лише алмазною киркою, дає кристал");
    }

    static void frame(World w, BlockType obs, long x0, int y0, long z, int wdt, int hgt, boolean corners) {
        for (int i = -1; i <= wdt; i++) for (int j = -1; j <= hgt; j++) {
            boolean edge = i == -1 || i == wdt || j == -1 || j == hgt, corner = (i == -1 || i == wdt) && (j == -1 || j == hgt);
            if (edge && (corners || !corner)) w.setBlock(P, x0 + i, y0 + j, z, obs);
        }
    }

    static void portal() throws Exception {
        g.createWorld("p", 3, WorldInfo.Type.FLAT);
        World w = g.openWorld("p");
        w.naturalSpawning = false;
        load(w, 0, 0);
        int y = 1; while (w.blockId(0, y, 0) > 0) y++;
        y += 2;
        BlockType obs = w.blocks.byName(NetherPortal.FRAME);
        frame(w, obs, 0, y, 4, 2, 3, false);
        check(NetherPortal.ignite(w, 1, y + 1, 4) == 6, "рамка 2x3 без кутів розпалюється - 6 блоків порталу");
        check(w.blockId(0, y, 4) == id("nether_portal") && w.blockId(1, y + 2, 4) == id("nether_portal"), "портал усередині");
        w.setBlock(P, -1, y + 1, 4, w.blocks.air());
        check(w.blockId(0, y, 4) == 0 && w.blockId(1, y + 2, 4) == 0, "зламано блок рамки - портал згас");
        frame(w, obs, 10, y, 10, 3, 4, true);
        w.setBlock(P, 10, y - 1, 10, w.blocks.byName("voxelin:stone")); // дірка в низу рамки
        check(NetherPortal.ignite(w, 11, y, 10) == 0, "неповна рамка - ні");
        w.setBlock(P, 10, y - 1, 10, obs);
        check(NetherPortal.ignite(w, 11, y, 10) == 12, "рамка 3x4 з кутами - 12");
        // уздовж Z
        for (int j = -1; j <= 3; j++) for (int i = -1; i <= 2; i++) if (i == -1 || i == 2 || j == -1 || j == 3) w.setBlock(P, 20, y + j, 20 + i, obs);
        check(NetherPortal.ignite(w, 20, y + 2, 21) == 6, "рамка вздовж Z");
        w.setBlock(P, 20, y + 1, 20, w.blocks.byName("voxelin:stone"));
        check(w.blockId(20, y + 1, 21) == 0, "замінили блок порталу - згас увесь");
        check(NetherPortal.ignite(w, 30, y, 30) == 0, "без рамки - ні");
        Inventory.Stack fs = Crafting.match(g.recipes, g.items, new Inventory.Stack[]{new Inventory.Stack("voxelin:iron_ingot", 1), new Inventory.Stack("voxelin:flint", 1), null, null}, 2);
        check(fs != null && fs.item.equals(NetherPortal.LIGHTER), "кресало: залізо + кремінь");
        int flint = 0;
        Random r = new Random(5);
        for (int i = 0; i < 2000; i++) for (Inventory.Stack s : Mining.drops(g.blocks.byName("voxelin:gravel"), null, r)) if (s.item.equals("voxelin:flint")) flint++;
        check(flint > 140 && flint < 270, "кремінь із гравію ~10%: " + flint);
        w.close();
    }

    static void arrive() throws Exception {
        g.createWorld("a", 9, WorldInfo.Type.NORMAL);
        World n0 = g.openDimension("a", Dimensions.NETHER);
        check(n0.info.type == WorldInfo.Type.NETHER && n0.dimension().equals(Dimensions.NETHER) && n0.skyLevel() == 0, "вимір Незер");
        long[] t = NetherPortal.target(800, -1605);
        check(t[0] == 100 && t[1] == -201, "1/8 координат");
        load(n0, Math.floorDiv(t[0], 16L), Math.floorDiv(t[1], 16L));
        Pos p = NetherPortal.arrive(n0, t[0], t[1]);
        long px = p.blockX(), pz = p.blockZ(); int py = (int) Math.floor(p.y - PlayerEntity.EYE + 0.05);
        check(n0.blockId(px, py, pz) == 0 && n0.blockId(px, py + 1, pz) == 0 && n0.blocks.get(n0.blockId(px, py - 1, pz)).solid, "стоїмо на твердому, над головою порожньо");
        int portals = 0;
        for (long x = px - 4; x <= px + 4; x++) for (long z = pz - 4; z <= pz + 4; z++) for (int y = py - 2; y <= py + 4; y++) if (n0.blockId(x, y, z) == id("nether_portal")) portals++;
        check(portals == 6, "поруч портал назад: " + portals);
        Pos p2 = NetherPortal.arrive(n0, t[0] + 3, t[1] - 2);
        int portals2 = 0;
        for (long x = px - 16; x <= px + 16; x++) for (long z = pz - 16; z <= pz + 16; z++) for (int y = 32; y < 120; y++) if (n0.blockId(x, y, z) == id("nether_portal")) portals2++;
        check(portals2 == 6 && Math.abs(p2.blockX() - px) <= 4, "удруге - до того самого порталу, новий не будується");
        check(n0.info.otherDim() && !n0.infection.affects(px, pz), "у Незері зараження не діє");
        n0.close();
    }

    static void lava() throws Exception {
        g.createWorld("l", 4, WorldInfo.Type.FLAT);
        World w = g.openWorld("l");
        w.naturalSpawning = false;
        load(w, 0, 0);
        int y = 1; while (w.blockId(0, y, 0) > 0) y++;
        BlockType lava = w.blocks.byName(World.LAVA);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) w.setBlock(P, x, y - 1, z, lava);
        Mob cow = w.spawnMob(w.mobTypes.find("cow"), Pos.ofBlock(0, y - 1, 0));
        double hp = cow.health;
        for (int i = 0; i < 25; i++) w.tick();
        check(cow.health < hp || cow.dying, "корова в лаві горить");
        w.spawnItem(Pos.ofBlock(1, y - 0.5, 1), "voxelin:stick", 3, 0, false);
        int before = w.itemEntities().size();
        w.tick();
        check(w.itemEntities().size() < before, "предмет у лаві згорів");
        check(lava.liquid && !lava.solid && lava.light == 15, "лава: рідина, світить");
        w.close();
    }

    static void overworld() {
        TerrainGenerator tg = new TerrainGenerator(77, WorldInfo.Type.NORMAL, g.blocks, java.util.List.of());
        int lava = 0, obs = 0, high = 0;
        for (int cx = -6; cx < 6; cx++) for (int cz = -6; cz < 6; cz++) {
            Chunk c = new Chunk(cx, cz);
            tg.generate(c);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 120; y++) {
                int b = c.get(x, y, z);
                if (b == id("lava")) { lava++; if (y > TerrainGenerator.LAVA_Y) high++; }
                if (b == id("obsidian")) obs++;
            }
        }
        check(lava > 100 && high == 0, "лава в глибоких печерах (до y=10): " + lava);
        check(obs > 10, "обсидіан на берегах лави: " + obs);
    }
}
