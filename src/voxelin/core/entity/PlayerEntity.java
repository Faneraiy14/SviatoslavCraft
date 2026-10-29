package voxelin.core.entity;

import voxelin.core.Pos;

/**
 * Гравець: позиція очей як Pos (чанк long + зсув double) - однаково точна на будь-якій відстані. Усе в БЛОКАХ.
 * Логіка в тіках (20/с), рендер інтерполює між prev і pos. Плавний розгін, спринт лише вперед, присід (повільно, не падає з краю,
 * камера опускається). Рух дрібними кроками (<= STEP), тож навіть швидке падіння не проходить крізь блок і не «зависає».
 * Колізії рахуються в локальних координатах відносно чанка гравця, блоки перевіряються за абсолютними long.
 */
public final class PlayerEntity {
    public interface Solid {
        boolean at(long x, int y, long z);
        /** Коробки неповного блока в частках (по 6 чисел) або null - повний куб. */
        default double[] boxes(long x, int y, long z) { return null; }

        /** Чи перетинає коробка (абсолютні межі в блоках відносно baseX/baseZ) щось суцільне. */
        static boolean hits(Solid s, long baseX, long baseZ, double x0, double y0, double z0, double x1, double y1, double z1) {
            int bx0 = (int) Math.floor(x0), bx1 = (int) Math.floor(x1 - 1e-6), by0 = (int) Math.floor(y0), by1 = (int) Math.floor(y1 - 1e-6),
                    bz0 = (int) Math.floor(z0), bz1 = (int) Math.floor(z1 - 1e-6);
            for (int bx = bx0; bx <= bx1; bx++) for (int by = by0; by <= by1; by++) for (int bz = bz0; bz <= bz1; bz++) {
                if (!s.at(baseX + bx, by, baseZ + bz)) continue;
                double[] b = s.boxes(baseX + bx, by, baseZ + bz);
                if (b == null) return true;
                for (int k = 0; k + 5 < b.length; k += 6)
                    if (x0 < bx + b[k + 3] && x1 > bx + b[k] && y0 < by + b[k + 4] && y1 > by + b[k + 1] && z0 < bz + b[k + 5] && z1 > bz + b[k + 2]) return true;
            }
            return false;
        }
    }
    /** Найвищий уступ, на який гравець заходить без стрибка (дошка - 0.25, дві - 0.5). */
    public static final double STEP_UP = 0.5;
    /** Рідина (вода) в точці: гравець у ній пливе. */
    public interface Liquid { boolean at(long x, int y, long z); }

    public static final double HEIGHT = 1.8, EYE = 1.6, WIDTH = 0.6;
    private static final double EYE_OFFSET = HEIGHT / 2 - EYE;
    private static final double SPEED = 5.5, JUMP = 9.0, GRAVITY = 25.0, MAX_FALL = 40.0, ACCEL = 9.0, SPRINT = 1.6, CROUCH = 0.3, CROUCH_DROP = 0.4;
    private static final double STEP = 0.25 / 1.5;

    public final Pos pos = new Pos(), prev = new Pos();
    public float yaw, pitch;
    public double eyeOffset, prevEyeOffset;
    private double vx, vy, vz;
    private boolean onGround;
    private final Solid world;
    private final Liquid liquid;
    public boolean inWater, headInWater;
    /** Стрибнув у цьому тіку (для виснаження). */
    public boolean jumped;
    private double fall, landedFall;
    /** Крила надягнуті й придатні (клієнт виставляє щотіку); gliding - зараз планує. */
    public boolean wings, gliding;
    private boolean prevJump;
    private double impact;

    public PlayerEntity(Solid world, Pos at) { this(world, (x, y, z) -> false, at); }
    public PlayerEntity(Solid world, Liquid liquid, Pos at) { this.world = world; this.liquid = liquid; teleport(at); }

    public void teleport(Pos p) { pos.set(p); prev.set(p); vx = vy = vz = 0; fall = landedFall = 0; }

    /** Висота падіння (блоків), накопичена до приземлення в цьому тіку; читання скидає. Падіння у воду не рахується. */
    public double takeLanded() { double f = landedFall; landedFall = 0; return f; }

    /** Шкода від удару об стіну під час планування (накопичена); читання скидає. */
    public double takeImpact() { double f = impact; impact = 0; return f; }

    /** Поштовх (відкидання від удару), блоків/с. */
    public void knock(double kx, double ky, double kz) { vx += kx; vz += kz; vy = Math.max(vy, ky); if (ky > 0) onGround = false; }

    public void rotate(float dYaw, float dPitch) { yaw += dYaw; pitch = Math.max(-89f, Math.min(89f, pitch + dPitch)); }

