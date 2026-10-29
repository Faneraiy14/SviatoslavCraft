package voxelin.core;

import java.util.*;

/**
 * Співставлення сітки крафту з рецептами (чисті функції). Форма шукається де завгодно в сітці (порожні краї обрізаються) і, якщо дозволено,
 * у дзеркальному відбитті; безформні - в будь-якому порядку. Кожен крафт витрачає по одному предмету з кожної зайнятої клітинки.
 * Окремо: ремонт - два однакових зношених інструменти дають один зі сумою міцності +10%; Крила Шву (вони одні на світ) лагодяться
 * уламками лімініту: кожна клітинка з уламком повертає чверть міцності.
 */
public final class Crafting {
    private Crafting() {}

    public static boolean ingredientMatches(ItemRegistry items, String ing, String item) {
        return ing.startsWith("#") ? items.inTag(ing.substring(1), item) : ing.equals(item);
    }

    /** Результат крафту і скільки взяти з кожної клітинки сітки (use[i] - для grid[i]). */
    public record Match(Inventory.Stack result, int[] use) {}

    /** Результат крафту для сітки n x n (grid - n*n стеків, null - порожньо), або null. */
    public static Inventory.Stack match(RecipeRegistry recipes, ItemRegistry items, Inventory.Stack[] grid, int n) {
        Match m = matchFull(recipes, items, grid, n);
        return m == null ? null : m.result;
    }

    public static Match matchFull(RecipeRegistry recipes, ItemRegistry items, Inventory.Stack[] grid, int n) {
        List<RecipeRegistry.Recipe> all = matchAll(recipes, items, grid, n);
        int[] use = new int[grid.length];
        if (!all.isEmpty()) {
            RecipeRegistry.Recipe r = all.get(0);
            if (r.shaped) {
                int minX = n, minY = n;
                for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (grid[x + y * n] != null) { minX = Math.min(minX, x); minY = Math.min(minY, y); }
                boolean mirror = !fitsGrid(items, r, grid, n, minX, minY, false);
                for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) use[(minX + x) + (minY + y) * n] = r.need(mirror ? r.w - 1 - x : x, y);
            } else for (int i = 0; i < grid.length; i++) use[i] = grid[i] == null ? 0 : 1;
            return new Match(new Inventory.Stack(r.result, r.count), use);
        }
        Inventory.Stack w = repairWings(items, grid);
        if (w == null) w = repair(items, grid);
        if (w == null) return null;
        for (int i = 0; i < grid.length; i++) use[i] = grid[i] == null ? 0 : 1;
        return new Match(w, use);
    }

    private static boolean fitsGrid(ItemRegistry items, RecipeRegistry.Recipe r, Inventory.Stack[] grid, int n, int minX, int minY, boolean mirror) {
        for (int y = 0; y < r.h; y++) for (int x = 0; x < r.w; x++) {
            Inventory.Stack s = grid[(minX + x) + (minY + y) * n];
            String ing = r.cell(mirror ? r.w - 1 - x : x, y);
            if ((ing == null) != (s == null)) return false;
            if (ing != null && (!ingredientMatches(items, ing, s.item) || s.count < r.need(mirror ? r.w - 1 - x : x, y))) return false;
        }
        return true;
    }

    /** Усі рецепти, що підходять до сітки (для перевірки на неоднозначність). */
    public static List<RecipeRegistry.Recipe> matchAll(RecipeRegistry recipes, ItemRegistry items, Inventory.Stack[] grid, int n) {
        List<RecipeRegistry.Recipe> out = new ArrayList<>();
        int minX = n, minY = n, maxX = -1, maxY = -1, filled = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (grid[x + y * n] != null) { filled++; minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        if (filled == 0) return out;
        int gw = maxX - minX + 1, gh = maxY - minY + 1;
        String[] cells = new String[gw * gh];
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) { Inventory.Stack s = grid[(minX + x) + (minY + y) * n]; cells[x + y * gw] = s == null ? null : s.item; }
        for (RecipeRegistry.Recipe r : recipes.all()) {
            if (r.shaped) {
                if (r.w != gw || r.h != gh) continue;
                if (fitsGrid(items, r, grid, n, minX, minY, false) || (r.mirror && fitsGrid(items, r, grid, n, minX, minY, true))) out.add(r);
            } else if (r.ingredients.size() == filled) {
                List<String> have = new ArrayList<>();
                for (String c : cells) if (c != null) have.add(c);
                if (assign(items, r.ingredients, have, 0, new boolean[have.size()])) out.add(r);
            }
        }
        return out;
    }

    private static boolean assign(ItemRegistry items, List<String> ing, List<String> have, int i, boolean[] used) {
        if (i == ing.size()) return true;
        for (int j = 0; j < have.size(); j++) {
            if (used[j] || !ingredientMatches(items, ing.get(i), have.get(j))) continue;
            used[j] = true;
            if (assign(items, ing, have, i + 1, used)) return true;
            used[j] = false;
        }
        return false;
    }

    /** Крила Шву + 1..4 клітинки з уламком лімініту -> крила, кожен уламок повертає чверть міцності (з кожної клітинки береться один). */
    static Inventory.Stack repairWings(ItemRegistry items, Inventory.Stack[] grid) {
        Inventory.Stack wings = null; int shards = 0;
        for (Inventory.Stack s : grid) {
            if (s == null) continue;
            if (s.item.equals(Inventory.WINGS) && wings == null && s.count == 1) wings = s;
            else if (s.item.equals(Infection.SHARD)) shards++;
            else return null;
        }
        ItemType t = wings == null ? null : items.get(wings.item);
        if (t == null || t.durability <= 0 || shards == 0 || shards > 4 || wings.damage == 0) return null;
        return new Inventory.Stack(wings.item, 1, Math.max(0, wings.damage - shards * (t.durability / 4)));
    }

    /** Ремонт: рівно два однакових інструменти (хоч один зношений) -> один зі сумою залишку міцності +10% (не більше повної). */
    static Inventory.Stack repair(ItemRegistry items, Inventory.Stack[] grid) {
        Inventory.Stack a = null, b = null;
        for (Inventory.Stack s : grid) { if (s == null) continue; if (a == null) a = s; else if (b == null) b = s; else return null; }
        if (a == null || b == null || !a.item.equals(b.item) || a.count != 1 || b.count != 1) return null;
        ItemType t = items.get(a.item);
        if (t == null || t.durability <= 0 || (a.damage == 0 && b.damage == 0)) return null;
        int remain = (t.durability - a.damage) + (t.durability - b.damage) + t.durability / 10;
        return new Inventory.Stack(a.item, 1, Math.max(0, t.durability - Math.min(t.durability, remain)));
    }
}
