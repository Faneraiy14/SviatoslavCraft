package voxelin.core.entity;

import voxelin.core.Pos;

/**
 * Предмет на землі: падає, лежить, спливає у воді, зливається з сусідніми однаковими, зникає через 5 хвилин. Усе в БЛОКАХ, позиція - Pos
 * (однаково точна на будь-якій відстані). Незавантажений чанк вважається суцільним (предмет не провалюється й «заморожується»).
 */
public final class ItemEntity {
    public static final double HALF = 0.125, GRAVITY = 22.0, DT = 0.05, MAX_STEP = 0.1, PICKUP_RADIUS = 1.5;
    public static final int LIFETIME = 6000, PICKUP_DELAY = 10;

    public final Pos pos;
    public double vx, vy, vz;
    public final String item;
    public int count, damage, age, pickupDelay = PICKUP_DELAY;
    public boolean onGround;

    public ItemEntity(Pos at, String item, int count, int damage) {
        this.pos = at.copy(); this.item = item; this.count = count; this.damage = damage;
    }

    public boolean expired() { return age >= LIFETIME; }

    /** Один тік: гравітація, рух з колізіями, тертя. */
    public void tick(PlayerEntity.Solid solid, PlayerEntity.Liquid liquid) {
        age++;
        if (pickupDelay > 0) pickupDelay--;
        boolean wet = liquid.at(pos.blockX(), pos.blockY(), pos.blockZ());
        if (collides(solid, pos.x, pos.y, pos.z)) { // замурований (блок поставили на предмет) - витискаємо вгору
            for (int i = 0; i < 40 && collides(solid, pos.x, pos.y, pos.z); i++) pos.y += 0.1;
            if (collides(solid, pos.x, pos.y, pos.z)) return; // нема куди - лежить
        }
        if (wet) { vy = Math.max(-1.2, Math.min(1.0, vy + 6.0 * DT)); vx *= 0.9; vz *= 0.9; }
        else vy = Math.max(-40.0, vy - GRAVITY * DT);
        moveY(solid, vy * DT);
        double fr = onGround ? 0.6 : 0.98;
        vx *= fr; vz *= fr;
        if (Math.abs(vx) < 0.01) vx = 0;
        if (Math.abs(vz) < 0.01) vz = 0;
        moveH(solid, vx * DT, vz * DT);
        pos.normalize();
    }

    private void moveY(PlayerEntity.Solid solid, double d) {
        int n = (int) Math.ceil(Math.abs(d) / MAX_STEP);
        double step = n == 0 ? 0 : d / n;
        boolean hit = false;
        for (int i = 0; i < n; i++) {
            if (collides(solid, pos.x, pos.y + step, pos.z)) { hit = true; break; }
            pos.y += step;
        }
        if (hit) { onGround = d < 0; vy = 0; } else onGround = false;
    }

    private void moveH(PlayerEntity.Solid solid, double dx, double dz) {
        int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / MAX_STEP);
        if (n == 0) return;
        double sx = dx / n, sz = dz / n;
        for (int i = 0; i < n; i++) {
            if (sx != 0 && !collides(solid, pos.x + sx, pos.y, pos.z)) pos.x += sx; else { sx = 0; vx = 0; }
            if (sz != 0 && !collides(solid, pos.x, pos.y, pos.z + sz)) pos.z += sz; else { sz = 0; vz = 0; }
        }
    }

    /** Коробка предмета (центр px,py,pz у координатах відносно чанка предмета) перетинає суцільний блок. */
    private boolean collides(PlayerEntity.Solid solid, double px, double py, double pz) {
        long baseX = pos.cx * 16, baseZ = pos.cz * 16;
        int x0 = (int) Math.floor(px - HALF), x1 = (int) Math.floor(px + HALF - 1e-6), y0 = (int) Math.floor(py - HALF), y1 = (int) Math.floor(py + HALF - 1e-6),
                z0 = (int) Math.floor(pz - HALF), z1 = (int) Math.floor(pz + HALF - 1e-6);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
            if (solid.at(baseX + bx, by, baseZ + bz)) return true;
        return false;
    }

    /** Чи лежать два предмети достатньо близько й однакові, щоб злитись. */
    public boolean canMerge(ItemEntity o, int maxStack) {
        if (o == this || !item.equals(o.item) || damage != o.damage || count + o.count > maxStack) return false;
        double dx = pos.relX(o.pos.cx) - o.pos.x, dz = pos.relZ(o.pos.cz) - o.pos.z, dy = pos.y - o.pos.y;
        return dx * dx + dz * dz + dy * dy < 0.75 * 0.75;
    }
}
