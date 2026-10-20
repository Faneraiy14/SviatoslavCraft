package com.sviatoslav.craft.engine.world;

import java.io.Serializable;
import java.util.Random;

public class Chunk implements Serializable {
    // Версію піднято - формат збереження ЗМІНЕНО (HashMap<String,Block> ->
    // плаский масив, див. коментар при `blocks` нижче), старі файли
    // saves/world1/*.dat більше НЕ сумісні (readObject кине
    // InvalidClassException, loadChunkFromDisk у World це ловить і просто
    // згенерує чанк заново на тому самому місці - не крах, але старі
    // побудови гравця на тій ділянці не відновляться).
    private static final long serialVersionUID = 2L;
    public static final int SIZE = 16;
    // Скільки по висоті може сягати чанк (генерація рельєфу - максимум
    // ~9-10, дерева - ще трохи вище; із запасом під ручну забудову
    // гравцем).
    public static final int MAX_HEIGHT = 64;
    // Sviatoslav попросив "блоки більшими, але щоб не перетинались" -
    // тобто розмір блока = відстань між сусідніми (як і зараз, 1:1), лише
    // сам масштаб більший. ОДНА спільна константа для всього світу:
    // рендер (World/Renderer), колізія й рух (Player), промінь прицілу
    // (BlockRayCast) - усі переводять між ЦІЛОЧИСЕЛЬНОЮ grid-координатою
    // блока (як і раніше, не змінюється - сховище/isBlockVisible/індекси
    // чанків і далі в grid-одиницях) і СВІТОВОЮ float-позицією через
    // множення/ділення на цю константу. Якщо десь забути - блоки виглядали
    // б більшими, а колізія/промінь і далі рахували по-старому - гравець
    // проходив би крізь "повітря", яке насправді вже частина куба.
    public static final float BLOCK_SIZE = 1.5f;
    private int chunkX, chunkZ;
    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "фризи, коли чанки
    // з'являються/вивантажуються"): раніше тут був `HashMap<String,Block>`
    // з ключем "x,y,z" - КОЖЕН getBlock/setBlock будував новий String
    // (конкатенація + подальше хешування). getBlock викликається
    // величезну кількість разів САМЕ під час перебудови display list'а
    // (World.isBlockVisible - до 6 разів на КОЖЕН блок чанка, для
    // перевірки видимості кожної грані) - для чанка з ~1000-2000 блоків
    // це тисячі короткоживучих String-об'єктів одразу, що ставали сміттям
    // у той самий момент. Перебудова відбувається саме тоді, коли чанк
    // щойно згенерувався чи сусід вивантажився (dirty-позначка) - тому
    // сплески сміття були синхронізовані ТОЧНО з появою/вивантаженням
    // чанків, як і описав Sviatoslav. Плаский масив з прямим індексом
    // (x + z*SIZE + y*SIZE*SIZE) - доступ за O(1) без виділення пам'яті
    // й без хешування рядка.
    private Block[] blocks = new Block[SIZE * SIZE * MAX_HEIGHT];
    private transient Random random;

