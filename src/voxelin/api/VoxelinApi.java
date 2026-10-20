package voxelin.api;

/**
 * Версія API модів. Піднімається ЛИШЕ тоді, коли змінюється щось, на що вже спираються моди (видалили/змінили метод).
 * Нові методи й класи версію не змінюють - старі моди продовжують працювати.
 */
public final class VoxelinApi {
    private VoxelinApi() {}
    public static final int VERSION = 1;
}
