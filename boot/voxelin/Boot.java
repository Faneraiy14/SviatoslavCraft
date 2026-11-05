package voxelin;

/**
 * Стартовий клас, зібраний під Java 8 (build.sh: --release 8): на старій Java гра не падає з незрозумілим
 * UnsupportedClassVersionError, а пояснює, що потрібна Java 21 і де її взяти (у консоль і вікном, якщо є екран).
 * На правильній Java просто запускає voxelin.client.Voxelin (а з --server - сервер без вікна).
 */
public final class Boot {
    static final int NEED = 21;

    public static void main(String[] args) throws Throwable {
        int have = major(System.getProperty("java.specification.version"));
        if (have < NEED) {
            String msg = "Voxelin потребує Java " + NEED + " або новішу, а запущено Java " + System.getProperty("java.version")
                    + " (" + System.getProperty("java.home") + ").\n\n"
                    + "Що зробити:\n"
                    + " - встанови Java " + NEED + " (безкоштовно: https://adoptium.net, Temurin " + NEED + "), або\n"
                    + " - запускай гру через FL Launcher - він сам завантажить потрібну Java.\n\n"
                    + "Якщо Java 21 уже встановлена, а запускається стара - прибери стару Java або допиши повний шлях до нової java в run.bat / run.sh.";
            System.out.println("[Voxelin] ПАДІННЯ: потрібна Java " + NEED + ", а запущено " + System.getProperty("java.version"));
            System.out.println("[Voxelin] ЩО РОБИТИ: встанови Java " + NEED + " (adoptium.net) або запускай через FL Launcher");
            System.out.println(msg);
            boolean server = false;
            for (String a : args) if ("--server".equals(a)) server = true;
            if (!server && !java.awt.GraphicsEnvironment.isHeadless()) {
                try { javax.swing.JOptionPane.showMessageDialog(null, msg, "Voxelin: потрібна Java " + NEED, javax.swing.JOptionPane.ERROR_MESSAGE); }
                catch (Throwable ignored) { /* без екрана - досить консолі */ }
            }
            System.exit(3);
            return;
        }
        try {
            Class.forName("voxelin.client.Voxelin").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }

    /** "1.8" -> 8, "17" -> 17, "21.0.2" -> 21. */
    static int major(String v) {
        if (v == null) return 0;
        if (v.startsWith("1.")) v = v.substring(2);
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
        try { return Integer.parseInt(v.substring(0, i)); } catch (NumberFormatException e) { return 0; }
    }
}
