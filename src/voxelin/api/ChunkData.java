package voxelin.api;

/** Чанк під час генерації: локальні координати x,z 0..15, y 0..height-1. Без прав і подій - чанк ще нікому не видно. */
public interface ChunkData {
    long chunkX();
    long chunkZ();
    int height();
    BlockRef get(int x, int y, int z);
    void set(int x, int y, int z, BlockRef block);
}
