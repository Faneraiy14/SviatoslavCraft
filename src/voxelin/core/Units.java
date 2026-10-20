package voxelin.core;

/**
 * Масштаб малювання: блок = 1.5 світової одиниці (Святослав попросив «блоки більшими»). Тепер це лише справа рендера:
 * ядро, фізика й API рахують у БЛОКАХ (long/double), а множення на BLOCK_SIZE робить шейдер і матриця виду.
 */
public final class Units {
    private Units() {}
    public static final float BLOCK_SIZE = 1.5f;
}
