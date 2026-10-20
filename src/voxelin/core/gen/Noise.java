package voxelin.core.gen;

/**
 * Шум Перліна на цілочисельній ґратці з 64-бітним хешем - однаково точний і без повторів на БУДЬ-ЯКІЙ відстані.
 * Чому не «таблиця перестановок на 256» (як було): вона повторює візерунок кожні 256 клітинок (при масштабі 96 - кожні ~24 тис. блоків),
 * а double на координатах 10^15 втрачає дробову частину. Тут клітинка = floorDiv(x, період) (long), дробова частина = floorMod(x, період)/період
 * (рахується точно, бо остача мала), градієнт кожного вузла ґратки береться з хешу (зерно, сіль, ix, iy, iz) - період повтору 2^64.
 */
public final class Noise {
    private final long seed;

    public Noise(long seed) { this.seed = mix(seed ^ 0x5DEECE66DL); }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private long hash(long salt, long ix, long iy, long iz) {
        long h = mix(seed + salt * 0x9E3779B97F4A7C15L);
        h = mix(h ^ (ix * 0xC2B2AE3D27D4EB4FL));
        h = mix(h ^ (iy * 0x165667B19E3779F9L));
        return mix(h ^ (iz * 0x27D4EB2F165667C5L));
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }

    private static double grad2(long h, double x, double z) {
        switch ((int) (h >>> 60) & 7) {
            case 0: return x + z;
            case 1: return -x + z;
            case 2: return x - z;
            case 3: return -x - z;
            case 4: return x;
            case 5: return -x;
            case 6: return z;
            default: return -z;
        }
    }

    private static double grad3(long h, double x, double y, double z) {
        switch ((int) (h >>> 60) % 12) {
            case 0: return x + y;   case 1: return -x + y;  case 2: return x - y;  case 3: return -x - y;
            case 4: return x + z;   case 5: return -x + z;  case 6: return x - z;  case 7: return -x - z;
            case 8: return y + z;   case 9: return -y + z;  case 10: return y - z; default: return -y - z;
        }
    }

    /** 2D шум у клітинці (ix,iz) зі зсувом (fx,fz) у [0,1): приблизно -1..1. */
    public double noise2(long salt, long ix, long iz, double fx, double fz) {
        double u = fade(fx), v = fade(fz);
        double n00 = grad2(hash(salt, ix, 0, iz), fx, fz), n10 = grad2(hash(salt, ix + 1, 0, iz), fx - 1, fz);
        double n01 = grad2(hash(salt, ix, 0, iz + 1), fx, fz - 1), n11 = grad2(hash(salt, ix + 1, 0, iz + 1), fx - 1, fz - 1);
        return lerp(v, lerp(u, n00, n10), lerp(u, n01, n11)) * 1.4142135623730951;
    }

    /** 3D шум у клітинці (ix,iy,iz) зі зсувом (fx,fy,fz) у [0,1): приблизно -1..1. */
    public double noise3(long salt, long ix, long iy, long iz, double fx, double fy, double fz) {
        double u = fade(fx), v = fade(fy), w = fade(fz);
        double a = lerp(u, grad3(hash(salt, ix, iy, iz), fx, fy, fz), grad3(hash(salt, ix + 1, iy, iz), fx - 1, fy, fz));
        double b = lerp(u, grad3(hash(salt, ix, iy + 1, iz), fx, fy - 1, fz), grad3(hash(salt, ix + 1, iy + 1, iz), fx - 1, fy - 1, fz));
        double c = lerp(u, grad3(hash(salt, ix, iy, iz + 1), fx, fy, fz - 1), grad3(hash(salt, ix + 1, iy, iz + 1), fx - 1, fy, fz - 1));
        double d = lerp(u, grad3(hash(salt, ix, iy + 1, iz + 1), fx, fy - 1, fz - 1), grad3(hash(salt, ix + 1, iy + 1, iz + 1), fx - 1, fy - 1, fz - 1));
        return lerp(w, lerp(v, a, b), lerp(v, c, d));
    }

    /** Шум у точці блока (x,z) з масштабом period блоків (ціле >= 1). Точний на будь-яких long. */
    public double sample2(long salt, long x, long z, long period) {
        long ix = Math.floorDiv(x, period), iz = Math.floorDiv(z, period);
        return noise2(salt, ix, iz, (double) Math.floorMod(x, period) / period, (double) Math.floorMod(z, period) / period);
    }

    public double sample3(long salt, long x, long y, long z, long period) {
        long ix = Math.floorDiv(x, period), iy = Math.floorDiv(y, period), iz = Math.floorDiv(z, period);
        return noise3(salt, ix, iy, iz, (double) Math.floorMod(x, period) / period, (double) Math.floorMod(y, period) / period, (double) Math.floorMod(z, period) / period);
    }

    /** Сума октав: період кожної наступної вдвічі менший (мінімум 1), амплітуда - вдвічі слабша. Нормовано приблизно до -1..1. */
    public double fractal2(long x, long z, long basePeriod, int octaves) {
        double sum = 0, amp = 1, norm = 0;
        long period = basePeriod;
        for (int i = 0; i < octaves && period >= 1; i++) {
            sum += sample2(i, x, z, period) * amp;
            norm += amp; amp *= 0.5; period /= 2;
        }
        return sum / norm;
    }
}
