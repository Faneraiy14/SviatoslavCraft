package voxelin.core;

import voxelin.api.RecipeSettings;

import java.util.*;

/** Реєстр рецептів крафту (дані). Після завантаження модів перевіряється: рецепти з невідомими предметами/мітками відкидаються з попередженням. */
public final class RecipeRegistry {
    public static final class Recipe {
        public final String id, result;
        public final int count, w, h;
        public final boolean shaped, mirror;
        /** Форма: w*h клітинок (null - порожня). */
        final String[] cells;
        /** Скільки предметів потрібно в кожній клітинці форми (паралельно cells). */
        final int[] need;
        final List<String> ingredients;

        Recipe(String id, RecipeSettings s) {
            this.id = id; result = s.resultItem(); count = s.resultCount(); shaped = s.isShaped(); mirror = s.mirrorAllowed();
            if (shaped) {
                String[] rows = s.rows();
                h = rows.length; w = rows[0].length(); cells = new String[w * h]; need = new int[w * h]; ingredients = null;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    char c = rows[y].charAt(x);
                    if (c == '.' || c == ' ') continue;
                    String ing = s.keys().get(c);
                    if (ing == null) throw new IllegalArgumentException("рецепт " + id + ": нема ключа для '" + c + "'");
                    cells[x + y * w] = ing; need[x + y * w] = s.perCell(c);
                }
                boolean any = false; for (String c : cells) if (c != null) any = true;
                if (!any) throw new IllegalArgumentException("рецепт " + id + ": порожня форма");
            } else { w = h = 0; cells = null; need = null; ingredients = List.copyOf(s.ingredients()); }
            if (result == null) throw new IllegalArgumentException("рецепт " + id + ": не задано результат");
        }

        /** Усі інгредієнти з повторами (клітинка з потребою 2 дає два записи). */
        public List<String> allIngredients() {
            List<String> r = new ArrayList<>();
            if (shaped) { for (int i = 0; i < cells.length; i++) if (cells[i] != null) for (int k = 0; k < need[i]; k++) r.add(cells[i]); } else r.addAll(ingredients);
            return r;
        }

        /** Інгредієнт у клітинці форми (x, y) або null. */
        public String cell(int x, int y) { return shaped ? cells[x + y * w] : null; }
        /** Скільки предметів потрібно в клітинці форми (x, y). */
        public int need(int x, int y) { return shaped && cells[x + y * w] != null ? need[x + y * w] : 0; }
    }

    /** Плавка: вхід (предмет або #мітка) -> результат за seconds секунд. */
    public static final class Smelt {
        public final String id, input, result; public final int count; public final int ticks;
        Smelt(String id, RecipeSettings s) {
            this.id = id; input = s.smeltInputItem(); result = s.resultItem(); count = s.resultCount(); ticks = Math.max(10, Math.round(s.smeltSeconds() * 20));
            if (result == null) throw new IllegalArgumentException("плавка " + id + ": не задано результат");
        }
    }

    private final List<Smelt> smelts = new ArrayList<>();
    private final Map<String, Integer> fuels = new LinkedHashMap<>(); // предмет -> тіків горіння
    private ItemRegistry itemsRef;
    private final List<Recipe> list = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private boolean frozen;

    public synchronized void add(String id, RecipeSettings s) {
        if (frozen) throw new IllegalStateException("реєстр рецептів уже закритий: " + id);
        if (!id.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва рецепта: " + id);
        if (!ids.add(id)) throw new IllegalArgumentException("рецепт уже є: " + id);
        if (s.isSmelting()) smelts.add(new Smelt(id, s)); else list.add(new Recipe(id, s));
    }

    /** Закрити: відкинути рецепти з невідомими предметами/мітками (зникли разом із модом) - гра від цього не ламається. */
    public synchronized void freeze(ItemRegistry items) {
        for (Iterator<Recipe> it = list.iterator(); it.hasNext(); ) {
            Recipe r = it.next();
            String bad = null;
            if (items.get(r.result) == null) bad = r.result;
            for (String ing : r.allIngredients()) {
                if (ing.startsWith("#") ? !items.hasTag(ing.substring(1)) : items.get(ing) == null) { bad = ing; break; }
            }
            if (bad != null) { System.out.println("[Voxelin] ⚠️ рецепт " + r.id + " пропущено: невідомо «" + bad + "»"); it.remove(); }
        }
        for (Iterator<Smelt> it = smelts.iterator(); it.hasNext(); ) {
            Smelt m = it.next();
            if (items.get(m.result) == null || (m.input.startsWith("#") ? !items.hasTag(m.input.substring(1)) : items.get(m.input) == null)) { System.out.println("[Voxelin] ⚠️ плавку " + m.id + " пропущено: невідомі предмети"); it.remove(); }
        }
        for (Iterator<String> it = fuels.keySet().iterator(); it.hasNext(); ) { String f = it.next(); if (items.get(f) == null) { System.out.println("[Voxelin] ⚠️ паливо " + f + " пропущено: невідомий предмет"); it.remove(); } }
        itemsRef = items;
        frozen = true;
    }

    public synchronized void fuel(String item, float seconds) {
        if (frozen) throw new IllegalStateException("реєстр рецептів уже закритий (паливо " + item + ")");
        fuels.put(item, Math.max(1, Math.round(Math.max(0.05f, Math.min(36000f, Float.isNaN(seconds) ? 1f : seconds)) * 20)));
    }

    /** Тіків горіння предмета як палива (0 - не паливо). */
    public int fuelTicks(String item) { Integer t = fuels.get(item); return t == null ? 0 : t; }

    /** Рецепт плавки для предмета або null. */
    public Smelt smeltFor(String item) {
        for (Smelt m : smelts) if (m.input.startsWith("#") ? itemsRef != null && itemsRef.inTag(m.input.substring(1), item) : m.input.equals(item)) return m;
        return null;
    }

    public List<Smelt> smelts() { return Collections.unmodifiableList(smelts); }

    public List<Recipe> all() { return Collections.unmodifiableList(list); }
}
