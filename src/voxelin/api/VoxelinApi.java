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

    /**
     * Можливості цієї версії гри. Кожна нова частина API (подія, сервіс, метод) додає сюди рядок - мод перевіряє ctx.has(...) або
     * вимагає її в маніфесті (Voxelin-Api-Requires: events.chat, service.config): тоді на старішій грі він не падає, а отримує
     * зрозуміле «онови гру». Рядки ніколи не прибираються, поки VERSION той самий.
     */
    public static final java.util.Set<String> FEATURES = java.util.Set.of(
            "registries.blocks", "registries.items", "registries.recipes", "registries.mobs", "registries.loot", "registries.structures", "registries.records", "registries.textures",
            "events.tick", "events.block_change", "events.chunk_generated", "events.world_open",
            "events.mob_death", "events.player_join", "events.player_leave", "events.chat",
            "commands", "service.config", "service.players", "commands.guests");

    public static boolean has(String feature) { return feature != null && FEATURES.contains(feature.trim()); }
}
