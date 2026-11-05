package voxelin.api;

/** Усе, що мод отримує під час init(). */
public interface ModContext {
    /** Версія API, яку надає гра (VoxelinApi.VERSION). */
    int apiVersion();
    /** Ідентифікатор мода ("voxelin" для ванільного вмісту) - префікс його блоків: "mymod:ruby". */
    String modId();
    Registries registries();
    Events events();
    Commands commands();
    /** Повідомлення в лог гри з позначкою мода. */
    void log(String message);

    /**
     * Додаткова частина API (сервіс): {@code ctx.api(ModConfig.class)}. null - ця версія гри такого не має.
     * Так нові можливості додаються новими інтерфейсами, а не зміною ModContext чи ядра (див. docs/MODDING.md «Як росте API»).
     */
    default <T> T api(Class<T> service) { return null; }

    /** Чи є в грі можливість (VoxelinApi.FEATURES): перевір перед тим, як чіпати нові класи API, - тоді мод працює й на старішій грі. */
    default boolean has(String feature) { return VoxelinApi.has(feature); }
}
