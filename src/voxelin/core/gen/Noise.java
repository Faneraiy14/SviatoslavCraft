package voxelin.core.gen;

import java.util.Random;

/** Класичний шум Перліна 2D/3D із зерном (власна реалізація, без бібліотек) + фрактальна сума октав. */
public final class Noise {
    private final int[] p = new int[512];

    public Noise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = perm[i]; perm[i] = perm[j]; perm[j] = t; }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad(int h, double x, double y, double z) {
        int k = h & 15;
        double u = k < 8 ? x : y, v = k < 4 ? y : (k == 12 || k == 14 ? x : z);
        return ((k & 1) == 0 ? u : -u) + ((k & 2) == 0 ? v : -v);
    }

    /** -1..1 (приблизно). */
    public double noise(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                               lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                       lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                               lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    public double noise(double x, double z) { return noise(x, 0.5, z); }

    /** Сума октав: кожна наступна вдвічі дрібніша й удвічі слабша. Результат нормовано приблизно до -1..1. */
    public double fractal(double x, double z, int octaves) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int i = 0; i < octaves; i++) { sum += noise(x * freq, z * freq) * amp; norm += amp; amp *= 0.5; freq *= 2; }
        return sum / norm;
    }
}
