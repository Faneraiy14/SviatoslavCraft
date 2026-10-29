import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** «Шлях»: цілі, порядок, гілки, збереження; і чи взагалі можна пройти гру з нуля (досяжність предметів фіналу). */
public class VoxelinProgressTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-progress").toFile();
        g = new Game(new File(root, "saves"), null);
        goals(); order(); branches(); persistence(); reachable(); gateCompass();
        System.out.println("VoxelinProgressTest: " + n + " перевірок OK");
    }

    static World world(String name) throws Exception {
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        return w;
    }

    static List<String> ids(List<Progress.Goal> l) { List<String> r = new ArrayList<>(); for (Progress.Goal x : l) r.add(x.id()); return r; }

    static void goals() {
        Set<String> seen = new HashSet<>();
        for (Progress.Goal x : Progress.all()) {
            check(seen.add(x.id()), "ціль унікальна: " + x.id());
            check(x.id().matches("[a-z0-9_]+"), "id без коми й двокрапки (зберігається списком): " + x.id());
            check(!x.title().isBlank() && x.hint().length() > 15, "назва й підказка: " + x.id());
        }
        check(seen.contains(Progress.RITUAL_END) && seen.contains(Progress.INFECTION_END), "обидві фінальні цілі є");
    }

    static void order() throws Exception {
        World w = world("p1");
        Inventory inv = new Inventory();
        check(Progress.current(w.info).id().equals("wood"), "перша ціль - дерево");
        check(Progress.update(w, inv).isEmpty(), "порожній інвентар нічого не закриває");
        inv.add("voxelin:oak_log", 3);
        check(ids(Progress.update(w, inv)).equals(List.of("wood")), "колода закриває «дерево»");
        check(Progress.current(w.info).id().equals("workbench"), "далі верстак");
        inv.clear();
        check(Progress.update(w, inv).isEmpty() && w.info.goals.contains("wood"), "витрачене дерево не відкочує ціль");
        inv.add("voxelin:diamond", 1); // алмаз зі скрині - без кирок
        List<String> f = ids(Progress.update(w, inv));
        check(f.equals(List.of("workbench", "pickaxe", "stone", "iron", "iron_pickaxe", "diamond")), "пізніша ціль закриває ранніші по порядку: " + f);
        check(Progress.current(w.info).id().equals("diamond_pickaxe"), "далі алмазна кирка");
        inv.clear();
        inv.add("voxelin:end_eye", 11);
        Progress.update(w, inv);
        check(!w.info.goals.contains("eyes"), "11 очей - замало");
        inv.add("voxelin:end_eye", 1);
        Progress.update(w, inv);
        check(w.info.goals.contains("eyes") && w.info.goals.contains("armor"), "12 очей закривають і броню перед ними");
        w.info.records.add("voxelin:gate");
        Progress.update(w, inv);
        check(Progress.current(w.info).id().equals("end"), "Брама відчинена - далі в Край");
        check(!Progress.finished(w.info), "ще не пройдено");
        w.close();
    }

    static void branches() throws Exception {
        World w = world("p2");
        Inventory inv = new Inventory();
        List<String> before = ids(Progress.path(w.info));
        check(!before.contains("anchors") && !before.contains("egg") && before.get(before.size() - 1).equals("choice"), "до вибору гілки не показуються: " + before);
        w.info.dragonDead = true;
        Progress.update(w, inv);
        check(Progress.current(w.info).id().equals("choice"), "дракон упав - поговорити зі Сторожем");
        w.info.eggChoice = WorldInfo.EggChoice.RITUAL;
        Progress.update(w, inv);
        List<String> r = ids(Progress.path(w.info));
        check(r.contains("ritual_end") && !r.contains("egg"), "Ритуал: лише його цілі");
        check(Progress.current(w.info).id().equals("anchors"), "далі якорі");
        inv.add(Keeper.ANCHOR, 3);
        Progress.update(w, inv);
        check(Progress.current(w.info).id().equals("ritual"), "три якорі є - віддати");
        w.info.ritualStage = 2;
        Progress.update(w, inv);
        check(Progress.finished(w.info) && Progress.current(w.info) == null, "Ритуал звершено - гру пройдено");
        w.close();

        World z = world("p3");
        z.info.dragonDead = true; z.info.eggChoice = WorldInfo.EggChoice.INFECTION;
        Progress.update(z, inv);
        check(Progress.current(z.info).id().equals("egg"), "Зараження: поставити яйце");
        z.info.infState = WorldInfo.InfState.ACTIVE;
        Progress.update(z, inv);
        check(Progress.current(z.info).id().equals(Progress.INFECTION_END) && !Progress.finished(z.info), "активне зараження - ще не кінець");
        for (WorldInfo.InfState s : new WorldInfo.InfState[]{WorldInfo.InfState.BURNED}) { z.info.infState = s; Progress.update(z, inv); }
        check(Progress.finished(z.info), "спалене ядро - гру пройдено");
        z.close();
    }

    static void persistence() throws Exception {
        World w = world("p4");
        Inventory inv = new Inventory();
        inv.add("voxelin:iron_ingot", 1);
        Progress.update(w, inv);
        w.info.creditsShown = true;
        File dir = g.worldDir("p4");
        w.info.save(dir);
        w.close();
        WorldInfo i = WorldInfo.load(dir);
        check(i.goals.contains("iron") && i.goals.contains("wood") && i.creditsShown, "цілі й титри зберігаються");
        check(Progress.current(i).id().equals("iron_pickaxe"), "після завантаження - та сама наступна ціль");
        WorldInfo end = new WorldInfo(); end.type = WorldInfo.Type.END;
        end.copyPlayerFrom(i);
        check(end.goals.contains("iron") && end.creditsShown, "цілі переходять у інший вимір");
    }

    /** Око Енду в повітря кличе World.locate("seam_gate"): Брама має знаходитись із будь-якого місця звичайного світу. */
    static void gateCompass() throws Exception {
        g.createWorld("p5", 12345, WorldInfo.Type.NORMAL);
        World w = g.openWorld("p5");
        for (long[] at : new long[][]{{0, 0}, {5000, -3000}, {-123456, 98765}}) {
            long[] r = w.locate("seam_gate", at[0], at[1]);
            check(r != null && r[3] < 3000, "Брама поблизу (" + at[0] + "," + at[1] + "): " + (r == null ? "нема" : r[3] + " блоків"));
        }
        w.close();
    }

    // ------------------------------------------------------------------ чи можна пройти гру з нуля

    /** Блоки, які гравець знаходить у звичайному світі (генератор, структури). */
    static final String[] OVERWORLD = {"oak_log", "birch_log", "spruce_log", "dirt", "grass", "sand", "gravel", "stone", "deep_stone", "sandstone",
            "coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore", "liminite_ore"};

    static int toolTier(Set<String> have, ToolKind kind) {
        int t = 0;
        for (String it : have) { ItemType x = g.items.get(it); if (x != null && x.tool == kind) t = Math.max(t, x.tier); }
        return t;
    }

    static boolean has(Set<String> have, String ing) {
        if (!ing.startsWith("#")) return have.contains(ing);
        for (String it : have) if (g.items.inTag(ing.substring(1), it)) return true;
        return false;
    }

    static void mine(Set<String> have, String block) {
        BlockType b = g.blocks.byName(block.contains(":") ? block : "voxelin:" + block);
        check(b != null, "блок існує: " + block);
        if (b.minTier > 0 && toolTier(have, b.tool) < b.minTier) return;
        for (BlockSettings.Drop d : b.drops) if (d.max > 0 && d.chance > 0) have.add(d.item.contains(":") ? d.item : "voxelin:" + d.item);
    }

    static void mobs(Set<String> have, String dim) {
        for (MobType m : g.mobTypes.all()) {
            if (!m.dimension.equals(dim) || m.invulnerable || m.boss) continue;
            for (BlockSettings.Drop d : m.drops) if (d.max > 0 && d.chance > 0) have.add(d.item.contains(":") ? d.item : "voxelin:" + d.item);
        }
    }

    /** Замикання: що здобувається з порожніх рук без скринь (скрині - бонус, на них не покладаємось). */
    static Set<String> obtainable() {
        Set<String> have = new HashSet<>();
        for (int round = 0; round < 60; round++) {
            int before = have.size();
            for (String b : OVERWORLD) mine(have, b);
            mobs(have, "overworld");
            boolean end = have.contains("voxelin:end_eye") && have.containsAll(List.of("voxelin:diamond_helmet", "voxelin:diamond_chestplate", "voxelin:diamond_leggings", "voxelin:diamond_boots"));
            if (end) { // боси не мають виміру в даних - їх створює сюжет у Краї
                mine(have, "end_stone"); mine(have, "obsidian"); mobs(have, "end");
                for (String boss : List.of("ender_dragon", "seam_weaver", "seam_unraveler")) for (BlockSettings.Drop d : g.mobTypes.get("voxelin:" + boss).drops) have.add("voxelin:" + d.item.replace("voxelin:", ""));
            }
            if (have.contains("voxelin:threshold_key")) { mine(have, "threshold_bloom"); mobs(have, "between"); }
            boolean bench = have.contains("voxelin:workbench"), furnace = have.contains("voxelin:furnace");
            for (RecipeRegistry.Recipe r : g.recipes.all()) {
                if ((r.w > 2 || r.h > 2) && !bench) continue;
                boolean ok = true;
                for (String ing : r.allIngredients()) if (!has(have, ing)) { ok = false; break; }
                if (ok) have.add(r.result);
            }
            if (furnace && g.recipes.fuelTicks("voxelin:coal") > 0 && have.contains("voxelin:coal"))
                for (RecipeRegistry.Smelt m : g.recipes.smelts()) if (has(have, m.input)) have.add(m.result);
            if (have.size() == before) break;
        }
        return have;
    }

    static void reachable() {
        Set<String> have = obtainable();
        for (String need : List.of("workbench", "furnace", "stone_pickaxe", "iron_ingot", "iron_pickaxe", "diamond", "diamond_pickaxe", "liminite_shard",
                "diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots", "end_eye", "gold_ingot", "obsidian",
                "seam_anchor", "threshold_key", "threshold_bloom", "seam_cure", "seam_fire", "dragon_egg"))
            check(have.contains("voxelin:" + need), "з нуля можна здобути " + need);
    }
}
