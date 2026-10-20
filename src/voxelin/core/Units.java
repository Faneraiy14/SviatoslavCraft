package voxelin.core;

/**
 * Масштаб світу: блок = 1.5 світової одиниці (Святослав попросив «блоки більшими»). Сховище, команди й API - у цілих
 * координатах БЛОКІВ; рендер, фізика й камера - у світових одиницях. Переводити лише через ці методи.
 */
public final class Units {
    private Units() {}
    public static final float BLOCK_SIZE = 1.5f;
    public static int toBlock(float world) { return (int) Math.floor(world / BLOCK_SIZE); }
    public static float toWorld(int block) { return block * BLOCK_SIZE; }
    public static float center(int block) { return (block + 0.5f) * BLOCK_SIZE; }
}
