package voxelin.core;

import voxelin.core.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Село росте від ліжок. Перший раз, коли гравець поруч, запам'ятовується «основа» - скільки жителів у селі.
 * Кожне ліжко в радіусі BED_RADIUS від колодязя - місце ще для одного жителя: якщо жителів менше за основа + ліжка,
 * раз на ігрову добу приходить новий (випадкова професія), біля одного з ліжок. Так само загиблих заміняють нові, якщо є ліжка.
 * Нові хати сама гра не будує - щоб не розтрощити будівлі гравця. Перевірка раз на CHECK_TICKS, лише в звичайному світі.
 * Стан у WorldInfo.villages: "x_z" -> {основа, остання доба приходу}.
 */
public final class VillageLife {
    public static final int CHECK_TICKS = 600, NEAR = 128, BED_RADIUS = 32, COUNT_RADIUS = 48, MAX_VILLAGERS = 24;

    private VillageLife() {}

    static void tick(World w, PlayerTarget t, long tick) {
        if (tick % CHECK_TICKS != 0 || t == null || !t.alive() || w.info.type == WorldInfo.Type.BETWEEN || !w.dimension().equals("overworld")) return;
        Pos p = t.body();
        long[] v = w.locate("village", p.blockX(), p.blockZ());
        if (v == null) return;
        double dx = v[0] - p.blockX(), dz = v[2] - p.blockZ();
        if (dx * dx + dz * dz > (double) NEAR * NEAR) return;
        grow(w, v[0], v[2], w.mobRandom());
    }

    /** Одна перевірка села з колодязем у (cx,cz). Повертає нового жителя або null. */
    public static Mob grow(World w, long cx, long cz, Random rng) {
        if (w.chunk(Math.floorDiv(cx, 16L), Math.floorDiv(cz, 16L)) == null) return null;
        String key = cx + "_" + cz;
        List<Mob> villagers = villagersNear(w, cx, cz);
        long[] st = w.info.villages.get(key);
        if (st == null) { st = new long[]{villagers.size(), w.dayNumber()}; w.info.villages.put(key, st); return null; } // перший візит: основа
        long day = w.dayNumber();
        if (st[1] >= day) return null; // сьогодні вже приходили
        List<long[]> beds = beds(w, cx, cz);
        int cap = (int) Math.min(MAX_VILLAGERS, st[0] + beds.size());
        if (beds.isEmpty() || villagers.size() >= cap) return null;
        List<MobType> kinds = new ArrayList<>();
        for (MobType mt : w.mobTypes.all()) if (mt.villager() && !mt.name.endsWith("_elder")) kinds.add(mt); // старійшина - один на село, не приходить
        if (kinds.isEmpty()) return null;
        long[] b = beds.get(rng.nextInt(beds.size()));
        Pos at = spot(w, b[0], (int) b[1], b[2]);
        if (at == null) return null;
        Mob m = w.spawnMob(kinds.get(rng.nextInt(kinds.size())), at);
        if (m == null) return null;
        m.keep = true;
        st[1] = day;
        if (t(w) != null) t(w).notify("До села прийшов новий житель: " + m.type.title);
        return m;
    }

    private static PlayerTarget t(World w) { return w.playerTarget(); }

    static List<Mob> villagersNear(World w, long cx, long cz) {
        List<Mob> out = new ArrayList<>();
        for (Mob m : w.mobs()) {
            if (m.dying || !m.type.villager()) continue;
            double dx = m.pos.relX(0) - (cx + 0.5), dz = m.pos.relZ(0) - (cz + 0.5);
            // relX(0) - координата відносно чанка 0; для далеких сіл це double без втрати до ~1e15 блоків, досить для порівняння в межах 48
            if (dx * dx + dz * dz <= (double) COUNT_RADIUS * COUNT_RADIUS) out.add(m);
        }
        return out;
    }

    /** Ліжка навколо колодязя (по стовпчиках, лише в завантажених чанках, висота колодязя +-12). */
    static List<long[]> beds(World w, long cx, long cz) {
        List<long[]> out = new ArrayList<>();
        BlockType bed = w.blocks.byName(Sleep.BED);
        if (bed == null) return out;
        int cy = w.surfaceY(cx, cz);
        for (long x = cx - BED_RADIUS; x <= cx + BED_RADIUS; x++) for (long z = cz - BED_RADIUS; z <= cz + BED_RADIUS; z++) {
            if (w.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L)) == null) continue;
            for (int y = Math.max(1, cy - 12); y <= Math.min(Chunk.HEIGHT - 2, cy + 12); y++) if (w.blockId(x, y, z) == bed.id) out.add(new long[]{x, y, z});
        }
        return out;
    }

    /** Вільне місце (2 блоки повітря над твердим) біля ліжка. */
    static Pos spot(World w, long bx, int by, long bz) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {2, 0}, {0, 2}, {-2, 0}, {0, -2}};
        for (int[] o : d) for (int dy = -1; dy <= 1; dy++) {
            long x = bx + o[0], z = bz + o[1]; int y = by + dy;
            if (w.isSolid(x, y - 1, z) && !w.isSolid(x, y, z) && !w.isSolid(x, y + 1, z) && !w.isLiquid(x, y, z)) return Pos.ofBlock(x, y, z);
        }
        return null;
    }
}
