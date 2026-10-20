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
}
