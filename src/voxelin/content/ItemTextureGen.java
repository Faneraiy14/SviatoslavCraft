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

    /** Дошка: товстий скісний брусок кольору породи зі світлою гранню й волокнами. */
    public static int[] board(int base) {
        int[] a = new int[256];
        int c = 0xFF000000 | base, hi = 0xFF000000 | mix(base, 0xFFFFFF, 0.3), lo = 0xFF000000 | mix(base, 0, 0.3);
        for (int k = 0; k < 3; k++) line(a, 2 + k, 12, 12, 2 + k, c);
        line(a, 2, 11, 11, 2, hi);
        line(a, 4, 13, 13, 4, lo);
        px(a, 6, 9, lo); px(a, 9, 6, lo);
        return outlined(a);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
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

    // ------------------------------------------------------------------ верстак (текстури блока)

    private static int dark(int c, int d) { return 0xFF000000 | (shadeRgb(c & 0xFFFFFF, d)); }

    /** Верх верстака: дошки з темною рамкою й сіткою 3x3 посередині. */
    public static int[] workbenchTop() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            boolean border = x < 2 || y < 2 || x > 13 || y > 13;
            boolean grid = x >= 3 && x <= 12 && y >= 3 && y <= 12 && ((x - 3) % 3 == 0 || (y - 3) % 3 == 0);
            if (border) a[x + y * N] = dark(a[x + y * N], -55);
            else if (grid) a[x + y * N] = dark(a[x + y * N], -38);
        }
        return a;
    }

    /** Бік верстака: дошки з рамкою й силуетами пили та молотка. */
    public static int[] workbenchSide() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (x < 1 || x > 14 || y < 1 || y > 14 || y == 8) a[x + y * N] = dark(a[x + y * N], -45);
        for (int x = 3; x <= 6; x++) a[x + 4 * N] = 0xFF8E8E8E; // пила
        for (int y = 5; y <= 6; y++) a[3 + y * N] = 0xFF5A3A1A;
        for (int x = 9; x <= 12; x++) a[x + 3 * N] = 0xFF6B6B6B; // молоток
        for (int y = 3; y <= 6; y++) a[11 + y * N] = 0xFF5A3A1A;
        return a;
    }

    // ------------------------------------------------------------------ піч (текстури блока)

    public static int[] furnaceTop() {
        int[] a = TextureGen.stone().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (x < 1 || y < 1 || x > 14 || y > 14) a[x + y * N] = dark(a[x + y * N], -40);
        return a;
    }

    /** Бік печі: бруківка з чорним отвором і вогником. */
    public static int[] furnaceSide() {
        int[] a = TextureGen.cobblestone().clone();
        for (int y = 6; y <= 12; y++) for (int x = 4; x <= 11; x++) a[x + y * N] = 0xFF14110F;
        for (int x = 4; x <= 11; x++) { a[x + 5 * N] = 0xFF4A4A4E; a[x + 13 * N] = 0xFF4A4A4E; }
        for (int y = 5; y <= 13; y++) { a[3 + y * N] = 0xFF4A4A4E; a[12 + y * N] = 0xFF4A4A4E; }
        a[7 + 10 * N] = 0xFFFF9B2B; a[8 + 10 * N] = 0xFFFFC94A; a[8 + 11 * N] = 0xFFFF7A1A; a[7 + 11 * N] = 0xFFE05A10; a[8 + 9 * N] = 0xFFFFE08A;
        return a;
    }

    // ------------------------------------------------------------------ меч, їжа й здобич з істот

    public static int[] sword(int mat) {
        int[] a = new int[256], m = MAT[mat];
        line(a, 2, 14, 5, 11, HANDLE); line(a, 3, 14, 5, 12, HANDLE_HI);
        line(a, 4, 9, 8, 13, 0xFF9A7A2A); line(a, 4, 10, 7, 13, 0xFFC9A646); // гарда
        line(a, 6, 10, 14, 2, m[0]); line(a, 5, 10, 13, 2, m[1]); line(a, 7, 10, 14, 3, m[2]);
        line(a, 6, 9, 13, 2, m[0]);
        return outlined(a);
    }

    /** Окорок: м'ясо (колір base) на кістці. */
    public static int[] drumstick(int base) {
        int[] a = new int[256];
        line(a, 7, 9, 3, 13, 0xFFF0E6D0); px(a, 2, 12, 0xFFF0E6D0); px(a, 3, 14, 0xFFF0E6D0); px(a, 2, 14, 0xFFD9CDB4); px(a, 1, 13, 0xFFD9CDB4);
        int[] c = {0xFF000000 | base, 0xFF000000 | shadeRgb(base, 45), 0xFF000000 | shadeRgb(base, -45)};
        ellipse(a, 9.5, 6.5, 4.6, 4.0, (x, y, nx, ny) -> lit(c, nx, ny));
        return outlined(a);
    }

    /** Стейк: пласка шматина з жирком по краю; cooked - ґратки. */
    public static int[] steak(int base, boolean cooked) {
        int[] a = new int[256];
        int[] c = {0xFF000000 | base, 0xFF000000 | shadeRgb(base, 40), 0xFF000000 | shadeRgb(base, -40)};
        ellipse(a, 8, 8.5, 6.2, 4.6, (x, y, nx, ny) -> {
            double r = nx * nx + ny * ny;
            if (r > 0.62) return cooked ? 0xFF000000 | shadeRgb(base, -20) : 0xFFF1D6CC;
            if (cooked && ((x + y) % 4 == 0) && r < 0.5) return c[2];
            return lit(c, nx, ny);
        });
        return outlined(a);
    }

    public static int[] leather() {
        int[] a = new int[256];
        for (int y = 3; y <= 13; y++) for (int x = 4; x <= 12; x++) {
            boolean edge = x == 4 || x == 12 || y == 3 || y == 13;
            a[x + y * N] = edge ? 0xFF6B4423 : ((x + y) % 5 == 0 ? 0xFFA06A3C : 0xFF8A5A32);
        }
        for (int i = 5; i <= 11; i += 2) { px(a, i, 5, 0xFFC9A070); px(a, i, 11, 0xFFC9A070); }
        a[4 + 3 * N] = 0; a[12 + 3 * N] = 0; a[4 + 13 * N] = 0; a[12 + 13 * N] = 0;
        return outlined(a);
    }

    public static int[] feather() {
        int[] a = new int[256];
        line(a, 3, 13, 12, 3, 0xFFD8D8D8);
        for (int i = 0; i < 8; i++) { int x = 4 + i, y = 12 - i; px(a, x + 1, y, 0xFFF5F5F5); px(a, x, y + 1, 0xFFEAEAEA); if (i > 1 && i < 7) { px(a, x + 2, y, 0xFFFFFFFF); px(a, x, y + 2, 0xFFE0E0E0); } }
        px(a, 12, 2, 0xFFFFFFFF); px(a, 13, 3, 0xFFF0F0F0);
        return outlined(a);
    }

    public static int[] rottenFlesh() {
        int[] a = new int[256];
        ellipse(a, 8, 8.5, 5.6, 4.4, (x, y, nx, ny) -> {
            int h = hash(x, y, 21) % 6;
            return h == 0 ? 0xFF4A5A22 : (h == 1 ? 0xFF8C6A3A : (nx + ny < -0.4 ? 0xFF7C8C4A : 0xFF667538));
        });
        return outlined(a);
    }

    public static int[] stringItem() {
        int[] a = new int[256];
        int[][] pts = {{3, 13}, {5, 10}, {4, 8}, {8, 6}, {7, 3}, {11, 2}, {13, 5}};
        for (int i = 0; i + 1 < pts.length; i++) line(a, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], 0xFFE6E6D8);
        for (int[] p : pts) px(a, p[0], p[1], 0xFFFFFFFF);
        return outlined(a);
    }

    // ------------------------------------------------------------------ скриня (текстури блока)

    public static int[] chestTop() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (x < 1 || y < 1 || x > 14 || y > 14) a[x + y * N] = dark(a[x + y * N], -60);
        for (int x = 1; x <= 14; x++) a[x + 8 * N] = dark(a[x + 8 * N], -25);
        return a;
    }

    /** Бік скрині: дошки, залізна рамка, зверху кришка, по центру застібка. */
    public static int[] chestSide() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            boolean frame = x < 1 || y < 1 || x > 14 || y > 14 || y == 6;
            if (frame) a[x + y * N] = dark(a[x + y * N], -65);
        }
        for (int y = 5; y <= 8; y++) for (int x = 7; x <= 8; x++) a[x + y * N] = 0xFFC9C9C9;
        a[7 + 8 * N] = 0xFF8A8A8A; a[8 + 8 * N] = 0xFF8A8A8A;
        return a;
    }

    /** Верх ліжка: біла подушка вгорі, червона ковдра з відворотом, дерев'яна рамка. */
    public static int[] bedTop() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 1; y <= 14; y++) for (int x = 1; x <= 14; x++) {
            int c;
            if (y <= 4) c = (x == 1 || x == 14 || y == 1) ? 0xFFC8C8C0 : 0xFFF0EEE6; // подушка
            else if (y == 5) c = 0xFFE8E0D8; // відворот простирадла
            else c = ((x + y * 3) % 7 == 0) ? 0xFF9E2222 : 0xFFB82C2C; // ковдра
            a[x + y * N] = c;
        }
        return a;
    }

    /** Бік ліжка: червона ковдра звисає над дерев'яною основою. */
    public static int[] bedSide() {
        int[] a = TextureGen.oakPlanks().clone();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            if (y < 2) a[x + y * N] = 0xFFB82C2C;
            else if (y < 8) a[x + y * N] = y == 7 ? 0xFF7A1A1A : (x % 5 == 0 ? 0xFF9E2222 : 0xFFB82C2C);
            else if (y > 12 && (x < 2 || x > 13)) a[x + y * N] = dark(a[x + y * N], -40); // ніжки
            else if (y > 12) a[x + y * N] = 0xFF2A2018;
            else a[x + y * N] = dark(a[x + y * N], -10);
        }
        return a;
    }

    // ------------------------------------------------------------------ броня

    private static final int[][] ARMOR_MAT = { // шкіра, залізо, алмаз: основний, світлий, темний
            {0xFFA06A3C, 0xFFC48E5C, 0xFF70482A}, {0xFFDADADA, 0xFFFFFFFF, 0xFFA6A6A6}, {0xFF5DE6E0, 0xFFBDFFFB, 0xFF2FAEA8}};
    private static final String[][] ARMOR_SHAPE = {
            { // шолом
                    "................", "................", "....hhhhhhhh....", "...hhhhhhhhhh...", "..hhhhhhhhhhhh..", "..hhhhhhhhhhhh..", "..hhhhhhhhhhhh..", "..hhh......hhh..", "..hhh......hhh..", "..dd........dd.."},
            { // нагрудник
                    "................", "..hhh......hhh..", "..hhhh....hhhh..", "..hhhhhhhhhhhh..", "..hhhhhhhhhhhh..", "..hh.hhhhhh.hh..", "..hh.hhhhhh.hh..", "....hhhhhhhh....", "....hhhhhhhh....", "....hhhhhhhh....", "....dddddddd...."},
            { // поножі
                    "................", "....hhhhhhhh....", "....hhhhhhhh....", "....hhhhhhhh....", "....hhh..hhh....", "....hhh..hhh....", "....hhh..hhh....", "....hhh..hhh....", "....ddd..ddd...."},
            { // черевики
                    "................", "................", "................", "...hhh....hhh...", "...hhh....hhh...", "...hhh....hhh...", "..hhhh....hhhh..", "..dddd....dddd.."}};

    /** Іконка броні: slot 0 шолом, 1 нагрудник, 2 поножі, 3 черевики; mat 0 шкіра, 1 залізо, 2 алмаз. */
    public static int[] armor(int slot, int mat) {
        int[] a = new int[256], m = ARMOR_MAT[mat];
        String[] shape = ARMOR_SHAPE[slot];
        int top = shape.length, y0 = (N - shape.length) / 2;
        for (int r = 0; r < shape.length; r++) {
            String row = shape[r];
            int first = row.indexOf('h') >= 0 ? row.indexOf('h') : row.indexOf('d');
            for (int x = 0; x < row.length(); x++) {
                char c = row.charAt(x);
                if (c == '.') continue;
                int col = c == 'd' ? m[2] : (r <= 2 || x == first ? m[1] : (x > first + 8 || (c == 'h' && r >= shape.length - 3) ? m[2] : m[0]));
                if (c == 'h' && r > 2 && x != first && col == m[1]) col = m[0];
                px(a, x, r + Math.max(0, y0 - 1), col);
            }
        }
        return outlined(a);
    }

    // ------------------------------------------------------------------ Енд: блоки й Око Енду

    private static int[] noise(int base, int amp, int seed) {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int d = (hash(x, y, seed) % (2 * amp + 1)) - amp;
            a[x + y * N] = 0xFF000000 | shadeRgb(base, d);
        }
        return a;
    }

    public static int[] endStone() {
        int[] a = noise(0xDDD8A0, 14, 41);
        for (int i = 0; i < 256; i++) if (hash(i & 15, i >> 4, 42) % 9 == 0) a[i] = 0xFF000000 | shadeRgb(0xDDD8A0, -34);
        return a;
    }

    public static int[] obsidian() {
        int[] a = noise(0x1B1228, 7, 51);
        for (int i = 0; i < 256; i++) if (hash(i & 15, i >> 4, 52) % 11 == 0) a[i] = 0xFF4A2A78;
        return a;
    }

    private static int[] frameBase() {
        int[] a = noise(0x2E4A3C, 8, 61);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if (x < 1 || y < 1 || x > 14 || y > 14) a[x + y * N] = 0xFF1B2C24;
        return a;
    }

    public static int[] frameSide() { int[] a = frameBase(); for (int x = 3; x <= 12; x++) { a[x + 4 * N] = 0xFF4F7A64; a[x + 11 * N] = 0xFF1B2C24; } return a; }

    /** Верх рамки: гніздо для ока; eye - з оком (зелена зіниця). */
    public static int[] frameTop(boolean eye) {
        int[] a = frameBase();
        for (int y = 4; y <= 11; y++) for (int x = 4; x <= 11; x++) a[x + y * N] = 0xFF0F1A15;
        if (eye) {
            for (int y = 4; y <= 11; y++) for (int x = 4; x <= 11; x++) {
                double dx = x - 7.5, dy = y - 7.5, d = Math.hypot(dx, dy);
                if (d <= 3.8) a[x + y * N] = d <= 1.6 ? 0xFF0B2A12 : 0xFF2FD16A;
            }
        }
        return a;
    }

    /** Портал: напівпрозорий фіолетово-синій вир. */
    public static int[] portal() {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int h = hash(x / 2, y / 2, 71) % 5;
            int c = new int[]{0xFF3A1B7A, 0xFF5A2BB0, 0xFF2B2F9A, 0xFF7A3AD0, 0xFF1B1058}[h];
            a[x + y * N] = (170 << 24) | (c & 0xFFFFFF);
        }
        return a;
    }

    /** Око Енду: зелене око з вертикальною зіницею. */
    public static int[] eye() {
        int[] a = new int[256];
        ellipse(a, 8, 8, 6.2, 6.2, (x, y, nx, ny) -> nx * nx + ny * ny > 0.62 ? 0xFF1F9E55 : (nx * nx + ny * ny > 0.12 ? 0xFF2FD16A : 0xFF0B2A12));
        px(a, 6, 5, 0xFFB6FFD0); px(a, 7, 5, 0xFFB6FFD0); px(a, 6, 6, 0xFF8FF0B4);
        return outlined(a);
    }

    /** Яйце дракона: темно-фіолетовий овал із сяючими крапками. */
    public static int[] dragonEgg() {
        int[] a = noise(0x1A0A2C, 6, 81);
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double dx = (x - 7.5) / 6.5, dy = (y - 7.5) / 7.5;
            if (dx * dx + dy * dy > 1) a[x + y * N] = 0xFF0A0414;
            else if (hash(x, y, 83) % 7 == 0) a[x + y * N] = 0xFFB040FF;
            else if (hash(x, y, 84) % 11 == 0) a[x + y * N] = 0xFF5A20A0;
        }
        return a;
    }

    /** Ядро Шву: яйце, проросле фіолетовими жилами. */
    public static int[] seamCore() {
        int[] a = dragonEgg();
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) if ((x + y * 3 + hash(x, y, 91) % 3) % 7 == 0) a[x + y * N] = 0xFFE070FF;
        return a;
    }

    /** Наріст Шву: сіро-фіолетова тканина з жилками. */
    public static int[] seamGrowth() {
        int[] a = noise(0x4A3458, 12, 93);
        for (int i = 0; i < 256; i++) { int h = hash(i & 15, i >> 4, 94) % 13; if (h == 0) a[i] = 0xFFA060E0; else if (h == 1) a[i] = 0xFF2A1A36; }
        return a;
    }

    /** Шрам Шву: обпалена чорно-сіра порода. */
    public static int[] seamScar() {
        int[] a = noise(0x2A2628, 9, 95);
        for (int i = 0; i < 256; i++) if (hash(i & 15, i >> 4, 96) % 10 == 0) a[i] = 0xFF5A3A2A;
        return a;
    }

    /** Пляшечка з рідиною (зілля/вогонь Шву). */
    public static int[] flask(int liquid) {
        int[] a = new int[256];
        for (int y = 2; y <= 4; y++) for (int x = 7; x <= 8; x++) px(a, x, y, 0xFF8A6A4A);
        for (int y = 5; y <= 6; y++) for (int x = 7; x <= 8; x++) px(a, x, y, 0xFFD8E8F0);
        ellipse(a, 8, 10, 4.6, 4.6, (x, y, nx, ny) -> ny < -0.3 ? 0xFFD8E8F0 : (0xFF000000 | liquid));
        px(a, 6, 8, 0xFFFFFFFF);
        return outlined(a);
    }

    /** Якір Шву: фіолетовий якір із сяйвом. */
    public static int[] anchor() {
        int[] a = new int[256]; int c = 0xFF7A4AD0, l = 0xFFC8A0FF;
        for (int y = 2; y <= 12; y++) px(a, 7, y, c);
        for (int y = 2; y <= 12; y++) px(a, 8, y, l);
        for (int x = 4; x <= 11; x++) px(a, x, 5, c);
        for (int x = 3; x <= 12; x++) { int y = 12 - (int) Math.round(Math.abs(x - 7.5) * 0.6); px(a, x, y, c); px(a, x, y + 1, l); }
        px(a, 7, 1, l); px(a, 8, 1, l);
        return outlined(a);
    }

    /** Серце Шву: сяюче рожево-фіолетове серце. */
    public static int[] heart() {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            double nx = (x - 7.5) / 6.5, ny = (y - 7) / 6.5;
            double v = Math.pow(nx * nx + ny * ny - 0.6, 3) - nx * nx * Math.pow(-ny, 3) * 1.4;
            if (v <= 0) a[x + y * N] = hash(x, y, 97) % 5 == 0 ? 0xFFFFF0FF : (y < 6 ? 0xFFF0C0FF : 0xFFC060E0);
        }
        return outlined(a);
    }

    /** Пороговий камінь: блідо-сірий з холодними прожилками. */
    public static int[] thresholdStone() {
        int[] a = noise(0x8A8894, 10, 101);
        for (int i = 0; i < 256; i++) { int h = hash(i & 15, i >> 4, 102) % 17; if (h == 0) a[i] = 0xFFB8B0D8; else if (h == 1) a[i] = 0xFF5E5C6A; }
        return a;
    }

    /** Нитка Шву: волокна сіро-фіолетового кольору. */
    public static int[] seamThread() {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int band = (x + y / 3 + hash(0, y, 103) % 2) % 4;
            a[x + y * N] = band == 0 ? 0xFFC8B8F0 : band == 1 ? 0xFF9A88C8 : band == 2 ? 0xFF7A6AA8 : 0xFF5A4A88;
        }
        return a;
    }

    /** Розрив: напівпрозорий сіро-фіолетовий вир. */
    public static int[] rift() {
        int[] a = new int[256];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) {
            int c = new int[]{0xFF9A70E0, 0xFFC8A8FF, 0xFF6A40B0, 0xFFE8D8FF, 0xFF4A2A80}[hash(x / 2, y, 104) % 5];
            a[x + y * N] = (150 << 24) | (c & 0xFFFFFF);
        }
        return a;
    }

    /** Квітка Порогу: бліда світна квітка на прозорому тлі. */
    public static int[] thresholdBloom() {
        int[] a = new int[256];
        for (int y = 9; y <= 15; y++) px(a, 7 + (y % 3 == 0 ? 1 : 0), y, 0xFF5A8A70);
        px(a, 6, 12, 0xFF5A8A70); px(a, 5, 11, 0xFF6A9A80); px(a, 9, 13, 0xFF5A8A70); px(a, 10, 12, 0xFF6A9A80);
        ellipse(a, 8, 6, 3.6, 3.6, (x, y, nx, ny) -> nx * nx + ny * ny < 0.2 ? 0xFFFFF8C0 : 0xFFD8E8FF);
        px(a, 5, 4, 0xFFB8C8FF); px(a, 11, 8, 0xFFB8C8FF);
        return a;
    }

    /** Ключ Порогу: сріблястий ключ з лімінітовим оком. */
    public static int[] thresholdKey() {
        int[] a = new int[256];
        ellipse(a, 5, 5, 3.4, 3.4, (x, y, nx, ny) -> nx * nx + ny * ny < 0.25 ? 0xFF8F6BFF : 0xFFC8C8D8);
        for (int k = 0; k < 8; k++) { px(a, 7 + k, 7 + k, 0xFFC8C8D8); px(a, 8 + k, 7 + k, 0xFF8A8A9A); }
        px(a, 11, 13, 0xFFC8C8D8); px(a, 12, 12, 0xFFC8C8D8); px(a, 13, 15, 0xFFC8C8D8);
        return outlined(a);
    }

    /** Крила Шву: два розкриті крила з пір'ям (ліве й дзеркальне праве). */
    public static int[] wings() {
        int[] a = new int[256];
        for (int k = 0; k < 5; k++) { // пір'їни зверху вниз, коротшають донизу
            int y0 = 3 + k * 2, len = 6 - k;
            for (int i = 0; i <= len; i++) {
                int y = y0 + i / 3, c = i == len ? 0xFFE0D0FF : (k < 2 ? 0xFFA890D8 : 0xFF7A68A8);
                if (y > 14) continue;
                px(a, 6 - i, y, c); px(a, 9 + i, y, c);
                if (y + 1 <= 14) { px(a, 6 - i, y + 1, 0xFF6A5898); px(a, 9 + i, y + 1, 0xFF6A5898); }
            }
        }
        for (int y = 3; y <= 9; y++) { px(a, 7, y, 0xFF4A3A70); px(a, 8, y, 0xFF4A3A70); }
        return outlined(a);
    }

    /** Пил: купка дрібних світлих крупинок. */
    public static int[] dust(int rgb) {
        int[] a = new int[256];
        for (int y = 6; y < 15; y++) for (int x = 2; x < 14; x++) {
            double nx = (x - 7.5) / 5.5, ny = (y - 14) / 7.0;
            if (nx * nx + ny * ny <= 1 && hash(x, y, 105) % 3 != 0) a[x + y * N] = hash(x, y, 106) % 4 == 0 ? 0xFFFFFFFF : 0xFF000000 | shadeRgb(rgb, -(hash(x, y, 107) % 40));
        }
        return a;
    }
}
