package voxelin.core;

/** Стовпчик 16 x HEIGHT x 16 із секцій 16^3; порожня секція - null (нуль пам'яті). */
public final class Chunk {
    public static final int SIZE = 16, HEIGHT = 256, SECTIONS = HEIGHT / Section.SIZE;
    public final long cx, cz;
    final Section[] sections = new Section[SECTIONS];
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

    public Section section(int sy) { return sy >= 0 && sy < SECTIONS ? sections[sy] : null; }
    void setSection(int sy, Section s) { sections[sy] = s; }

    public int nonAirCount() { int n = 0; for (Section s : sections) if (s != null) n += s.nonAirCount(); return n; }

    public ChunkPos pos() { return new ChunkPos(cx, cz); }
}
