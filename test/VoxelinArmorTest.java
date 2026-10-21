import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Броня: предмети й рецепти, надягання, захист, зношення, збереження, меню. */
public class VoxelinArmorTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final String[] MATS = {"leather", "iron", "diamond"}, KINDS = {"helmet", "chestplate", "leggings", "boots"};
    static final int[][] PTS = {{1, 3, 2, 1}, {2, 6, 5, 2}, {3, 8, 6, 3}};

    static String id(String mat, String kind) { return "voxelin:" + mat + "_" + kind; }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        items(); equip(); protection(); menu(); persistence(); wings();
        System.out.println("VoxelinArmorTest: " + n + " перевірок OK");
    }

    static void items() {
        for (int m = 0; m < 3; m++) {
            int total = 0;
            for (int k = 0; k < 4; k++) {
                ItemType t = g.items.get(id(MATS[m], KINDS[k]));
                check(t != null && t.armorSlot == ArmorSlot.values()[k] && t.armorPoints == PTS[m][k] && t.maxStack == 1 && t.durability > 0, "предмет " + MATS[m] + " " + KINDS[k]);
                total += t.armorPoints;
            }
            check(total == new int[]{7, 15, 20}[m], "сума очок " + MATS[m] + ": " + total);
        }
        check(g.items.get(id("diamond", "helmet")).armorTier == 4 && g.items.get(id("iron", "boots")).armorTier == 3 && g.items.get(id("leather", "boots")).armorTier == 1, "рівні матеріалів");
        check(g.items.get("voxelin:stone").armorSlot == null && g.items.get("voxelin:iron_sword").armorSlot == null, "решта предметів - не броня");
        // рецепти: сітка 3x3
        String[][] shapes = {{"MMM", "M.M", "..."}, {"M.M", "MMM", "MMM"}, {"MMM", "M.M", "M.M"}, {"M.M", "M.M", "..."}};
        String[] heads = {"voxelin:leather", "voxelin:iron_ingot", "voxelin:diamond"};
        for (int m = 0; m < 3; m++) for (int k = 0; k < 4; k++) {
            Inventory.Stack[] grid = new Inventory.Stack[9];
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) if (shapes[k][y].charAt(x) == 'M') grid[x + y * 3] = new Inventory.Stack(heads[m], 1);
            Inventory.Stack r = Crafting.match(g.recipes, g.items, grid, 3);
            check(r != null && r.item.equals(id(MATS[m], KINDS[k])) && r.count == 1, "рецепт " + MATS[m] + " " + KINDS[k]);
            check(Crafting.matchAll(g.recipes, g.items, grid, 3).size() == 1, "однозначний " + MATS[m] + " " + KINDS[k]);
        }
        check(g.recipes.all().stream().anyMatch(r -> r.result.equals(id("iron", "helmet"))), "рецепти зареєстровані");
        check(Trading.offers(g.mobTypes.find("villager_smith"), g.items).stream().anyMatch(t -> t.out.equals(id("iron", "helmet"))), "коваль продає залізний шолом");
    }

    static void equip() {
        Inventory inv = new Inventory(g.items);
        check(inv.armorPoints(g.items) == 0 && !inv.fullArmor(g.items, 1), "без броні 0 очок");
        inv.add(id("iron", "chestplate"), 1);
        check(inv.equipSelected(g.items) && inv.armor(1) != null && inv.get(0) == null && inv.armorPoints(g.items) == 6, "надягання з руки в слот нагрудника");
        inv.add(id("iron", "chestplate"), 1, 7);
        inv.select(0);
        check(inv.equipSelected(g.items) && inv.armor(1).damage == 7 && inv.get(0).damage == 0, "заміна: стара броня потрапляє в руку");
        inv.set(0, new Inventory.Stack("voxelin:stone", 5));
        check(!inv.equipSelected(g.items) && inv.get(0).count == 5, "не броню не надягти");
        check(!new Inventory(g.items).equipSelected(g.items), "порожня рука");
        // повний комплект
        Inventory full = new Inventory(g.items);
        for (int k = 0; k < 4; k++) full.setArmor(k, new Inventory.Stack(id("diamond", KINDS[k]), 1));
        check(full.fullArmor(g.items, 4) && full.armorPoints(g.items) == 20, "повний алмазний комплект");
        full.setArmor(2, new Inventory.Stack(id("iron", "leggings"), 1));
        check(!full.fullArmor(g.items, 4) && full.fullArmor(g.items, 3), "один залізний шматок ламає алмазний комплект");
        full.setArmor(2, null);
        check(!full.fullArmor(g.items, 1), "бракує слота - не комплект");
        // броня у неправильному слоті не рахується
        Inventory wrong = new Inventory(g.items);
        wrong.setArmor(0, new Inventory.Stack(id("diamond", "boots"), 1));
        check(!wrong.fullArmor(g.items, 1), "черевики в слоті шолома - не комплект");
        // drain/clear/isEmpty
        check(!full.isEmpty(), "броня робить інвентар непорожнім");
        List<Inventory.Stack> dropped = full.drain();
        check(dropped.size() == 3 && full.isEmpty() && full.armor(0) == null, "drain забирає й броню");
        Inventory c = new Inventory(g.items); c.setArmor(0, new Inventory.Stack(id("iron", "helmet"), 1)); c.clear();
        check(c.armor(0) == null, "clear знімає броню");
    }

    static void protection() {
        Inventory inv = new Inventory(g.items);
        Random rng = new Random(1);
        for (int k = 0; k < 4; k++) inv.setArmor(k, new Inventory.Stack(id("diamond", KINDS[k]), 1));
        // знос: випадковий шматок, не менше 1
        int worn = 0;
        for (int i = 0; i < 200; i++) { inv.wearArmor(g.items, 3, rng); }
        for (int k = 0; k < 4; k++) if (inv.armor(k) != null) worn += inv.armor(k).damage;
        check(worn == 200, "кожен удар зношує на 1: " + worn);
        inv.wearArmor(g.items, 40, rng);
        int w2 = 0; for (int k = 0; k < 4; k++) if (inv.armor(k) != null) w2 += inv.armor(k).damage;
        check(w2 >= worn + 10 || inv.armorPoints(g.items) < 20, "сильний удар зношує на шкода/4");
        // поломка
        Inventory b = new Inventory(g.items);
        b.setArmor(3, new Inventory.Stack(id("leather", "boots"), 1, 64));
        b.wearArmor(g.items, 3, rng);
        check(b.armor(3) == null, "броня ламається при повному зносі");
        b.wearArmor(g.items, 3, rng);
        check(b.armorPoints(g.items) == 0, "без броні знос нічого не робить");
        // формула
        for (int pts = 0; pts <= 30; pts++) {
            double red = Math.min(0.8, pts * 0.04);
            check(red >= 0 && red <= 0.8 && (pts < 20 || red == 0.8), "межа захисту 80%: " + pts);
        }
    }

    static void menu() {
        Inventory inv = new Inventory(g.items);
        CraftMenu m = new CraftMenu(g.items, g.recipes, inv, 2);
        check(m.armorBase == CraftMenu.GRID + 4 && m.size() == 36 + 1 + 4 + 4, "слоти броні в інвентарі гравця: " + m.size());
        check(new CraftMenu(g.items, g.recipes, inv, 3).armorBase == -1, "на верстаку броні нема");
        inv.add(id("iron", "helmet"), 1);
        // у чужий слот не лягає
        m.click(0, 0, false); m.click(m.armorBase + 1, 0, false);
        check(inv.armor(1) == null && m.cursor() != null, "шолом не лягає в слот нагрудника");
        m.click(m.armorBase, 0, false);
        check(inv.armor(0) != null && m.cursor() == null, "шолом лягає у свій слот");
        m.click(m.armorBase, 0, false); m.click(0, 0, false);
        check(inv.armor(0) == null && inv.get(0) != null, "знімається назад курсором");
        // shift
        m.click(0, 0, true);
        check(inv.armor(0) != null && inv.get(0) == null, "shift надягає");
        m.click(m.armorBase, 0, true);
        check(inv.armor(0) == null && total(inv, id("iron", "helmet")) == 1, "shift знімає");
        // не броню не покласти, стопка 2 броні не лягає
        inv.set(5, new Inventory.Stack("voxelin:stone", 3));
        m.click(5, 0, false); m.click(m.armorBase, 0, false);
        check(inv.armor(0) == null && m.cursor() != null, "камінь не лягає в слот броні");
        m.click(5, 0, false);
        // закриття вікна не знімає броню
        inv.set(6, new Inventory.Stack(id("iron", "boots"), 1));
        m.click(6, 0, true);
        check(m.drainExtra().isEmpty() && inv.armor(3) != null, "закриття вікна лишає броню надягнутою");
        // доук
        Inventory.Stack s = new Inventory.Stack(id("iron", "boots"), 2);
        inv.set(7, s);
        m.click(7, 0, false); m.click(m.armorBase + 3, 0, false);
        check(inv.armor(3).count == 1, "стопка з двох не стає в слот броні цілою");
    }

    static int total(Inventory inv, String item) { int t = 0; for (int i = 0; i < 36; i++) if (inv.get(i) != null && inv.get(i).item.equals(item)) t += inv.get(i).count; return t; }

    static void persistence() throws Exception {
        Inventory inv = new Inventory(g.items);
        inv.setArmor(0, new Inventory.Stack(id("diamond", "helmet"), 1, 12));
        inv.setArmor(3, new Inventory.Stack(id("leather", "boots"), 1, 3));
        Inventory r = new Inventory(g.items);
        r.decodeArmor(inv.encodeArmor());
        check(r.armor(0).damage == 12 && r.armor(3).item.endsWith("leather_boots") && r.armor(1) == null, "encode/decode броні");
        String[] bad = {"voxelin:stone|1|0", "voxelin:diamond_boots|1|0", "garbage", "voxelin:iron_helmet|2|0"};
        Inventory b = new Inventory(g.items);
        b.decodeArmor(bad);
        check(b.armor(0) == null && b.armor(1) == null && b.armor(2) == null && b.armor(3) == null, "биті записи й предмети не для слота відкидаються");
        // через світ
        File root = Files.createTempDirectory("vx-armor").toFile();
        Game g2 = new Game(new File(root, "saves"), null);
        g2.createWorld("A", 1, WorldInfo.Type.FLAT);
        World w = g2.openWorld("A");
        Source p = Sources.player("P");
        w.inventory(p).setArmor(1, new Inventory.Stack(id("iron", "chestplate"), 1, 9));
        List<String> out = g2.commands.execute(p, w, null, "/give iron_boots");
        out = g2.commands.execute(p, w, null, "/wear");
        check(out.get(0).contains("надягнуто") && w.inventory(p).armor(3) != null, "/wear: " + out);
        check(g2.commands.execute(p, w, null, "/wear").get(0).contains("нема броні"), "/wear без броні в руці");
        w.close();
        World w2 = new Game(new File(root, "saves"), null).openWorld("A");
        Inventory ri = w2.inventory(Sources.player("P"));
        check(ri.armor(1) != null && ri.armor(1).damage == 9 && ri.armor(3) != null, "броня зберігається у світі");
        w2.close();
    }

    /** Гравець над рівною землею (y < 1 - тверде); wall - стіна x >= wallX. */
    static PlayerEntity flyer(double y, long wallX) {
        PlayerEntity p = new PlayerEntity((x, yy, z) -> yy < 1 || x >= wallX, Pos.ofBlock(0, y, 0));
        p.yaw = 90; // дивимось у +x
        return p;
    }

    static void start(PlayerEntity p) {
        p.tick(0.05, false, false, false, false, false, false, false);
        p.tick(0.05, false, false, false, false, true, false, false);
    }

    static void wings() {
        ItemType w = g.items.get(Inventory.WINGS);
        check(w != null && w.armorSlot == voxelin.api.ArmorSlot.CHEST && w.armorPoints == 0 && w.durability == 432, "крила: слот нагрудника, без захисту, міцність 432");
        Inventory inv = new Inventory(g.items);
        check(!inv.canGlide(g.items), "без крил не планує");
        inv.setArmor(1, new Inventory.Stack(Inventory.WINGS, 1));
        check(inv.canGlide(g.items) && !inv.fullArmor(g.items, 4), "у крилах планує, але це не повна алмазна броня (Енд не відчинить)");
        for (int k = 0; k < 1000; k++) inv.wearWings(g.items);
        check(inv.armor(1) != null && inv.armor(1).damage == 431 && !inv.canGlide(g.items), "зношені крила не ламаються, але не несуть");
        for (int k = 0; k < 50; k++) inv.wearArmor(g.items, 20, new Random(k));
        check(inv.armor(1) != null, "удари теж не ламають крил");

        PlayerEntity none = flyer(200, Long.MAX_VALUE);
        start(none);
        check(!none.gliding, "без крил стрибок у повітрі нічого не робить");
        PlayerEntity onGround = flyer(1 + PlayerEntity.EYE + 0.01, Long.MAX_VALUE);
        onGround.wings = true;
        for (int k = 0; k < 5; k++) onGround.tick(0.05, false, false, false, false, true, false, false);
        check(!onGround.gliding, "із землі крила не розкриваються");

        PlayerEntity p = flyer(240, Long.MAX_VALUE);
        p.wings = true;
        start(p);
        check(p.gliding, "стрибок у падінні розкриває крила");
        p.pitch = 25; // розгін (як у Minecraft: з місця крила спершу треба «набрати» швидкість)
        for (int t = 0; t < 150; t++) p.tick(0.05, false, false, false, false, false, false, false);
        p.pitch = 0;
        double y0 = p.pos.y, x0 = p.pos.relX(0);
        for (int t = 0; t < 100; t++) p.tick(0.05, false, false, false, false, false, false, false);
        double drop = y0 - p.pos.y, dist = p.pos.relX(0) - x0;
        check(p.gliding && dist > 4 * drop && dist > 50, "планування: далеко вперед, мало вниз: " + Math.round(dist) + " / " + Math.round(drop));
        p.pitch = 60;
        double v1 = 0;
        for (int t = 0; t < 60; t++) { double before = p.pos.blockX(); p.tick(0.05, false, false, false, false, false, false, false); v1 = p.pos.blockX() - before; }
        p.pitch = 0;
        check(v1 > 1.0, "пікірування розганяє: " + v1 + " блоків/тік");
        for (int t = 0; t < 5000 && p.gliding; t++) p.tick(0.05, false, false, false, false, false, false, false);
        check(!p.gliding && p.onGround(), "на землі крила складаються");
        check(p.takeLanded() < 4, "плавне приземлення без шкоди від падіння");

        PlayerEntity c2 = flyer(120, 300);
        c2.wings = true; start(c2); c2.pitch = 45;
        double imp = 0;
        for (int t = 0; t < 2000 && c2.gliding; t++) { c2.tick(0.05, false, false, false, false, false, false, false); imp += c2.takeImpact(); if (t == 40) c2.pitch = 0; }
        check(imp > 0, "удар об стіну на швидкості - шкода: " + imp);

        PlayerEntity off = flyer(200, Long.MAX_VALUE);
        off.wings = true; start(off);
        off.wings = false;
        off.tick(0.05, false, false, false, false, false, false, false);
        check(!off.gliding, "зняв крила - падаєш");
    }
}
