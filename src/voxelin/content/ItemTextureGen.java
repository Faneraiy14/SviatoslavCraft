package voxelin.content;

/** Іконки предметів 16x16 (палиця, руди, алмаз, яблуко, інструменти), що малюються кодом: прості фігури + темний контур. Прозорість = 0. */
public final class ItemTextureGen {
    private ItemTextureGen() {}

    private static final int N = 16, OUT = 0xFF1E1A16;
    public static final int WOOD = 0, STONE = 1, IRON = 2, DIAMOND = 3;
    private static final int[][] MAT = { // основний, світлий, темний
            {0xFFB8925A, 0xFFD6B67E, 0xFF8A6A3A}, {0xFF8E8E8E, 0xFFB4B4B4, 0xFF646464},
            {0xFFDADADA, 0xFFFFFFFF, 0xFFA6A6A6}, {0xFF5DE6E0, 0xFFBDFFFB, 0xFF2FAEA8}};
    private static final int HANDLE = 0xFF7A5A30, HANDLE_HI = 0xFF9C7A48;

    private static void px(int[] a, int x, int y, int c) { if (x >= 0 && y >= 0 && x < N && y < N) a[x + y * N] = c; }

    private static void line(int[] a, int x0, int y0, int x1, int y1, int c) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            px(a, x0, y0, c);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private interface Color { int at(int x, int y, double nx, double ny); }

