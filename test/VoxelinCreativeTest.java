import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Творчий інвентар: кожен предмет гри рівно в одній вкладці, розумні категорії, нескінченна палітра, кошик, прокрутка. */
public class VoxelinCreativeTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) {
        Game g = new Game(new File("nope"), null);
        Inventory inv = new Inventory();
        CreativeMenu m = new CreativeMenu(g.items, g.blocks, inv);
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (CreativeMenu.Tab t : CreativeMenu.Tab.values()) { check(!m.items(t).isEmpty(), "вкладка не порожня: " + t.shortName); for (String s : m.items(t)) { check(seen.add(s), "лише в одній вкладці: " + s); total++; } }
        check(total == g.items.all().size(), "усі предмети: " + total + " з " + g.items.all().size());
        String[][] expect = {{"oak_planks", "BUILD"}, {"bricks", "BUILD"}, {"oak_board", "BUILD"}, {"grass", "NATURE"}, {"oak_log", "NATURE"}, {"iron_ore", "ORES"}, {"iron_ingot", "ORES"}, {"stick", "ORES"},
                {"workbench", "USEFUL"}, {"chest", "USEFUL"}, {"torch", "USEFUL"}, {"diamond_pickaxe", "TOOLS"}, {"bow", "TOOLS"}, {"arrow", "TOOLS"}, {"iron_helmet", "ARMOR"},
                {"apple", "FOOD"}, {"steak", "FOOD"}, {"liminite_shard", "SEAM"}, {"cinder", "SEAM"}, {"obsidian", "SEAM"}, {"ember_crystal", "SEAM"}};
        for (String[] e : expect) check(m.items(CreativeMenu.Tab.valueOf(e[1])).contains("voxelin:" + e[0]), e[0] + " у вкладці " + e[1]);
        m.tab(CreativeMenu.Tab.FOOD);
        check(m.get(CreativeMenu.PALETTE) != null && g.items.get(m.get(CreativeMenu.PALETTE).item).food > 0, "палітра показує вкладку");
        String first = m.get(CreativeMenu.PALETTE).item;
        m.click(CreativeMenu.PALETTE, 0, false);
        check(m.cursor() != null && m.cursor().item.equals(first) && m.cursor().count == g.items.maxStack(first), "ліва - повна стопка");
        check(m.get(CreativeMenu.PALETTE) != null, "палітра нескінченна");
        m.click(3, 0, false);
        check(inv.get(3) != null && inv.get(3).item.equals(first) && m.cursor() == null, "поклали в інвентар");
        m.click(CreativeMenu.PALETTE + 1, 1, false);
        check(m.cursor() != null && m.cursor().count == 1, "права - один");
        m.click(CreativeMenu.PALETTE + 5, 0, false);
        check(m.cursor() == null, "клік по палітрі з курсором - кошик");
        m.click(CreativeMenu.PALETTE + 2, 0, true);
        check(inv.get(0) != null && inv.get(0).item.equals(m.get(CreativeMenu.PALETTE + 2).item), "shift - одразу в інвентар");
        m.swapHotbar(CreativeMenu.PALETTE, 8);
        check(inv.get(8) != null && inv.get(8).item.equals(first), "1-9 над палітрою - у хотбар");
        m.tab(CreativeMenu.Tab.BUILD);
        int before = m.scroll();
        m.scrollBy(100);
        check(m.scroll() == m.maxScroll() && m.scroll() >= before, "прокрутка до кінця");
        m.scrollBy(-100);
        check(m.scroll() == 0, "і назад");
        System.out.println("VoxelinCreativeTest: " + n + " перевірок OK");
    }
}
