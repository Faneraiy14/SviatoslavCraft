package voxelin.core;

import voxelin.api.Source;
import voxelin.core.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Міф (DESIGN.md, «Сутність на кшталт Херобрина»; рішення Святослава 29.10.2026 - на розсуд Claude). У грі ніде не називається:
 * не моб, без хітбокса, не нападає, без запису в журналі й без досягнення. Лише сліди - рідко й не за розкладом, завжди поза очима гравця:
 * прорубаний у камені тунель, дерево без листя, піщана піраміда, лампа в темряві, де ніхто її не ставив. Частіше там, де Шов тонкий
 * (міжсвіття, зона зараження, Тихі пустища). Іноді - обірваний рядок у чаті, який нікуди не записується.
 * «Допомога» - справжня механіка, але трохи не та, і кожна лише раз за світ: вороги розбігаються, але гаснуть смолоскипи; лампи виводять
 * з темряви, але до точки появи світу, а не до ліжка; речі після смерті складено в скриню, але однієї стопки бракує.
 */
public final class Herobrine {
    private Herobrine() {}

    private static final Source SRC = Sources.SYSTEM;
    /** Найменше між слідами (тіків; ~10 хв) і базовий шанс за перевірку раз на 10 с (~1 на годину гри, де Шов товстий). */
    static final int GAP = 12000, CHECK = 200;
    static final double BASE = 1.0 / 360;
    public enum Trace { TUNNEL, LEAFLESS, PYRAMID, LAMP }
    public static final String HELP_SCARE = "scare", HELP_LAMPS = "lamps", HELP_CHEST = "chest";
    static final String[] WHISPERS = {"хтось уже був т", "не йди за світл", "він не з цього св", "я лишив тобі", "...ти теж це бачиш?", "не дякуй. мене тут не бу", "шов тонкий біля"};

    /** Наскільки тонкий Шов тут: множник шансу сліду. */
    static double thin(World w, Pos at) {
        if (w.info.type == WorldInfo.Type.BETWEEN) return 3;
        double k = 1;
        if (w.infection != null && w.infection.affects(at.blockX(), at.blockZ())) k *= 2;
        if ("Тихі пустища".equals(w.biomeAt(at.blockX(), at.blockZ()))) k *= 2;
        return k;
    }

    /** Раз на CHECK тіків: можливий слід або допомога. Лише звичайний світ і міжсвіття. */
    static void tick(World w, PlayerTarget p, Random rng, long tick) {
        if (p == null || !p.alive() || tick % CHECK != 0) return;
        WorldInfo i = w.info;
        if (i.type == WorldInfo.Type.END || i.type == WorldInfo.Type.NETHER) return;
        Pos at = p.body();
        if (!p.creative()) help(w, p, rng, tick);
        if (tick < i.hbNext || rng.nextDouble() >= BASE * thin(w, at)) return;
        Trace t = Trace.values()[rng.nextInt(Trace.values().length)];
        if (trace(w, t, at, rng)) {
            i.hbNext = tick + GAP + rng.nextInt(GAP);
            if (rng.nextInt(10) < 3) w.notifyPlayer(WHISPERS[rng.nextInt(WHISPERS.length)]);
        }
    }

