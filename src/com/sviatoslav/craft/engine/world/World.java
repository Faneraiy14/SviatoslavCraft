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

    // px/pz - СВІТОВІ float-координати гравця (масштаб Chunk.BLOCK_SIZE);
    // спершу переводимо в grid-простір (/BLOCK_SIZE), а вже тоді - у
    // номер чанка (/Chunk.SIZE, 16 grid-блоків на чанк). Пропуск першого
    // ділення (як було раніше, до BLOCK_SIZE) означав би, що довантаження
    // чанків орієнтується на застарілий, "маленький" масштаб світу.
    public void update(float px, float pz) {
        float gx = px / Chunk.BLOCK_SIZE, gz = pz / Chunk.BLOCK_SIZE;
        int cx = (int) Math.floor(gx / Chunk.SIZE), cz = (int) Math.floor(gz / Chunk.SIZE);
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
                    // Сусідні чанки (якщо вже завантажені) могли намалювати
                    // грань на межі як "видима" ЛИШЕ тому, що цей чанк тоді
                    // ще не існував (isBlockVisible консервативно вважає
                    // незавантажений сусідній чанк суцільним - див. нижче) -
                    // тепер, коли реальні дані відомі, їхній display list
                    // треба перебудувати, щоб межа стала коректною.
                    markNeighborsDirty(tx, tz);
                });
            }
        }
        chunks.entrySet().removeIf(e -> {
            String[] p = e.getKey().split(",");
            int x = Integer.parseInt(p[0]), z = Integer.parseInt(p[1]);
            if (Math.abs(x - cx) > loadDistance || Math.abs(z - cz) > loadDistance) {
                saveChunkToDisk(e.getValue());
                // Виклик ЛИШЕ з update() (головний потік, той самий, що й
                // render()) - безпечно звільняти GL-ресурс тут.
                if (e.getValue().displayListId > 0) {
                    org.lwjgl.opengl.GL11.glDeleteLists(e.getValue().displayListId, 1);
                }
                return true;
            }
            return false;
        });
    }

    private void markNeighborsDirty(int cx, int cz) {
        markDirtyIfLoaded(cx - 1, cz); markDirtyIfLoaded(cx + 1, cz);
        markDirtyIfLoaded(cx, cz - 1); markDirtyIfLoaded(cx, cz + 1);
    }

    private void markDirtyIfLoaded(int cx, int cz) {
        Chunk c = chunks.get(cx + "," + cz);
        if (c != null) c.dirty = true;
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
            // Зміна блока на самій межі чанка може розкрити/сховати грань і
            // в СУСІДНЬОМУ чанку - його display list теж застаріває.
            if (localX == 0) markDirtyIfLoaded(cx - 1, cz);
            if (localX == Chunk.SIZE - 1) markDirtyIfLoaded(cx + 1, cz);
            if (localZ == 0) markDirtyIfLoaded(cx, cz - 1);
            if (localZ == Chunk.SIZE - 1) markDirtyIfLoaded(cx, cz + 1);
        }
    }
    public Map<String, Chunk> getChunks() { return chunks; }
    public void saveAllChunks() { chunks.values().forEach(this::saveChunkToDisk); }
    public void shutdown() { chunkExecutor.shutdown(); try { chunkExecutor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { chunkExecutor.shutdownNow(); } }

    // РЕАЛЬНИЙ ФІКС ("продірявлені" блоки, знайдено Sviatoslav'ом живцем):
    // раніше НЕЗАВАНТАЖЕНИЙ сусідній чанк трактувався як "видимо" (b==null
    // -> true), тому щоразу, як гравець рухався й сусідні чанки
    // довантажувались асинхронно, межі МИГТІЛИ дірками - грань малювалась
    // видимою просто тому, що сусід ще не встиг завантажитись, хоча
    // насправді там суцільна земля. Тепер розрізняємо два випадки:
    // "чанк не завантажений" (консервативно = НЕВИДИМО, ховаємо грань,
    // самокориговується на наступному кадрі після довантаження - markDirty
    // в loadChunksAsync/setBlock уже подбали про перебудову) і "чанк
    // завантажений, блока там дійсно нема" (= видимо по-справжньому).
    private boolean isBlockVisible(int x, int y, int z) {
        int cx = (int) Math.floor((double) x / Chunk.SIZE), cz = (int) Math.floor((double) z / Chunk.SIZE);
        if (getChunk(cx, cz) == null) return false;
        Block b = getBlock(x, y, z);
        return b == null || b.getType() == Block.Type.AIR;
    }

    // РЕАЛЬНА ОПТИМІЗАЦІЯ (лаги, знайдено Sviatoslav'ом живцем): раніше
    // КОЖЕН окремий куб малювався своїм власним glPushMatrix/glTranslatef/
    // glBegin/glEnd - тисячі окремих викликів у LWJGL через JNI щокадру,
    // навіть якщо жоден блок не змінився. Тепер геометрія кожного чанка
    // компілюється ОДИН РАЗ у OpenGL display list (glNewList/glEndList) і
    // просто відтворюється (glCallList) щокадру - перебудова лише коли
    // chunk.dirty (реально змінився блок чи сусід довантажився).
    //
    // РЕАЛЬНА ОПТИМІЗАЦІЯ #2 (мертвий код у чернетці DeepSeek): поля
    // renderDistance/isChunkVisible() були ОГОЛОШЕНІ, але НІДЕ не
    // викликались - render() малював УСІ завантажені чанки (loadDistance=4,
    // 9x9=81 чанк), хоча реально малювати треба лише найближчі
    // (renderDistance=2, 5x5=25) - решта лишаються завантаженими в пам'яті
    // (для колізій/плавного довантаження під час руху), просто НЕ
    // рендеряться, поки гравець до них не наблизиться.
    // РЕАЛЬНА ОПТИМІЗАЦІЯ #3 (лаг при русі, знайдено Sviatoslav'ом живцем -
    // "зупиняюсь, коли спавниться далі чанк", той самий ефект, що й у
    // ванільному Minecraft): генерація РЕЛЬЄФУ вже йде у фоновому потоці
    // (chunkExecutor), але ЗБІРКА ГЕОМЕТРІЇ (display list) МУСИТЬ
    // відбуватись на головному/GL-потоці - при перетині межі чанка одразу
    // кілька сусідів ставали dirty й перебудовувались УСІ в один кадр,
    // даючи помітний стрибок. MAX_REBUILDS_PER_FRAME розтягує цю роботу
    // на кілька кадрів - інші dirty-чанки просто чекають своєї черги
    // (показуючи поки що застарілу геометрію чи порожній список, якщо
    // щойно з'явились - не помилка, glCallList на порожній список просто
    // нічого не малює).
    private static final int MAX_REBUILDS_PER_FRAME = 2;

    public void render(Renderer renderer) {
        int rebuildsThisFrame = 0;
        for (Chunk chunk : chunks.values()) {
            if (chunk == null) continue;
            if (!isChunkVisible(chunk.getChunkX(), chunk.getChunkZ(), lastChunkX, lastChunkZ)) continue;
            if (chunk.displayListId <= 0) {
                chunk.displayListId = org.lwjgl.opengl.GL11.glGenLists(1);
            }
            if (chunk.dirty && rebuildsThisFrame < MAX_REBUILDS_PER_FRAME) {
                rebuildsThisFrame++;
                org.lwjgl.opengl.GL11.glNewList(chunk.displayListId, org.lwjgl.opengl.GL11.GL_COMPILE);
                buildChunkGeometry(chunk, renderer);
                org.lwjgl.opengl.GL11.glEndList();
                chunk.dirty = false;
            }
            org.lwjgl.opengl.GL11.glCallList(chunk.displayListId);
        }
    }

    private void buildChunkGeometry(Chunk chunk, Renderer renderer) {
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
            // grid-координата (x,y,z) -> світова float-позиція через
            // Chunk.BLOCK_SIZE (див. коментар при константі) - розмір
            // куба теж BLOCK_SIZE, не 1.0, щоб суцільно стикався із
            // сусідами на новому масштабі, без перетину/щілин.
            renderer.renderCube((x+0.5f)*Chunk.BLOCK_SIZE, (y+0.5f)*Chunk.BLOCK_SIZE, (z+0.5f)*Chunk.BLOCK_SIZE,
                Chunk.BLOCK_SIZE, r, g, b, top, bottom, front, back, left, right);
        }
    }
}
