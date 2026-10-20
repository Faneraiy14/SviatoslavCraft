package voxelin.client.render;

/** Ключ секції (cx, sy, cz) в одному long: cx і cz по 24 біти зі знаком, sy 4 біти. Діапазон чанків -8 388 608..8 388 607. */
public final class SectionKey {
    private SectionKey() {}

    public static long of(int cx, int sy, int cz) { return ((cx & 0xFFFFFFL) << 28) | ((cz & 0xFFFFFFL) << 4) | (sy & 15); }
    public static int cx(long k) { return (int) (k << 12 >> 40); }
    public static int cz(long k) { return (int) (k << 36 >> 40); }
    public static int sy(long k) { return (int) (k & 15); }
}
