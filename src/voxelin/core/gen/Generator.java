package voxelin.core.gen;

import voxelin.core.Chunk;

/** Генератор рельєфу. Викликається з фонових потоків - має бути безпечним для паралельного виклику (без спільного змінного стану). */
public interface Generator {
    void generate(Chunk chunk);
    /** Висота поверхні в точці (для пошуку місця появи гравця). */
    int surfaceY(long x, long z);
    /** Назва біому в колонці (для відладки й HUD). */
    default String biomeAt(long x, long z) { return ""; }
    /** Найближча структура цього виду {x, y, z, відстань}; null - не знайдено або генератор без структур. */
    default long[] locate(String name, long x, long z) { return null; }
    default java.util.List<String> structureNames() { return java.util.List.of(); }
    /** Рівень моря (вода до цієї висоти); для місця появи гравця. */
    default int seaLevel() { return 62; }
}
