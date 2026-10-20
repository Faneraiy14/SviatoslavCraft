package voxelin.core.entity;

import voxelin.core.AABB;
import voxelin.core.Units;

/**
 * Гравець: позиція очей (світові одиниці), кути огляду, фізика. Логіка в тіках (20/с), рендер інтерполює між prevX..x.
 * Перенесено зі старого Player: плавний розгін, спринт лише вперед, присід (повільно, не падає з краю, камера опускається).
 * Нове: рух дрібними кроками (не більше 0.25 одиниці), тож навіть швидке падіння не проходить крізь блок і не «зависає» над землею.
 */
public final class PlayerEntity {
    public interface Solid { boolean at(int x, int y, int z); }

    public static final float HEIGHT = 1.8f * Units.BLOCK_SIZE, EYE = 1.6f * Units.BLOCK_SIZE, WIDTH = 0.6f * Units.BLOCK_SIZE;
    private static final float EYE_OFFSET = HEIGHT / 2f - EYE;
    private static final float SPEED = 5.5f * Units.BLOCK_SIZE, JUMP = 9.0f * Units.BLOCK_SIZE, GRAVITY = 25f * Units.BLOCK_SIZE;
    private static final float MAX_FALL = 40f * Units.BLOCK_SIZE, ACCEL = 9f, SPRINT = 1.6f, CROUCH = 0.3f, CROUCH_DROP = 0.4f * Units.BLOCK_SIZE;
    private static final float STEP = 0.25f;

    public float x, y, z, yaw, pitch;
    public float prevX, prevY, prevZ;
    public float eyeOffset, prevEyeOffset;
    private float vx, vy, vz;
    private boolean onGround;
    private final Solid world;

    public PlayerEntity(Solid world, float x, float y, float z) { this.world = world; teleport(x, y, z); }

    public void teleport(float x, float y, float z) { this.x = prevX = x; this.y = prevY = y; this.z = prevZ = z; vx = vy = vz = 0; }

    public void rotate(float dYaw, float dPitch) { yaw += dYaw; pitch = Math.max(-89f, Math.min(89f, pitch + dPitch)); }

    /** Один тік: рух за клавішами, стрибок, гравітація, колізії. */
    public void tick(float dt, boolean fwd, boolean back, boolean left, boolean right, boolean jump, boolean sprint, boolean crouch) {
        prevX = x; prevY = y; prevZ = z; prevEyeOffset = eyeOffset;
        double yr = Math.toRadians(yaw);
        float fx = (float) Math.sin(yr), fz = -(float) Math.cos(yr), rx = (float) Math.cos(yr), rz = (float) Math.sin(yr);
        float dx = 0, dz = 0;
        if (fwd) { dx += fx; dz += fz; }
        if (back) { dx -= fx; dz -= fz; }
        if (right) { dx += rx; dz += rz; }
        if (left) { dx -= rx; dz -= rz; }
        float tvx = 0, tvz = 0;
        if (dx != 0 || dz != 0) {
            float len = (float) Math.sqrt(dx * dx + dz * dz), s = crouch ? SPEED * CROUCH : (sprint && fwd ? SPEED * SPRINT : SPEED);
            tvx = dx / len * s; tvz = dz / len * s;
        }
        float t = Math.min(1f, ACCEL * dt);
        vx += (tvx - vx) * t; vz += (tvz - vz) * t;
        if (tvx == 0 && tvz == 0 && Math.abs(vx) < 0.01f && Math.abs(vz) < 0.01f) { vx = 0; vz = 0; }
        eyeOffset += ((crouch ? -CROUCH_DROP : 0f) - eyeOffset) * Math.min(1f, 10f * dt);

        if (jump && onGround) { vy = JUMP; onGround = false; }
        vy = Math.max(-MAX_FALL, vy - GRAVITY * dt);
        moveY(vy * dt);
        moveH(vx * dt, vz * dt, crouch);
    }

    private void moveY(float d) {
        int n = (int) Math.ceil(Math.abs(d) / STEP);
        float step = n == 0 ? 0 : d / n;
        boolean hit = false;
        for (int i = 0; i < n; i++) {
            if (collides(x, y + step, z)) { hit = true; break; }
            y += step;
        }
        if (hit) { onGround = d < 0; vy = 0; } else onGround = false; // d ніколи не 0: гравітація діє щотіку
    }

    private void moveH(float dx, float dz, boolean edgeGuard) {
        int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / STEP);
        if (n == 0) return;
        float sx = dx / n, sz = dz / n;
        for (int i = 0; i < n; i++) {
            if (sx != 0 && !collides(x + sx, y, z) && !(edgeGuard && onGround && !grounded(x + sx, z))) x += sx; else { sx = 0; vx = 0; }
            if (sz != 0 && !collides(x, y, z + sz) && !(edgeGuard && onGround && !grounded(x, z + sz))) z += sz; else { sz = 0; vz = 0; }
        }
    }

    private boolean grounded(float px, float pz) {
        float feet = y + EYE_OFFSET - HEIGHT / 2f, probe = 0.1f * Units.BLOCK_SIZE;
        return collidesBox(new AABB(px, feet - probe / 2f, pz, WIDTH, probe));
    }

    private boolean collides(float px, float py, float pz) { return collidesBox(new AABB(px, py + EYE_OFFSET, pz, WIDTH, HEIGHT)); }

    private boolean collidesBox(AABB b) {
        int x0 = Units.toBlock(b.minX), x1 = Units.toBlock(b.maxX - 1e-4f), y0 = Units.toBlock(b.minY), y1 = Units.toBlock(b.maxY - 1e-4f),
                z0 = Units.toBlock(b.minZ), z1 = Units.toBlock(b.maxZ - 1e-4f);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
            if (world.at(bx, by, bz)) return true;
        return false;
    }

    /** Чи перетне гравця блок, поставлений у (bx,by,bz) - щоб не замурувати себе. */
    public boolean overlapsBlock(int bx, int by, int bz) {
        AABB me = new AABB(x, y + EYE_OFFSET, z, WIDTH, HEIGHT);
        return me.intersects(new AABB(Units.center(bx), Units.center(by), Units.center(bz), Units.BLOCK_SIZE, Units.BLOCK_SIZE));
    }

    public boolean onGround() { return onGround; }
    public float bodyCenterY() { return y + EYE_OFFSET; }
    /** Висота очей із присіданням - звідси йде промінь прицілу. */
    public float aimY() { return y + eyeOffset; }
}
