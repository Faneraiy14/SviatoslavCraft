package voxelin.core;

/** Стовпчик 16 x HEIGHT x 16 із секцій 16^3; порожня секція - null (нуль пам'яті). */
public final class Chunk {
    public static final int SIZE = 16, HEIGHT = 256, SECTIONS = HEIGHT / Section.SIZE;
    public final long cx, cz;
    final Section[] sections = new Section[SECTIONS];
    /**
     * Світло: по секції масив 4096 (старші 4 біти - небесне, молодші - від блоків); null = «усе небо 15, від блоків 0» (повітря над землею
     * не займає пам'яті). Індекс клітинки x | z<<4 | y<<8 (y 0..255) - секція sy = idx>>12. На диск не пишеться: перераховується при завантаженні.
     */
    final byte[][] light = new byte[SECTIONS][];
    /** Сусідні чанки: 0 = +x, 1 = -x, 2 = +z, 3 = -z (null - не завантажений). Вказівники веде світ на головному потоці; потрібні світлу. */
    final Chunk[] nb = new Chunk[4];
    /** Змінено після завантаження/генерації - треба зберегти. */
    volatile boolean modified;

    public Chunk(long cx, long cz) { this.cx = cx; this.cz = cz; }

    public int get(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) return 0;
        Section s = sections[y >> 4];
        return s == null ? 0 : s.get(x, y & 15, z);
    }

    /** Повертає попередній id. */
    public int set(int x, int y, int z, int id) {
        if (y < 0 || y >= HEIGHT) return 0;
        Section s = sections[y >> 4];
        if (s == null) { if (id == 0) return 0; s = sections[y >> 4] = new Section(); }
        int old = s.set(x, y & 15, z, id);
        if (old != id) modified = true;
        return old;
    }

    public int sky(int x, int y, int z) { return y < 0 ? 0 : (y >= HEIGHT ? 15 : skyIdx(x | (z << 4) | (y << 8))); }
    public int blockLight(int x, int y, int z) { return y < 0 || y >= HEIGHT ? 0 : blkIdx(x | (z << 4) | (y << 8)); }
    public Chunk neighbor(int dir) { return nb[dir]; }

    int skyIdx(int idx) { byte[] a = light[idx >> 12]; return a == null ? 15 : (a[idx & 4095] >> 4) & 15; }
    int blkIdx(int idx) { byte[] a = light[idx >> 12]; return a == null ? 0 : a[idx & 4095] & 15; }

    void setSkyIdx(int idx, int v) {
        byte[] a = light[idx >> 12];
        if (a == null) { if (v == 15) return; a = light[idx >> 12] = filled(); }
        a[idx & 4095] = (byte) ((a[idx & 4095] & 15) | (v << 4));
    }

    void setBlkIdx(int idx, int v) {
        byte[] a = light[idx >> 12];
        if (a == null) { if (v == 0) return; a = light[idx >> 12] = filled(); }
        a[idx & 4095] = (byte) ((a[idx & 4095] & 0xF0) | v);
    }

    private static byte[] filled() { byte[] a = new byte[4096]; java.util.Arrays.fill(a, (byte) 0xF0); return a; }

    /** id блока за індексом клітинки (0 - повітря). */
    int idAt(int idx) { Section s = sections[idx >> 12]; return s == null ? 0 : s.blocks[idx & 4095]; }

    public Section section(int sy) { return sy >= 0 && sy < SECTIONS ? sections[sy] : null; }
    void setSection(int sy, Section s) { sections[sy] = s; }

    public int nonAirCount() { int n = 0; for (Section s : sections) if (s != null) n += s.nonAirCount(); return n; }

    public ChunkPos pos() { return new ChunkPos(cx, cz); }
}
