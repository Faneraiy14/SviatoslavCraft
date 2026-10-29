package voxelin.core;

import voxelin.core.entity.Mob;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Стріли з лука: летять із гравітацією й опором повітря, б'ють першу істоту на шляху (шкода від швидкості), застрягають у блоках
 * і лежать там хвилину - стрілу, випущену у виживанні, можна підібрати. Швидкість - у блоках за тік (повний натяг - 3, як у Minecraft).
 * Не зберігаються: після перезаходу застряглих стріл нема (як і летючих).
 */
public final class Arrows {
    public static final String ITEM = "voxelin:arrow", BOW = "voxelin:bow";
    public static final double FULL_SPEED = 3.0, GRAVITY = 0.05, DRAG = 0.99;
    public static final int STUCK_TICKS = 1200, MAX = 256;
    static final double STEP = 0.2;

    public static final class Arrow {
        public final Pos pos;
        public double vx, vy, vz;
        public boolean stuck;
        /** Підбирається (випущена у виживанні; у творчому - ні, щоб не плодити). */
        public final boolean pickup;
        public int age;
        /** Повний натяг: +2 шкоди. */
        public final boolean full;
        long sx, sz; int sy; // клітинка, у якій застрягла
        Arrow(Pos pos, double vx, double vy, double vz, boolean pickup, boolean full) { this.pos = pos; this.vx = vx; this.vy = vy; this.vz = vz; this.pickup = pickup; this.full = full; }
        public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }
    }

    private final World w;
    private final List<Arrow> list = new ArrayList<>();

    Arrows(World w) { this.w = w; }

    public List<Arrow> all() { return list; }

    /** Натяг 0..1 -> частка повної швидкості (як у Minecraft: (c^2 + 2c) / 3). */
    public static double power(double charge) { double c = Math.max(0, Math.min(1, charge)); return (c * c + 2 * c) / 3; }

    /** Шкода за швидкістю в момент влучання (до 6) і +2 за повний натяг. */
    public static double damage(double speed, boolean full) { return Math.ceil(speed * 2) + (full ? 2 : 0); }

    /** Випустити стрілу з точки (очі) в напрямку (yaw, pitch) зі швидкістю speed блоків/тік. */
    public Arrow shoot(Pos from, float yaw, float pitch, double speed, boolean pickup) {
        double[] d = Raycast.dir(yaw, pitch);
        Arrow a = new Arrow(from.copy(), d[0] * speed, d[1] * speed, d[2] * speed, pickup, speed >= FULL_SPEED * 0.99);
        list.add(a);
        if (list.size() > MAX) list.remove(0);
        return a;
    }

    void tick() {
        for (Iterator<Arrow> it = list.iterator(); it.hasNext(); ) {
            Arrow a = it.next();
            a.age++;
            if (a.stuck) {
                if (a.age > STUCK_TICKS) { it.remove(); continue; }
                if (!w.isSolid(a.sx, a.sy, a.sz)) { a.stuck = false; a.vx = a.vy = a.vz = 0; } // блок зламали - падає
                continue;
            }
            if (a.age > STUCK_TICKS || a.pos.y < -30) { it.remove(); continue; }
            if (fly(a)) it.remove();
        }
    }

    /** Один тік польоту дрібними кроками. true - стріла влучила в істоту й зникла. */
    private boolean fly(Arrow a) {
        double len = a.speed();
        int steps = Math.max(1, (int) Math.ceil(len / STEP));
        double dx = a.vx / steps, dy = a.vy / steps, dz = a.vz / steps, seg = len / steps;
        for (int i = 0; i < steps; i++) {
            Mob m = mobOnSegment(a, dx, dy, dz, seg);
            if (m != null) {
                double kx = a.vx, kz = a.vz, h = Math.hypot(kx, kz);
                if (h < 1e-6) { kx = 0; kz = -1; } else { kx /= h; kz /= h; }
                Pos from = a.pos.copy().add(-kx, 0, -kz);
                w.hurtMob(m, damage(len, a.full), kx, kz, from);
                return true;
            }
            a.pos.add(dx, dy, dz);
            if (inSolid(a.pos)) {
                a.pos.add(-dx, -dy, -dz); // лишаємо вістря на межі
                a.pos.add(dx * 0.6, dy * 0.6, dz * 0.6);
                a.stuck = true; a.age = 0;
                a.sx = a.pos.blockX(); a.sy = a.pos.blockY(); a.sz = a.pos.blockZ();
                if (!w.isSolid(a.sx, a.sy, a.sz)) { a.sx = a.pos.copy().add(dx, 0, 0).blockX(); a.sy = (int) Math.floor(a.pos.y + dy * 0.5); a.sz = a.pos.copy().add(0, 0, dz).blockZ(); }
                w.sound.play("voxelin:arrow.hit", a.pos, 0.8f, 1f);
                return false;
            }
        }
        boolean water = w.isLiquid(a.pos.blockX(), a.pos.blockY(), a.pos.blockZ());
        double drag = water ? 0.6 : DRAG;
        a.vx *= drag; a.vy = a.vy * drag - GRAVITY; a.vz *= drag;
        return false;
    }

    private Mob mobOnSegment(Arrow a, double dx, double dy, double dz, double seg) {
        if (seg < 1e-9) return null;
        Mob best = null; double bestT = Double.POSITIVE_INFINITY;
        double ux = dx / seg, uy = dy / seg, uz = dz / seg;
        for (Mob m : w.mobs()) {
            if (m.dying || !m.alive()) continue;
            double hw = m.type.width / 2, mx = m.pos.relX(a.pos.cx), mz = m.pos.relZ(a.pos.cz);
            double t = Combat.rayBox(a.pos.x, a.pos.y, a.pos.z, ux, uy, uz, mx - hw, m.pos.y, mz - hw, mx + hw, m.pos.y + m.type.height, mz + hw);
            if (t >= 0 && t <= seg && t < bestT) { bestT = t; best = m; }
        }
        return best;
    }

    private boolean inSolid(Pos p) {
        long bx = p.blockX(), bz = p.blockZ(); int by = p.blockY();
        if (!w.isSolid(bx, by, bz)) return false;
        double[] b = w.boxes(bx, by, bz);
        if (b == null) return true;
        double fx = p.x - Math.floor(p.x), fy = p.y - by, fz = p.z - Math.floor(p.z);
        for (int k = 0; k + 5 < b.length; k += 6) if (fx >= b[k] && fx <= b[k + 3] && fy >= b[k + 1] && fy <= b[k + 4] && fz >= b[k + 2] && fz <= b[k + 5]) return true;
        return false;
    }

    /** Підбір застряглих стріл гравцем (центр тіла at). Повертає кількість. */
    public int collect(Pos at, Inventory inv) {
        int n = 0;
        for (Iterator<Arrow> it = list.iterator(); it.hasNext(); ) {
            Arrow a = it.next();
            if (!a.stuck || !a.pickup || a.age < 10) continue;
            double dx = a.pos.relX(at.cx) - at.x, dz = a.pos.relZ(at.cz) - at.z, dy = a.pos.y - at.y;
            if (dx * dx + dy * dy + dz * dz > 1.5 * 1.5 || !inv.canFit(ITEM, 1, 0)) continue;
            inv.add(ITEM, 1, 0);
            it.remove(); n++;
        }
        return n;
    }

    /** Забрати одну стрілу з інвентаря (першу знайдену). false - стріл нема. */
    public static boolean takeArrow(Inventory inv) {
        for (int i = 0; i < Inventory.SIZE; i++) {
            Inventory.Stack s = inv.get(i);
            if (s == null || !s.item.equals(ITEM)) continue;
            inv.set(i, s.count > 1 ? new Inventory.Stack(s.item, s.count - 1, s.damage) : null);
            return true;
        }
        return false;
    }

    public static boolean hasArrow(Inventory inv) {
        for (int i = 0; i < Inventory.SIZE; i++) { Inventory.Stack s = inv.get(i); if (s != null && s.item.equals(ITEM)) return true; }
        return false;
    }
}