    /** Один тік: рух за клавішами, стрибок, гравітація, колізії. */
    public void tick(double dt, boolean fwd, boolean back, boolean left, boolean right, boolean jump, boolean sprint, boolean crouch) {
        prev.set(pos); prevEyeOffset = eyeOffset;
        double yr = Math.toRadians(yaw);
        double fx = Math.sin(yr), fz = -Math.cos(yr), rx = Math.cos(yr), rz = Math.sin(yr);
        double dx = 0, dz = 0;
        if (fwd) { dx += fx; dz += fz; }
        if (back) { dx -= fx; dz -= fz; }
        if (right) { dx += rx; dz += rz; }
        if (left) { dx -= rx; dz -= rz; }
        long bx = pos.blockX(), bz = pos.blockZ();
        inWater = liquid.at(bx, (int) Math.floor(pos.y - EYE + 0.6), bz);   // вода на рівні пояса
        headInWater = liquid.at(bx, (int) Math.floor(pos.y), bz);            // вода на рівні очей
        boolean jumpEdge = jump && !prevJump;
        prevJump = jump;
        if (gliding && (onGround || inWater || !wings)) gliding = false;
        else if (!gliding && wings && jumpEdge && !onGround && !inWater && vy < 0) gliding = true; // стрибок у повітрі під час падіння розкриває крила
        if (gliding) { glide(dt); return; }
        double tvx = 0, tvz = 0;
        if (dx != 0 || dz != 0) {
            double len = Math.sqrt(dx * dx + dz * dz), s = (crouch ? SPEED * CROUCH : (sprint && fwd ? SPEED * SPRINT : SPEED)) * (inWater ? 0.55 : 1.0);
            tvx = dx / len * s; tvz = dz / len * s;
        }
        double t = Math.min(1.0, ACCEL * dt);
        vx += (tvx - vx) * t; vz += (tvz - vz) * t;
        if (tvx == 0 && tvz == 0 && Math.abs(vx) < 0.01 && Math.abs(vz) < 0.01) { vx = 0; vz = 0; }
        eyeOffset += ((crouch ? -CROUCH_DROP : 0.0) - eyeOffset) * Math.min(1.0, 10.0 * dt);

        jumped = false;
        if (jump && onGround) { vy = JUMP; onGround = false; jumped = true; }
        if (inWater) { // плавання: слабка гравітація, повільне занурення, пробіл - вгору (біля поверхні - сильніший поштовх, щоб вилізти на берег)
            vy = Math.max(-3.0, vy - GRAVITY * 0.12 * dt);
            if (jump) vy = Math.max(vy, headInWater ? 3.8 : 5.5);
        } else vy = Math.max(-MAX_FALL, vy - GRAVITY * dt);
        moveY(vy * dt);
        moveH(vx * dt, vz * dt, crouch);
        pos.normalize();
        double down = prev.y - pos.y; // prev.y у тому ж чанку (normalize не міняє y)
        boolean wet = liquid.at(pos.blockX(), (int) Math.floor(pos.y - EYE + 0.6), pos.blockZ());
        if (wet) fall = 0;
        else if (onGround) { if (fall > 0) landedFall = fall; fall = 0; }
        else if (down > 0) fall += down;
        else fall = Math.max(0, fall + down);
    }

