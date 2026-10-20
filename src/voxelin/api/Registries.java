package voxelin.api;

/** Реєстри. Працюють лише в init(); після завантаження всіх модів закриваються (подальша реєстрація кидає IllegalStateException). */
public interface Registries {
    /** Зареєструвати блок. name без двокрапки отримує префікс мода: "ruby" -> "mymod:ruby". */
    BlockRef block(String name, BlockSettings settings);
    /** Знайти блок за повною назвою ("voxelin:stone") або null. */
    BlockRef findBlock(String fullName);
    /**
     * Текстура 16x16 як масив 256 кольорів 0xAARRGGBB (рядками, зверху вниз). Етап «Новий світ» малює текстури з атласу;
     * зараз вони зберігаються, а блоки малюються кольором.
     */
    void texture(String name, int[] argb16x16);
}
