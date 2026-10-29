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
    /** Створена структурою (житель, тварини загону): не зникає від відстані. */
    public boolean keep;
    /** Лютує (напарника-боса вбито): швидше й б'є сильніше. */
    public boolean enraged;
    public int talkTicks;
    public int age, hurtTicks, invuln, deathTicks, attackCooldown, fleeTicks, wanderTicks;
    /** Стан польоту дракона: 0 кружляє, 1 пікірує, 2 відлітає. */
    public int flyState, flyTimer, diveCooldown;
    public double orbitAngle;
    public boolean dying, onGround, provoked, inWater, burning, lootDropped;
    /** Пройдена відстань - для анімації ходьби. */
    public double walk;
    private double wanderDx, wanderDz;
    private int burnTimer;
    private Pos threat; // звідки вдарили (для втечі)
    /** Ціль-істота (вибирає World раз на 10 тіків): ворог - житель чи Вартовий, Вартовий - ворог. */
    public Mob prey;

    /** Тікати від точки (житель бачить ворога). */
    public void fleeFrom(Pos from, int ticks) { if (type.passive()) { fleeTicks = Math.max(fleeTicks, ticks); threat = from.copy(); } }

    public Mob(MobType type, Pos at) {
        this.type = type; this.pos = at.copy(); this.health = type.health;
    }

    public boolean removable() { return dying && deathTicks >= (type.boss ? 80 : DEATH_TICKS); }
    public boolean alive() { return !dying; }

    /** Шкода від удару; true - удар прийнято (не в невразливості). kx,kz - напрям поштовху від нападника. */
    public boolean damage(double amount, double kx, double kz, Pos from) {
        if (dying || invuln > 0 || !(amount > 0) || type.invulnerable) return false;
        health -= Math.min(amount, 1e6);
        invuln = INVULN; hurtTicks = 10;
        double len = Math.hypot(kx, kz);
        if (len > 1e-6 && !type.boss) { vx = kx / len * 5.0; vz = kz / len * 5.0; vy = 4.0; onGround = false; }
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
        if (type.flying) { flyTick(w, target, rng); return; }
        if (talkTicks > 0) { talkTicks--; vx *= 0.5; vz *= 0.5; moveHOnly(w); return; }
        long bx = pos.blockX(), bz = pos.blockZ();
        inWater = w.isLiquid(bx, pos.blockY(), bz);
        sunBurn(w);
        if (dying) return;

        double dirX = 0, dirZ = 0, speed = 0;
        boolean infected = w.infection != null && w.infection.affects(bx, bz);
        if (infected && type.passive() && fleeTicks == 0 && rng.nextInt(40) == 0) { fleeTicks = 60; threat = w.infection.corePos(); } // тварини тікають від ядра
        boolean aggressive = type.hostile() || (type.behavior == voxelin.api.MobSettings.Behavior.NEUTRAL_DAY && (provoked || !daylight(w)));
        double tdx = 0, tdz = 0, tdist = Double.MAX_VALUE;
        if (target != null && target.alive() && !target.creative()) {
            Pos tb = target.body();
            tdx = tb.relX(pos.cx) - pos.x; tdz = tb.relZ(pos.cz) - pos.z;
            double dy = tb.y - (pos.y + type.height / 2);
            tdist = Math.sqrt(tdx * tdx + tdz * tdz + dy * dy);
        }
        if (prey != null && (!prey.alive() || prey.removable())) prey = null;
        double pdx = 0, pdz = 0, pdist = Double.MAX_VALUE;
        if (prey != null) {
            pdx = prey.pos.relX(pos.cx) - pos.x; pdz = prey.pos.relZ(pos.cz) - pos.z;
            double dy = prey.pos.y - pos.y;
            pdist = Math.sqrt(pdx * pdx + pdz * pdz + dy * dy);
        }
        boolean night = w.skyLevel() < 0.5;
        if (fleeTicks > 0 && type.passive()) {
            fleeTicks--;
            double fx = threat == null ? rng.nextDouble() - 0.5 : pos.x - threat.relX(pos.cx), fz = threat == null ? rng.nextDouble() - 0.5 : pos.z - threat.relZ(pos.cz);
            double l = Math.hypot(fx, fz);
            if (l > 1e-6) { dirX = fx / l; dirZ = fz / l; speed = type.speed * 1.6; }
        } else if (prey != null && pdist <= type.detect && (!aggressive || pdist < tdist)) { // істота-ціль ближча за гравця
            double l = Math.hypot(pdx, pdz);
            if (l > 1e-6) { dirX = pdx / l; dirZ = pdz / l; }
            speed = l > type.reach * 0.6 ? type.speed : 0;
            if (pdist <= type.reach + (type.width + prey.type.width) / 2 && attackCooldown == 0 && type.attackDamage > 0) {
                attackCooldown = type.cooldownTicks;
                prey.damage(type.attackDamage * (infected ? 1.5 : 1), dirX, dirZ, pos);
            }
        } else if (aggressive && tdist <= type.detect) {
            double l = Math.hypot(tdx, tdz);
            if (l > 1e-6) { dirX = tdx / l; dirZ = tdz / l; }
            speed = l > type.reach * 0.6 ? type.speed : 0;
            if (tdist <= type.reach + type.width / 2 && Math.abs(target.body().y - (pos.y + type.height / 2)) < 1.6 && attackCooldown == 0 && type.attackDamage > 0) {
                attackCooldown = type.cooldownTicks;
                target.hurt(type.attackDamage * (infected ? 1.5 : 1), dirX, dirZ, type.armorPierce); // у зараженій зоні вороги сильніші
            }
        } else if (night && type.villager() && home != null) { // вночі житель іде додому й стоїть там
            double hx = home.relX(pos.cx) - pos.x, hz = home.relZ(pos.cz) - pos.z, hd = Math.hypot(hx, hz);
            if (hd > 0.8) { dirX = hx / hd; dirZ = hz / hd; speed = type.speed * 0.8; }
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

    /** Політ дракона: кружляє над домом на висоті +22, помітивши гравця - пікірує, вдаривши - відлітає. Колізій немає. */
    /** Зсув кола польоту (щоб двоє босів не летіли одне в одному). */
    public void orbitOffset(double a) { orbitAngle = a; }

    private void flyTick(World w, PlayerTarget target, Random rng) {
        if (home == null) home = pos.copy();
        double hx = home.relX(pos.cx), hz = home.relZ(pos.cz);
        double cx = pos.x, cy = pos.y, cz = pos.z;
        double speed = type.speed * (enraged ? 1.4 : 1);
        double tx, ty, tz;
        boolean hunt = target != null && target.alive() && !target.creative();
        double dist = Double.MAX_VALUE, tbx = 0, tby = 0, tbz = 0;
        if (hunt) {
            Pos tb = target.body();
            tbx = tb.relX(pos.cx); tby = tb.y; tbz = tb.relZ(pos.cz);
            dist = Math.sqrt((tbx - cx) * (tbx - cx) + (tby - cy) * (tby - cy) + (tbz - cz) * (tbz - cz));
        }
        orbitAngle += 0.35 * DT;
        double orbX = hx + 35 * Math.cos(orbitAngle), orbZ = hz + 35 * Math.sin(orbitAngle), orbY = home.y + 22;
        flyTimer++;
        if (diveCooldown > 0) diveCooldown--;
        if (flyState == 0) {
            tx = orbX; ty = orbY; tz = orbZ;
            if (hunt && diveCooldown == 0 && dist < type.detect) { flyState = 1; flyTimer = 0; }
        } else if (flyState == 1) {
            if (!hunt) { flyState = 2; flyTimer = 0; tx = orbX; ty = orbY; tz = orbZ; }
            else {
                tx = tbx; ty = tby; tz = tbz; speed *= 1.5;
                if (dist <= type.reach + type.width / 2 && attackCooldown == 0 && type.attackDamage > 0) {
                    attackCooldown = type.cooldownTicks;
                    double dx = tbx - cx, dz = tbz - cz, l = Math.hypot(dx, dz);
                    target.hurt(type.attackDamage * (enraged ? 1.5 : 1), l > 1e-6 ? dx / l : 0, l > 1e-6 ? dz / l : 1, type.armorPierce);
                    flyState = 2; flyTimer = 0;
                }
                if (flyTimer > 200) { flyState = 2; flyTimer = 0; }
            }
        } else {
            tx = orbX; ty = orbY + 6; tz = orbZ;
            if (flyTimer > 60) { flyState = 0; diveCooldown = enraged ? 40 : 80; }
        }
        double dx = tx - cx, dy = ty - cy, dz = tz - cz, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double wx = 0, wy = 0, wz = 0;
        if (l > 1e-6) { wx = dx / l * speed; wy = dy / l * speed; wz = dz / l * speed; }
        vx += (wx - vx) * 0.12; vy += (wy - vy) * 0.12; vz += (wz - vz) * 0.12;
        pos.add(vx * DT, vy * DT, vz * DT);
        walk += Math.hypot(vx, vz) * DT;
        if (Math.hypot(vx, vz) > 0.5) yaw = (float) Math.toDegrees(Math.atan2(vx, -vz));
        if (pos.y < 2) pos.y = 2;
        if (pos.y > 250) pos.y = 250;
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
        return PlayerEntity.Solid.hits(w.solidView, pos.cx * 16, pos.cz * 16, px - hw, py, pz - hw, px + hw, py + type.height, pz + hw);
    }
}
