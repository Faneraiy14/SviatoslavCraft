package voxelin.api;

import java.util.*;

/**
 * Рецепт крафту як дані. Форма: {@code RecipeSettings.shaped("MM", ".S", ".S").key('M', "#planks").key('S', "stick").result("wooden_pickaxe", 1)};
 * безформний: {@code RecipeSettings.shapeless("oak_log").result("oak_planks", 3)}. Інгредієнт - предмет ("stick", "mod:ruby") або мітка ("#planks");
 * у формі '.' чи пробіл - порожня клітинка. Назва без двокрапки - предмет цього ж мода. {@code key('C', "cobblestone", 2)} - у кожній клітинці
 * з цим ключем має лежати щонайменше 2 предмети, і стільки ж витрачається (об'ємні блоки: піч, верстак).
 */
public final class RecipeSettings {
    final String[] rows;
    final List<String> shapeless;
    final Map<Character, String> key = new HashMap<>();
    final Map<Character, Integer> perCell = new HashMap<>();
    String result;
    int count = 1;
    boolean mirror = true;
    String smeltInput;
    float seconds;

    private RecipeSettings(String[] rows, List<String> shapeless) { this.rows = rows; this.shapeless = shapeless; }

    public static RecipeSettings shaped(String... rows) {
        if (rows == null || rows.length < 1 || rows.length > 3) throw new IllegalArgumentException("форма: від 1 до 3 рядків");
        int w = rows[0].length();
        for (String r : rows) if (r.length() != w || w < 1 || w > 3) throw new IllegalArgumentException("форма: рядки однакової довжини 1..3");
        return new RecipeSettings(rows.clone(), null);
    }

    public static RecipeSettings shapeless(String... ingredients) {
        if (ingredients == null || ingredients.length < 1 || ingredients.length > 9) throw new IllegalArgumentException("безформний: від 1 до 9 інгредієнтів");
        return new RecipeSettings(null, new ArrayList<>(Arrays.asList(ingredients)));
    }

    /** Плавка в печі: вхід (предмет або #мітка) -> результат за seconds секунд. Результат задається .result(...). */
    public static RecipeSettings smelting(String input, float seconds) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("плавка: нема входу");
        RecipeSettings r = new RecipeSettings(null, null);
        r.smeltInput = input; r.seconds = Float.isNaN(seconds) ? 10f : Math.max(0.5f, Math.min(3600f, seconds));
        return r;
    }

    public RecipeSettings key(char c, String ingredient) { key.put(c, ingredient); perCell.remove(c); return this; }
    /** Ключ, що вимагає count предметів у кожній своїй клітинці (1..64). */
    public RecipeSettings key(char c, String ingredient, int count) { key.put(c, ingredient); perCell.put(c, Math.max(1, Math.min(64, count))); return this; }
    public RecipeSettings result(String item, int count) { result = item; this.count = Math.max(1, Math.min(64, count)); return this; }
    /** Заборонити дзеркальне відбиття форми (типово дозволене). */
    public RecipeSettings noMirror() { mirror = false; return this; }

    private static String q(String s, String modId) { return s == null || s.startsWith("#") || s.indexOf(':') >= 0 ? s : modId + ":" + s; }

    public RecipeSettings qualified(String modId) {
        RecipeSettings r = new RecipeSettings(rows, shapeless == null ? null : new ArrayList<>());
        r.smeltInput = q(smeltInput, modId); r.seconds = seconds;
        if (shapeless != null) for (String s : shapeless) r.shapeless.add(q(s, modId));
        for (Map.Entry<Character, String> e : key.entrySet()) r.key.put(e.getKey(), q(e.getValue(), modId));
        r.perCell.putAll(perCell);
        r.result = q(result, modId); r.count = count; r.mirror = mirror;
        return r;
    }

    public boolean isShaped() { return rows != null; }
    public boolean isSmelting() { return smeltInput != null; }
    public String smeltInputItem() { return smeltInput; }
    public float smeltSeconds() { return seconds; }
    public String[] rows() { return rows == null ? null : rows.clone(); }
    public List<String> ingredients() { return shapeless == null ? null : Collections.unmodifiableList(shapeless); }
    public Map<Character, String> keys() { return Collections.unmodifiableMap(key); }
    /** Скільки предметів на клітинку для ключа (типово 1). */
    public int perCell(char c) { return perCell.getOrDefault(c, 1); }
    public String resultItem() { return result; }
    public int resultCount() { return count; }
    public boolean mirrorAllowed() { return mirror; }
}
