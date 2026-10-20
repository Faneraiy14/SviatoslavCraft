package voxelin.api;

/**
 * Точка входу мода. Клас вказується в META-INF/MANIFEST.MF jar-а:
 * <pre>
 *   Voxelin-Mod: com.example.MyMod
 *   Voxelin-Mod-Id: mymod
 *   Voxelin-Api: 1
 * </pre>
 * Ванільний вміст гри реєструється через цей самий інтерфейс (voxelin.content.VanillaContent),
 * тож мод може все, що може гра.
 */
public interface VoxelinMod {
    /** Викликається один раз під час завантаження, ДО відкриття будь-якого світу. Тут реєструються блоки, команди, слухачі подій. */
    void init(ModContext ctx);
}
