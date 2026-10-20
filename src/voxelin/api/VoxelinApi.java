package voxelin.api;

/**
 * Версія API модів. Піднімається ЛИШЕ тоді, коли змінюється щось, на що вже спираються моди (видалили/змінили метод).
 * Нові методи й класи версію не змінюють - старі моди продовжують працювати.
 */
public final class VoxelinApi {
    private VoxelinApi() {}
    public static final int VERSION = 2;
    /** Найстаріша версія API, яку гра ще вміє виконувати. v1 (координати X/Z типу int) несумісна з безмежним світом (long). */
    public static final int MIN_COMPATIBLE = 2;
}
