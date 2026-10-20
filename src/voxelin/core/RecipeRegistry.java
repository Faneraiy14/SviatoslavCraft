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
        final List<String> ingredients;

        Recipe(String id, RecipeSettings s) {
            this.id = id; result = s.resultItem(); count = s.resultCount(); shaped = s.isShaped(); mirror = s.mirrorAllowed();
            if (shaped) {
                String[] rows = s.rows();
                h = rows.length; w = rows[0].length(); cells = new String[w * h]; ingredients = null;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    char c = rows[y].charAt(x);
                    if (c == '.' || c == ' ') continue;
                    String ing = s.keys().get(c);
                    if (ing == null) throw new IllegalArgumentException("рецепт " + id + ": нема ключа для '" + c + "'");
                    cells[x + y * w] = ing;
                }
                boolean any = false; for (String c : cells) if (c != null) any = true;
                if (!any) throw new IllegalArgumentException("рецепт " + id + ": порожня форма");
            } else { w = h = 0; cells = null; ingredients = List.copyOf(s.ingredients()); }
            if (result == null) throw new IllegalArgumentException("рецепт " + id + ": не задано результат");
        }

        public List<String> allIngredients() {
            List<String> r = new ArrayList<>();
            if (shaped) { for (String c : cells) if (c != null) r.add(c); } else r.addAll(ingredients);
            return r;
        }
    }

    private final List<Recipe> list = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private boolean frozen;

    public synchronized void add(String id, RecipeSettings s) {
        if (frozen) throw new IllegalStateException("реєстр рецептів уже закритий: " + id);
        if (!id.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва рецепта: " + id);
        if (!ids.add(id)) throw new IllegalArgumentException("рецепт уже є: " + id);
        list.add(new Recipe(id, s));
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
        frozen = true;
    }

    public List<Recipe> all() { return Collections.unmodifiableList(list); }
}