    /** Лишити слід виду t за 28-44 блоки від гравця (в завантажених чанках). true - вдалося. */
    public static boolean trace(World w, Trace t, Pos near, Random rng) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double a = rng.nextDouble() * Math.PI * 2, d = 28 + rng.nextDouble() * 16;
            long x = near.blockX() + Math.round(Math.cos(a) * d), z = near.blockZ() + Math.round(Math.sin(a) * d);
            if (w.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L)) == null) continue;
            boolean ok = switch (t) {
                case TUNNEL -> tunnel(w, x, z, rng);
                case LEAFLESS -> leafless(w, x, z);
                case PYRAMID -> pyramid(w, x, z);
                case LAMP -> lamp(w, x, z, rng);
            };
            if (ok) return true;
        }
        return false;
    }

    private static boolean is(World w, long x, int y, long z, String... names) {
        int id = w.blockId(x, y, z);
        if (id <= 0) return false;
        String n = w.blocks.get(id).name;
        for (String s : names) if (n.equals("voxelin:" + s)) return true;
        return false;
    }

    private static int surface(World w, long x, long z) { for (int y = Chunk.HEIGHT - 2; y > 1; y--) { int id = w.blockId(x, y, z); if (id > 0 && w.blocks.get(id).solid) return y; } return -1; }

    /** Рівний тунель 1x2 у суцільному камені, 8-16 блоків уздовж X чи Z. */
    private static boolean tunnel(World w, long x, long z, Random rng) {
        int top = surface(w, x, z);
        if (top < 20) return false;
        int y = 12 + rng.nextInt(Math.max(1, top - 20));
        boolean alongX = rng.nextBoolean();
        int len = 8 + rng.nextInt(9);
        for (int k = 0; k < len; k++) for (int h = 0; h < 2; h++) {
            long tx = alongX ? x + k : x, tz = alongX ? z : z + k;
            if (!is(w, tx, y + h, tz, "stone", "deep_stone")) return false;
        }
        for (int k = 0; k < len; k++) for (int h = 0; h < 2; h++) w.setBlock(SRC, alongX ? x + k : x, y + h, alongX ? z : z + k, w.blocks.air());
        return true;
    }

    /** Дерево, з якого зникло листя (стовбур лишився). */
    private static boolean leafless(World w, long x, long z) {
        for (long dx = -6; dx <= 6; dx++) for (long dz = -6; dz <= 6; dz++) {
            int top = surface(w, x + dx, z + dz);
            if (top < 2 || !w.blocks.get(w.blockId(x + dx, top, z + dz)).name.endsWith("_leaves")) continue;
            int removed = 0;
            for (long ax = -3; ax <= 3; ax++) for (long az = -3; az <= 3; az++) for (int ay = -5; ay <= 1; ay++)
                if (w.blocks.get(Math.max(0, w.blockId(x + dx + ax, top + ay, z + dz + az))).name.endsWith("_leaves")) { w.setBlock(SRC, x + dx + ax, top + ay, z + dz + az, w.blocks.air()); removed++; }
            return removed > 0;
        }
        return false;
    }

    /** Маленька піщана піраміда 5x5 -> 3x3 -> 1 на рівній траві чи піску. */
    private static boolean pyramid(World w, long x, long z) {
        int top = surface(w, x, z);
        if (top < 2) return false;
        for (long dx = -2; dx <= 2; dx++) for (long dz = -2; dz <= 2; dz++) {
            if (surface(w, x + dx, z + dz) != top || !is(w, x + dx, top, z + dz, "grass", "sand", "dirt", "pale_grass")) return false;
            for (int h = 1; h <= 3; h++) if (w.blockId(x + dx, top + h, z + dz) != 0) return false;
        }
        BlockType sand = w.blocks.byName("voxelin:sand");
        for (int h = 0; h < 3; h++) for (long dx = -(2 - h); dx <= 2 - h; dx++) for (long dz = -(2 - h); dz <= 2 - h; dz++) w.setBlock(SRC, x + dx, top + 1 + h, z + dz, sand);
        return true;
    }

    /** Лампа на стіні печери в повній темряві. */
    private static boolean lamp(World w, long x, long z, Random rng) {
        int top = surface(w, x, z);
        for (int y = 8; y < top - 4; y++) {
            if (w.blockId(x, y, z) != 0 || !is(w, x, y - 1, z, "stone", "deep_stone")) continue;
            Chunk c = w.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L));
            if (c == null || (c.lightAt((int) Math.floorMod(x, 16L), y, (int) Math.floorMod(z, 16L)) & 15) > 0) continue;
            w.setBlock(SRC, x, y - 1, z, w.blocks.byName("voxelin:lamp"));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ «допомога», кожна раз за світ

    private static boolean used(World w, String h) { return w.info.hbUsed.contains(h); }
    private static void use(World w, String h) { w.info.hbUsed.add(h); }

    private static void help(World w, PlayerTarget p, Random rng, long tick) {
        Pos at = p.body();
        if (!used(w, HELP_SCARE) && w.skyLevel() < 0.5) { // мало здоров'я, вороги довкола - розбігаються (але гаснуть смолоскипи поруч)
            int near = 0;
            for (Mob m : w.mobs()) if (!m.dying && m.type.hostile() && dist(m.pos, at) < 12) near++;
            if (near >= 2 && p.health() <= 6) scare(w, at);
        }
        if (!used(w, HELP_LAMPS) && w.info.type != WorldInfo.Type.BETWEEN && at.y < 40) { // хвилину в темряві під землею - лампи
            Chunk c = w.chunk(at.cx, at.cz);
            boolean dark = c != null && c.lightAt((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)) == 0;
            w.info.hbDark = dark ? w.info.hbDark + CHECK : 0;
            if (w.info.hbDark >= 1200) lamps(w, at);
        }
    }

    /** Вороги в радіусі 24 тікають 10 с; смолоскипи й лампи в радіусі 8 гаснуть. */
    public static void scare(World w, Pos at) {
        use(w, HELP_SCARE);
        for (Mob m : w.mobs()) if (!m.dying && m.type.hostile() && dist(m.pos, at) < 24) m.scare(at, 200);
        for (long x = at.blockX() - 8; x <= at.blockX() + 8; x++) for (long z = at.blockZ() - 8; z <= at.blockZ() + 8; z++) for (int y = at.blockY() - 4; y <= at.blockY() + 4; y++)
            if (is(w, x, y, z, "torch", "lamp")) w.setBlock(SRC, x, y, z, w.blocks.air());
    }

    /** Лінія ламп під стелею від гравця в бік точки появи світу (0, 0), а не до ліжка. Повертає кількість. */
    public static int lamps(World w, Pos at) {
        use(w, HELP_LAMPS);
        double dx = -at.blockX(), dz = -at.blockZ(), l = Math.hypot(dx, dz);
        if (l < 1) { dx = 1; l = 1; }
        BlockType lamp = w.blocks.byName("voxelin:lamp");
        int n = 0;
        for (int k = 3; k <= 40 && n < 6; k += 4) {
            long x = at.blockX() + Math.round(dx / l * k), z = at.blockZ() + Math.round(dz / l * k);
            for (int y = at.blockY(); y < at.blockY() + 8; y++) {
                int id = w.blockId(x, y, z);
                if (id < 0) break;
                if (id > 0 && w.blocks.get(id).solid) { if (y > at.blockY() && w.blockId(x, y - 1, z) == 0) { w.setBlock(SRC, x, y, z, lamp); n++; } break; }
            }
        }
        return n;
    }

    /**
     * Смерть гравця: раз за світ (із шансом 1/3) речі не розлітаються, а складені в скриню на місці смерті - без однієї стопки.
     * Повертає true, якщо речі забрано (тоді викликач нічого не викидає).
     */
    public static boolean deathChest(World w, Pos at, List<Inventory.Stack> drops, Random rng) {
        if (used(w, HELP_CHEST) || drops.size() < 2 || w.info.otherDim() && w.info.type != WorldInfo.Type.BETWEEN || rng.nextInt(3) != 0) return false;
        long x = at.blockX(), z = at.blockZ(); int y = (int) Math.floor(at.y);
        while (y > 1 && w.blockId(x, y - 1, z) == 0) y--;
        BlockType chest = w.blocks.byName("voxelin:chest");
        if (chest == null || w.blockId(x, y, z) != 0 || !w.setBlock(SRC, x, y, z, chest)) return false;
        ChestEntity ce = w.chestAt(x, y, z);
        if (ce == null) return false;
        use(w, HELP_CHEST);
        List<Inventory.Stack> keep = new ArrayList<>(drops);
        keep.remove(rng.nextInt(keep.size())); // однієї бракує
        int k = 0, overflow = 0;
        for (Inventory.Stack s : keep) { if (k < ChestEntity.SIZE) ce.set(k++, s); else { w.spawnItem(Pos.ofBlock(x, y + 1, z), s.item, s.count, s.damage, true); overflow++; } }
        return true;
    }

    private static double dist(Pos a, Pos b) { double dx = a.relX(b.cx) - b.x, dz = a.relZ(b.cz) - b.z, dy = a.y - b.y; return Math.sqrt(dx * dx + dy * dy + dz * dz); }
}
