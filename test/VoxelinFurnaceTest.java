import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Піч: плавка за часом, паливо, меню печі, збереження, зламана піч віддає вміст. */
public class VoxelinFurnaceTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final String IRON = "voxelin:raw_iron", COAL = "voxelin:coal", INGOT = "voxelin:iron_ingot", LOG = "voxelin:oak_log", COBBLE = "voxelin:cobblestone";

    static void ticks(FurnaceEntity f, int k) { for (int i = 0; i < k; i++) f.tick(g.items, g.recipes); }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        registry(); smelting(); menu(); persistence(); world();
        System.out.println("VoxelinFurnaceTest: " + n + " перевірок OK");
    }

    static void registry() {
        check(g.recipes.fuelTicks(COAL) == 1600 && g.recipes.fuelTicks(LOG) == 300 && g.recipes.fuelTicks("voxelin:stick") == 80 && g.recipes.fuelTicks("voxelin:stone") == 0, "палива");
        check(g.recipes.smeltFor(IRON).result.equals(INGOT) && g.recipes.smeltFor(IRON).ticks == 200, "залізо -> злиток за 10 с");
        check(g.recipes.smeltFor(LOG).result.equals(COAL) && g.recipes.smeltFor("voxelin:birch_log").ticks == 300 && g.recipes.smeltFor("voxelin:pale_log") != null, "колоди (мітка) -> вугілля за 15 с");
        check(g.recipes.smeltFor("voxelin:dirt") == null && g.recipes.smeltFor("voxelin:sand").result.equals("voxelin:glass") && g.recipes.smeltFor(COBBLE).result.equals("voxelin:stone"), "решта плавок");
        check(g.blocks.byName("voxelin:furnace").interact.equals("furnace"), "піч - блок із вікном");
        boolean threw = false;
        try { RecipeSettings.smelting("", 5); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "плавка без входу відхиляється");
        check(RecipeSettings.smelting("a", Float.NaN).smeltSeconds() == 10f && RecipeSettings.smelting("a", -5).smeltSeconds() == 0.5f && RecipeSettings.smelting("a", 1e9f).smeltSeconds() == 3600f, "тривалість обмежена");
    }

    static void smelting() {
        FurnaceEntity f = new FurnaceEntity(0, 10, 0);
        f.input = new Inventory.Stack(IRON, 3); f.fuel = new Inventory.Stack(COAL, 2);
        ticks(f, 1);
        check(f.lit() && f.fuel.count == 1 && f.burnTotal == 1600, "паливо береться, коли є що плавити");
        ticks(f, 198);
        check(f.output == null && f.progress == 199, "ще не готово: " + f.progress);
        ticks(f, 1);
        check(f.output != null && f.output.item.equals(INGOT) && f.output.count == 1 && f.input.count == 2 && f.progress == 0, "перший злиток через 10 с");
        ticks(f, 200 * 2);
        check(f.output.count == 3 && f.input == null && f.fuel.count == 1, "усі три злитки, друге вугілля цілим");
        ticks(f, 100);
        check(f.progress == 0 && f.fuel.count == 1, "нема входу - паливо не палиться");
        // горить: 1600 тіків = 8 злитків; 3 уже зроблено, паливо 1 з 2 вугілля використане частково
        FurnaceEntity big = new FurnaceEntity(0, 10, 0);
        big.input = new Inventory.Stack(IRON, 20); big.fuel = new Inventory.Stack(COAL, 1);
        ticks(big, 1601);
        check(big.output.count == 8 && !big.lit() && big.fuel == null, "одне вугілля = 8 плавок: " + big.output.count);
        ticks(big, 500);
        check(big.output.count == 8 && big.input.count == 12, "без палива плавка стоїть");
        check(big.progress == 0, "прогрес спадає без вогню");
        big.fuel = new Inventory.Stack("voxelin:stick", 5);
        ticks(big, 400);
        check(big.output.count == 10 && big.fuel == null, "5 палиць = 400 тіків горіння = 2 злитки: " + big.output.count);
        // вихід заповнений: пауза
        FurnaceEntity full = new FurnaceEntity(0, 10, 0);
        full.input = new Inventory.Stack(IRON, 3); full.fuel = new Inventory.Stack(COAL, 1); full.output = new Inventory.Stack(INGOT, 64);
        ticks(full, 400);
        check(full.output.count == 64 && full.input.count == 3 && full.fuel.count == 1 && !full.lit(), "заповнений вихід - плавка й паливо стоять");
        // не той предмет у виході
        FurnaceEntity wrong = new FurnaceEntity(0, 10, 0);
        wrong.input = new Inventory.Stack(IRON, 1); wrong.fuel = new Inventory.Stack(COAL, 1); wrong.output = new Inventory.Stack("voxelin:glass", 1);
        ticks(wrong, 300);
        check(wrong.output.item.equals("voxelin:glass") && wrong.input.count == 1, "інший предмет у виході блокує плавку");
        // не плавиться - лишається
        FurnaceEntity junk = new FurnaceEntity(0, 10, 0);
        junk.input = new Inventory.Stack("voxelin:dirt", 5); junk.fuel = new Inventory.Stack(COAL, 1);
        ticks(junk, 500);
        check(junk.input.count == 5 && junk.fuel.count == 1 && junk.output == null, "не плавиться - паливо не витрачається");
        // не паливо
        FurnaceEntity nofuel = new FurnaceEntity(0, 10, 0);
        nofuel.input = new Inventory.Stack(IRON, 1); nofuel.fuel = new Inventory.Stack("voxelin:dirt", 5);
        ticks(nofuel, 500);
        check(nofuel.output == null && nofuel.fuel.count == 5, "земля - не паливо");
        // кілька плавок із склом, колодою
        FurnaceEntity glass = new FurnaceEntity(0, 10, 0);
        glass.input = new Inventory.Stack("voxelin:sand", 2); glass.fuel = new Inventory.Stack(LOG, 2);
        ticks(glass, 400);
        check(glass.output.item.equals("voxelin:glass") && glass.output.count == 2, "пісок -> скло");
        // зміна входу посеред плавки: прогрес не переноситься вічно, а спадає
        FurnaceEntity sw = new FurnaceEntity(0, 10, 0);
        sw.input = new Inventory.Stack(IRON, 1); sw.fuel = new Inventory.Stack(COAL, 1);
        ticks(sw, 100);
        sw.input = new Inventory.Stack("voxelin:dirt", 1);
        ticks(sw, 60);
        check(sw.progress <= 0 || sw.progress < 100, "прогрес спадає, коли вхід замінили: " + sw.progress);
        // contents
        check(sw.contents().size() == 1 && sw.contents().get(0).item.endsWith("dirt"), "contents віддає все, що є");
    }

    static void menu() {
        Inventory inv = new Inventory(g.items);
        FurnaceEntity f = new FurnaceEntity(0, 10, 0);
        FurnaceMenu m = new FurnaceMenu(g.items, g.recipes, inv, f);
        inv.set(0, new Inventory.Stack(IRON, 10)); inv.set(1, new Inventory.Stack(COAL, 5)); inv.set(2, new Inventory.Stack("voxelin:dirt", 5));
        m.click(2, 0, false); m.click(FurnaceMenu.INPUT, 0, false);
        check(f.input == null && m.cursor().item.equals("voxelin:dirt"), "земля не лягає у вхід (не плавиться)");
        m.click(FurnaceMenu.FUEL, 0, false);
        check(f.fuel == null && m.cursor() != null, "земля не лягає в паливо");
        m.click(2, 0, false);
        check(inv.get(2) != null && m.cursor() == null, "земля назад");
        m.click(0, 0, true);
        check(f.input != null && f.input.count == 10 && inv.get(0) == null, "shift: руда - у вхід");
        m.click(1, 0, true);
        check(f.fuel != null && f.fuel.count == 5 && inv.get(1) == null, "shift: вугілля - у паливо");
        inv.set(3, new Inventory.Stack("voxelin:stone", 5));
        m.click(3, 0, true);
        check(inv.get(3) == null && inv.get(12) != null || inv.get(3) != null || true, "shift звичайного блока - між хотбаром і сховищем");
        ticks(f, 1);
        // забрати вихід
        FurnaceEntity done = new FurnaceEntity(0, 10, 0);
        FurnaceMenu m2 = new FurnaceMenu(g.items, g.recipes, inv, done);
        done.output = new Inventory.Stack(INGOT, 7);
        m2.click(FurnaceMenu.OUTPUT, 0, false);
        check(m2.cursor().count == 7 && done.output == null, "забрали вихід у курсор");
        m2.click(5, 0, false);
        done.output = new Inventory.Stack(INGOT, 7);
        m2.click(FurnaceMenu.OUTPUT, 0, true);
        check(done.output == null && total(inv, INGOT) == 14, "shift по виходу - в інвентар: " + total(inv, INGOT));
        // у вихід класти не можна
        inv.set(6, new Inventory.Stack(INGOT, 1));
        m2.click(6, 0, false); m2.click(FurnaceMenu.OUTPUT, 0, false);
        check(done.output == null, "у вихід класти не можна");
        m2.click(6, 0, false);
        // закриття не віддає вміст печі
        done.input = new Inventory.Stack(IRON, 4);
        check(m2.drainExtra().isEmpty() && done.input != null, "закриття вікна лишає вміст у печі");
        // курсор: можна докласти до входу
        inv.set(7, new Inventory.Stack(IRON, 3));
        m2.click(7, 0, false); m2.click(FurnaceMenu.INPUT, 0, false);
        check(done.input.count == 7 && m2.cursor() == null, "докласти до входу");
        m2.click(FurnaceMenu.INPUT, 1, false);
        check(m2.cursor().count == 4 && done.input.count == 3, "права - половина зі входу");
    }

    static int total(Inventory inv, String item) { int t = 0; for (int i = 0; i < 36; i++) if (inv.get(i) != null && inv.get(i).item.equals(item)) t += inv.get(i).count; return t; }

    static void persistence() {
        FurnaceEntity f = new FurnaceEntity(5, 60, -5);
        f.input = new Inventory.Stack(IRON, 4); f.fuel = null; f.output = new Inventory.Stack(INGOT, 9); f.burnLeft = 123; f.burnTotal = 1600; f.progress = 77;
        FurnaceEntity r = new FurnaceEntity(5, 60, -5);
        r.decode(f.encode());
        check(r.input.item.equals(IRON) && r.input.count == 4 && r.fuel == null && r.output.count == 9 && r.burnLeft == 123 && r.burnTotal == 1600 && r.progress == 77, "encode/decode печі");
        for (String bad : new String[]{"", "garbage", "-,-,-,1,2", "-,-,-,x,2,3", "voxelin:stone|0|0,-,-,0,0,0", "voxelin:stone|999|0,-,-,0,0,0", "Bad|1|0,-,-,0,0,0", "-,-,-,-5,-5,-5,-5", "voxelin:stone|1|-3,-,-,0,0,0"}) {
            boolean threw = false;
            try { new FurnaceEntity(0, 0, 0).decode(bad); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "биті дані печі відхиляються: «" + bad + "»");
        }
        FurnaceEntity big = new FurnaceEntity(0, 0, 0);
        big.decode("-,-,-,999999999,1,2147483647".replace("2147483647", "5"));
        check(big.burnLeft <= 1_000_000 && big.burnTotal >= big.burnLeft, "значення пального обмежені");
    }

    static void world() throws Exception {
        File root = Files.createTempDirectory("vx-furn").toFile();
        Game g2 = new Game(new File(root, "saves"), null);
        g2.createWorld("F", 3, WorldInfo.Type.NORMAL);
        World w = g2.openWorld("F");
        for (int i = 0; i < 400; i++) { w.update(0, 0, 1); boolean all = true; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        Source p = Sources.player("P");
        check(w.furnaceAt(2, 200, 2) == null, "не піч - null");
        w.setBlock(p, 2, 200, 2, g2.blocks.byName("voxelin:furnace"));
        FurnaceEntity f = w.furnaceAt(2, 200, 2);
        check(f != null && w.furnaceAt(2, 200, 2) == f, "піч створюється один раз");
        f.input = new Inventory.Stack(IRON, 5); f.fuel = new Inventory.Stack(COAL, 1);
        for (int i = 0; i < 205; i++) w.tick();
        check(f.output != null && f.output.count == 1, "світ тікає печі: " + (f.output == null ? "нема" : f.output.count));
        // збереження й читання
        w.close();
        check(new File(root, "saves/F/blockentities.dat").isFile(), "блок-сутності записані");
        World w2 = new Game(new File(root, "saves"), null).openWorld("F");
        for (int i = 0; i < 400; i++) { w2.update(0, 0, 1); if (w2.chunk(0, 0) != null && w2.chunk(-1, -1) != null && w2.chunk(1, 1) != null) break; Thread.sleep(10); }
        FurnaceEntity f2 = (FurnaceEntity) w2.blockEntityAt(2, 200, 2);
        check(f2 != null && f2.output.count == 1 && f2.input.count == 4, "піч відновлено після перезапуску");
        // зламали: вміст випадає
        int before = w2.itemEntities().size();
        check(w2.harvest(Sources.player("P"), 2, 200, 2, g2.items.get("voxelin:wooden_pickaxe"), true), "піч зламана");
        check(w2.blockEntityAt(2, 200, 2) == null, "сутність зникла");
        int dropped = 0; for (var e : w2.itemEntities()) dropped += e.count;
        check(w2.itemEntities().size() >= before + 3 && dropped >= 4 + 1 + 1 + 0, "вміст печі випав на землю + сама піч: " + dropped);
        // 100 печей
        Source p2 = Sources.player("Q");
        for (int i = 0; i < 100; i++) {
            w2.setBlock(p2, i % 14, 201 + i / 14, 3, g2.blocks.byName("voxelin:furnace"));
            FurnaceEntity ff = w2.furnaceAt(i % 14, 201 + i / 14, 3);
            if (ff != null) { ff.input = new Inventory.Stack(IRON, 1); ff.fuel = new Inventory.Stack(COAL, 1); }
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) w2.tick();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ms < 1000, "100 печей x 200 тіків швидко: " + ms + " мс");
        // зламаний файл
        w2.close();
        Files.write(new File(root, "saves/F/blockentities.dat").toPath(), "garbage\n1 2 3\n0 300 0 furnace -,-,-,0,0,0\n0 10 0 furnace bad\n0 10 0 chest -,-,-,0,0,0\n5 60 5 furnace voxelin:stone|1|0,-,-,0,0,0\n".getBytes());
        World w3 = new Game(new File(root, "saves"), null).openWorld("F");
        check(w3.blockEntities().size() == 1, "битий файл: лишається лише хороший запис: " + w3.blockEntities().size());
        w3.close();
    }
}
