package voxelin.core;

/** Версія гри (одна на клієнт, сервер і світи). Voxelin.VERSION - те саме число для старого коду. */
public final class GameVersion {
    private GameVersion() {}

    public static final String VERSION = "1.4.0";

    /** Порівняти "1.2.10" і "1.3.0" по числах: <0, 0, >0. Нечислові хвости ігноруються; null - найстаріша. */
    public static int compare(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        return 0;
    }

    static int[] parts(String v) {
        int[] r = new int[3];
        if (v == null) return new int[]{-1, -1, -1};
        String[] s = v.trim().split("\\.");
        for (int i = 0; i < 3 && i < s.length; i++) {
            int j = 0;
            while (j < s[i].length() && Character.isDigit(s[i].charAt(j))) j++;
            try { r[i] = j == 0 ? 0 : Integer.parseInt(s[i].substring(0, j)); } catch (NumberFormatException e) { r[i] = 0; }
        }
        return r;
    }
}
