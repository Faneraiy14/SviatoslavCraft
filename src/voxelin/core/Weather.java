package voxelin.core;

/**
 * Погода - чиста функція від сіда світу й ігрового часу: нічого не зберігається й не передається мережею, а дощ у хоста,
 * гостей і сервера однаковий (час у них спільний). Час ділиться на відрізки по SLOT тіків; кожен відрізок за хешем сіда
 * ясний або дощовий (RAIN_CHANCE); на межі відрізків дощ плавно наростає чи стихає за FADE тіків.
 * Дощ іде лише у верхньому світі й не в посушливих біомах (пустелі - сухо, хоча небо так само хмурніє).
 */
public final class Weather {
    private Weather() {}

    /** 6 хвилин гри на відрізок; частка дощових; перехід - 20 с. */
    public static final long SLOT = 7200, FADE = 400;
    public static final double RAIN_CHANCE = 0.22;

    /** Біоми без дощу (назви для людей, як у World.biomeAt). */
    private static final java.util.Set<String> DRY = java.util.Set.of("Пустеля", "Скляні дюни", "Тихі пустища");

    public static boolean rainySlot(long seed, long slot) {
        long z = seed ^ 0x5241494E5241494EL;
        z += slot * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53 < RAIN_CHANCE;
    }

    /** Сила дощу 0..1 у момент time. */
    public static double rain(long seed, long time) {
        long slot = Math.floorDiv(time, SLOT), in = time - slot * SLOT;
        double a = rainySlot(seed, slot) ? 1 : 0;
        if (in < SLOT - FADE) return a;
        double b = rainySlot(seed, slot + 1) ? 1 : 0;
        double t = (double) (in - (SLOT - FADE)) / FADE;
        t = t * t * (3 - 2 * t);
        return a + (b - a) * t;
    }

    /** Чи падає дощ у біомі з такою назвою (посушливі - ні). */
    public static boolean wetBiome(String biomeTitle) { return biomeTitle == null || !DRY.contains(biomeTitle); }

    /** Тіків до наступної зміни погоди (ясно <-> дощ) від time; -1, якщо за 200 відрізків не змінюється. */
    public static long ticksToChange(long seed, long time) {
        long slot = Math.floorDiv(time, SLOT);
        boolean now = rainySlot(seed, slot);
        for (long k = 1; k <= 200; k++) if (rainySlot(seed, slot + k) != now) return (slot + k) * SLOT - time;
        return -1;
    }
}
