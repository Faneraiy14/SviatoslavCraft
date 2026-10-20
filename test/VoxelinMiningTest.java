import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Предмети, видобуток (час ламання, дроп), інвентар, предмети на землі, режими гри, команди /give /gamemode /clear, збереження. */
public class VoxelinMiningTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static BlockType b(String name) { return g.blocks.byName("voxelin:" + name); }
    static ItemType it(String name) { return g.items.get("voxelin:" + name); }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        registry();
        mining();
        inventory();
        entities();
        world();
        System.out.println("VoxelinMiningTest: " + n + " перевірок OK");
    }

    static void registry() {
        check(g.items.isFrozen() && it("stone") != null && it("stone").placesBlock.equals("voxelin:stone"), "кожен блок має предмет-блок");
        check(it("bedrock") == null && it("water") == null && it("air") == null && it("unknown") == null, "корінна порода, вода, повітря - без предмета");
        check(it("wooden_pickaxe").maxStack == 1 && it("wooden_pickaxe").durability == 60 && it("diamond_pickaxe").tier == 4 && it("iron_axe").tool == ToolKind.AXE, "інструменти");
        check(it("apple").food == 4 && it("coal").maxStack == 64, "їжа й матеріали");
        check(g.items.find("stone") == it("stone") && g.items.find("VOXELIN:STONE") == it("stone") && g.items.find("nonsense") == null && g.items.find(null) == null, "пошук за короткою назвою");
        boolean threw = false;
        try { g.items.register("x:y", new ItemSettings()); } catch (IllegalStateException e) { threw = true; }
        check(threw, "реєстрація предметів після закриття заборонена");
        ItemRegistry r = new ItemRegistry();
        r.register("m:a", new ItemSettings());
        threw = false; try { r.register("m:a", new ItemSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "повтор предмета відхиляється");
        threw = false; try { r.register("Bad", new ItemSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "погана назва предмета відхиляється");
        check(new ItemSettings().maxStack(500).maxStackValue() == 64 && new ItemSettings().maxStack(-3).maxStackValue() == 1 && new ItemSettings().durability(5).maxStackValue() == 1, "межі розміру стопки");
        check(new ItemSettings().tool(ToolKind.AXE, 2, Float.NaN).speedValue() == 1f, "NaN швидкість -> 1");
    }

    static void mining() {
        BlockType stone = b("stone"), dirt = b("dirt"), log = b("oak_log"), iron = b("iron_ore"), gold = b("gold_ore"), dia = b("diamond_ore"), lim = b("liminite_ore"), coal = b("coal_ore"), bed = b("bedrock"), leaves = b("oak_leaves"), glass = b("glass");
        ItemType wp = it("wooden_pickaxe"), sp = it("stone_pickaxe"), ip = it("iron_pickaxe"), dp = it("diamond_pickaxe"), wa = it("wooden_axe"), ws = it("wooden_shovel");
        // чи можна добути
        check(!Mining.canHarvest(stone, null) && Mining.canHarvest(stone, wp), "камінь: рукою без дропу, киркою з дропом");
        check(!Mining.canHarvest(iron, wp) && Mining.canHarvest(iron, sp) && Mining.canHarvest(iron, ip), "залізна руда: з кам'яної кирки");
        check(!Mining.canHarvest(gold, sp) && Mining.canHarvest(gold, ip) && !Mining.canHarvest(dia, sp) && Mining.canHarvest(dia, ip), "золото/алмаз: із залізної");
        check(!Mining.canHarvest(lim, ip) && Mining.canHarvest(lim, dp), "лімініт: лише алмазна кирка");
        check(!Mining.canHarvest(iron, wa) && !Mining.canHarvest(iron, ws), "не той інструмент не годиться");
        check(Mining.canHarvest(dirt, null) && Mining.canHarvest(log, null) && Mining.canHarvest(leaves, null), "земля, колода, листя - рукою");
        check(!Mining.canHarvest(bed, dp) && Mining.seconds(bed, dp, false) == Double.POSITIVE_INFINITY && Mining.seconds(bed, null, true) == Double.POSITIVE_INFINITY, "корінна порода незламна навіть у креативі");
        // час
        check(near(Mining.seconds(stone, null, false), 1.5 * 5), "камінь рукою: 7.5 с");
        check(near(Mining.seconds(stone, wp, false), 1.5 * 1.5 / 2), "камінь дерев'яною киркою");
        check(near(Mining.seconds(stone, dp, false), 1.5 * 1.5 / 8), "камінь алмазною киркою");
        check(near(Mining.seconds(dirt, ws, false), 0.5 * 1.5 / 2), "земля лопатою: 0.375 с");
        check(near(Mining.seconds(dirt, null, false), 0.5 * 1.5), "земля рукою (дроп є, швидкості нема): 0.75 с");
        check(Mining.seconds(stone, dp, true) == 0 && Mining.seconds(dirt, null, true) == 0, "креатив - миттєво");
        check(near(Mining.seconds(iron, wp, false), 3 * 5 / 2.0), "залізна руда дерев'яною киркою без дропу: повільно");
        // монотонність: кращий інструмент не повільніший (для підходящого блока)
        ItemType[] picks = {null, wp, sp, ip, dp};
        for (BlockType blk : new BlockType[]{stone, iron, gold, dia, coal, lim}) {
            double prev = Double.MAX_VALUE;
            for (ItemType t : picks) { double s = Mining.seconds(blk, t, false); check(s <= prev + 1e-9, "кращий інструмент не повільніший: " + blk.name + " " + (t == null ? "рука" : t.name)); prev = s; }
        }
        // дроп
        Random rng = new Random(1);
        check(Mining.drops(stone, null, rng).isEmpty() && Mining.drops(stone, wp, rng).size() == 1 && Mining.drops(stone, wp, rng).get(0).item.equals("voxelin:cobblestone"), "камінь -> бруківка лише з киркою");
        check(Mining.drops(iron, sp, rng).get(0).item.equals("voxelin:raw_iron") && Mining.drops(coal, wp, rng).get(0).item.equals("voxelin:coal") && Mining.drops(dia, ip, rng).get(0).item.equals("voxelin:diamond"), "руди -> сировина");
        check(Mining.drops(glass, null, rng).isEmpty() && Mining.drops(glass, dp, rng).isEmpty(), "скло нічого не дає");
        check(Mining.drops(dirt, null, rng).get(0).item.equals("voxelin:dirt") && Mining.drops(b("grass"), null, rng).get(0).item.equals("voxelin:dirt"), "трава -> земля");
        check(Mining.drops(log, null, rng).get(0).item.equals("voxelin:oak_log"), "колода -> колода");
        int apples = 0, sticks = 0, total = 20000;
        Random r2 = new Random(7);
        for (int i = 0; i < total; i++) for (Inventory.Stack s : Mining.drops(leaves, null, r2)) { if (s.item.endsWith("apple")) apples++; if (s.item.endsWith("stick")) { sticks++; check(s.count >= 1 && s.count <= 2, "палиць 1..2"); } }
        check(apples > total * 0.04 && apples < total * 0.06, "яблуко ~5%: " + apples);
        check(sticks > total * 0.08 && sticks < total * 0.12, "палиці ~10%: " + sticks);
        int cu = 0; for (int i = 0; i < 1000; i++) { int c = Mining.drops(b("copper_ore"), sp, r2).get(0).count; check(c == 2 || c == 3, "мідь 2..3"); cu += c; }
        check(cu > 2300 && cu < 2700, "мідь у середньому ~2.5: " + cu);
        check(Mining.drops(lim, dp, new Random(3)).get(0).count >= 1, "лімініт");
        // уламки: у спільному реєстрі немає блока з незрозумілим дропом
        for (BlockType t : g.blocks.all()) for (BlockSettings.Drop d : t.drops) check(g.items.get(d.item) != null, "дроп існує як предмет: " + t.name + " -> " + d.item);
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    static void inventory() {
        Inventory inv = new Inventory(g.items);
        check(inv.add("voxelin:stone", 100) == 0 && inv.get(0).count == 64 && inv.get(1).count == 36, "стопки по 64");
        check(inv.add("voxelin:wooden_pickaxe", 3) == 0 && inv.get(2).count == 1 && inv.get(3).count == 1 && inv.get(4).count == 1, "інструменти по 1");
        // зношені не склеюються
        inv.add("voxelin:wooden_axe", 1, 10);
        check(inv.get(5).damage == 10, "зношеність зберігається");
        inv.select(2);
        check(!inv.wearSelected(60) && inv.selectedStack().damage == 1, "удар зношує");
        for (int i = 0; i < 58; i++) check(!inv.wearSelected(60), "ще ціла");
        check(inv.selectedStack().damage == 59 && inv.wearSelected(60) && inv.selectedStack() == null, "ламається на 60-му ударі");
        check(!inv.wearSelected(60) && !new Inventory().wearSelected(0), "порожня рука / безсмертний предмет");
        // переповнення
        Inventory full = new Inventory(g.items);
        check(full.add("voxelin:dirt", 64 * 36) == 0 && full.add("voxelin:dirt", 1) == 1 && !full.canFit("voxelin:dirt", 1, 0), "переповнення повертає залишок");
        check(full.add("voxelin:wooden_pickaxe", 1) == 1, "інструмент не влізає в повний інвентар");
        // canFit
        Inventory h = new Inventory(g.items);
        h.add("voxelin:coal", 60);
        check(h.canFit("voxelin:coal", 4, 0) && h.canFit("voxelin:coal", 64 * 35 + 4, 0) && !h.canFit("voxelin:coal", 64 * 35 + 5, 0), "canFit рахує вільне місце");
        // drain
        List<Inventory.Stack> d = h.drain();
        check(d.size() == 1 && d.get(0).count == 60 && h.isEmpty(), "drain забирає все");
        // збереження
        Inventory s = new Inventory(g.items);
        s.add("voxelin:stone", 70); s.add("voxelin:iron_pickaxe", 1, 5); s.select(4);
        String[] enc = s.encode();
        Inventory t = new Inventory(g.items);
        t.decode(enc, s.selected());
        check(t.get(0).count == 64 && t.get(1).count == 6 && t.get(2).item.equals("voxelin:iron_pickaxe") && t.get(2).damage == 5 && t.selected() == 4, "encode/decode");
        // биті записи
        String[] bad = new String[36];
        bad[0] = "voxelin:stone|0|0"; bad[1] = "voxelin:stone|999|0"; bad[2] = "voxelin:stone|5|-1"; bad[3] = "garbage"; bad[4] = "voxelin:stone|x|0"; bad[5] = "voxelin:stone|3|0";
        bad[6] = "Bad Name|1|0"; bad[7] = "voxelin:wooden_pickaxe|2|0"; bad[8] = "gone:mod_item|3|0";
        Inventory q = new Inventory(g.items);
        q.decode(bad, 99);
        check(q.get(0) == null && q.get(1) == null && q.get(2) == null && q.get(3) == null && q.get(4) == null && q.get(6) == null && q.get(7) == null, "биті записи ігноруються");
        check(q.get(5) != null && q.get(5).count == 3 && q.get(8) != null && q.get(8).item.equals("gone:mod_item") && q.selected() == 0, "хороші лишаються (і предмети зниклого мода), слот поза межами ігнорується");
        check(new Inventory().give("x:y", -5) == 0 && new Inventory().give("x:y", 10) == 0, "give");
    }

    static final PlayerEntity.Solid FLOOR = (x, y, z) -> y <= 10;
    static final PlayerEntity.Liquid NO = (x, y, z) -> false;

    static void entities() {
        ItemEntity e = new ItemEntity(Pos.ofBlock(0, 30, 0), "voxelin:stone", 3, 0);
        for (int i = 0; i < 200; i++) e.tick(FLOOR, NO);
        check(e.onGround && Math.abs(e.pos.y - (11 + ItemEntity.HALF)) < 0.2, "предмет падає й лежить на землі: y=" + e.pos.y);
        check(e.vx == 0 && e.vz == 0, "зупиняється");
        // відскок вбік по стіні
        ItemEntity w = new ItemEntity(Pos.ofBlock(0, 12, 0), "voxelin:stone", 1, 0);
        w.vx = 10;
        PlayerEntity.Solid wall = (x, y, z) -> y <= 10 || x >= 3;
        for (int i = 0; i < 100; i++) w.tick(wall, NO);
        check(w.pos.relX(0) < 3 && w.pos.relX(0) > 1.5, "стіна зупиняє предмет: x=" + w.pos.relX(0));
        // вода: спливає
        ItemEntity wet = new ItemEntity(Pos.ofBlock(0, 8, 0), "voxelin:stone", 1, 0);
        PlayerEntity.Liquid pond = (x, y, z) -> y >= 11 && y <= 20;
        PlayerEntity.Solid bottom = (x, y, z) -> y <= 10;
        for (int i = 0; i < 400; i++) wet.tick(bottom, pond);
        check(wet.pos.y > 20 - 1 && wet.pos.y < 23, "спливає до поверхні: y=" + wet.pos.y);
        // замурований
        ItemEntity inside = new ItemEntity(Pos.ofBlock(0, 11.5, 0), "voxelin:stone", 1, 0);
        PlayerEntity.Solid box = (x, y, z) -> y <= 10 || (y == 11 && x == 0 && z == 0);
        for (int i = 0; i < 30; i++) inside.tick(box, NO);
        check(inside.pos.y >= 11.9, "витиснутий угору: " + inside.pos.y);
        // незавантажений чанк (суцільний) - не провалюється
        ItemEntity frozen = new ItemEntity(Pos.ofBlock(100, 50, 100), "voxelin:stone", 1, 0);
        for (int i = 0; i < 100; i++) frozen.tick((x, y, z) -> true, NO);
        check(frozen.pos.y >= 50 - 1e-9, "у незавантаженому чанку не провалюється");
        // великі координати
        for (long base : new long[]{1_000_000_000_000L, 1_000_000_000_000_000L, -1_000_000_000_000_000L, -(1L << 60)}) {
            ItemEntity far = new ItemEntity(Pos.ofBlock(base, 30, base), "voxelin:stone", 1, 0);
            far.vx = 5; far.vz = -5;
            for (int i = 0; i < 200; i++) far.tick((x, y, z) -> y <= 10, NO);
            check(far.onGround && Math.abs(far.pos.y - 11.125) < 0.2, "падає однаково на " + base);
        }
        // термін життя
        ItemEntity old = new ItemEntity(Pos.ofBlock(0, 11.2, 0), "voxelin:stone", 1, 0);
        for (int i = 0; i < ItemEntity.LIFETIME - 1; i++) old.tick(FLOOR, NO);
        check(!old.expired(), "ще живий");
        old.tick(FLOOR, NO);
        check(old.expired(), "зникає через 5 хвилин");
        // злиття
        ItemEntity a = new ItemEntity(Pos.ofBlock(0, 11.2, 0), "voxelin:stone", 30, 0), b2 = new ItemEntity(Pos.ofBlock(0, 11.2, 0), "voxelin:stone", 30, 0), c = new ItemEntity(Pos.ofBlock(0, 11.2, 0), "voxelin:dirt", 1, 0);
        check(a.canMerge(b2, 64) && !a.canMerge(b2, 59) && !a.canMerge(c, 64) && !a.canMerge(a, 64), "злиття лише однакових і в межах стопки");
        ItemEntity far2 = new ItemEntity(Pos.ofBlock(5, 11.2, 0), "voxelin:stone", 1, 0);
        check(!a.canMerge(far2, 64), "далекі не зливаються");
    }

    static void world() throws Exception {
        File root = Files.createTempDirectory("vx-mine").toFile();
        Game g2 = new Game(new File(root, "saves"), null);
        g2.createWorld("M", 11, WorldInfo.Type.NORMAL);
        World w = g2.openWorld("M");
        VoxelinCoreTest_loadAround(w);
        Source p = Sources.player("P");
        check(!w.creative() && w.gameMode().equals("survival"), "новий світ - виживання");
        check(w.inventory(p).isEmpty(), "інвентар порожній");
        // /give
        List<String> o = g2.commands.execute(p, w, null, "/give stone 70");
        check(o.get(0).startsWith("Видано 70") && w.inventory(p).get(0).count == 64, "/give stone 70: " + o);
        o = g2.commands.execute(p, w, null, "/give voxelin:iron_pickaxe");
        check(o.get(0).startsWith("Видано 1"), "/give інструмент: " + o);
        for (String bad : new String[]{"/give", "/give nope", "/give stone 0", "/give stone -5", "/give stone 99999", "/give stone abc", "/give stone 1 2", "/give bedrock", "/give water"}) {
            o = g2.commands.execute(p, w, null, bad);
            check(!o.isEmpty() && !o.get(0).startsWith("Видано"), "погана /give: " + bad + " -> " + o);
        }
        check(g2.commands.execute(Sources.of("g", Source.Kind.PLAYER, 0, 1), w, null, "/give stone").get(0).contains("прав"), "/give потребує прав");
        check(g2.commands.execute(Sources.mod("m"), w, null, "/give stone").get(0).contains("нема інвентаря"), "у мода інвентаря нема");
        o = g2.commands.execute(p, w, null, "/clear");
        check(w.inventory(p).isEmpty(), "/clear: " + o);
        // /gamemode
        o = g2.commands.execute(p, w, null, "/gamemode creative");
        check(w.creative() && w.vitals(p).god() && !w.inventory(p).isEmpty() && o.get(0).contains("creative"), "креатив: стартовий набір і безсмертя: " + o);
        g2.commands.execute(p, w, null, "/hurt 10");
        check(w.vitals(p).health() == 20, "у креативі шкоди нема");
        o = g2.commands.execute(p, w, null, "/gamemode survival");
        check(!w.creative() && !w.vitals(p).god(), "назад у виживання");
        check(g2.commands.execute(p, w, null, "/gamemode foo").get(0).contains("Невідомий"), "невідомий режим");
        check(g2.commands.execute(p, w, null, "/gamemode").get(0).contains("Потрібен"), "без аргументу");
        // ламання зі світу: дроп
        g2.commands.execute(p, w, null, "/clear");
        w.setBlock(p, 3, 200, 3, g2.blocks.byName("voxelin:stone"));
        ItemType none = null;
        check(w.harvest(p, 3, 200, 3, none, true) && w.blockId(3, 200, 3) == 0 && w.itemEntities().isEmpty(), "камінь рукою - зламано, дропу нема");
        w.setBlock(p, 3, 200, 3, g2.blocks.byName("voxelin:stone"));
        check(w.harvest(p, 3, 200, 3, g2.items.get("voxelin:wooden_pickaxe"), true) && w.itemEntities().size() == 1 && w.itemEntities().get(0).item.equals("voxelin:cobblestone"), "камінь киркою - бруківка на землі");
        w.setBlock(p, 3, 200, 3, g2.blocks.byName("voxelin:stone"));
        check(w.harvest(p, 3, 200, 3, g2.items.get("voxelin:wooden_pickaxe"), false) && w.itemEntities().size() == 1, "dropItems=false - без дропу");
        check(!w.harvest(p, 3, 200, 3, none, true), "повітря не ламається");
        check(!w.harvest(p, 3, 0, 3, none, true) || w.blockId(3, 0, 3) == 0, "корінну породу не зламати (y=0)");
        // підбір і затримка
        Inventory inv = w.inventory(p);
        Pos at = w.itemEntities().get(0).pos.copy();
        check(w.collectItems(at, inv) == 0, "затримка підбору");
        for (int i = 0; i < 12; i++) w.tick();
        at = w.itemEntities().get(0).pos.copy();
        Pos far3 = at.copy(); far3.add(5, 0, 0);
        check(w.collectItems(far3, inv) == 0, "далеко - не підбирає");
        check(w.collectItems(at, inv) == 1 && inv.get(0).item.equals("voxelin:cobblestone") && w.itemEntities().isEmpty(), "підбір поруч");
        // повний інвентар не підбирає
        Inventory fullInv = new Inventory(g2.items);
        fullInv.add("voxelin:dirt", 64 * 36);
        w.spawnItem(at, "voxelin:stone", 5, 0, false);
        for (int i = 0; i < 12; i++) w.tick();
        check(w.collectItems(w.itemEntities().get(0).pos.copy(), fullInv) == 0 && w.itemEntities().size() == 1, "повний інвентар - предмет лишається");
        // ліміт предметів і злиття
        for (int i = 0; i < World.MAX_ITEMS + 100; i++) w.spawnItem(at, "voxelin:dirt", 1, 0, false);
        check(w.itemEntities().size() == World.MAX_ITEMS, "ліміт предметів на землі: " + w.itemEntities().size());
        for (int i = 0; i < 40; i++) w.tick();
        check(w.itemEntities().size() < 20, "купа однакових зливається: " + w.itemEntities().size());
        int dirtTotal = 0; for (ItemEntity e : w.itemEntities()) if (e.item.endsWith("dirt")) dirtTotal += e.count;
        check(dirtTotal == World.MAX_ITEMS - 1 || dirtTotal == World.MAX_ITEMS, "злиття зберігає кількість: " + dirtTotal);
        // збереження: інвентар, режим, предмети
        g2.commands.execute(p, w, null, "/clear");
        g2.commands.execute(p, w, null, "/give diamond_pickaxe");
        g2.commands.execute(p, w, null, "/give apple 7");
        inv.select(3);
        int onGround = w.itemEntities().size();
        w.close();
        WorldInfo info = WorldInfo.load(new File(root, "saves/M"));
        check(info.hasInv && info.mode == WorldInfo.Mode.SURVIVAL && info.inv[0].startsWith("voxelin:diamond_pickaxe|1|0") && info.inv[1].startsWith("voxelin:apple|7|0") && info.invSelected == 3, "інвентар і режим записані");
        check(new File(root, "saves/M/items.dat").isFile(), "предмети записані");
        World w2 = new Game(new File(root, "saves"), null).openWorld("M");
        Source p2 = Sources.player("P");
        check(w2.inventory(p2).get(0).item.equals("voxelin:diamond_pickaxe") && w2.inventory(p2).get(1).count == 7 && w2.inventory(p2).selected() == 3, "інвентар відновлено");
        check(w2.itemEntities().size() == onGround, "предмети на землі відновлено: " + w2.itemEntities().size() + " з " + onGround);
        w2.close();
        // биті файли
        Files.write(new File(root, "saves/M/items.dat").toPath(), "garbage\n1 2 3\n0 0 99 99 99 voxelin:stone 1 0\n0 0 1 1 1 voxelin:stone 0 0\n0 0 1 1 1 Bad 1 0\n0 0 NaN 1 1 voxelin:stone 1 0\n0 0 1 1 1 voxelin:stone 3 0\n".getBytes());
        World w3 = new Game(new File(root, "saves"), null).openWorld("M");
        check(w3.itemEntities().size() == 1 && w3.itemEntities().get(0).count == 3, "битий items.dat: лишається лише хороший запис: " + w3.itemEntities().size());
        w3.close();
        // старий світ -> креатив
        File old = new File(root, "saves/M/voxelin.world");
        List<String> lines = new ArrayList<>();
        for (String l : Files.readAllLines(old.toPath())) if (!l.startsWith("mode=") && !l.startsWith("hasInv") && !l.startsWith("inv")) lines.add(l);
        Files.write(old.toPath(), lines);
        WorldInfo oi = WorldInfo.load(new File(root, "saves/M"));
        check(oi.mode == WorldInfo.Mode.CREATIVE && !oi.hasInv, "старий світ без режиму - творчий");
        Files.write(old.toPath(), (String.join("\n", lines) + "\nmode=NONSENSE\nhasInv=true\ninvSelected=abc\ninv.0=junk\n").getBytes());
        oi = WorldInfo.load(new File(root, "saves/M"));
        check(oi.mode == WorldInfo.Mode.CREATIVE && oi.invSelected == 0, "биті поля режиму/слота не ламають завантаження");
        World w4 = new Game(new File(root, "saves"), null).openWorld("M");
        check(w4.inventory(Sources.player("Z")).isEmpty(), "биті записи інвентаря - порожній інвентар");
        w4.close();
    }

    static void VoxelinCoreTest_loadAround(World w) throws Exception {
        for (int i = 0; i < 400; i++) {
            w.update(0, 0, 1);
            boolean all = true;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(dx, dz) == null) all = false;
            if (all) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("чанки не завантажились");
    }
}
