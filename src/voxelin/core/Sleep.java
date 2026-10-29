package voxelin.core;

import voxelin.core.entity.Mob;

/**
 * Ліжко: ПКМ ставить точку відродження (лише звичайний світ), а вночі ще й проводить сон до ранку, якщо поруч нема ворогів.
 * Відродження - біля ліжка, поки воно стоїть; зламали - знову початковий спавн (з поясненням). Чисті функції над World.
 */
public final class Sleep {
    private Sleep() {}

    public static final String BED = "voxelin:bed";
    /** Радіус, у якому вороги не дають заснути (блоки). */
    public static final double MONSTER_RADIUS = 8;

    public record Result(String message, boolean slept) {}

    /** Сон можливий від пізнього вечора до кінця ночі (фаза 0.66..0.95). */
    public static boolean night(World w) { double p = w.dayPhase(); return p >= 0.66 && p < 0.95; }

    public static Result use(World w, long x, int y, long z, Pos player) {
        if (w.info.type != WorldInfo.Type.NORMAL && w.info.type != WorldInfo.Type.FLAT) return new Result("Тут не спиться: цей світ не відпускає в сон.", false);
        if (!BED.equals(w.blocks.get(Math.max(0, w.blockId(x, y, z))).name)) return null;
        boolean changed = !w.info.hasBed || w.info.bedX != x || w.info.bedY != y || w.info.bedZ != z;
        w.info.hasBed = true; w.info.bedX = x; w.info.bedY = y; w.info.bedZ = z;
        String spawn = changed ? "Точку відродження встановлено. " : "";
        if (!night(w)) return new Result(spawn + "Спати можна лише вночі.", false);
        for (Mob m : w.mobs()) {
            if (!m.alive() || !m.type.hostile()) continue;
            double dx = m.pos.relX(player.cx) - player.x, dy = m.pos.y - player.y, dz = m.pos.relZ(player.cz) - player.z;
            if (dx * dx + dy * dy + dz * dz < MONSTER_RADIUS * MONSTER_RADIUS) return new Result(spawn + "Не можна спати: поруч вороги.", false);
        }
        long day = w.dayTicks();
        w.setTime((Math.floorDiv(w.time(), day) + 1) * day); // наступний ранок
        return new Result(spawn + "Ти проспав до ранку.", true);
    }

    /** Де відроджуватись: над ліжком, якщо воно ціле; інакше null (і ліжко забувається). */
    public static long[] respawnAt(World w) {
        WorldInfo i = w.info;
        if (!i.hasBed) return null;
        int id = w.blockId(i.bedX, i.bedY, i.bedZ); // незавантажений чанк (-1): ліжку віримо - зламати його міг лише гравець, поки чанк був поруч
        if (id >= 0 && !BED.equals(w.blocks.get(id).name)) { i.hasBed = false; return null; }
        return new long[]{i.bedX, i.bedY + 1, i.bedZ};
    }
}
