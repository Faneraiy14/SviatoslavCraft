package voxelin.core;

/**
 * Секція чанка 16x16x16: блоки - short[4096] (індекс x + z*16 + y*256). Ніяких об'єктів на блок.
 * version росте при кожній зміні - рендер так дізнається, що меш застарів.
 */
public final class Section {
    public static final int SIZE = 16, VOLUME = SIZE * SIZE * SIZE;
    final short[] blocks = new short[VOLUME];
    private int nonAir;
    public volatile int version;

    public static int index(int x, int y, int z) { return x | (z << 4) | (y << 8); }

    public int get(int x, int y, int z) { return blocks[index(x, y, z)]; }

    /** Повертає попередній id. */
    public int set(int x, int y, int z, int id) {
        int i = index(x, y, z);
        int old = blocks[i];
        if (old == id) return old;
        blocks[i] = (short) id;
        if (old == 0) nonAir++;
        if (id == 0) nonAir--;
        version++;
        return old;
    }

    public boolean isEmpty() { return nonAir == 0; }
    public int nonAirCount() { return nonAir; }
    /** Прямий доступ для збереження/мешингу (не змінювати ззовні ядра). */
    public short[] raw() { return blocks; }

    void recount() { int n = 0; for (short b : blocks) if (b != 0) n++; nonAir = n; }
}
