package voxelin.api;

/** Реєстри. Працюють лише в init(); після завантаження всіх модів закриваються (подальша реєстрація кидає IllegalStateException). */
public interface Registries {
    /** Зареєструвати блок. name без двокрапки отримує префікс мода: "ruby" -> "mymod:ruby". */
    BlockRef block(String name, BlockSettings settings);
    /** Зареєструвати предмет (не блок). name без двокрапки отримує префікс мода. Предмети-блоки створюються автоматично разом із блоками. */
    ItemRef item(String name, ItemSettings settings);
    ItemRef findItem(String fullName);
    /** Рецепт крафту (id без двокрапки отримує префікс мода). */
    void recipe(String id, RecipeSettings settings);
    /** Скільки секунд горить предмет як паливо в печі. */
    void fuel(String item, float seconds);
    /** Додати предмети до мітки ("planks", "logs"): у рецептах пишеться "#planks". Мітки спільні для всіх модів. */
    void tag(String tag, String... items);
    /** Зареєструвати вид істоти (назва без двокрапки отримує префікс мода). */
    MobRef mob(String name, MobSettings settings);
    MobRef findMob(String fullName);
    /** Таблиця здобичі для скринь (id без двокрапки отримує префікс мода). */
    void loot(String id, LootSettings settings);
    /** Шаблон структури (текст формату .vxs; назва без двокрапки отримує префікс мода). Помилка в тексті - структура пропускається з попередженням. */
    void structure(String name, String text);
    /** Знайти блок за повною назвою ("voxelin:stone") або null. */
    BlockRef findBlock(String fullName);
    /**
     * Текстура 16x16 як масив 256 кольорів 0xAARRGGBB (рядками, зверху вниз). Етап «Новий світ» малює текстури з атласу;
     * зараз вони зберігаються, а блоки малюються кольором.
     */
    void texture(String name, int[] argb16x16);
}
