package voxelin.api;

/** Інвентар гравця для команд і модів. */
public interface ItemHolder {
    /** Додати предмет; повертає, скільки НЕ влізло. */
    int give(String item, int count);
    void clear();
}
