import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.util.*;

/** Крафт: співставлення форм, безформні, мітки, ремонт, меню з сіткою, перелік рецептів без неоднозначностей. */
public class VoxelinCraftTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static Inventory.Stack s(String item) { return new Inventory.Stack("voxelin:" + item, 1); }

    static Inventory.Stack[] grid(int size, String... rows) { // кожен рядок - ширина size, комірки через кому, '-' порожньо
        Inventory.Stack[] a = new Inventory.Stack[size * size];
        for (int y = 0; y < rows.length; y++) { String[] c = rows[y].split(","); for (int x = 0; x < c.length; x++) if (!c[x].equals("-")) a[x + y * size] = s(c[x]); }
        return a;
    }

    static Inventory.Stack craft(Inventory.Stack[] gr, int size) { return Crafting.match(g.recipes, g.items, gr, size); }

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        recipes(); matching(); repair(); menu(); validation(); ambiguity();
        System.out.println("VoxelinCraftTest: " + n + " перевірок OK");
    }

    static void recipes() {
        check(g.recipes.all().size() >= 19, "рецептів: " + g.recipes.all().size());
        check(g.items.inTag("planks", "voxelin:oak_planks") && g.items.inTag("logs", "voxelin:spruce_log") && !g.items.inTag("planks", "voxelin:oak_log"), "мітки");
        check(g.blocks.byName("voxelin:workbench").interact.equals("workbench") && g.items.get("voxelin:workbench") != null, "верстак - блок з вікном і предметом");
    }

    static void matching() {
        Inventory.Stack r = craft(grid(2, "oak_log,-", "-,-"), 2);
        check(r != null && r.item.equals("voxelin:oak_planks") && r.count == 3, "колода -> 3 дошки (у будь-якій клітинці)");
        check(craft(grid(2, "-,-", "-,oak_log"), 2).item.equals("voxelin:oak_planks"), "безформний у куті");
        check(craft(grid(3, "-,-,-", "-,birch_log,-", "-,-,-"), 3).item.equals("voxelin:birch_planks"), "береза -> березові дошки на 3x3 по центру");
        check(craft(grid(2, "spruce_planks,-", "-,-"), 2).item.equals("voxelin:stick") && craft(grid(2, "spruce_planks,-", "-,-"), 2).count == 2, "дошка -> 2 палиці (будь-яка мітка #planks)");
        check(craft(grid(2, "oak_planks,oak_planks", "oak_planks,oak_planks"), 2).item.equals("voxelin:workbench"), "верстак з 4 дощок");
        check(craft(grid(2, "oak_planks,birch_planks", "spruce_planks,oak_planks"), 2).item.equals("voxelin:workbench"), "верстак із різних дощок (мітка)");
        check(craft(grid(2, "oak_planks,oak_planks", "oak_planks,-"), 2) == null, "3 дошки - не верстак");
        check(craft(grid(2, "stone,stone", "stone,stone"), 2).count == 4, "камінь 2x2 -> 4 цегли");
        // кирка в різних положеннях і дзеркально
        check(craft(grid(3, "oak_planks,oak_planks,-", "-,stick,-", "-,stick,-"), 3).item.equals("voxelin:wooden_pickaxe"), "дерев'яна кирка");
        check(craft(grid(3, "-,cobblestone,cobblestone", "-,-,stick", "-,-,stick"), 3).item.equals("voxelin:stone_pickaxe"), "кам'яна кирка зі зсувом праворуч");
        check(craft(grid(3, "cobblestone,cobblestone,-", "stick,-,-", "stick,-,-"), 3).item.equals("voxelin:stone_pickaxe"), "дзеркальна кирка");
        check(craft(grid(3, "-,-,-", "iron_ingot,iron_ingot,-", "-,stick,-"), 3) == null, "кирка не по формі - нічого");
        check(craft(grid(3, "diamond,diamond,-", "-,stick,-", "-,stick,-"), 3).item.equals("voxelin:diamond_pickaxe"), "алмазна кирка");
        check(craft(grid(3, "iron_ingot,-,-", "stick,-,-", "stick,-,-"), 3).item.equals("voxelin:iron_shovel"), "залізна лопата");
        check(craft(grid(3, "iron_ingot,-,-", "iron_ingot,stick,-", "-,stick,-"), 3).item.equals("voxelin:iron_axe") && craft(grid(3, "-,iron_ingot,-", "stick,iron_ingot,-", "stick,-,-"), 3).item.equals("voxelin:iron_axe"), "сокира і її відбиття");
        check(craft(grid(3, "-,glass,-", "glass,coal,glass", "-,glass,-"), 3).item.equals("voxelin:lamp") && craft(grid(3, "-,glass,-", "glass,coal,glass", "-,glass,-"), 3).count == 2, "лампа хрестом");
        check(craft(grid(3, "-,glass,-", "glass,coal,glass", "-,-,-"), 3) == null, "лампа без нижнього скла - нічого");
        // порожньо, чужі предмети
        check(craft(new Inventory.Stack[9], 3) == null && craft(new Inventory.Stack[4], 2) == null, "порожня сітка");
        check(craft(grid(2, "dirt,-", "-,-"), 2) == null && craft(grid(2, "dirt,stone", "-,-"), 2) == null, "несумісне");
        // форма 2x3 (кирка) не влізає в сітку 2x2
        check(craft(grid(2, "oak_planks,oak_planks", "-,stick"), 2) == null, "кирка не крафтиться в 2x2");
        // розмір по обох осях: 1x3 і 3x1
        check(craft(grid(3, "iron_ingot,-,-", "stick,-,-", "stick,-,-"), 3) != null && craft(grid(3, "-,-,-", "iron_ingot,stick,stick", "-,-,-"), 3) == null, "лопата вертикальна, горизонтальна - ні");
    }

    static void repair() {
        Inventory.Stack[] gr = new Inventory.Stack[4];
        gr[0] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 200); gr[3] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 100);
        Inventory.Stack r = craft(gr, 2);
        int remain = (251 - 200) + (251 - 100) + 25;
        check(r != null && r.item.equals("voxelin:iron_pickaxe") && r.damage == 251 - remain, "ремонт: сума залишку +10%: " + (r == null ? "null" : r.damage));
        gr[0] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 10); gr[3] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 20);
        check(craft(gr, 2).damage == 0, "ремонт не перевищує повну міцність");
        gr[0] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 0); gr[3] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 0);
        check(craft(gr, 2) == null, "цілі інструменти не «ремонтуються»");
        gr[0] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 5); gr[3] = new Inventory.Stack("voxelin:stone_pickaxe", 1, 5);
        check(craft(gr, 2) == null, "різні інструменти не ремонтуються");
        gr[3] = null; gr[1] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 5); gr[2] = new Inventory.Stack("voxelin:iron_pickaxe", 1, 5);
        check(craft(gr, 2) == null, "три інструменти - нічого");
    }

    static void menu() {
        Inventory inv = new Inventory(g.items);
        CraftMenu m = new CraftMenu(g.items, g.recipes, inv, 2);
        inv.set(0, new Inventory.Stack("voxelin:oak_log", 5));
        m.click(0, 0, false);
        m.click(CraftMenu.GRID, 1, false); // один у клітинку
        check(m.result() != null && m.result().item.equals("voxelin:oak_planks") && m.result().count == 3, "результат з'являється при розкладанні");
        check(m.get(CraftMenu.RESULT) != null && m.takeOnly(CraftMenu.RESULT), "результат - лише брати");
        m.click(CraftMenu.RESULT, 0, false); // беремо в порожній курсор? курсор тримає колоди - не можна
        check(m.cursor().item.equals("voxelin:oak_log") && m.gridStack(0) != null, "курсор зайнятий іншим - результат не береться");
        m.click(1, 0, false); // кладемо колоди назад в слот 1
        m.click(CraftMenu.RESULT, 0, false);
        check(m.cursor() != null && m.cursor().item.equals("voxelin:oak_planks") && m.cursor().count == 3 && m.gridStack(0) == null && m.result() == null, "взяли результат: інгредієнт витрачено, результат зник");
        m.click(2, 0, false);
        check(inv.get(2).count == 3, "дошки лягли в інвентар");
        // shift по результату: крафт максимум
        m.click(1, 0, false); // колоди (4 шт) у курсор
        m.click(CraftMenu.GRID, 0, false);
        check(m.gridStack(0).count == 4 && m.result() != null, "4 колоди в клітинці");
        m.click(CraftMenu.RESULT, 0, true);
        check(m.gridStack(0) == null && total(inv, "voxelin:oak_planks") == 3 + 12, "shift по результату крафтить усе: " + total(inv, "voxelin:oak_planks"));
        // повний інвентар: shift не крафтить
        Inventory full = new Inventory(g.items);
        for (int i = 0; i < 36; i++) full.set(i, new Inventory.Stack("voxelin:dirt", 64));
        CraftMenu mf = new CraftMenu(g.items, g.recipes, full, 2);
        full.set(0, new Inventory.Stack("voxelin:oak_log", 1)); mf.click(0, 0, false); mf.click(CraftMenu.GRID, 0, false); full.set(0, new Inventory.Stack("voxelin:dirt", 64));
        mf.click(CraftMenu.RESULT, 0, true);
        check(mf.gridStack(0) != null && mf.result() != null, "повний інвентар - крафт не з'їдає інгредієнти");
        // закриття віддає сітку
        List<Inventory.Stack> back = mf.drainExtra();
        check(back.size() == 1 && back.get(0).item.equals("voxelin:oak_log") && mf.gridStack(0) == null, "drainExtra віддає вміст сітки");
        mf.refresh();
        check(mf.result() == null, "після звільнення сітки результату нема");
        // верстак 3x3
        CraftMenu b = new CraftMenu(g.items, g.recipes, inv, 3);
        check(b.size() == 36 + 1 + 9, "верстак: слотів " + b.size());
        inv.set(5, new Inventory.Stack("voxelin:stick", 2)); inv.set(6, new Inventory.Stack("voxelin:oak_planks", 2));
        b.click(6, 0, false); b.click(CraftMenu.GRID, 1, false); b.click(CraftMenu.GRID + 1, 1, false);
        b.click(5, 0, false); b.click(CraftMenu.GRID + 4, 1, false); b.click(CraftMenu.GRID + 7, 1, false);
        check(b.result() != null && b.result().item.equals("voxelin:wooden_pickaxe"), "кирка на верстаку: " + (b.result() == null ? "null" : b.result().item));
        // розділена стопка-інгредієнт: крафт витрачає по одному, лишок лишається
        b.click(CraftMenu.GRID + 4, 0, false);
        check(b.cursor() != null, "забрали з клітинки");
    }

    static int total(Inventory inv, String item) { int t = 0; for (int i = 0; i < 36; i++) if (inv.get(i) != null && inv.get(i).item.equals(item)) t += inv.get(i).count; return t; }

    static void validation() {
        boolean threw = false;
        try { RecipeSettings.shaped("MMMM"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "форма завширшки 4 відхиляється");
        threw = false; try { RecipeSettings.shaped("MM", "M"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "нерівні рядки відхиляються");
        threw = false; try { RecipeSettings.shapeless(); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "порожній безформний відхиляється");
        RecipeRegistry reg = new RecipeRegistry();
        threw = false; try { reg.add("x:a", RecipeSettings.shaped("MX").key('M', "a").result("b", 1)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "нема ключа для символу");
        threw = false; try { reg.add("x:a", RecipeSettings.shaped("..").result("b", 1)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "порожня форма відхиляється");
        threw = false; try { reg.add("x:a", RecipeSettings.shapeless("a")); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "рецепт без результату відхиляється");
        reg.add("x:ok", RecipeSettings.shapeless("x:a").result("x:b", 2));
        threw = false; try { reg.add("x:ok", RecipeSettings.shapeless("x:a").result("x:b", 2)); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "повтор id");
        ItemRegistry items = new ItemRegistry();
        items.register("x:a", new ItemSettings());
        reg.add("x:ghost", RecipeSettings.shapeless("x:missing").result("x:a", 1));
        reg.add("x:tagless", RecipeSettings.shapeless("#nope").result("x:a", 1));
        reg.freeze(items);
        check(reg.all().size() == 0, "рецепти з невідомими предметами/мітками відкидаються: " + reg.all().size()); // x:b теж невідомий
        threw = false; try { reg.add("x:late", RecipeSettings.shapeless("x:a").result("x:a", 1)); } catch (IllegalStateException e) { threw = true; }
        check(threw, "реєстрація після закриття заборонена");
        // кількість результату обмежена
        check(RecipeSettings.shapeless("a").result("b", 999).resultCount() == 64 && RecipeSettings.shapeless("a").result("b", -5).resultCount() == 1, "к-сть результату 1..64");
    }

    /** Для кожного рецепта будуємо канонічну сітку й перевіряємо, що вона підходить РІВНО до нього (нема двозначності між рецептами). */
    static void ambiguity() {
        for (RecipeRegistry.Recipe r : g.recipes.all()) {
            int size = r.shaped ? Math.max(r.w, r.h) : 3;
            if (size < 2) size = 2;
            Inventory.Stack[] gr = new Inventory.Stack[size * size];
            if (r.shaped) { int k = 0; for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) { String ing = null; try { var f = r.getClass().getDeclaredField("cells"); f.setAccessible(true); ing = ((String[]) f.get(r))[x + y * r.w]; } catch (Exception e) { check(false, "рефлексія"); } if (ing != null) gr[x + y * size] = new Inventory.Stack(canon(ing), 1); } }
            else { int i = 0; for (String ing : r.allIngredients()) gr[i++] = new Inventory.Stack(canon(ing), 1); }
            List<RecipeRegistry.Recipe> hit = Crafting.matchAll(g.recipes, g.items, gr, size);
            check(hit.size() == 1 && hit.get(0) == r, "рецепт " + r.id + " однозначний: підходять " + hit.size());
            Inventory.Stack res = Crafting.match(g.recipes, g.items, gr, size);
            check(res.item.equals(r.result) && res.count == r.count, "результат " + r.id);
        }
    }

    static String canon(String ing) { return ing.startsWith("#") ? g.items.tagMembers(ing.substring(1)).iterator().next() : ing; }
}
