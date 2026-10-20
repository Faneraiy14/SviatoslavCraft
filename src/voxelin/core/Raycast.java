package voxelin.core;

/**
 * Промінь прицілу - справжній DDA (Amanatidis-Woo): щоразу рівно в одну сусідню клітинку, тож кут між двома блоками,
 * що торкаються ребром, не «проскочити». Рахується в локальних координатах відносно чанка ока (малі числа), а блоки перевіряються
 * за абсолютними long - тому однаково точний на будь-якій відстані від початку координат. Усе в БЛОКАХ.
 */
public final class Raycast {
    private Raycast() {}

    public static final double MAX_REACH = 6.0;

    public interface Solid { boolean at(long x, int y, long z); }

    /** Влучений блок і клітинка перед ним (куди ставити). distance - у блоках. */
    public record Hit(long x, int y, long z, long px, int py, long pz, double distance) {}

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
        int lx = gx, ly = gy, lz = gz;
        double t = 0;
        while (t <= maxReach) {
            if (solid.at(baseX + gx, gy, baseZ + gz)) return new Hit(baseX + gx, gy, baseZ + gz, baseX + lx, ly, baseZ + lz, t);
            lx = gx; ly = gy; lz = gz;
            if (tx < ty && tx < tz) { gx += sx; t = tx; tx += dx; }
            else if (ty < tz) { gy += sy; t = ty; ty += dy; }
            else { gz += sz; t = tz; tz += dz; }
        }
        return null;
    }

    /** Відстань до першої перешкоди (для камери 3-ї особи) або maxReach. */
    public static double distance(Pos eye, float yawDeg, float pitchDeg, double maxReach, Solid solid) {
        Hit h = cast(eye, yawDeg, pitchDeg, maxReach, solid);
        return h == null ? maxReach : h.distance();
    }

    private static int sign(double v) { return v > 1e-9 ? 1 : (v < -1e-9 ? -1 : 0); }
    private static double first(double o, int cell, int step, double d) { return step == 0 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1 : cell) - o) / d; }
}
