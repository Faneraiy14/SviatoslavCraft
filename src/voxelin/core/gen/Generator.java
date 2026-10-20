package voxelin.core.gen;

import voxelin.core.Chunk;

/** Генератор рельєфу. Викликається з фонових потоків - має бути безпечним для паралельного виклику (без спільного змінного стану). */
public interface Generator {
    void generate(Chunk chunk);
    /** Висота поверхні в точці (для пошуку місця появи гравця). */
    int surfaceY(long x, long z);
}
