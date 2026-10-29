package voxelin.client.ui;

import java.util.Random;

/**
 * Живий фон меню: піксельний нічний пейзаж з блоків 16 px - градієнт неба, зорі, що мерехтять, місяць, далекі й ближні пагорби
 * з паралаксом (повільно пливуть), блокові дерева, хмари і ледь помітний фіолетовий Шов у небі (натяк на сюжет). Усе з прямокутників
 * Gui.rect, детерміновано за зерном - без текстур і файлів.
 */
public final class MenuScene {
    private static final int B = 16; // розмір «блока» пейзажу
    private final float[][] stars;
    private final long seed;

    public MenuScene(long seed) {
        this.seed = seed;
        Random r = new Random(seed);
        stars = new float[90][];
        for (int i = 0; i < stars.length; i++) stars[i] = new float[]{r.nextFloat() * Gui.W, r.nextFloat() * Gui.H * 0.55f, 1 + r.nextInt(2), r.nextFloat() * 6.28f};
    }

    /** Висота шару пагорбів у колонці (у блоках від низу), детерміновано. */
    private int height(long col, int layer, int base, int amp) {
        double x = col * (layer == 0 ? 0.11 : 0.19) + layer * 31.7 + seed % 1000;
        return base + (int) Math.round(amp * (0.6 * Math.sin(x) + 0.3 * Math.sin(x * 2.3 + 1.1) + 0.1 * Math.sin(x * 5.1)));
    }

    private static long hash(long a, long b) { long h = a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L); h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; return h ^ (h >>> 32); }

    public void draw(double seconds) {
        // небо: смуги від темно-синього вгорі до фіолетово-бузкового біля обрію
        int bands = 24;
        for (int i = 0; i < bands; i++) {
            float t = i / (float) (bands - 1);
            Gui.rect(0, i * Gui.H / (float) bands, Gui.W, Gui.H / (float) bands + 1, 0.04f + t * 0.16f, 0.05f + t * 0.08f, 0.14f + t * 0.2f, 1f);
        }
        for (float[] s : stars) {
            float a = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(seconds * 1.7 + s[3]));
            Gui.rect(s[0], s[1], s[2] * 2, s[2] * 2, 1f, 1f, 0.9f, a);
        }
        // Шов: тонка ламана фіолетова тріщина з пульсуючим сяйвом
        float pulse = (float) (0.5 + 0.5 * Math.sin(seconds * 0.9));
        int[][] seam = {{1110, 40}, {1125, 72}, {1116, 100}, {1132, 130}, {1124, 162}, {1138, 190}};
        for (int i = 0; i + 1 < seam.length; i++) {
            float x0 = seam[i][0], y0 = seam[i][1], x1 = seam[i + 1][0], y1 = seam[i + 1][1];
            for (int k = 0; k < 8; k++) {
                float t = k / 8f, x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t;
                Gui.rect(x - 6, y - 2, 12, 8, 0.6f, 0.25f, 0.9f, 0.06f + 0.08f * pulse);
                Gui.rect(x - 1, y, 3, 5, 0.85f, 0.6f, 1f, 0.55f + 0.35f * pulse);
            }
        }
        // місяць (квадратний, як у блоковому світі)
        Gui.rect(176, 84, 64, 64, 0.95f, 0.95f, 0.85f, 0.12f);
        Gui.rect(184, 92, 48, 48, 0.93f, 0.93f, 0.82f, 1f);
        Gui.rect(192, 104, 10, 10, 0.78f, 0.78f, 0.7f, 1f);
        Gui.rect(212, 120, 8, 8, 0.8f, 0.8f, 0.72f, 1f);
        // хмари
        for (int c = 0; c < 5; c++) {
            Random r = new Random(hash(seed, c));
            float w = (6 + r.nextInt(6)) * B, y = 150 + r.nextInt(140), speed = 6 + r.nextInt(8);
            float x = (float) ((r.nextFloat() * (Gui.W + w) + seconds * speed) % (Gui.W + w)) - w;
            Gui.rect(x, y, w, B, 0.75f, 0.72f, 0.88f, 0.16f);
            Gui.rect(x + 2 * B, y - B, w - 4 * B, B, 0.75f, 0.72f, 0.88f, 0.12f);
        }
        layer(seconds, 0, 3f, 13, 4, new float[]{0.09f, 0.1f, 0.2f}, null, null);
        layer(seconds, 1, 9f, 5, 2, new float[]{0.2f, 0.14f, 0.1f}, new float[]{0.16f, 0.36f, 0.18f}, new float[]{0.08f, 0.2f, 0.1f});
    }

    /** Шар пагорбів: зсув speed px/с, висота base±amp блоків; grass - верхній блок, tree - колір крон (null - без дерев). */
    private void layer(double seconds, int layer, float speed, int base, int amp, float[] body, float[] grass, float[] tree) {
        double off = seconds * speed;
        long first = (long) Math.floor(off / B);
        float shift = (float) (off - first * B);
        int cols = Gui.W / B + 2;
        for (int i = 0; i < cols; i++) {
            long col = first + i;
            int h = height(col, layer, base, amp);
            float x = i * B - shift, top = Gui.H - h * B;
            Gui.rect(x, top, B, h * B, body[0], body[1], body[2], 1f);
            if (grass != null) {
                Gui.rect(x, top, B, B, grass[0], grass[1], grass[2], 1f);
                Gui.rect(x, top + B, B, 3, grass[0] * 0.7f, grass[1] * 0.7f, grass[2] * 0.7f, 1f);
                if ((hash(col, 7) & 3) == 0) Gui.rect(x + 5, top + B + 8, 4, 4, 0.36f, 0.36f, 0.4f, 1f); // камінчик у землі
            }
            if (tree != null && Math.floorMod(hash(col, 3), 9) == 0) { // дерево: стовбур 4 блоки, крона 3x3 (+верх)
                for (int k = 1; k <= 3; k++) Gui.rect(x, top - k * B, B, B, 0.3f, 0.2f, 0.12f, 1f);
                Gui.rect(x - B, top - 6 * B, 3 * B, 3 * B, tree[0], tree[1], tree[2], 1f);
                Gui.rect(x, top - 7 * B, B, B, tree[0], tree[1], tree[2], 1f);
                Gui.rect(x - B, top - 6 * B, 3 * B, 3, tree[0] * 1.5f, tree[1] * 1.5f, tree[2] * 1.5f, 1f);
            }
        }
    }
}
