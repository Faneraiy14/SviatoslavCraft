package voxelin.loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Зрозуміла помилка замість стеку: що сталося людською мовою, хто винен (гра, мод, відеокарта, пам'ять, Java, диск, мережа)
 * і що робити. Звіт - crash-reports/crash-РРРР-ММ-ДД_ГГ.ХХ.СС.txt поруч із грою (пояснення зверху, подробиці для розробника знизу).
 * У консоль - рядки «[Voxelin] ПАДІННЯ: ...» / «[Voxelin] ЩО РОБИТИ: ...» / «[Voxelin] ЗВІТ: шлях»: їх читає FL Launcher і показує вікно.
 */
public final class CrashReport {
    private CrashReport() {}

    /** Пояснення: короткий заголовок, що робити, винний мод (або null). */
    public record Explanation(String title, String advice, String mod) {}

    public static Explanation explain(Throwable t, ModLoader mods) {
        String mod = culprit(t, mods);
        Throwable root = t;
        for (int i = 0; i < 20 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        String all = chainText(t);
        if (any(t, OutOfMemoryError.class))
            return new Explanation("Грі забракло пам'яті (RAM)" + (mod != null ? " - схоже, через мод «" + mod + "»" : ""),
                    "Дай грі більше пам'яті: у FL Launcher - налаштування збірки, «Пам'ять» (4 ГБ і більше); без лаунчера - java -Xmx4G ... Також допомагає менша дальність огляду.", mod);
        if (any(t, UnsupportedClassVersionError.class))
            return new Explanation(mod != null ? "Мод «" + mod + "» зібрано під новішу Java" : "Потрібна новіша Java",
                    mod != null ? "Онови Java до версії, під яку зібрано мод (або візьми версію мода під Java 21)." : "Встанови Java 21 (Temurin з adoptium.net) або запускай гру через FL Launcher - він сам підбере Java.", mod);
        if (mod != null && (any(t, NoSuchMethodError.class) || any(t, NoSuchFieldError.class) || any(t, NoClassDefFoundError.class) || any(t, AbstractMethodError.class) || any(t, IncompatibleClassChangeError.class)))
            return new Explanation("Мод «" + mod + "» не підходить до цієї версії гри",
                    "Він звертається до частини гри, якої тут немає або яка змінилась. Онови мод або прибери його з теки mods/.", mod);
        if (any(t, StackOverflowError.class))
            return new Explanation(mod != null ? "Мод «" + mod + "» зациклився" : "Гра зациклилась (переповнення стеку)",
                    mod != null ? "Прибери мод з теки mods/ і повідом автора - звіт йому допоможе." : "Надішли цей звіт розробнику Voxelin.", mod);
        if (any(t, UnsatisfiedLinkError.class))
            return new Explanation("Не завантажилась бібліотека LWJGL для цієї системи",
                    "Архів гри пошкоджений або не для цієї ОС/процесора (потрібні Windows чи Linux x64). Завантаж гру заново: flhub.luchezarikcentr.com/voxelin/download", null);
        if (all.contains("OpenGL") || all.contains("GLFW") || all.contains("glfw") || stackHas(t, "org.lwjgl.opengl") || stackHas(t, "org.lwjgl.glfw"))
            return new Explanation("Проблема з відеокартою або її драйвером",
                    "Онови драйвер відеокарти. На ноутбуці з двома відеокартами запусти гру на дискретній (у налаштуваннях графіки Windows). Потрібен OpenGL 3.0 або новіший.", null);
        if (all.contains("No space left") || all.contains("There is not enough space") || all.contains("Недостатньо місця"))
            return new Explanation("Закінчилось місце на диску", "Звільни місце на диску, де лежить гра, і запусти знову - світ збережений до останнього автозбереження.", null);
        if (any(t, AccessDeniedException.class) || all.contains("Access is denied") || all.contains("Permission denied") || all.contains("Відмовлено в доступі"))
            return new Explanation("Гра не може записати свої файли", "Не запускай гру прямо з архіву й не клади її в Program Files - розпакуй у свою теку (напр. Документи) і запусти звідти.", null);
        if (mod != null)
            return new Explanation("Помилка в моді «" + mod + "»", "Прибери мод з теки mods/ (або онови його) і запусти знову. Автору мода допоможе цей звіт.", mod);
        return new Explanation("Помилка в самій грі: " + root.getClass().getSimpleName() + (root.getMessage() != null ? " - " + cut(root.getMessage(), 120) : ""),
                "Запусти гру ще раз. Якщо повторюється - надішли цей звіт розробнику Voxelin (там є все потрібне, особистих даних немає).", null);
    }

    /** Перший мод у стеку (від місця помилки вглиб причин). */
    static String culprit(Throwable t, ModLoader mods) {
        if (mods == null) return null;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause())
            for (StackTraceElement e : c.getStackTrace()) { String m = mods.modOf(e); if (m != null) return m; }
        return null;
    }

    /**
     * Записати звіт і надрукувати пояснення. where - що робилось («гра», «сервер», «завантаження світу «X»»); context - додаткові
     * рядки «назва: значення» (версія OpenGL, гра по мережі з ким тощо). Повертає файл або null, якщо записати не вдалося.
     */
    public static File report(File dir, String where, Throwable t, ModLoader mods, Map<String, String> context, java.util.function.Consumer<String> out) {
        Explanation e = explain(t, mods);
        StringBuilder b = new StringBuilder();
        b.append("Voxelin - звіт про падіння\n");
        b.append("==========================\n\n");
        b.append("ЩО СТАЛОСЯ:  ").append(e.title()).append('\n');
        b.append("ЩО РОБИТИ:   ").append(e.advice()).append('\n');
        b.append("ДЕ:          ").append(where).append('\n');
        b.append("КОЛИ:        ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))).append("\n\n");
        b.append("-- Система --\n");
        b.append("Voxelin: ").append(version()).append('\n');
        b.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(", ").append(System.getProperty("java.home")).append(")\n");
        b.append("ОС: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(", ").append(System.getProperty("os.arch")).append('\n');
        Runtime rt = Runtime.getRuntime();
        b.append("Пам'ять: використано ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" МБ з ").append(rt.maxMemory() >> 20).append(" МБ дозволених, ядер ").append(rt.availableProcessors()).append('\n');
        if (context != null) for (Map.Entry<String, String> c : context.entrySet()) b.append(c.getKey()).append(": ").append(c.getValue()).append('\n');
        if (mods != null) {
            b.append("\n-- Моди --\n");
            for (ModLoader.ModInfo m : mods.mods()) b.append(m.id).append(" [").append(m.status).append("] ").append(m.file).append(m.message == null ? "" : " - " + m.message).append('\n');
        }
        b.append("\n-- Для розробника --\n");
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        b.append(sw);
        File f = null;
        try {
            Files.createDirectories(dir.toPath());
            f = new File(dir, "crash-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".txt");
            Files.writeString(f.toPath(), b.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException io) { f = null; }
        if (out != null) {
            out.accept("[Voxelin] ПАДІННЯ: " + e.title());
            out.accept("[Voxelin] ЩО РОБИТИ: " + e.advice());
            out.accept("[Voxelin] ЗВІТ: " + (f != null ? f.getAbsolutePath() : "(не вдалось записати - див. стек нижче)"));
            if (f == null) out.accept(sw.toString());
        }
        return f;
    }

    static String version() {
        try { return (String) Class.forName("voxelin.client.Voxelin").getField("VERSION").get(null); } catch (ReflectiveOperationException | LinkageError e) { return "?"; }
    }

    private static boolean any(Throwable t, Class<? extends Throwable> k) {
        for (int i = 0; t != null && i < 30; i++, t = t.getCause() == t ? null : t.getCause()) if (k.isInstance(t)) return true;
        return false;
    }

    private static boolean stackHas(Throwable t, String prefix) {
        for (int i = 0; t != null && i < 30; i++, t = t.getCause() == t ? null : t.getCause())
            for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().startsWith(prefix)) return true;
        return false;
    }

    private static String chainText(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; t != null && i < 30; i++, t = t.getCause() == t ? null : t.getCause()) b.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        return b.toString();
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) + "..." : s; }
}