    /** Еліпс (центр, півосі) із кольором за пікселем; nx,ny - нормовані координати -1..1 усередині фігури (для освітлення). */
    private static void ellipse(int[] a, double cx, double cy, double rx, double ry, Color c) {
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double nx = (x + 0.5 - cx) / rx, ny = (y + 0.5 - cy) / ry;
            if (nx * nx + ny * ny <= 1) px(a, x, y, c.at(x, y, nx, ny));
        }
    }

    private static int lit(int[] mat, double nx, double ny) { return (nx + ny < -0.45) ? mat[1] : (nx + ny > 0.5 ? mat[2] : mat[0]); }

    private static int hash(int x, int y, int s) { int h = x * 73856093 ^ y * 19349663 ^ s * 83492791; h ^= h >>> 13; h *= 0x5bd1e995; return (h ^ (h >>> 15)) & 0xFFFF; }

    /** Темний контур навколо непрозорих пікселів (4 сусіди). */
    private static int[] outlined(int[] a) {
        int[] o = a.clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if ((a[x + y * N] >>> 24) != 0) continue;
            boolean near = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], ny = y + d[1];
                if (nx >= 0 && ny >= 0 && nx < N && ny < N && (a[nx + ny * N] >>> 24) != 0) near = true;
            }
            if (near) o[x + y * N] = OUT;
        }
        return o;
    }

    private static void handle(int[] a, int x0, int y0, int x1, int y1) {
        line(a, x0, y0, x1, y1, HANDLE);
        line(a, x0 + 1, y0, x1 + 1, y1, HANDLE_HI); // світліша «грань» поруч - об'єм
        line(a, x0, y0, x1, y1, HANDLE);
    }

    public static int[] stick() { int[] a = new int[256]; handle(a, 3, 13, 12, 4); return outlined(a); }

    public static int[] coal() {
        int[] a = new int[256];
        ellipse(a, 8, 8.5, 5.2, 4.6, (x, y, nx, ny) -> {
            int base = 0x2E2E32 + (hash(x, y, 3) % 5) * 0x030303;
            if (nx + ny < -0.5 && hash(x, y, 7) % 3 == 0) base = 0x5C5C66;
            return 0xFF000000 | base;
        });
        return outlined(a);
    }

    /** Сира руда: сіро-коричнева грудка з вкрапленнями кольору руди. */
    public static int[] rawOre(int spot) {
        int[] a = new int[256];
        ellipse(a, 8, 8.5, 5.4, 4.8, (x, y, nx, ny) -> {
            int c = 0xFF8C8478;
            if (nx + ny < -0.4) c = 0xFFA9A094; else if (nx + ny > 0.45) c = 0xFF6C665C;
            return hash(x, y, 11) % 5 < 2 ? 0xFF000000 | spot : c;
        });
        return outlined(a);
    }

    public static int[] diamond() {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double dx = Math.abs(x + 0.5 - 8) / 6.0, dy = Math.abs(y + 0.5 - 8.5) / 6.0;
            if (dx + dy <= 1) { double nx = (x + 0.5 - 8) / 6, ny = (y + 0.5 - 8.5) / 6; a[x + y * N] = lit(MAT[DIAMOND], nx * 1.6, ny * 1.6); }
        }
        px(a, 6, 6, 0xFFFFFFFF); px(a, 7, 5, 0xFFE8FFFE);
        return outlined(a);
    }

    public static int[] shard() {
        int[] a = new int[256];
        int[] purple = {0xFF8F6BFF, 0xFFC9B4FF, 0xFF5A3FC0};
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double dx = Math.abs(x + 0.5 - 8) / 3.4, dy = Math.abs(y + 0.5 - 8) / 7.2;
            if (dx + dy <= 1) a[x + y * N] = lit(purple, (x + 0.5 - 8) / 3.4, (y + 0.5 - 8) / 7.2);
        }
        return outlined(a);
    }

    public static int[] apple() {
        int[] a = new int[256];
        ellipse(a, 8, 9.3, 5.3, 4.9, (x, y, nx, ny) -> nx + ny < -0.6 ? 0xFFFF7B7B : (nx + ny > 0.55 ? 0xFF9E1C1C : 0xFFD62C2C));
        line(a, 8, 5, 9, 2, 0xFF6B4A24);
        px(a, 10, 3, 0xFF3E9B35); px(a, 11, 3, 0xFF3E9B35); px(a, 11, 2, 0xFF58B84C);
        px(a, 6, 7, 0xFFFFC2C2);
        return outlined(a);
    }

    public static int[] ingot(int base) {
        int[] a = new int[256];
        int[] m = {0xFF000000 | base, 0xFF000000 | shadeRgb(base, 55), 0xFF000000 | shadeRgb(base, -55)};
        for (int y = 5; y <= 11; y++) for (int x = 2; x <= 13; x++) {
            int xs = x - Math.max(0, y - 8); // скіс: нижня частина зсунута - «злиток у перспективі»
            if (xs < 2 || xs > 13 - Math.max(0, 2)) continue;
            double nx = (x - 7.5) / 6, ny = (y - 8) / 3.5;
            a[x + y * N] = y <= 6 ? m[1] : (y >= 10 ? m[2] : (x > 11 ? m[2] : m[0]));
        }
        return outlined(a);
    }

    private static int shadeRgb(int c, int d) {
        int r = Math.max(0, Math.min(255, ((c >> 16) & 255) + d)), g = Math.max(0, Math.min(255, ((c >> 8) & 255) + d)), b = Math.max(0, Math.min(255, (c & 255) + d));
        return r << 16 | g << 8 | b;
    }

    public static int[] pickaxe(int mat) {
        int[] a = new int[256], m = MAT[mat];
        handle(a, 2, 14, 11, 5);
        int[][] arc = {{4, 6}, {5, 4}, {7, 3}, {10, 2}, {12, 3}, {14, 5}, {14, 8}};
        for (int i = 0; i + 1 < arc.length; i++) {
            line(a, arc[i][0], arc[i][1], arc[i + 1][0], arc[i + 1][1], m[0]);
            line(a, arc[i][0], arc[i][1] - 1, arc[i + 1][0], arc[i + 1][1] - 1, m[1]);
            line(a, arc[i][0] + 1, arc[i][1] + 1, arc[i + 1][0] + 1, arc[i + 1][1] + 1, m[2]);
        }
        line(a, 11, 5, 12, 4, m[2]);
        return outlined(a);
    }

    public static int[] axe(int mat) {
        int[] a = new int[256], m = MAT[mat];
        handle(a, 2, 14, 10, 6);
        int[][] rows = {{2, 9, 12}, {3, 8, 13}, {4, 8, 14}, {5, 8, 14}, {6, 9, 13}, {7, 10, 12}};
        for (int[] r : rows) for (int x = r[1]; x <= r[2]; x++) a[x + r[0] * N] = (r[0] <= 3 || x == r[1]) ? m[1] : (r[0] >= 6 || x >= r[2] - 1 ? m[2] : m[0]);
        px(a, 10, 6, HANDLE);
        return outlined(a);
    }

    public static int[] shovel(int mat) {
        int[] a = new int[256], m = MAT[mat];
        handle(a, 2, 14, 10, 6);
        ellipse(a, 11.6, 4.4, 3.3, 3.6, (x, y, nx, ny) -> lit(m, nx, ny));
        px(a, 10, 6, HANDLE);
        return outlined(a);
    }
}
