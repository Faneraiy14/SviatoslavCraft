package voxelin.core;

import voxelin.core.entity.Mob;

/** Бій: промінь від очей по істотах, удар із відкиданням. Чисті функції над World. */
public final class Combat {
    private Combat() {}

    /** Найближча істота на промені (відстань до коробки не більше maxDist і ближче за blockDist - найближчий блок на промені). */
    public static Mob raycast(World w, Pos eye, float yaw, float pitch, double maxDist, double blockDist) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double dx = Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = -Math.cos(yr) * Math.cos(pr);
        Mob best = null; double bestT = Math.min(maxDist, blockDist);
        for (Mob m : w.mobs()) {
            if (m.dying) continue;
            double hw = m.type.width / 2, mx = m.pos.relX(eye.cx), mz = m.pos.relZ(eye.cz);
            double t = rayBox(eye.x, eye.y, eye.z, dx, dy, dz, mx - hw, m.pos.y, mz - hw, mx + hw, m.pos.y + m.type.height, mz + hw);
            if (t >= 0 && t <= bestT) { bestT = t; best = m; }
        }
        return best;
    }

    /** Відстань уздовж променя до входу в коробку або -1. */
    static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = Double.MAX_VALUE;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) { if (o[i] < lo[i] || o[i] > hi[i]) return -1; continue; }
            double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            tmin = Math.max(tmin, t1); tmax = Math.min(tmax, t2);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    /** Удар по істоті від нападника (центр тіла): відкидання - від нього. true - удар прийнято. */
    public static boolean attack(World w, Mob m, double damage, Pos attacker) {
        double kx = m.pos.relX(attacker.cx) - attacker.x, kz = m.pos.relZ(attacker.cz) - attacker.z;
        if (Math.hypot(kx, kz) < 1e-6) { kx = 0; kz = -1; }
        return w.hurtMob(m, damage, kx, kz, attacker);
    }
}
