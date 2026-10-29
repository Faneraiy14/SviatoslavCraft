package voxelin.core;

/**
 * Промінь прицілу - справжній DDA (Amanatidis-Woo): щоразу рівно в одну сусідню клітинку, тож кут між двома блоками,
 * що торкаються ребром, не «проскочити». Рахується в локальних координатах відносно чанка ока (малі числа), а блоки перевіряються
 * за абсолютними long - тому однаково точний на будь-якій відстані від початку координат. Усе в БЛОКАХ.
 */
public final class Raycast {
    private Raycast() {}

    public static final double MAX_REACH = 6.0;

    public interface Solid {
        boolean at(long x, int y, long z);
        /** Коробки неповного блока (частки, по 6 чисел) або null - повний куб. */
        default double[] boxes(long x, int y, long z) { return null; }
    }

    /**
     * Влучений блок і клітинка перед ним (куди ставити). distance - у блоках. face - грань влучання (0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z),
     * fx,fy,fz - точка влучання в частках влученого блока (для дошок: яку саме дошку влучено і куди ставити нову).
     */
    public record Hit(long x, int y, long z, long px, int py, long pz, double distance, int face, double fx, double fy, double fz) {
        public Hit(long x, int y, long z, long px, int py, long pz, double distance) { this(x, y, z, px, py, pz, distance, -1, 0.5, 0.5, 0.5); }
    }

    public static double[] dir(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new double[]{Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)};
    }

    public static Hit cast(Pos eye, float yawDeg, float pitchDeg, double maxReach, Solid solid) {
        double[] d = dir(yawDeg, pitchDeg);
        long baseX = eye.cx * 16, baseZ = eye.cz * 16;
        double ox = eye.x, oy = eye.y, oz = eye.z;
        int gx = (int) Math.floor(ox), gy = (int) Math.floor(oy), gz = (int) Math.floor(oz);
        int sx = sign(d[0]), sy = sign(d[1]), sz = sign(d[2]);
        double tx = first(ox, gx, sx, d[0]), ty = first(oy, gy, sy, d[1]), tz = first(oz, gz, sz, d[2]);
        double dx = sx != 0 ? Math.abs(1 / d[0]) : Double.POSITIVE_INFINITY, dy = sy != 0 ? Math.abs(1 / d[1]) : Double.POSITIVE_INFINITY,
                dz = sz != 0 ? Math.abs(1 / d[2]) : Double.POSITIVE_INFINITY;
        int lx = gx, ly = gy, lz = gz, face = -1;
        double t = 0;
        while (t <= maxReach) {
            if (solid.at(baseX + gx, gy, baseZ + gz)) {
                double[] b = solid.boxes(baseX + gx, gy, baseZ + gz);
                if (b == null) {
                    double hx = ox + d[0] * t - gx, hy = oy + d[1] * t - gy, hz = oz + d[2] * t - gz;
                    return new Hit(baseX + gx, gy, baseZ + gz, baseX + lx, ly, baseZ + lz, t, face, clamp(hx), clamp(hy), clamp(hz));
                }
                // неповний блок: найближча коробка, яку промінь перетинає всередині цієї клітинки
                double best = Double.POSITIVE_INFINITY; int bestFace = -1;
                for (int k = 0; k + 5 < b.length; k += 6) {
                    double[] r = slab(ox - gx, oy - gy, oz - gz, d, b, k);
                    if (r != null && r[0] >= t - 1e-9 && r[0] < best) { best = r[0]; bestFace = (int) r[1]; }
                }
                if (best <= maxReach && bestFace >= 0) {
                    double hx = ox + d[0] * best - gx, hy = oy + d[1] * best - gy, hz = oz + d[2] * best - gz;
                    int[] n = NORMALS[bestFace];
                    return new Hit(baseX + gx, gy, baseZ + gz, baseX + gx + n[0], gy + n[1], baseZ + gz + n[2], best, bestFace, clamp(hx), clamp(hy), clamp(hz));
                }
            }
            lx = gx; ly = gy; lz = gz;
            if (tx < ty && tx < tz) { gx += sx; t = tx; tx += dx; face = sx > 0 ? 1 : 0; }
            else if (ty < tz) { gy += sy; t = ty; ty += dy; face = sy > 0 ? 3 : 2; }
            else { gz += sz; t = tz; tz += dz; face = sz > 0 ? 5 : 4; }
        }
        return null;
    }

    /** Відстань до першої перешкоди (для камери 3-ї особи) або maxReach. */
    public static double distance(Pos eye, float yawDeg, float pitchDeg, double maxReach, Solid solid) {
        Hit h = cast(eye, yawDeg, pitchDeg, maxReach, solid);
        return h == null ? maxReach : h.distance();
    }

    private static final int[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static double clamp(double v) { return Math.max(0, Math.min(0.999999, v)); }

    /** Промінь (початок у координатах клітинки) проти коробки b[k..k+5]: {t входу, грань} або null. */
    private static double[] slab(double ox, double oy, double oz, double[] d, double[] b, int k) {
        double[] o = {ox, oy, oz};
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY; int face = -1;
        for (int a = 0; a < 3; a++) {
            double lo = b[k + a], hi = b[k + 3 + a];
            if (Math.abs(d[a]) < 1e-12) { if (o[a] < lo || o[a] > hi) return null; continue; }
            double t1 = (lo - o[a]) / d[a], t2 = (hi - o[a]) / d[a];
            int f = d[a] > 0 ? a * 2 + 1 : a * 2; // входимо через нижню межу при d>0 - грань «мінус»
            if (t1 > t2) { double tt = t1; t1 = t2; t2 = tt; }
            if (t1 > tmin) { tmin = t1; face = f; }
            tmax = Math.min(tmax, t2);
        }
        if (tmin > tmax || tmax < 0 || face < 0) return null;
        return new double[]{Math.max(0, tmin), face};
    }

    private static int sign(double v) { return v > 1e-9 ? 1 : (v < -1e-9 ? -1 : 0); }
    private static double first(double o, int cell, int step, double d) { return step == 0 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1 : cell) - o) / d; }
}
