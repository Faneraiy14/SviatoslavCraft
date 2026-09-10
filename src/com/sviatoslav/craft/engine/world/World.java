package com.sviatoslav.craft.engine.world;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sviatoslav.craft.engine.graphics.Renderer;

public class World {
    private Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    private int loadDistance = 4, renderDistance = 2;
    private int lastChunkX = Integer.MAX_VALUE, lastChunkZ = Integer.MAX_VALUE;
    private String saveFolder = "saves/world1/";
    private ExecutorService chunkExecutor = Executors.newFixedThreadPool(4);

    public World() { new File(saveFolder).mkdirs(); }

    public void update(float px, float pz) {
        int cx = (int) Math.floor(px / Chunk.SIZE), cz = (int) Math.floor(pz / Chunk.SIZE);
        if (cx != lastChunkX || cz != lastChunkZ) { lastChunkX = cx; lastChunkZ = cz; loadChunksAsync(cx, cz); }
    }

    private void loadChunksAsync(int cx, int cz) {
        for (int dx = -loadDistance; dx <= loadDistance; dx++) for (int dz = -loadDistance; dz <= loadDistance; dz++) {
            int tx = cx + dx, tz = cz + dz;
            String key = tx+","+tz;
            if (!chunks.containsKey(key)) {
                chunkExecutor.submit(() -> {
                    Chunk chunk = loadChunkFromDisk(tx, tz);
                    if (chunk == null) chunk = new Chunk(tx, tz);
                    chunks.put(key, chunk);
                });
            }
        }
        chunks.entrySet().removeIf(e -> {
            String[] p = e.getKey().split(",");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
            if (Math.abs(x - cx) > loadDistance || Math.abs(z - cz) > loadDistance) {
                saveChunkToDisk(e.getValue()); return true;
            }
            return false;
        });
    }

    public boolean isChunkVisible(int cx, int cz, int pcx, int pcz) {
        return Math.abs(cx - pcx) <= renderDistance && Math.abs(cz - pcz) <= renderDistance;
    }

    private void saveChunkToDisk(Chunk chunk) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(saveFolder + "chunk_"+chunk.getChunkX()+"_"+chunk.getChunkZ()+".dat"))) {
            oos.writeObject(chunk);
        } catch (IOException e) {}
    }

    private Chunk loadChunkFromDisk(int x, int z) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(saveFolder + "chunk_"+x+"_"+z+".dat"))) {
            return (Chunk) ois.readObject();
        } catch (IOException | ClassNotFoundException e) { return null; }
    }

    public Chunk getChunk(int x, int z) { return chunks.get(x+","+z); }
    public Block getBlock(int x, int y, int z) {
        int cx = (int) Math.floor((double)x / Chunk.SIZE), cz = (int) Math.floor((double)z / Chunk.SIZE);
        Chunk c = getChunk(cx, cz);
        return c == null ? null : c.getBlock(x - cx*Chunk.SIZE, y, z - cz*Chunk.SIZE);
    }
    // РЕАЛЬНИЙ ФІКС (прихований, "сплячий" баг у чернетці DeepSeek: цей
    // метод НІКИМ не викликався, тому ніколи не спрацьовував): Block, що
    // ЗБЕРІГАЄТЬСЯ в чанку, мусить мати ЛОКАЛЬНІ (0..15) координати - саме
    // такі World.render() очікує, додаючи chunkX*SIZE. Якщо викликач
    // передає Block зі СВІТОВИМИ координатами (природно для будь-якого
    // коду, що ламає/ставить блок за позицією гравця) - блок рендерився й
    // колізіонував би в геть неправильному місці (подвійне додавання
    // chunkX*SIZE). Тепер Block перестворюється тут з коректними
    // локальними координатами - тип беремо з переданого, координати
    // рахуємо самі.
    public void setBlock(int x, int y, int z, Block block) {
        int cx = (int) Math.floor((double)x / Chunk.SIZE), cz = (int) Math.floor((double)z / Chunk.SIZE);
        Chunk c = getChunk(cx, cz);
        if (c != null) {
            int localX = x - cx*Chunk.SIZE, localZ = z - cz*Chunk.SIZE;
            c.setBlock(localX, y, localZ, new Block(block.getType(), localX, y, localZ));
            saveChunkToDisk(c);
        }
    }
    public Map<String, Chunk> getChunks() { return chunks; }
    public void saveAllChunks() { chunks.values().forEach(this::saveChunkToDisk); }
    public void shutdown() { chunkExecutor.shutdown(); try { chunkExecutor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { chunkExecutor.shutdownNow(); } }

    public void render(Renderer renderer) {
        for (Chunk chunk : chunks.values()) {
            if (chunk == null) continue;
            for (Block block : chunk.getBlocks().values()) {
                if (block == null || block.getType() == Block.Type.AIR) continue;
                float x = block.getX() + chunk.getChunkX()*Chunk.SIZE, y = block.getY(), z = block.getZ() + chunk.getChunkZ()*Chunk.SIZE;
                boolean top = isBlockVisible((int)x, (int)y+1, (int)z), bottom = isBlockVisible((int)x, (int)y-1, (int)z);
                boolean front = isBlockVisible((int)x, (int)y, (int)z+1), back = isBlockVisible((int)x, (int)y, (int)z-1);
                boolean left = isBlockVisible((int)x-1, (int)y, (int)z), right = isBlockVisible((int)x+1, (int)y, (int)z);
                if (!top && !bottom && !front && !back && !left && !right) continue;
                float r=0.2f, g=0.6f, b=0.2f;
                switch (block.getType()) {
                    case GRASS: r=0.2f; g=0.7f; b=0.1f; break;
                    case DIRT: r=0.5f; g=0.3f; b=0.1f; break;
                    case STONE: r=0.5f; g=0.5f; b=0.5f; break;
                    case WOOD: r=0.4f; g=0.2f; b=0.05f; break;
                    case LEAVES: r=0.0f; g=0.5f; b=0.0f; break;
                    default: break;
                }
                // РЕАЛЬНИЙ БАГ (чернетка DeepSeek): тут стояло x-0.5f/y-0.5f/z-0.5f
                // - куб (розмір 1.0, центрований) виходив зі світовим
                // діапазоном [x-1, x], а колізія в Player.collidesWithWorld
                // рахує блок як AABB(x+0.5,...) - діапазон [x, x+1]. Видимий
                // блок і реальна колізія відрізнялись рівно на 1 юніт по
                // кожній осі - блоки "стояли" не там, де об них реально
                // спотикаєшся. Тепер +0.5f - той самий діапазон [x, x+1].
                renderer.renderCube(x+0.5f, y+0.5f, z+0.5f, 1.0f, r, g, b, top, bottom, front, back, left, right);
            }
        }
    }
    private boolean isBlockVisible(int x, int y, int z) { Block b = getBlock(x, y, z); return b == null || b.getType() == Block.Type.AIR; }
}
