import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.core.gen.NetherGenerator;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/** Незерит (рівень 5): руда лише глибоко в Незері, піч -> уламок -> злиток -> покращення алмазних речей; броня пускає в Енд, зменшує пробиття босів і відкидання; не горить у лаві. */
public class VoxelinNetheriteTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");
    static String v(String s) { return "voxelin:" + s; }

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-neth").toFile(), "saves"), null);
        ore(); chain(); stats(); armor(); lava();
        System.out.println("VoxelinNetheriteTest: " + n + " перевірок OK");
    }

    static Inventory.Stack craft(String... items) {
        Inventory.Stack[] gr = new Inventory.Stack[9];
        for (int i = 0; i < items.length; i++) gr[i] = new Inventory.Stack(v(items[i]), 1);
        return Crafting.match(g.recipes, g.items, gr, 3);
    }

    static void ore() {
        NetherGenerator gen = new NetherGenerator(42, g.blocks);
        int id = g.blocks.byName(v("netherite_ore")).id, ember = g.blocks.byName(v("ember_ore")).id;
        int neth = 0, high = 0, emb = 0, chunks = 0;
        for (int cx = -6; cx < 6; cx++) for (int cz = -6; cz < 6; cz++) {
            Chunk c = new Chunk(cx, cz); gen.generate(c); chunks++;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y <= NetherGenerator.CEIL; y++) {
                int b = c.get(x, y, z);
                if (b == id) { neth++; if (y > NetherGenerator.NETHERITE_MAX_Y) high++; }
                else if (b == ember) emb++;
            }
        }
        check(high == 0, "незерит лише нижче y=20: " + high);
        check(neth > 0 && neth < emb, "незерит є, але рідший за жаровий лімініт: " + neth + " проти " + emb + " на " + chunks + " чанків");
        check(neth >= chunks / 8 && neth <= chunks * 6, "частота в розумних межах: " + neth + " на " + chunks);
        BlockType b = g.blocks.byName(v("netherite_ore"));
        check(!Mining.canHarvest(b, g.items.get(v("iron_pickaxe"))) && Mining.canHarvest(b, g.items.get(v("diamond_pickaxe"))) && Mining.canHarvest(b, g.items.get(v("netherite_pickaxe"))), "руду добуває лише алмазна чи незеритова кирка");
        boolean self = false;
        for (Inventory.Stack s : Mining.drops(b, g.items.get(v("diamond_pickaxe")), new Random(1))) if (s.item.equals(v("netherite_ore"))) self = true;
        check(self, "руда падає сама (плавиться в печі)");
    }

    static void chain() {
        check(g.recipes.smeltFor(v("netherite_ore")) != null && g.recipes.smeltFor(v("netherite_ore")).result.equals(v("netherite_scrap")), "піч: руда -> уламок");
        Inventory.Stack ing = craft("netherite_scrap", "netherite_scrap", "netherite_scrap", "netherite_scrap", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot");
        check(ing != null && ing.item.equals(v("netherite_ingot")) && ing.count == 1, "4 уламки + 4 золота = злиток");
        check(craft("netherite_scrap", "netherite_scrap", "netherite_scrap", "gold_ingot", "gold_ingot", "gold_ingot", "gold_ingot") == null, "3 уламки - не злиток");
        for (String k : new String[]{"helmet", "chestplate", "leggings", "boots", "pickaxe", "axe", "sword", "shovel"}) {
            Inventory.Stack up = craft("diamond_" + k, "netherite_ingot");
            check(up != null && up.item.equals(v("netherite_" + k)), "покращення: алмазна " + k + " + злиток");
            check(craft("iron_" + k, "netherite_ingot") == null, "залізна " + k + " не покращується");
        }
    }

    static void stats() {
        for (String k : new String[]{"helmet", "chestplate", "leggings", "boots"}) {
            ItemType d = g.items.get(v("diamond_" + k)), nt = g.items.get(v("netherite_" + k));
            check(nt.armorTier == Inventory.NETHERITE_TIER && nt.armorPoints == d.armorPoints && nt.durability > d.durability && nt.fireproof && !d.fireproof, "незеритова " + k + ": рівень 5, очки як в алмазу, міцніша, не горить");
        }
        for (String k : new String[]{"pickaxe", "axe", "shovel"}) {
            ItemType d = g.items.get(v("diamond_" + k)), nt = g.items.get(v("netherite_" + k));
            check(nt.tier == 5 && nt.tool == d.tool && nt.speed > d.speed && nt.attack > d.attack && nt.durability > d.durability, "незеритова " + k + " краща за алмазну");
        }
        check(g.items.get(v("netherite_sword")).attack > g.items.get(v("diamond_sword")).attack, "незеритовий меч б'є сильніше");
        check(g.items.get(v("netherite_scrap")).fireproof && g.items.get(v("netherite_ingot")).fireproof, "уламок і злиток не горять");
    }

    static void armor() {
        Inventory inv = new Inventory();
        String[] k = {"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < 4; i++) inv.setArmor(i, new Inventory.Stack(v("netherite_" + k[i]), 1));
        check(inv.fullArmor(g.items, 4), "повний незерит пускає в Енд (рівень >= 4)");
        check(inv.armorPieces(g.items, Inventory.NETHERITE_TIER) == 4 && inv.armorPoints(g.items) == 20, "4 незеритові частини, 20 очок");
        inv.setArmor(0, new Inventory.Stack(v("diamond_helmet"), 1));
        check(inv.fullArmor(g.items, 4) && inv.armorPieces(g.items, 5) == 3, "змішаний алмаз+незерит теж пускає в Енд");
        double dia = Inventory.reduce(10, 20, 0.5), net = Inventory.reduce(10, 20, Inventory.netheritePierce(0.5, 4));
        check(net < dia && Math.abs(Inventory.netheritePierce(0.5, 4) - 0.1) < 1e-9, "повний незерит: пробиття боса x0.2 (" + dia + " -> " + net + ")");
        check(Inventory.netheritePierce(0.5, 0) == 0.5 && Inventory.netheritePierce(0.5, 9) == Inventory.netheritePierce(0.5, 4), "без незериту пробиття те саме; більше 4 не рахується");
        check(Math.abs(Inventory.netheriteKnock(4) - 0.6) < 1e-9 && Inventory.netheriteKnock(0) == 1, "відкидання: -10% за частину");
    }

    static void lava() throws Exception {
        g.createWorld("l", 3, WorldInfo.Type.FLAT);
        World w = g.openWorld("l");
        w.naturalSpawning = false;
        for (int i = 0; i < 900 && w.chunk(0, 0) == null; i++) { w.update(0, 0, 2); Thread.sleep(10); }
        int y = 0; for (int yy = 200; yy > 0; yy--) if (w.blockId(4, yy, 4) > 0) { y = yy; break; }
        BlockType lava = g.blocks.byName(World.LAVA);
        for (int x = 3; x <= 9; x++) w.setBlock(P, x, y, 4, lava);
        w.spawnItem(Pos.ofBlock(4, y + 0.2, 4), v("netherite_ingot"), 1, 0, false);
        w.spawnItem(Pos.ofBlock(6, y + 0.2, 4), v("diamond_helmet"), 1, 0, false);
        w.spawnItem(Pos.ofBlock(8, y + 0.2, 4), v("netherite_sword"), 1, 0, false);
        for (int i = 0; i < 60; i++) w.tick();
        boolean ingot = false, helmet = false, sword = false;
        for (ItemEntity e : w.itemEntities()) { if (e.item.equals(v("netherite_ingot"))) ingot = true; if (e.item.equals(v("diamond_helmet"))) helmet = true; if (e.item.equals(v("netherite_sword"))) sword = true; }
        check(ingot && sword, "незерит у лаві лежить цілим");
        check(!helmet, "алмаз у лаві згорає");
    }
}
