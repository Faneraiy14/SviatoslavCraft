package com.sviatoslav.craft.engine.world;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sviatoslav.craft.engine.graphics.Renderer;

public class World {
    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "фризи, коли бігаю,
    // стрибаю, мотаю камерою і завантажуються/вивантажуються чанки"): той
    // самий патерн, що я вже виправив УСЕРЕДИНІ Chunk (String-ключ "x,y,z"
    // -> плаский масив), лишався ТУТ, на рівень вище - chunks і далі
    // ключувався рядком "x,z". getChunk() (через getBlock/isBlockVisible)
    // викликається ТИСЯЧІ разів під час КОЖНОЇ перебудови чанка (6
    // перевірок видимості на кожен блок) - кожен виклик будував НОВИЙ
    // String. Плюс chunks.entrySet().removeIf() при вивантаженні
    // РОЗБИРАВ той рядок назад через split(",")+parseInt - ще одна купа
    // виділень на кожен перетин межі loadDistance. Long-ключ (cx у
    // старших 32 бітах, cz у молодших) - жодних рядків, лише
    // побітові операції.
    private Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private int loadDistance = 4, renderDistance = 2;
    private int lastChunkX = Integer.MAX_VALUE, lastChunkZ = Integer.MAX_VALUE;
    private String saveFolder;
    // РЕАЛЬНИЙ ФІКС (Sviatoslav - "все ще трохи підлагує"): на 4-ядерному
    // ноутбуці пул був теж на 4 потоки - перетин межі loadDistance
    // одразу ставить у чергу ~7-9 задач генерації чанків, і пул міг
    // зайняти ВСІ 4 ядра одночасно САМЕ в момент, коли головному
    // потоку (рендер+фізика+ввід) теж треба виконуватись - реальна
    // конкуренція за CPU, не просто "фонова" робота. 2 потоки лишають
    // ядра вільними для головного потоку й GC.
    private ExecutorService chunkExecutor = Executors.newFixedThreadPool(2);

    // РЕАЛЬНА фіча (Sviatoslav попросив - екран вибору світу як у
    // Minecraft): раніше папка збереження була ЖОРСТКО "saves/world1" -
    // тепер кожен світ отримує свою папку за назвою, обраною на екрані
    // вибору/створення світу (WorldSelectManager).
    private Chunk.WorldType worldType;