    /**
     * Планування (за формулою крил Minecraft, у блоках за тік): дивишся вниз - розганяєшся, вгору - набираєш висоту коштом швидкості,
     * горизонтально - повільно знижуєшся. Удар об стіну на швидкості - шкода (втрата швидкості x10 - 3).
     */
    private void glide(double dt) {
        jumped = false;
        eyeOffset += (0.0 - eyeOffset) * Math.min(1.0, 10.0 * dt);
        double tx = vx * dt, ty = vy * dt, tz = vz * dt;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), cp = Math.cos(pr);
        double lx = Math.sin(yr) * cp, lz = -Math.cos(yr) * cp, hlook = Math.abs(cp), hvel = Math.sqrt(tx * tx + tz * tz), sq = cp * cp;
        ty += -0.08 + sq * 0.06;
        if (ty < 0 && hlook > 1e-6) { double y = ty * -0.1 * sq; ty += y; tx += lx * y / hlook; tz += lz * y / hlook; }
        if (pr < 0 && hlook > 1e-6) { double d = hvel * -Math.sin(pr) * 0.04; ty += d * 3.2; tx -= lx * d / hlook; tz -= lz * d / hlook; }
        if (hlook > 1e-6) { tx += (lx / hlook * hvel - tx) * 0.1; tz += (lz / hlook * hvel - tz) * 0.1; }
        tx *= 0.99; ty *= 0.98; tz *= 0.99;
        vx = tx / dt; vy = ty / dt; vz = tz / dt;
        double before = Math.sqrt(tx * tx + tz * tz);
        moveY(vy * dt);
        moveH(vx * dt, vz * dt, false);
        pos.normalize();
        double after = Math.sqrt(vx * vx + vz * vz) * dt, loss = before - after;
        if (loss * 10 - 3 > 0) impact += loss * 10 - 3;
        double down = prev.y - pos.y;
        if (onGround) { if (fall > 0) landedFall = fall; fall = 0; gliding = false; }
        else if (down > 0) fall += down; else fall = Math.max(0, fall + down);
        if (ty > -0.5) fall = Math.min(fall, 1); // плавне планування не накопичує висоту падіння
    }

    private void moveY(double d) {
        int n = (int) Math.ceil(Math.abs(d) / STEP);
        double step = n == 0 ? 0 : d / n;
        boolean hit = false;
        for (int i = 0; i < n; i++) {
            if (collides(pos.x, pos.y + step, pos.z)) { hit = true; break; }
            pos.y += step;
        }
        if (hit) { onGround = d < 0; vy = 0; } else onGround = false; // d ніколи не 0: гравітація діє щотіку
    }

    private void moveH(double dx, double dz, boolean edgeGuard) {
        int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / STEP);
        if (n == 0) return;
        double sx = dx / n, sz = dz / n;
        for (int i = 0; i < n; i++) {
            if (sx != 0 && collides(pos.x + sx, pos.y, pos.z) && onGround) stepUp(sx, 0); // невисокий уступ (дошки) - заходимо
            if (sz != 0 && collides(pos.x, pos.y, pos.z + sz) && onGround) stepUp(0, sz);
            if (sx != 0 && !collides(pos.x + sx, pos.y, pos.z) && !(edgeGuard && onGround && !grounded(pos.x + sx, pos.z))) pos.x += sx; else { sx = 0; vx = 0; }
            if (sz != 0 && !collides(pos.x, pos.y, pos.z + sz) && !(edgeGuard && onGround && !grounded(pos.x, pos.z + sz))) pos.z += sz; else { sz = 0; vz = 0; }
        }
    }

    /** Піднятись на найменшу висоту (кроком 1/16, до STEP_UP), з якої рух уже вільний. Повна стіна (1 блок) лишається стіною. */
    private void stepUp(double sx, double sz) {
        for (double h = 1 / 16.0; h <= STEP_UP + 1e-9; h += 1 / 16.0)
            if (!collides(pos.x, pos.y + h, pos.z) && !collides(pos.x + sx, pos.y + h, pos.z + sz)) { pos.y += h; return; }
    }

    private boolean grounded(double px, double pz) {
        double feet = pos.y + EYE_OFFSET - HEIGHT / 2, probe = 0.1;
        return collidesBox(px, feet - probe / 2, pz, WIDTH, probe);
    }

    private boolean collides(double px, double py, double pz) { return collidesBox(px, py + EYE_OFFSET, pz, WIDTH, HEIGHT); }

    /** Коробка (центр, ширина, висота) у координатах відносно чанка гравця (x,z можуть виходити за 0..16 - це нормально). */
    private boolean collidesBox(double cx, double cy, double cz, double w, double h) {
        return Solid.hits(world, pos.cx * 16, pos.cz * 16, cx - w / 2, cy - h / 2, cz - w / 2, cx + w / 2, cy + h / 2, cz + w / 2);
    }

    /** Чи перетне гравця блок, поставлений у (bx,by,bz) - щоб не замурувати себе. */
    public boolean overlapsBlock(long bx, int by, long bz) { return overlapsBox(bx, by, bz, 0, 0, 0, 1, 1, 1); }

    /** Чи перетинає тіло коробку (x0..x1 у частках) клітинки (bx,by,bz) - наприклад, дошку. */
    public boolean overlapsBox(long bx, int by, long bz, double x0, double y0, double z0, double x1, double y1, double z1) {
        double lx = (double) (bx - pos.cx * 16), lz = (double) (bz - pos.cz * 16);
        double cy = pos.y + EYE_OFFSET;
        return pos.x - WIDTH / 2 < lx + x1 && pos.x + WIDTH / 2 > lx + x0 && cy - HEIGHT / 2 < by + y1 && cy + HEIGHT / 2 > by + y0 && pos.z - WIDTH / 2 < lz + z1 && pos.z + WIDTH / 2 > lz + z0;
    }

    public boolean onGround() { return onGround; }
    /** Швидкість, блоків/с (для звуку вітру під час польоту). */
    public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }
    public double bodyCenterY() { return pos.y + EYE_OFFSET; }
    /** Висота очей із присіданням - звідси йде промінь прицілу. */
    public double aimY() { return pos.y + eyeOffset; }
}
