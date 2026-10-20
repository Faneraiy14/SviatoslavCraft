import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Скриня й здобич: збереження, меню, таблиці здобичі, світ. */
public class VoxelinChestTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final String STONE = "voxelin:stone", DIRT = "voxelin:dirt";

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        entity(); menu(); loot(); registry(); world();
        System.out.println("VoxelinChestTest: " + n + " перевірок OK");
    }

    static void entity() {
        ChestEntity c = new ChestEntity(1, 2, 3);
        check(c.isEmpty() && c.contents().isEmpty() && c.kind().equals("chest"), "порожня скриня");
        c.set(0, new Inventory.Stack(STONE, 5)); c.set(26, new Inventory.Stack("voxelin:iron_pickaxe", 1, 7)); c.set(99, new Inventory.Stack(STONE, 1)); c.set(-1, new Inventory.Stack(STONE, 1)); c.set(3, new Inventory.Stack(STONE, 0));
        check(c.get(0).count == 5 && c.get(26).damage == 7 && c.get(3) == null && c.get(99) == null && c.contents().size() == 2, "set/get і межі слотів");
        ChestEntity r = new ChestEntity(1, 2, 3);
        r.decode(c.encode());
        check(r.get(0).item.equals(STONE) && r.get(0).count == 5 && r.get(26).damage == 7 && r.get(1) == null, "encode/decode");
        for (String bad : new String[]{"", "garbage", "-;-;-", String.join(";", Collections.nCopies(26, "-")), String.join(";", Collections.nCopies(28, "-")), "voxelin:stone|0|0" + ";-".repeat(26), "voxelin:stone|65|0" + ";-".repeat(26), "Bad|1|0" + ";-".repeat(26), "voxelin:stone|1|-1" + ";-".repeat(26), "voxelin:stone|x|0" + ";-".repeat(26)}) {
            boolean threw = false;
            ChestEntity t = new ChestEntity(0, 0, 0);
            t.set(5, new Inventory.Stack(DIRT, 3));
            try { t.decode(bad); } catch (IllegalArgumentException e) { threw = true; }
            check(threw && t.get(5) != null && t.get(5).count == 3, "биті дані відхиляються й не псують наявне: «" + bad.substring(0, Math.min(20, bad.length())) + "»");
        }
    }

    static void menu() {
        Inventory inv = new Inventory(g.items);
        ChestEntity c = new ChestEntity(0, 0, 0);
        ChestMenu m = new ChestMenu(g.items, inv, c);
        check(m.size() == 36 + 27, "слотів " + m.size());
        inv.set(0, new Inventory.Stack(STONE, 20));
        m.click(0, 0, true);
        check(inv.get(0) == null && c.get(0).count == 20, "shift: з інвентарю у скриню");
        m.click(ChestMenu.CHEST, 0, true);
        check(c.get(0) == null && inv.get(0) != null && total(inv, STONE) == 20, "shift: зі скрині в інвентар (спершу хотбар)");
        inv.set(5, new Inventory.Stack(DIRT, 64));
        m.click(5, 0, false); m.click(ChestMenu.CHEST + 4, 0, false);
        check(c.get(4).count == 64 && m.cursor() == null, "курсором у конкретний слот скрині");
        m.click(ChestMenu.CHEST + 4, 1, false);
        check(m.cursor().count == 32 && c.get(4).count == 32, "права - половина");
        check(m.drainExtra().isEmpty(), "закриття лишає вміст у скрині");
        // повна скриня: shift не вміщається - лишається в інвентарі
        ChestEntity full = new ChestEntity(0, 0, 0);
        for (int i = 0; i < 27; i++) full.set(i, new Inventory.Stack(DIRT, 64));
        Inventory i2 = new Inventory(g.items); ChestMenu m2 = new ChestMenu(g.items, i2, full);
        i2.set(0, new Inventory.Stack(STONE, 5));
        m2.click(0, 0, true);
        check(i2.get(0) != null && i2.get(0).count == 5, "повна скриня не приймає");
        m2.click(ChestMenu.CHEST + 3, 0, true);
        check(full.get(3) != null || total(i2, DIRT) == 64, "зі скрині в інвентар, коли є місце");
    }

    static int total(Inventory inv, String item) { int t = 0; for (int i = 0; i < 36; i++) if (inv.get(i) != null && inv.get(i).item.equals(item)) t += inv.get(i).count; return t; }

    static void loot() {
        LootTable t = g.loot.get("dungeon");
        check(t != null && g.loot.get("voxelin:dungeon") == t && g.loot.get("nope") == null && g.loot.get(null) == null, "таблиці за назвою");
        check(g.loot.get("ruins") != null && g.loot.get("mine") != null, "стартові таблиці");
        List<Inventory.Stack> a = t.roll(new Random(1), g.items), b = t.roll(new Random(1), g.items);
        check(a.size() == b.size() && a.size() >= 2 && a.size() <= 4, "детерміновано, 2..4 кидки: " + a.size());
        for (int i = 0; i < a.size(); i++) check(a.get(i).item.equals(b.get(i).item) && a.get(i).count == b.get(i).count, "однакове за тим самим зерном");
        Map<String, Integer> cnt = new HashMap<>();
        Random r = new Random(7);
        int rolls = 0;
        for (int i = 0; i < 5000; i++) for (Inventory.Stack s : t.roll(r, g.items)) { cnt.merge(s.item, 1, Integer::sum); rolls++; check(s.count >= 1 && s.count <= g.items.maxStack(s.item), "кількість у межах стопки"); }
        int iron = cnt.getOrDefault("voxelin:raw_iron", 0), diamond = cnt.getOrDefault("voxelin:diamond", 0);
        check(iron > diamond * 4 && diamond > 0 && diamond < rolls * 0.08, "ваги: залізо часте (" + iron + "), алмаз рідкий (" + diamond + ")");
        ChestEntity c = new ChestEntity(0, 0, 0);
        for (int i = 0; i < 100; i++) { ChestEntity cc = new ChestEntity(0, 0, 0); t.fill(cc, new Random(i), g.items); check(!cc.isEmpty() && cc.contents().size() <= 4, "fill кладе 1..4 стеків"); }
        t.fill(c, new Random(3), g.items);
        ChestEntity c2 = new ChestEntity(0, 0, 0); t.fill(c2, new Random(3), g.items);
        check(c.encode().equals(c2.encode()), "fill детерміновано за зерном");
    }

    static void registry() {
        boolean threw = false;
        try { new LootRegistry().add("Bad", new LootSettings().entry("a", 1, 1, 1)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "погана назва таблиці");
        threw = false; try { new LootRegistry().add("x:a", new LootSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "порожня таблиця відхиляється");
        LootRegistry reg = new LootRegistry();
        reg.add("x:a", new LootSettings().entry("x:item", 1, 2, 3).entry("x:ghost", 1, 1, 100));
        reg.add("x:allghost", new LootSettings().entry("x:ghost", 1, 1, 1));
        threw = false; try { reg.add("x:a", new LootSettings().entry("x:item", 1, 1, 1)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "повтор таблиці");
        ItemRegistry items = new ItemRegistry();
        items.register("x:item", new ItemSettings());
        reg.freeze(items);
        check(reg.get("x:a") != null && reg.get("x:a").entries().size() == 1 && reg.get("x:allghost") == null, "невідомі предмети відкинуто, таблиця лише з привидів зникла");
        threw = false; try { reg.add("x:late", new LootSettings().entry("x:item", 1, 1, 1)); } catch (IllegalStateException e) { threw = true; }
        check(threw, "після закриття реєструвати не можна");
        check(new LootSettings.Entry("a", -5, -9, -1).min == 1 && new LootSettings.Entry("a", 5, 2, 0).max == 5 && new LootSettings.Entry("a", 1, 1, 0).weight == 1, "межі запису");
        LootSettings ls = new LootSettings().rolls(-3, 999);
        check(ls.minRollsValue() == 1 && ls.maxRollsValue() == 27, "межі кидків");
    }

    static void world() throws Exception {
        File root = Files.createTempDirectory("vx-chest").toFile();
        Game g2 = new Game(new File(root, "saves"), null);
        g2.createWorld("C", 3, WorldInfo.Type.NORMAL);
        World w = g2.openWorld("C");
        for (int i = 0; i < 400; i++) { w.update(0, 0, 1); boolean all = true; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        Source p = Sources.player("P");
        check(w.chestAt(2, 200, 2) == null, "не скриня - null");
        w.setBlock(p, 2, 200, 2, g2.blocks.byName("voxelin:chest"));
        ChestEntity c = w.chestAt(2, 200, 2);
        check(c != null && w.chestAt(2, 200, 2) == c, "скриня створюється один раз");
        c.set(0, new Inventory.Stack(STONE, 9)); c.set(10, new Inventory.Stack("voxelin:diamond", 2));
        w.close();
        World w2 = new Game(new File(root, "saves"), null).openWorld("C");
        for (int i = 0; i < 400; i++) { w2.update(0, 0, 1); if (w2.chunk(0, 0) != null && w2.chunk(-1, -1) != null && w2.chunk(1, 1) != null) break; Thread.sleep(10); }
        ChestEntity c2 = (ChestEntity) w2.blockEntityAt(2, 200, 2);
        check(c2 != null && c2.get(0).count == 9 && c2.get(10).item.endsWith("diamond"), "скриня відновлена після перезапуску");
        check(w2.harvest(Sources.player("P"), 2, 200, 2, g2.items.get("voxelin:wooden_axe"), true), "скриня зламана");
        int dropped = 0; for (var e : w2.itemEntities()) dropped += e.count;
        check(w2.blockEntityAt(2, 200, 2) == null && dropped >= 9 + 2 + 1, "вміст і сама скриня випали: " + dropped);
        w2.close();
        // зламаний файл: скриня й піч поряд
        Files.write(new File(root, "saves/C/blockentities.dat").toPath(), ("garbage\n0 10 0 chest -;-\n0 10 0 chest " + String.join(";", Collections.nCopies(27, "-")) + "\n1 11 1 furnace -,-,-,0,0,0\n").getBytes());
        World w3 = new Game(new File(root, "saves"), null).openWorld("C");
        check(w3.blockEntities().size() == 2, "битий файл: лишаються хороші записи (скриня й піч): " + w3.blockEntities().size());
        w3.close();
        // рецепт скрині
        Inventory.Stack[] grid = new Inventory.Stack[9];
        for (int i = 0; i < 9; i++) grid[i] = i == 4 ? null : new Inventory.Stack("voxelin:oak_planks", 1);
        Inventory.Stack r = Crafting.match(g2.recipes, g2.items, grid, 3);
        check(r != null && r.item.equals("voxelin:chest"), "рецепт скрині: кільце з 8 дощок");
        grid[4] = new Inventory.Stack("voxelin:oak_planks", 1);
        check(Crafting.match(g2.recipes, g2.items, grid, 3) == null, "9 дощок - не скриня");
    }
}
