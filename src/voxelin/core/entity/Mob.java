package voxelin.core.entity;

import voxelin.core.*;

import java.util.Random;

/**
 * Істота: фізика (гравітація, колізії коробкою, вода, автострибок на 1 блок), прості стани ШІ (блукання, переслідування, удар, втеча), здоров'я.
 * Позиція pos - центр підніжжя (блоки, Pos: чанк long + зсув - точна на будь-якій відстані). Незавантажений чанк вважається суцільним: істота стоїть.
 * Усе в одному потоці (головному): World тікає істот у tick().
 */
public final class Mob {
    public static final double GRAVITY = 25.0, JUMP = 8.0, DT = 0.05, MAX_STEP = 0.1, DEATH_TICKS = 12;
    public static final int INVULN = 10;

    public final MobType type;
    public final Pos pos;
    public double vx, vy, vz;
    public float yaw;
    public double health;
    /** Дім (для жителів: блукають не далі homeRadius) і лічильник розмови (стоїть, поки гравець говорить). */
    public Pos home;
    public int talkTicks;
    public int age, hurtTicks, invuln, deathTicks, attackCooldown, fleeTicks, wanderTicks;
    public boolean dying, onGround, provoked, inWater, burning, lootDropped;
    /** Пройдена відстань - для анімації ходьби. */
    public double walk;
    private double wanderDx, wanderDz;
    private int burnTimer;
    private Pos threat; // звідки вдарили (для втечі)

    public Mob(MobType type, Pos at) {
        this.type = type; this.pos = at.copy(); this.health = type.health;
    }

    public boolean removable() { return dying && deathTicks >= DEATH_TICKS; }
    public boolean alive() { return !dying; }

    /** Шкода від удару; true - удар прийнято (не в невразливості). kx,kz - напрям поштовху від нападника. */
    public boolean damage(double amount, double kx, double kz, Pos from) {
        if (dying || invuln > 0 || !(amount > 0)) return false;
        health -= Math.min(amount, 1e6);
        invuln = INVULN; hurtTicks = 10;
        double len = Math.hypot(kx, kz);
        if (len > 1e-6) { vx = kx / len * 5.0; vz = kz / len * 5.0; vy = 4.0; onGround = false; }
        provoked = true;
        if (type.passive()) { fleeTicks = 100; threat = from == null ? null : from.copy(); }
        if (health <= 0) { dying = true; deathTicks = 0; }
        return true;
    }

    /** Один тік гри. */
    public void tick(World w, PlayerTarget target, Random rng) {
        age++;
        if (hurtTicks > 0) hurtTicks--;
        if (invuln > 0) invuln--;
        if (attackCooldown > 0) attackCooldown--;
        if (dying) { deathTicks++; return; }
        if (talkTicks > 0) { talkTicks--; vx *= 0.5; vz *= 0.5; moveHOnly(w); return; }
        long bx = pos.blockX(), bz = pos.blockZ();
        inWater = w.isLiquid(bx, pos.blockY(), bz);
        sunBurn(w);
        if (dying) return;

        double dirX = 0, dirZ = 0, speed = 0;
        boolean aggressive = type.hostile() || (type.behavior == voxelin.api.MobSettings.Behavior.NEUTRAL_DAY && (provoked || !daylight(w)));
        double tdx = 0, tdz = 0, tdist = Double.MAX_VALUE;
        if (target != null && target.alive() && !target.creative()) {
            Pos tb = target.body();
            tdx = tb.relX(pos.cx) - pos.x; tdz = tb.relZ(pos.cz) - pos.z;
            double dy = tb.y - (pos.y + type.height / 2);
            tdist = Math.sqrt(tdx * tdx + tdz * tdz + dy * dy);
        }
        if (fleeTicks > 0 && type.passive()) {
            fleeTicks--;
            double fx = threat == null ? rng.nextDouble() - 0.5 : pos.x - threat.relX(pos.cx), fz = threat == null ? rng.nextDouble() - 0.5 : pos.z - threat.relZ(pos.cz);
            double l = Math.hypot(fx, fz);
            if (l > 1e-6) { dirX = fx / l; dirZ = fz / l; speed = type.speed * 1.6; }
        } else if (aggressive && tdist <= type.detect) {
            double l = Math.hypot(tdx, tdz);
            if (l > 1e-6) { dirX = tdx / l; dirZ = tdz / l; }
            speed = l > type.reach * 0.6 ? type.speed : 0;
            if (tdist <= type.reach + type.width / 2 && Math.abs(target.body().y - (pos.y + type.height / 2)) < 1.6 && attackCooldown == 0 && type.attackDamage > 0) {
                attackCooldown = type.cooldownTicks;
                target.hurt(type.attackDamage, dirX, dirZ);
            }
        } else {
            if (wanderTicks <= 0) { // нова мить блукання: пауза або іти в випадковому напрямі
                if (rng.nextInt(10) < 4) { wanderDx = wanderDz = 0; wanderTicks = 40 + rng.nextInt(60); }
                else { double a = rng.nextDouble() * Math.PI * 2; wanderDx = Math.cos(a); wanderDz = Math.sin(a); wanderTicks = 20 + rng.nextInt(40); }
            }
            wanderTicks--;
            if (home != null && type.homeRadius > 0) { // далеко від дому - повертаємось
                double hx = home.relX(pos.cx) - pos.x, hz = home.relZ(pos.cz) - pos.z, hd = Math.hypot(hx, hz);
                if (hd > type.homeRadius) { wanderDx = hx / hd; wanderDz = hz / hd; wanderTicks = Math.max(wanderTicks, 10); }
            }
            dirX = wanderDx; dirZ = wanderDz; speed = (dirX != 0 || dirZ != 0) ? type.speed * 0.6 : 0;
        }
        if (speed > 0) yaw = (float) Math.toDegrees(Math.atan2(dirX, -dirZ));
        double tvx = dirX * speed * (inWater ? 0.6 : 1), tvz = dirZ * speed * (inWater ? 0.6 : 1);
        double k = Math.min(1.0, (onGround ? 10.0 : 2.0) * DT);
        vx += (tvx - vx) * k; vz += (tvz - vz) * k;
        if (speed > 0 && onGround && blockedAhead(w, dirX, dirZ)) { vy = JUMP; onGround = false; }
        if (inWater) vy = Math.max(-2.0, Math.min(2.5, vy + 8.0 * DT)); else vy = Math.max(-40.0, vy - GRAVITY * DT);
        double x0 = pos.relX(pos.cx), z0 = pos.relZ(pos.cz);
        moveY(w, vy * DT);
        moveH(w, vx * DT, vz * DT);
        pos.normalize();
        walk += Math.hypot(pos.relX(pos.cx) - x0, pos.relZ(pos.cz) - z0);
        if (pos.y < -30) { health = 0; dying = true; deathTicks = (int) DEATH_TICKS; }
    }

