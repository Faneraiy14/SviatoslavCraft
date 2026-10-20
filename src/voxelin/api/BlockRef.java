package voxelin.api;

/** Посилання на зареєстрований блок. id - номер у цьому запуску гри (у файлах світу зберігається назва, тож id може відрізнятись між запусками). */
public interface BlockRef {
    String name();
    int id();
}
