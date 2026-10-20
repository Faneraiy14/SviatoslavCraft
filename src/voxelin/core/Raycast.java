package voxelin.core;

/**
 * Промінь прицілу - справжній DDA (Amanatidis-Woo): щоразу рівно в одну сусідню клітинку, тож кут між двома блоками,
 * що торкаються ребром, не «проскочити» (баг ревізії 2 старої версії). Вхід - світові одиниці й кути камери.
 */
public final class Raycast {
    private Raycast() {}

    public static final float MAX_REACH = 6.0f * Units.BLOCK_SIZE;

    public interface Solid { boolean at(int x, int y, int z); }

    /** Влучений блок і клітинка перед ним (куди ставити). */
    public record Hit(int x, int y, int z, int px, int py, int pz, float distance) {}

    public static double[] dir(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new double[]{Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)};
    }

    public static Hit cast(float ox, float oy, float oz, float yawDeg, float pitchDeg, float maxReach, Solid solid) {
        double[] d = dir(yawDeg, pitchDeg);
        double gox = ox / Units.BLOCK_SIZE, goy = oy / Units.BLOCK_SIZE, goz = oz / Units.BLOCK_SIZE, max = maxReach / Units.BLOCK_SIZE;
        int gx = (int) Math.floor(gox), gy = (int) Math.floor(goy), gz = (int) Math.floor(goz);
        int sx = sign(d[0]), sy = sign(d[1]), sz = sign(d[2]);
        double tx = first(gox, gx, sx, d[0]), ty = first(goy, gy, sy, d[1]), tz = first(goz, gz, sz, d[2]);
        double dx = sx != 0 ? Math.abs(1 / d[0]) : Double.POSITIVE_INFINITY, dy = sy != 0 ? Math.abs(1 / d[1]) : Double.POSITIVE_INFINITY,
                dz = sz != 0 ? Math.abs(1 / d[2]) : Double.POSITIVE_INFINITY;
        int lx = gx, ly = gy, lz = gz;
        double t = 0;
        while (t <= max) {
            if (solid.at(gx, gy, gz)) return new Hit(gx, gy, gz, lx, ly, lz, (float) (t * Units.BLOCK_SIZE));
            lx = gx; ly = gy; lz = gz;
            if (tx < ty && tx < tz) { gx += sx; t = tx; tx += dx; }
            else if (ty < tz) { gy += sy; t = ty; ty += dy; }
            else { gz += sz; t = tz; tz += dz; }
        }
        return null;
    }

    /** Відстань до першої перешкоди (для камери 3-ї особи) або maxReach. */
    public static float distance(float ox, float oy, float oz, float yawDeg, float pitchDeg, float maxReach, Solid solid) {
        Hit h = cast(ox, oy, oz, yawDeg, pitchDeg, maxReach, solid);
        return h == null ? maxReach : h.distance();
    }

    private static int sign(double v) { return v > 1e-9 ? 1 : (v < -1e-9 ? -1 : 0); }
    private static double first(double o, int cell, int step, double d) { return step == 0 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1 : cell) - o) / d; }
}
