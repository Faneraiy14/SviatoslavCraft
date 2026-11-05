package voxelin.api;

/**
 * Налаштування мода: файл config/&lt;id мода&gt;.properties поруч із mods/ (гравець може правити його руками).
 * Отримати: {@code ctx.api(ModConfig.class)} (можливість "service.config"). Значення - рядки; save() пише файл атомарно.
 */
public interface ModConfig {
    /** Значення або def, якщо ключа нема. Відсутній ключ із def одразу записується у файл при save(), щоб гравець бачив, що можна змінити. */
    String get(String key, String def);
    int getInt(String key, int def);
    boolean getBool(String key, boolean def);
    void set(String key, String value);
    /** Записати файл (атомарно). */
    void save();
}
