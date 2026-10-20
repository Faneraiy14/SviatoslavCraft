package voxelin.core;

/**
 * Доба: чисті функції від ігрового часу (тіки, 20/с). Фаза p у [0,1): 0 - початок дня; 0..0.60 повний день, 0.60..0.70 вечір,
 * 0.70..0.95 ніч, 0.95..1.00 світанок. День довший за ніч (перший день не «проскакує»), типова доба - 30 хвилин.
 */
public final class DayCycle {
    private DayCycle() {}

    public static final double DEFAULT_MINUTES = 30.0, MIN_MINUTES = 1.0, MAX_MINUTES = 24 * 60.0;
    /** Найтемніше небо (множник небесного світла): ніч читабельна, не чорний екран. */
    public static final double NIGHT_SKY = 0.12;
    private static final float[] DAY = {0.53f, 0.71f, 0.92f}, NIGHT = {0.02f, 0.03f, 0.09f}, GLOW = {0.88f, 0.46f, 0.24f};

    public static long dayTicks(double minutes) {
        double m = Double.isNaN(minutes) ? DEFAULT_MINUTES : Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
        return Math.max(1, Math.round(m * 60 * 20));
    }

    /** Частина доби 0..1 (час не від'ємний за контрактом; від'ємний нормалізується). */
    public static double phase(long time, long dayTicks) { return (double) Math.floorMod(time, dayTicks) / dayTicks; }

    /** Номер доби, з 1. */
    public static long day(long time, long dayTicks) { return Math.floorDiv(Math.max(0, time), dayTicks) + 1; }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** Яскравість неба NIGHT_SKY..1. */
    public static double sky(double p) {
        if (p < 0.60) return 1;
        if (p < 0.70) return 1 - (1 - NIGHT_SKY) * smooth((p - 0.60) / 0.10);
        if (p < 0.95) return NIGHT_SKY;
        return NIGHT_SKY + (1 - NIGHT_SKY) * smooth((p - 0.95) / 0.05);
    }

    /** Сила «заходу/сходу» 0..1 - підфарбовує небо в помаранчевий. */
    public static double glow(double p) {
        if (p >= 0.60 && p < 0.70) return Math.sin(Math.PI * (p - 0.60) / 0.10);
        if (p >= 0.95) return 0.8 * Math.sin(Math.PI * (p - 0.95) / 0.05);
        return 0;
    }

    /** Колір неба/туману за яскравістю неба й силою заходу. */
    public static float[] skyColor(double sky, double glow) {
        float t = (float) ((sky - NIGHT_SKY) / (1 - NIGHT_SKY)), g = (float) (0.55 * glow);
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) { float base = NIGHT[i] + (DAY[i] - NIGHT[i]) * t; c[i] = base + (GLOW[i] - base) * g; }
        return c;
    }

    public static String phaseName(double p) {
        if (p < 0.60) return "день";
        if (p < 0.70) return "вечір";
        if (p < 0.95) return "ніч";
        return "світанок";
    }

    /** Назва для /time set -> фаза, або -1. */
    public static double presetPhase(String name) {
        switch (name) {
            case "день": case "day": return 0.0;
            case "полудень": case "noon": return 0.30;
            case "вечір": case "evening": return 0.62;
            case "ніч": case "night": return 0.78;
            case "північ": case "midnight": return 0.85;
            case "світанок": case "dawn": return 0.96;
            default: return -1;
        }
    }
}