    // РЕАЛЬНА ДІРА (Sviatoslav попросив перевірити код на "критичні
    // діри"): worldName напряму йде в шлях файлової системи
    // ("saves/" + worldName) - у КОЖНОМУ місці, де світ створюється/
    // відкривається/перейменовується. ЄДИНИМ захистом від path traversal
    // (наприклад, назва "../../../etc" писала б поза папкою saves/) був
    // ПОБІЧНИЙ ефект фільтра символів у текстовому полі вводу
    // (InputHandler - дозволяє лише літери/цифри/пробіл, тому "/" і "."
    // фізично не потрапляють у буфер) - НЕ явна перевірка тут, де шлях
    // реально будується. Крихко: якщо колись розширити дозволені символи
    // в полі вводу (наприклад, додати дефіс для "New-World"), діра
    // відкриється непомітно, без жодного зв'язку з цим файлом. Явна
    // перевірка ТУТ (і скрізь, де назва світу перетворюється на шлях -
    // WorldCreateManager/WorldEditManager/WorldSelectManager) - незалежна
    // від того, що зараз дозволяє чи забороняє поле вводу.
    public static boolean isValidWorldName(String name) {
        return name != null && !name.isEmpty()
            && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    public World(String worldName) {
        if (!isValidWorldName(worldName)) {
            throw new IllegalArgumentException("Недопустима назва світу: " + worldName);
        }
        this.saveFolder = "saves/" + worldName + "/";
        new File(saveFolder).mkdirs();
        worldType = readWorldType();
    }

    // Тип рельєфу (плаский/звичайний, обраний на екрані створення -
    // WorldCreateManager) записується ОДИН РАЗ у момент створення
    // (writeWorldMeta нижче, static, викликається до першого відкриття
    // світу) - World сам лише ЧИТАЄ, ніколи не пише, інакше довелось би
    // розрізняти "відкриваю новостворений" від "відкриваю вже існуючий"
    // прямо тут. Файла нема - старі світи (до цієї фічі) чи як
    // підстраховка - NORMAL за замовчуванням.
    private Chunk.WorldType readWorldType() {
        File meta = new File(saveFolder + "world.meta");
        if (meta.exists()) {
            try {
                String s = new String(java.nio.file.Files.readAllBytes(meta.toPath())).trim();
                return Chunk.WorldType.valueOf(s);
            } catch (Exception e) { /* лишаємо NORMAL нижче */ }
        }
        return Chunk.WorldType.NORMAL;
    }

    public static void writeWorldMeta(String worldName, Chunk.WorldType type) {
        if (!isValidWorldName(worldName)) return;
        File dir = new File("saves/" + worldName);
        dir.mkdirs();
        try (FileWriter w = new FileWriter(new File(dir, "world.meta"))) {
            w.write(type.name());
        } catch (IOException e) {}
    }

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
            long key = chunkKey(tx, tz);
            if (!chunks.containsKey(key)) {
                chunkExecutor.submit(() -> {
                    Chunk chunk = loadChunkFromDisk(tx, tz);
                    if (chunk == null) chunk = new Chunk(tx, tz, worldType);
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
        // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "просто йду і можу
        // зависнути", те саме, що й у ванільному Minecraft): saveChunkToDisk
        // (файловий запис через Java Serialization) викликався ТУТ, а цей
        // метод сам викликається з update() ГОЛОВНИМ потоком (тим самим,
        // що й render()) - на відміну від генерації нових чанків
        // (chunkExecutor.submit вище), збереження вивантажених чанків було
        // СИНХРОННИМ, блокуючи кадр диском щоразу, як перетнута межа
        // loadDistance вивантажувала одразу кілька чанків. glDeleteLists
        // МУСИТЬ лишитись на головному потоці (GL-виклик), а от сам запис
        // на диск - ні, переносимо в chunkExecutor.
        chunks.entrySet().removeIf(e -> {
            long k = e.getKey();
            int x = (int) (k >> 32), z = (int) k;
            if (Math.abs(x - cx) > loadDistance || Math.abs(z - cz) > loadDistance) {
                Chunk chunk = e.getValue();
                if (chunk.displayListId > 0) {
                    org.lwjgl.opengl.GL11.glDeleteLists(chunk.displayListId, 1);
                }
                chunkExecutor.submit(() -> saveChunkToDisk(chunk));
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
        Chunk c = chunks.get(chunkKey(cx, cz));
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

    public Chunk getChunk(int x, int z) { return chunks.get(chunkKey(x, z)); }
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
            // Той самий фікс, що й у loadChunksAsync вище - запис на диск
            // ПІСЛЯ кожного зламаного/поставленого блока теж синхронно
            // блокував кадр (setBlock викликається прямо з обробника
            // кліку в головному потоці).
            chunkExecutor.submit(() -> saveChunkToDisk(c));
            // Зміна блока на самій межі чанка може розкрити/сховати грань і
            // в СУСІДНЬОМУ чанку - його display list теж застаріває.
            if (localX == 0) markDirtyIfLoaded(cx - 1, cz);
            if (localX == Chunk.SIZE - 1) markDirtyIfLoaded(cx + 1, cz);
            if (localZ == 0) markDirtyIfLoaded(cx, cz - 1);
            if (localZ == Chunk.SIZE - 1) markDirtyIfLoaded(cx, cz + 1);
        }
    }
    public Map<Long, Chunk> getChunks() { return chunks; }
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

    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "мікрофризи, коли бігаю і
    // повертаю камеру"): раніше кожен видимий блок малювався ОКРЕМИМ
    // викликом renderer.renderCube (власний glPushMatrix/glBegin/glEnd/
    // glPopMatrix на КОЖЕН блок) - для чанка з сотнями видимих блоків це
    // сотні пар begin/end на КОЖНУ перебудову display list'а (World.render,
    // MAX_REBUILDS_PER_FRAME), а біг перетинає межі чанків (і тому
    // перебудови) значно частіше за ходьбу. Тепер - ДВА проходи по масиву,
    // кожен всередині ОДНОГО спільного begin/end на весь чанк
    // (beginChunkSolid/beginChunkOutline у Renderer) - координати
    // рахуються напряму в АБСОЛЮТНИХ світових одиницях, без per-блокової
    // матриці. Видимість граней рахується двічі (по разу на прохід) - те
    // саме дешеве O(1) звернення до масиву (isBlockVisible), не String/
    // алокації, тому дублювання тут не варте ускладнення кодом.
    private void buildChunkGeometry(Chunk chunk, Renderer renderer) {
        Block[] arr = chunk.getBlocksArray();
        int baseX = chunk.getChunkX() * Chunk.SIZE, baseZ = chunk.getChunkZ() * Chunk.SIZE;
        int planeSize = Chunk.SIZE * Chunk.SIZE;

        renderer.beginChunkSolid();
        for (int i = 0; i < arr.length; i++) {
            Block block = arr[i];
            if (block == null) continue;
            int ly = i / planeSize, rem = i % planeSize, lz = rem / Chunk.SIZE, lx = rem % Chunk.SIZE;
            int x = lx + baseX, y = ly, z = lz + baseZ;
            boolean top = isBlockVisible(x, y+1, z), bottom = isBlockVisible(x, y-1, z);
            boolean front = isBlockVisible(x, y, z+1), back = isBlockVisible(x, y, z-1);
            boolean left = isBlockVisible(x-1, y, z), right = isBlockVisible(x+1, y, z);
            if (!top && !bottom && !front && !back && !left && !right) continue;
            float[] color = Block.colorFor(block.getType());
            // grid-координата (x,y,z) -> світова float-позиція через
            // Chunk.BLOCK_SIZE (див. коментар при константі) - розмір
            // куба теж BLOCK_SIZE, не 1.0, щоб суцільно стикався із
            // сусідами на новому масштабі, без перетину/щілин.
            renderer.addCubeQuads((x+0.5f)*Chunk.BLOCK_SIZE, (y+0.5f)*Chunk.BLOCK_SIZE, (z+0.5f)*Chunk.BLOCK_SIZE,
                Chunk.BLOCK_SIZE, color[0], color[1], color[2], top, bottom, front, back, left, right);
        }
        renderer.endChunkSolid();

        renderer.beginChunkOutline();
        for (int i = 0; i < arr.length; i++) {
            Block block = arr[i];
            if (block == null) continue;
            int ly = i / planeSize, rem = i % planeSize, lz = rem / Chunk.SIZE, lx = rem % Chunk.SIZE;
            int x = lx + baseX, y = ly, z = lz + baseZ;
            boolean top = isBlockVisible(x, y+1, z), bottom = isBlockVisible(x, y-1, z);
            boolean front = isBlockVisible(x, y, z+1), back = isBlockVisible(x, y, z-1);
            boolean left = isBlockVisible(x-1, y, z), right = isBlockVisible(x+1, y, z);
            if (!top && !bottom && !front && !back && !left && !right) continue;
            renderer.addCubeOutline((x+0.5f)*Chunk.BLOCK_SIZE, (y+0.5f)*Chunk.BLOCK_SIZE, (z+0.5f)*Chunk.BLOCK_SIZE,
                Chunk.BLOCK_SIZE, top, bottom, front, back, left, right);
        }
        renderer.endChunkOutline();
    }
}
