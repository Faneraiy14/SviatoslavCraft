import voxelin.loader.CrashReport;
import voxelin.loader.Game;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Зрозумілі падіння: причина людською мовою, винний мод зі стеку, звіт у crash-reports/ і рядки для FL Launcher. */
public class VoxelinCrashTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-crash").toFile();
        Game g = new Game(new File(root, "saves"), null);

        check(CrashReport.explain(new OutOfMemoryError("Java heap space"), g.mods).title().contains("пам'яті"), "нестача пам'яті");
        check(CrashReport.explain(new RuntimeException("обгортка", new OutOfMemoryError()), g.mods).advice().contains("Пам'ять"), "пам'ять - і всередині причини, з порадою про лаунчер");
        check(CrashReport.explain(new IllegalStateException("Потрібен OpenGL 3.0 або новіший"), g.mods).title().contains("відеокарт"), "відеокарта");
        check(CrashReport.explain(new UnsatisfiedLinkError("no lwjgl"), g.mods).title().contains("LWJGL"), "нативна бібліотека");
        check(CrashReport.explain(new java.io.IOException("No space left on device"), g.mods).title().contains("місце"), "диск");
        check(CrashReport.explain(new java.nio.file.AccessDeniedException("saves/x"), g.mods).advice().contains("архіву"), "немає доступу до файлів");
        CrashReport.Explanation plain = CrashReport.explain(new NullPointerException("x is null"), g.mods);
        check(plain.mod() == null && plain.title().contains("самій грі") && plain.title().contains("NullPointerException"), "помилка гри: " + plain.title());

        // винний мод - за іменем завантажувача класів у стеку (ModLoader називає його «mod:<id>»)
        RuntimeException fromMod = new RuntimeException("бум");
        fromMod.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("mod:ruby", null, null, "ruby.RubyMod", "onTick", "RubyMod.java", 42),
                new StackTraceElement("app", null, null, "voxelin.core.World", "tick", "World.java", 10)});
        CrashReport.Explanation m = CrashReport.explain(fromMod, g.mods);
        check("ruby".equals(m.mod()) && m.title().contains("«ruby»") && m.advice().contains("mods/"), "мод у стеку - винен мод: " + m.title());
        NoSuchMethodError nsme = new NoSuchMethodError("voxelin.api.Old.gone()");
        nsme.setStackTrace(fromMod.getStackTrace());
        check(CrashReport.explain(nsme, g.mods).title().contains("не підходить до цієї версії гри"), "мод під іншу версію гри");
        StackOverflowError so = new StackOverflowError();
        so.setStackTrace(fromMod.getStackTrace());
        check(CrashReport.explain(so, g.mods).title().contains("зациклився"), "мод зациклився");

        // звіт: пояснення зверху, система, моди, стек; рядки для лаунчера
        List<String> out = new ArrayList<>();
        File f = CrashReport.report(new File(root, "crash-reports"), "гра у світі «Тест»", new OutOfMemoryError("Java heap space"), g.mods, Map.of("OpenGL", "4.6 / тест"), out::add);
        check(f != null && f.isFile() && f.getName().startsWith("crash-") && f.getName().endsWith(".txt"), "файл звіту");
        String text = Files.readString(f.toPath());
        check(text.startsWith("Voxelin - звіт про падіння") && text.contains("ЩО СТАЛОСЯ:  Грі забракло пам'яті") && text.contains("ЩО РОБИТИ:") && text.contains("ДЕ:          гра у світі «Тест»"), "пояснення зверху звіту");
        check(text.contains("Java: ") && text.contains("ОС: ") && text.contains("OpenGL: 4.6 / тест") && text.contains("voxelin [OK]") && text.contains("java.lang.OutOfMemoryError: Java heap space"), "система, моди й стек у звіті");
        check(out.size() == 3 && out.get(0).startsWith("[Voxelin] ПАДІННЯ: ") && out.get(1).startsWith("[Voxelin] ЩО РОБИТИ: ") && out.get(2).equals("[Voxelin] ЗВІТ: " + f.getAbsolutePath()), "рядки для FL Launcher: " + out);

        // не вдалось записати - стек усе одно в консолі
        File blocker = new File(root, "file-not-dir");
        Files.writeString(blocker.toPath(), "x");
        out.clear();
        check(CrashReport.report(blocker, "тест", new RuntimeException("ой"), null, null, out::add) == null && out.size() == 4 && out.get(3).contains("RuntimeException: ой"), "без звіту - стек у консоль");
        System.out.println("VoxelinCrashTest: " + n + " перевірок OK");
        System.exit(0);
    }
}
