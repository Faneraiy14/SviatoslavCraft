package com.sviatoslav.craft.engine.world;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Chunk implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int SIZE = 16;
    private int chunkX, chunkZ;
    private Map<String, Block> blocks = new HashMap<>();
    private transient Random random;

    public Chunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX; this.chunkZ = chunkZ;
        this.random = new Random(chunkX * 1000 + chunkZ);
        generateTerrain();
        generateTrees();
    }

    private void generateTerrain() {
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            int wx = x + chunkX * SIZE, wz = z + chunkZ * SIZE;
            int height = (int)(4 + Math.sin(wx*0.05)*Math.cos(wz*0.05)*3 + Math.sin(wx*0.1 + wz*0.08)*2);
            for (int y = 0; y < height; y++) {
                Block.Type type = (y == height-1) ? Block.Type.GRASS : (y > height-4) ? Block.Type.DIRT : Block.Type.STONE;
                setBlock(x, y, z, new Block(type, x, y, z));
            }
        }
    }

    private void generateTrees() {
        if (random == null) random = new Random(chunkX * 1000 + chunkZ);
        for (int i = 0; i < 2 + random.nextInt(3); i++) {
            int x = random.nextInt(SIZE), z = random.nextInt(SIZE);
            int height = (int)(4 + Math.sin((x+chunkX*SIZE)*0.05)*Math.cos((z+chunkZ*SIZE)*0.05)*3);
            Block block = getBlock(x, height-1, z);
            if (block != null && block.getType() == Block.Type.GRASS) {
                int trunk = 3 + random.nextInt(2);
                for (int y = 0; y < trunk; y++) setBlock(x, height+y, z, new Block(Block.Type.WOOD, x, height+y, z));
                for (int dy = 0; dy < 3; dy++) {
                    int radius = (dy == 0) ? 1 : 2;
                    for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx)==radius && Math.abs(dz)==radius && random.nextBoolean()) continue;
                        int lx = x+dx, lz = z+dz, ly = height + trunk - 2 + dy;
                        if (lx>=0 && lx<SIZE && lz>=0 && lz<SIZE && getBlock(lx, ly, lz)==null)
                            setBlock(lx, ly, lz, new Block(Block.Type.LEAVES, lx, ly, lz));
                    }
                }
            }
        }
    }

    public void setBlock(int x, int y, int z, Block block) {
        String key = x+","+y+","+z;
        if (block.getType() == Block.Type.AIR) blocks.remove(key);
        else blocks.put(key, block);
    }
    public Block getBlock(int x, int y, int z) { return blocks.get(x+","+y+","+z); }
    public Map<String, Block> getBlocks() { return blocks; }
    public int getChunkX() { return chunkX; } public int getChunkZ() { return chunkZ; }
}
