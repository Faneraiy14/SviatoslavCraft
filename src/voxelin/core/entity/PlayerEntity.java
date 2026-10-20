package voxelin.core.entity;

import voxelin.core.Pos;

/**
 * Гравець: позиція очей як Pos (чанк long + зсув double) - однаково точна на будь-якій відстані. Усе в БЛОКАХ.
 * Логіка в тіках (20/с), рендер інтерполює між prev і pos. Плавний розгін, спринт лише вперед, присід (повільно, не падає з краю,
 * камера опускається). Рух дрібними кроками (<= STEP), тож навіть швидке падіння не проходить крізь блок і не «зависає».
 * Колізії рахуються в локальних координатах відносно чанка гравця, блоки перевіряються за абсолютними long.
 */
public final class PlayerEntity {
    public interface Solid { boolean at(long x, int y, long z); }
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

    public PlayerEntity(Solid world, Pos at) { this(world, (x, y, z) -> false, at); }
    public PlayerEntity(Solid world, Liquid liquid, Pos at) { this.world = world; this.liquid = liquid; teleport(at); }

    public void teleport(Pos p) { pos.set(p); prev.set(p); vx = vy = vz = 0; fall = landedFall = 0; }

    /** Висота падіння (блоків), накопичена до приземлення в цьому тіку; читання скидає. Падіння у воду не рахується. */
    public double takeLanded() { double f = landedFall; landedFall = 0; return f; }

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
            if (sx != 0 && !collides(pos.x + sx, pos.y, pos.z) && !(edgeGuard && onGround && !grounded(pos.x + sx, pos.z))) pos.x += sx; else { sx = 0; vx = 0; }
            if (sz != 0 && !collides(pos.x, pos.y, pos.z + sz) && !(edgeGuard && onGround && !grounded(pos.x, pos.z + sz))) pos.z += sz; else { sz = 0; vz = 0; }
        }
    }

    private boolean grounded(double px, double pz) {
        double feet = pos.y + EYE_OFFSET - HEIGHT / 2, probe = 0.1;
        return collidesBox(px, feet - probe / 2, pz, WIDTH, probe);
    }

    private boolean collides(double px, double py, double pz) { return collidesBox(px, py + EYE_OFFSET, pz, WIDTH, HEIGHT); }

    /** Коробка (центр, ширина, висота) у координатах відносно чанка гравця (x,z можуть виходити за 0..16 - це нормально). */
    private boolean collidesBox(double cx, double cy, double cz, double w, double h) {
        long baseX = pos.cx * 16, baseZ = pos.cz * 16;
        int x0 = (int) Math.floor(cx - w / 2), x1 = (int) Math.floor(cx + w / 2 - 1e-6), y0 = (int) Math.floor(cy - h / 2), y1 = (int) Math.floor(cy + h / 2 - 1e-6),
                z0 = (int) Math.floor(cz - w / 2), z1 = (int) Math.floor(cz + w / 2 - 1e-6);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
            if (world.at(baseX + bx, by, baseZ + bz)) return true;
        return false;
    }

    /** Чи перетне гравця блок, поставлений у (bx,by,bz) - щоб не замурувати себе. */
    public boolean overlapsBlock(long bx, int by, long bz) {
        double lx = (double) (bx - pos.cx * 16), lz = (double) (bz - pos.cz * 16);
        double cy = pos.y + EYE_OFFSET;
        return pos.x - WIDTH / 2 < lx + 1 && pos.x + WIDTH / 2 > lx && cy - HEIGHT / 2 < by + 1 && cy + HEIGHT / 2 > by && pos.z - WIDTH / 2 < lz + 1 && pos.z + WIDTH / 2 > lz;
    }

    public boolean onGround() { return onGround; }
    public double bodyCenterY() { return pos.y + EYE_OFFSET; }
    /** Висота очей із присіданням - звідси йде промінь прицілу. */
    public double aimY() { return pos.y + eyeOffset; }
}