    private static int idx(int x, int y, int z) { return x + z * SIZE + y * SIZE * SIZE; }
    private static boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE && z >= 0 && z < SIZE && y >= 0 && y < MAX_HEIGHT;
    }

    // Display list для батчингу рендеру (НЕ серіалізується - лише
    // рантайм-стан OpenGL). displayListId <= 0 означає "ще не побудований"
    // (glGenLists() ніколи не повертає 0) - навмисно не покладаємось на
    // дефолт transient-поля після десеріалізації (Java НЕ виконує
    // ініціалізатори полів при readObject, лише обнулює transient-поля),
    // тому displayListId<=0 сам собою вже коректний сигнал "треба зібрати"
    // без додаткової логіки в readObject.
    public transient int displayListId = -1;
    public transient boolean dirty = true;

    // РЕАЛЬНА фіча (Sviatoslav попросив - екран створення світу: "плаский,
    // звичайний і налаштування"): тип рельєфу вирішується ЛИШЕ в момент
    // генерації (тут), не зберігається як поле Chunk - для вже
    // ЗБЕРЕЖЕНОГО (десеріалізованого) чанка тип узагалі не має значення,
    // блоки вже готові. Плаский - без дерев (як "Superflat" у Minecraft).
    public enum WorldType { NORMAL, FLAT }
    private static final int FLAT_HEIGHT = 4;

    public Chunk(int chunkX, int chunkZ, WorldType type) {
        this.chunkX = chunkX; this.chunkZ = chunkZ;
        this.random = new Random(chunkX * 1000 + chunkZ);
        generateTerrain(type);
        if (type != WorldType.FLAT) generateTrees();
    }

    // РЕАЛЬНИЙ БАГ (знайдено Sviatoslav'ом живцем - провалився під світ):
    // формула висоти могла дати 0 чи від'ємне число (амплітуда двох
    // синусоїд разом до ±5 навколо базових 4) - для такої колонки цикл
    // нижче `for y=0; y<height` не виконувався ЖОДНОГО разу, тобто блоків
    // не ставилось УЗАГАЛІ. Не "вода" (такого типу блока в грі й немає),
    // а справжня наскрізна діра в нікуди - те, що виглядало озером, це
    // просто колір неба, видний крізь порожнє провалля. MIN_HEIGHT
    // гарантує хоч якийсь суцільний шар землі в БУДЬ-ЯКІЙ точці світу.
    private static final int MIN_HEIGHT = 2;

    private void generateTerrain(WorldType type) {
        for (int x = 0; x < SIZE; x++) for (int z = 0; z < SIZE; z++) {
            int wx = x + chunkX * SIZE, wz = z + chunkZ * SIZE;
            int height;
            if (type == WorldType.FLAT) {
                height = FLAT_HEIGHT;
            } else {
                height = (int)(4 + Math.sin(wx*0.05)*Math.cos(wz*0.05)*3 + Math.sin(wx*0.1 + wz*0.08)*2);
                height = Math.max(height, MIN_HEIGHT);
            }
            for (int y = 0; y < height; y++) {
                Block.Type blockType = (y == height-1) ? Block.Type.GRASS : (y > height-4) ? Block.Type.DIRT : Block.Type.STONE;
                setBlock(x, y, z, new Block(blockType, x, y, z));
            }
        }
    }

    private void generateTrees() {
        if (random == null) random = new Random(chunkX * 1000 + chunkZ);
        for (int i = 0; i < 2 + random.nextInt(3); i++) {
            int x = random.nextInt(SIZE), z = random.nextInt(SIZE);
            int height = Math.max((int)(4 + Math.sin((x+chunkX*SIZE)*0.05)*Math.cos((z+chunkZ*SIZE)*0.05)*3), MIN_HEIGHT);
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

    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "FPS 11 само по собі"):
    // getBlockCount() нижче раніше сканував УВЕСЬ масив (16*16*64=16384
    // клітинок) на кожен виклик, а F3 (DebugOverlay) викликає його для
    // КОЖНОГО завантаженого чанка (81 при loadDistance=4) ЩОКАДРУ, поки
    // відкритий F3 - понад мільйон зайвих перевірок на кадр лише заради
    // цифри "BLOCKS" на екрані. Тепер лічильник оновлюється ТУТ, в
    // setBlock (єдине місце, де масив взагалі змінюється) - getBlockCount
    // стає миттєвим (просто повертає готове число), як і було з
    // HashMap.size() до переходу на масив.
    private int blockCount = 0;

    public void setBlock(int x, int y, int z, Block block) {
        if (!inBounds(x, y, z)) return;
        int i = idx(x, y, z);
        boolean wasBlock = blocks[i] != null;
        boolean willBeBlock = block.getType() != Block.Type.AIR;
        if (wasBlock && !willBeBlock) blockCount--;
        else if (!wasBlock && willBeBlock) blockCount++;
        blocks[i] = willBeBlock ? block : null;
        dirty = true;
    }
    public Block getBlock(int x, int y, int z) {
        return inBounds(x, y, z) ? blocks[idx(x, y, z)] : null;
    }
    // Прямий доступ до масиву (World.buildChunkGeometry ітерує за
    // індексом, обчислюючи lx/ly/lz назад через idx() - без .values()/
    // ітератора HashMap, яких тут уже нема).
    public Block[] getBlocksArray() { return blocks; }
    public int getBlockCount() { return blockCount; }
    public int getChunkX() { return chunkX; } public int getChunkZ() { return chunkZ; }
}
