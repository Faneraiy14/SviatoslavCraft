package voxelin.content;

/**
 * Типові текстури 16x16, що малюються кодом (піксель-арт зі «шумом» від детермінованого хешу назви й координат). Власні PNG гравця
 * (textures/<простір>/<назва>.png) перекривають ці. Колір - 0xAARRGGBB. Чиста Java, без OpenGL.
 */
public final class TextureGen {
    private TextureGen() {}

    public static final int N = 16;

    // ------------------------------------------------------------------ допоміжне

    private static long hash(long seed, int x, int y) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return h;
    }

    /** Число 0..1 для пікселя. */
    private static double rnd(long seed, int x, int y) { return (hash(seed, x, y) >>> 11) / (double) (1L << 53); }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    public static int rgb(int r, int g, int b) { return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }
    public static int argb(int a, int r, int g, int b) { return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b); }

    private static int shade(int c, int d) { return argb(c >>> 24, ((c >> 16) & 255) + d, ((c >> 8) & 255) + d, (c & 255) + d); }

    /** Базовий колір із випадковим відхиленням яскравості на піксель (amp - макс. відхилення). */
    private static int speck(int base, int amp, long seed, int x, int y) {
        int d = (int) Math.round((rnd(seed, x, y) * 2 - 1) * amp);
        return shade(base, d);
    }

    private interface Px { int at(int x, int y); }

    private static int[] make(Px f) {
        int[] a = new int[N * N];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) a[x + y * N] = f.at(x, y);
        return a;
    }

    /** Середній колір (з урахуванням прозорості) - для іконок і запасного кольору блока. */
    public static int average(int[] px) {
        long r = 0, g = 0, b = 0, w = 0;
        for (int c : px) { int a = c >>> 24; r += ((c >> 16) & 255) * a; g += ((c >> 8) & 255) * a; b += (c & 255) * a; w += a; }
        if (w == 0) return 0x808080;
        return (int) (r / w) << 16 | (int) (g / w) << 8 | (int) (b / w);
    }

    // ------------------------------------------------------------------ основа: камінь і земля

    public static int[] stone() { return make((x, y) -> {
        int c = speck(rgb(125, 125, 129), 13, 1, x, y);
        if (rnd(11, x / 2, y / 2) < 0.16) c = shade(c, -22);
        return c;
    }); }

    public static int[] deepStone() { return make((x, y) -> {
        int c = speck(rgb(76, 77, 88), 11, 2, x, y);
        if (rnd(12, x / 2, y / 2) < 0.2) c = shade(c, -16);
        return c;
    }); }

    public static int[] cobblestone() { return make((x, y) -> {
        int cx = x / 4, cy = y / 4; // 4x4 «камені» із зсувом рядків
        int ox = (cy % 2) * 2, bx = (x + ox) / 4;
        boolean edge = (x + ox) % 4 == 0 || y % 4 == 0;
        int c = speck(rgb(118, 118, 122), 18, 3 + bx * 7 + cy * 13, x, y);
        c = shade(c, (int) ((rnd(20, bx, cy) - 0.5) * 36));
        return edge ? shade(c, -42) : c;
    }); }

    public static int[] stoneBricks() { return make((x, y) -> {
        int row = y / 4, off = (row % 2) * 4;
        boolean mortar = y % 4 == 3 || (x + off) % 8 == 7;
        int c = speck(rgb(132, 132, 138), 8, 4, x, y);
        return mortar ? rgb(88, 88, 92) : shade(c, (int) ((rnd(21, (x + off) / 8, row) - 0.5) * 18));
    }); }

    public static int[] dirt() { return make((x, y) -> {
        int c = speck(rgb(134, 96, 67), 15, 5, x, y);
        if (rnd(13, x, y) < 0.08) c = shade(c, -30);
        return c;
    }); }

    public static int[] mud() { return make((x, y) -> {
        int c = speck(rgb(78, 62, 50), 7, 6, x, y);
        if (rnd(14, x, y) < 0.07) c = shade(c, 22);
        return c;
    }); }

    public static int[] clay() { return make((x, y) -> speck(rgb(160, 166, 181), 5, 7, x, y)); }

    public static int[] gravel() { return make((x, y) -> {
        int g = 90 + (int) (rnd(8, x / 3, y / 3) * 80);
        return speck(rgb(g, g - 2, g - 4), 12, 9, x, y);
    }); }

    public static int[] sand() { return make((x, y) -> speck(rgb(219, 207, 163), 8, 10, x, y)); }

    public static int[] sandstone() { return make((x, y) -> {
        int c = speck(rgb(213, 198, 150), 6, 15, x, y);
        return y % 5 == 4 ? shade(c, -26) : c;
    }); }

    public static int[] sinteredSand() { return make((x, y) -> {
        int c = speck(rgb(226, 218, 186), 7, 16, x, y);
        if (((x + y) % 7 == 0) || rnd(17, x, y) < 0.05) c = rgb(196, 232, 238); // скляні прожилки
        return c;
    }); }

    public static int[] snow() { return make((x, y) -> speck(rgb(246, 249, 252), 4, 17, x, y)); }

    public static int[] bedrock() { return make((x, y) -> {
        int c = speck(rgb(46, 46, 49), 14, 18, x, y);
        if (rnd(19, x / 2, y / 2) < 0.22) c = shade(c, 30);
        return c;
    }); }

    // ------------------------------------------------------------------ трава, вода, лід, скло

    public static int[] grassTop() { return make((x, y) -> speck(rgb(92, 160, 52), 14, 22, x, y)); }

    public static int[] grassSide() { return make((x, y) -> {
        int fringe = 2 + (int) (rnd(23, x, 0) * 3); // нерівний зелений край зверху
        if (y < fringe) return speck(rgb(92, 160, 52), 14, 22, x, y);
        int c = speck(rgb(134, 96, 67), 15, 5, x, y);
        return y == fringe ? shade(c, -18) : c;
    }); }

    public static int[] paleGrassTop() { return make((x, y) -> speck(rgb(164, 177, 148), 10, 24, x, y)); }

    public static int[] paleGrassSide() { return make((x, y) -> {
        int fringe = 2 + (int) (rnd(25, x, 0) * 3);
        if (y < fringe) return speck(rgb(164, 177, 148), 10, 24, x, y);
        return speck(rgb(120, 98, 84), 12, 5, x, y);
    }); }

    public static int[] water() { return make((x, y) -> {
        int c = argb(150, 44, 92, 204);
        if (((x * 2 + y) % 9 == 0) || rnd(26, x, y) < 0.08) c = argb(165, 90, 140, 235);
        return c;
    }); }

    public static int[] ice() { return make((x, y) -> {
        int c = argb(170, 160, 204, 238);
        if (((x + y) % 6 == 0) || ((x - y + 32) % 11 == 0)) c = argb(190, 205, 232, 250);
        return c;
    }); }

    public static int[] glass() { return make((x, y) -> {
        boolean frame = x == 0 || y == 0 || x == N - 1 || y == N - 1;
        if (frame) return argb(235, 200, 222, 232);
        boolean spark = (x == 3 && y >= 3 && y <= 5) || (x == 4 && y == 3) || (x == 3 && y == 3);
        return spark ? argb(200, 255, 255, 255) : argb(45, 210, 235, 245);
    }); }

    public static int[] duneGlass() { return make((x, y) -> {
        boolean frame = x == 0 || y == 0 || x == N - 1 || y == N - 1;
        int swirl = (int) (rnd(27, x / 3, y / 3) * 40);
        if (frame) return argb(230, 215, 190 + swirl / 3, 130);
        return argb(105, 230, 190 + swirl, 120 + swirl);
    }); }

    // ------------------------------------------------------------------ дерева

    private static int[] logSide(int r, int g, int b, int streak) { return make((x, y) -> {
        int c = speck(rgb(r, g, b), 6, 30, x, 0);
        c = shade(c, (int) ((rnd(31, x, y / 5) - 0.5) * streak));
        return c;
    }); }

    private static int[] logTop(int r, int g, int b) { return make((x, y) -> {
        double d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
        if (d > 6.5) return speck(rgb(r - 60, g - 50, b - 35), 6, 32, x, y); // кора по краю
        int ring = (int) d;
        return ring % 2 == 0 ? speck(rgb(r, g, b), 5, 33, x, y) : speck(rgb(r - 22, g - 20, b - 16), 5, 34, x, y);
    }); }

    public static int[] oakLog() { return logSide(101, 76, 44, 40); }
    public static int[] oakLogTop() { return logTop(176, 142, 88); }
    public static int[] birchLog() { return make((x, y) -> {
        int c = speck(rgb(226, 223, 214), 6, 35, x, y);
        return rnd(36, x / 2, y / 3) < 0.18 && (y % 3 != 0) ? rgb(40, 38, 36) : c;
    }); }
    public static int[] birchLogTop() { return logTop(196, 176, 126); }
    public static int[] spruceLog() { return logSide(62, 45, 28, 34); }
    public static int[] spruceLogTop() { return logTop(120, 90, 54); }
    public static int[] paleLog() { return make((x, y) -> {
        int c = speck(rgb(142, 137, 128), 10, 37, x, 0);
        return rnd(38, x, y / 2) < 0.1 ? shade(c, -34) : c;
    }); }
    public static int[] paleLogTop() { return logTop(150, 142, 128); }
    /** Скам'яніла колода (мутація лісу від Зараження): сірий камінь із прожилками кори й фіолетовими жилками Шву. */
    public static int[] petrifiedLog() { return make((x, y) -> {
        int c = speck(rgb(112, 108, 114), 9, 41, x, 0);
        c = shade(c, (int) ((rnd(42, x, y / 5) - 0.5) * 30));
        return rnd(43, x, y / 3) < 0.05 ? rgb(120, 70, 170) : c;
    }); }
    public static int[] petrifiedLogTop() { return logTop(138, 132, 140); }
    /** Дихальний мул (мутація болота): темний мул із фіолетовими «порами». */
    public static int[] seamMud() { return make((x, y) -> {
        int c = speck(rgb(62, 46, 64), 8, 44, x, y);
        double d = rnd(45, x / 3, y / 3);
        if (d < 0.14 && (x + y) % 3 == 0) c = rgb(150, 90, 190);
        return c;
    }); }

    private static int[] planks(int r, int g, int b) { return make((x, y) -> {
        int c = speck(rgb(r, g, b), 7, 40, x, y);
        if (y % 4 == 3) c = shade(c, -34);
        if ((y / 4) % 2 == 0 ? x == 5 : x == 11) c = shade(c, -26); // стики дощок
        return c;
    }); }

    public static int[] oakPlanks() { return planks(173, 138, 84); }
    public static int[] birchPlanks() { return planks(216, 196, 140); }
    public static int[] palePlanks() { return planks(176, 170, 158); }

    /** Смолоскип: у мешері це стовпчик 2x10 посередині, тож малюємо лише ці пікселі - полум'я в рядках 6-8, далі палиця. */
    public static int[] torch() { return make((x, y) -> {
        if (y >= 6 && y <= 8) return y == 6 ? rgb(255, 236, 140) : rgb(255, 170 + (x == 7 ? 30 : 0), 60);
        return speck(rgb(120, 88, 48), 6, 53, x, y);
    }); }

    /** Цегла: червоні цеглини з сірим розчином. */
    public static int[] bricks() { return make((x, y) -> {
        boolean mortar = y % 4 == 3 || ((y / 4) % 2 == 0 ? x % 8 == 7 : x % 8 == 3);
        if (mortar) return speck(rgb(170, 160, 150), 6, 54, x, y);
        return speck(rgb(150, 74, 56), 10, 55, x / 2, y);
    }); }
    public static int[] sprucePlanks() { return planks(112, 84, 50); }

    private static int[] leaves(int r, int g, int b, double holes) { return make((x, y) -> {
        if (rnd(41, x, y) < holes) return argb(0, r, g, b); // дірки: альфа 0, але колір листя (щоб міпмапи не темнішали); шейдер відкидає піксель
        int c = speck(rgb(r, g, b), 22, 42, x, y);
        return c;
    }); }

    public static int[] oakLeaves() { return leaves(60, 128, 38, 0.2); }
    public static int[] birchLeaves() { return leaves(116, 168, 62, 0.2); }
    public static int[] spruceLeaves() { return leaves(44, 88, 58, 0.14); }

    public static int[] cactusSide() { return make((x, y) -> {
        int c = speck(rgb(34, 118, 56), 6, 43, x, y);
        if (x % 4 == 1) c = shade(c, -20);
        if ((x % 4 == 3) && (y % 4 == 1)) c = rgb(214, 232, 190); // колючки
        return c;
    }); }

    public static int[] cactusTop() { return make((x, y) -> {
        double d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
        return d > 6.5 ? rgb(24, 90, 42) : speck(rgb(46, 138, 66), 6, 44, x, y);
    }); }

    // ------------------------------------------------------------------ руди, моноліт, лампа

    private static int[] ore(int[] base, int spot, int spot2, long seed, int clusters) {
        int[] t = base.clone();
        for (int k = 0; k < clusters; k++) {
            int cx = 1 + (int) (rnd(seed, k, 1) * 13), cy = 1 + (int) (rnd(seed, k, 2) * 13);
            int w = 2 + (int) (rnd(seed, k, 3) * 2), h = 2 + (int) (rnd(seed, k, 4) * 2);
            for (int y = cy; y < Math.min(N, cy + h); y++) for (int x = cx; x < Math.min(N, cx + w); x++)
                if (rnd(seed + 99, x, y) < 0.86) t[x + y * N] = rnd(seed + 7, x, y) < 0.3 ? spot2 : spot;
        }
        return t;
    }

    public static int[] coalOre() { return ore(stone(), rgb(28, 28, 30), rgb(58, 58, 62), 50, 7); }
    public static int[] ironOre() { return ore(stone(), rgb(216, 166, 134), rgb(190, 140, 110), 51, 6); }
    public static int[] copperOre() { return ore(stone(), rgb(204, 112, 72), rgb(84, 180, 140), 52, 6); }
    public static int[] goldOre() { return ore(stone(), rgb(252, 222, 62), rgb(224, 180, 40), 53, 5); }
    public static int[] diamondOre() { return ore(stone(), rgb(92, 238, 228), rgb(180, 252, 248), 54, 5); }
    public static int[] liminiteOre() { return ore(deepStone(), rgb(152, 104, 255), rgb(110, 224, 255), 55, 7); }

    public static int[] monolith() { return make((x, y) -> {
        int c = speck(rgb(66, 66, 80), 9, 60, x, y);
        // фіолетові руни: кілька фіксованих штрихів
        boolean rune = (x == 7 && y >= 3 && y <= 12) || (y == 5 && x >= 5 && x <= 10) || (y == 10 && x >= 4 && x <= 8) || (x == 10 && y >= 8 && y <= 11);
        return rune ? rgb(160, 120, 255) : c;
    }); }

    // ---- Незер (29.10.2026)
    /** Лава: помаранчеві хвилі з жовтими прожилками й темними плямами кірки. */
    public static int[] lava() { return make((x, y) -> {
        double w = Math.sin((x + y * 0.6) * 0.8) + Math.sin((y - x * 0.4) * 0.7);
        int c = rgb(222 + (int) (w * 14), 92 + (int) (w * 22), 18);
        if (w > 1.2) c = rgb(255, 200, 70);
        if (rnd(71, x / 2, y / 2) < 0.08) c = rgb(150, 44, 14);
        return c;
    }); }

    /** Згарище: темно-бурий пористий камінь із червонуватими порами. */
    public static int[] cinder() { return make((x, y) -> {
        int c = speck(rgb(92, 44, 40), 14, 72, x, y);
        if (rnd(73, x, y) < 0.1) c = rgb(60, 24, 24);
        if (rnd(74, x, y) < 0.03) c = rgb(150, 66, 46);
        return c;
    }); }

    public static int[] ash() { return make((x, y) -> speck(rgb(112, 104, 100), 12, 75, x, y)); }

    /** Жарова жила: Згарище з тріщинами, що жевріють. */
    public static int[] emberVein() { int[] base = cinder(); return make((x, y) -> {
        boolean crack = (Math.abs((x * 3 + y * 2) % 11 - 5) < 1 && rnd(76, x / 3, y / 3) < 0.7) || (y == 8 && x > 3 && x < 12) || (x == 5 && y > 2 && y < 9);
        return crack ? rgb(255, 150 + (int) (rnd(77, x, y) * 60), 40) : base[x + y * N];
    }); }

    /** Жаровий лімініт: Згарище з фіолетово-помаранчевими кристалами (лімініт, перегрітий біля лави). */
    public static int[] emberOre() { return ore(cinder(), rgb(255, 128, 64), rgb(176, 110, 255), 78, 6); }

    public static int[] fortressBricks() { return make((x, y) -> {
        boolean mortar = y % 4 == 3 || ((y / 4) % 2 == 0 ? x % 8 == 7 : x % 8 == 3);
        if (mortar) return rgb(28, 14, 18);
        return speck(rgb(62, 28, 34), 8, 79, x / 2, y);
    }); }

    /** Портал Незеру: напівпрозорий червоно-фіолетовий вир. */
    public static int[] netherPortal() { return make((x, y) -> {
        double d = Math.hypot(x - 7.5, y - 7.5), a = Math.atan2(y - 7.5, x - 7.5);
        double s = Math.sin(d * 1.3 + a * 2);
        return argb(185, 150 + (int) (s * 50), 30, 120 + (int) (s * 40));
    }); }

    /** Залізний блок: світлі пластини з заклепками. */
    public static int[] ironBlock() { return make((x, y) -> {
        int c = speck(rgb(214, 214, 218), 5, 52, x, y);
        if (x == 0 || y == 0 || x == N - 1 || y == N - 1) c = shade(c, -40);
        if ((x == 2 || x == N - 3) && (y == 2 || y == N - 3)) c = shade(c, -60);
        return c;
    }); }

    public static int[] lamp() { return make((x, y) -> {
        boolean mortar = x % 8 == 0 || y % 8 == 0 || x == N - 1 || y == N - 1;
        if (mortar) return rgb(120, 82, 36);
        double d = Math.hypot(x % 8 - 3.5, y % 8 - 3.5);
        return rgb(255, 214 + (int) (20 - d * 6), 110 + (int) (40 - d * 12));
    }); }

    /** Біла плитка: шар 0 масиву текстур - блоки без текстури множаться на неї й отримують власний колір. */
    public static int[] white() { return make((x, y) -> 0xFFFFFFFF); }
}