    /** Під час розмови лише гасимо ковзання й падаємо (гравітація). */
    private void moveHOnly(World w) {
        vy = Math.max(-40.0, vy - GRAVITY * DT);
        moveY(w, vy * DT);
        moveH(w, vx * DT, vz * DT);
        pos.normalize();
    }

    private boolean daylight(World w) {
        if (w.skyLevel() < 0.8) return false;
        int bx = (int) (pos.blockX() & 15), bz = (int) (pos.blockZ() & 15);
        Chunk c = w.chunk(pos.cx, pos.cz);
        if (c == null) return false;
        return c.sky(bx, Math.min(255, pos.blockY() + 1), bz) >= 15;
    }

    private void sunBurn(World w) {
        burning = false;
        if (!type.burnsInSun || inWater || !daylight(w)) return;
        burning = true;
        if (++burnTimer >= 20) { burnTimer = 0; health -= 1; hurtTicks = 6; if (health <= 0) { dying = true; deathTicks = 0; } }
    }

    /** Перед істотою (за напрямом руху) суцільний блок на рівні ніг, а над ним вільно - можна стрибнути на 1 блок. */
    private boolean blockedAhead(World w, double dx, double dz) {
        double reach = type.width / 2 + 0.35;
        long fx = Pos.blockOf(pos.cx, pos.x + dx * reach), fz = Pos.blockOf(pos.cz, pos.z + dz * reach);
        int fy = (int) Math.floor(pos.y + 0.05);
        if (!w.isSolid(fx, fy, fz)) return false;
        int headroom = (int) Math.ceil(type.height) + 1;
        for (int i = 1; i <= headroom; i++) if (w.isSolid(fx, fy + i, fz)) return false; // стіна заввишки більше 1 - не стрибаємо
        for (int i = 1; i <= (int) Math.ceil(type.height); i++) if (w.isSolid(pos.blockX(), fy + i + 0, pos.blockZ())) return false;
        return true;
    }

    private void moveY(World w, double d) {
        int n = (int) Math.ceil(Math.abs(d) / MAX_STEP);
        double step = n == 0 ? 0 : d / n;
        boolean hit = false;
        for (int i = 0; i < n; i++) {
            if (collides(w, pos.x, pos.y + step, pos.z)) { hit = true; break; }
            pos.y += step;
        }
        if (hit) { onGround = d < 0; vy = 0; } else onGround = false;
    }

    private void moveH(World w, double dx, double dz) {
        int n = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / MAX_STEP);
        if (n == 0) return;
        double sx = dx / n, sz = dz / n;
        for (int i = 0; i < n; i++) {
            if (sx != 0 && !collides(w, pos.x + sx, pos.y, pos.z)) pos.x += sx; else { sx = 0; vx = 0; }
            if (sz != 0 && !collides(w, pos.x, pos.y, pos.z + sz)) pos.z += sz; else { sz = 0; vz = 0; }
        }
    }

    /** Коробка (підніжжя px,py,pz у координатах відносно чанка істоти) перетинає суцільний блок. */
    private boolean collides(World w, double px, double py, double pz) {
        double hw = type.width / 2;
        long baseX = pos.cx * 16, baseZ = pos.cz * 16;
        int x0 = (int) Math.floor(px - hw), x1 = (int) Math.floor(px + hw - 1e-6), y0 = (int) Math.floor(py), y1 = (int) Math.floor(py + type.height - 1e-6),
                z0 = (int) Math.floor(pz - hw), z1 = (int) Math.floor(pz + hw - 1e-6);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++)
            if (w.isSolid(baseX + bx, by, baseZ + bz)) return true;
        return false;
    }
}
