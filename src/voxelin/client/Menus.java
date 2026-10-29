package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.WorldInfo;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/** Меню: головне, список світів, створення й перейменування світу. Малює й обробляє ввід у кадрі (Gui - негайний режим). */
final class Menus {
    enum Screen { MAIN, WORLDS, CREATE, RENAME, SETTINGS, SOUND, SKIN }

    private final Voxelin v;
    Screen screen = Screen.MAIN;
    private List<String> worlds = List.of();
    private int selected, scroll, hiddenOld;
    private String status = "";
    private boolean flat;
    private String renameFrom;
    private SkinScreen skin;

    Menus(Voxelin v) { this.v = v; }

    void show(Screen s) {
        screen = s;
        status = "";
        v.input.clearPressed();
        v.input.endText();
        if (s == Screen.WORLDS) refresh();
        if (s == Screen.SKIN) skin = new SkinScreen(v);
        if (s == Screen.CREATE) { flat = false; v.input.beginText(autoName(), false); }
        if (s == Screen.RENAME) { renameFrom = selectedName(); v.input.beginText(renameFrom == null ? "" : renameFrom, false); }
    }

    void refresh() {
        worlds = v.game.listWorlds();
        selected = Math.max(0, Math.min(selected, worlds.size() - 1));
        // старі світи v0.0.3 лежать у saves/ без voxelin.world - нова гра їх не показує й не чіпає
        int old = 0;
        File[] ds = v.game.savesDir.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) if (!WorldInfo.exists(d)) old++;
        hiddenOld = old;
    }

    void select(String name) { refresh(); int i = worlds.indexOf(name); if (i >= 0) selected = i; }

    private String selectedName() { return worlds.isEmpty() || selected >= worlds.size() ? null : worlds.get(selected); }

    private String autoName() {
        for (int n = 1; ; n++) { String s = "Світ " + n; if (!new File(v.game.savesDir, s).exists()) return s; }
    }

    /** Налаштування чи звук поверх гри (з меню паузи). false - гравець натиснув «Готово»/Esc, треба повернутись у паузу. */
    boolean overlay(Gui g) {
        Gui.rect(0, 0, Gui.W, Gui.H, 0.04f, 0.05f, 0.1f, 0.88f);
        switch (screen) { case SETTINGS -> settings(g); case SOUND -> sound(g); default -> { } }
        return screen == Screen.SETTINGS || screen == Screen.SOUND;
    }

    void frame() {
        Gui g = v.gui;
        scene.draw((System.nanoTime() - started) / 1e9);
        if (screen != Screen.MAIN) Gui.rect(0, 0, Gui.W, Gui.H, 0.03f, 0.04f, 0.1f, 0.72f); // на інших екранах фон приглушений - текст читається
        switch (screen) {
            case MAIN -> main(g);
            case WORLDS -> worlds(g);
            case CREATE -> create(g);
            case RENAME -> rename(g);
            case SETTINGS -> settings(g);
            case SOUND -> sound(g);
            case SKIN -> { if (!skin.draw(g)) show(Screen.MAIN); }
        }
        Gui.text("Voxelin " + Voxelin.VERSION, 12, Gui.H - 22, 1.8f, 0.55f, 0.6f, 0.7f);
    }

    /** Об'ємний логотип: кілька темних шарів зі зсувом (бік «блока»), чорний контур, золотий верх зі світлішою верхньою половиною. */
    private static void logo(String s, float y, float scale) {
        float w = Gui.textWidth(s, scale), x = (Gui.W - w) / 2f;
        for (int d = 7; d >= 1; d--) Gui.text(s, x + d * 1.2f, y + d * 1.5f, scale, 0.32f - d * 0.02f, 0.18f - d * 0.01f, 0.05f);
        for (int ox = -2; ox <= 2; ox += 2) for (int oy = -2; oy <= 2; oy += 2) if (ox != 0 || oy != 0) Gui.text(s, x + ox, y + oy, scale, 0.08f, 0.04f, 0.02f);
        Gui.text(s, x, y, scale, 1f, 0.78f, 0.22f);
    }

    private static final String[] SPLASH = {"Шов тримається!", "Тепер зі звуком!", "Блоки на 50% більші!", "Квітка Порогу не росте вдома", "Не стій під драконом",
            "Зроблено в Україні!", "Херобрин? Ніколи не чули", "100% синтезовані звуки", "Крила не ламаються!", "Шукай нитки між островами", "Слава Україні!"};
    private final String splash = SPLASH[new java.util.Random().nextInt(SPLASH.length)];
    private final voxelin.client.ui.MenuScene scene = new voxelin.client.ui.MenuScene(0x5EA4L);
    private final long started = System.nanoTime();
    private String lastWorld;
    private long lastWorldCheck;

    /** Світ, у який грали останнім (за часом зміни файла світу) - для кнопки «Продовжити». */
    private String lastWorld() {
        long now = System.currentTimeMillis();
        if (now - lastWorldCheck < 2000) return lastWorld;
        lastWorldCheck = now;
        String best = null; long t = -1;
        for (String n : v.game.listWorlds()) { long m = new File(new File(v.game.savesDir, n), WorldInfo.FILE).lastModified(); if (m > t) { t = m; best = n; } }
        return lastWorld = best;
    }

    private void main(Gui g) {
        double sec = (System.nanoTime() - started) / 1e9;
        logo("VOXELIN", 92, 13f);
        // сплеш: жовтий рядок під кутом, що «дихає» (як у Minecraft)
        float k = 1f + 0.06f * (float) Math.abs(Math.sin(sec * 3.5));
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(Gui.W / 2f + 250, 178, 0);
        org.lwjgl.opengl.GL11.glRotatef(-12, 0, 0, 1);
        org.lwjgl.opengl.GL11.glScalef(k, k, 1);
        Gui.text(splash, -Gui.textWidth(splash, 2.4f) / 2f + 2, 2, 2.4f, 0.25f, 0.22f, 0);
        Gui.text(splash, -Gui.textWidth(splash, 2.4f) / 2f, 0, 2.4f, 1f, 1f, 0.2f);
        org.lwjgl.opengl.GL11.glPopMatrix();
        float x = 440, w = 400, y = 270;
        String last = lastWorld();
        if (last != null) {
            if (g.button("ПРОДОВЖИТИ", x, y, w, 64, true, true)) { v.beginLoading(last); return; }
            Gui.textCentered("світ: " + last, Gui.W / 2f, y + 72, 1.8f, 0.8f, 0.82f, 0.95f);
            y += 96;
        }
        if (g.button("ОДИНОЧНА ГРА", x, y, w, 64) || (v.input.pressed(GLFW_KEY_ENTER) && last == null)) show(Screen.WORLDS);
        if (v.input.pressed(GLFW_KEY_ENTER) && last != null) { v.beginLoading(last); return; }
        if (g.button("НАЛАШТУВАННЯ", x, y + 80, 150, 56)) { show(Screen.SETTINGS); return; }
        if (g.button("СКІН", x + 160, y + 80, 115, 56)) { show(Screen.SKIN); return; }
        if (g.button("ВИХІД", x + 285, y + 80, 115, 56) || v.input.pressed(GLFW_KEY_ESCAPE)) v.quit();
        Gui.text("Enter - " + (last != null ? "продовжити" : "грати") + "   Esc - вихід", Gui.W - 330, Gui.H - 22, 1.6f, 0.55f, 0.6f, 0.75f);
    }

    private void settings(Gui g) {
        Settings st = v.settings;
        Gui.textCentered("НАЛАШТУВАННЯ", Gui.W / 2f, 36, 5f, 1f, 0.85f, 0.3f);
        final float x = 250, w = 780, h = 50, gap = 10, y0 = 110;
        String autoNote = st.textureSize == 0 ? "авто (" + Settings.autoTextureSize(v.window.glRenderer()) + ")" : st.textureSize + "x" + st.textureSize;
        int[] row = {0};
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Розмір текстур", autoNote)) { st.textureSize = cycle(Settings.TEXTURE_SIZES, st.textureSize, stepDir); }
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Дальність огляду", st.renderDistance + " чанків")) st.renderDistance += 2 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Чутливість миші", String.format(java.util.Locale.ROOT, "%.2f", st.mouseSensitivity))) st.mouseSensitivity += 0.02f * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Поле зору", st.fov + "°")) st.fov += 5 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Згладжування (перезапуск)", st.msaa == 0 ? "вимкнено" : st.msaa + "x")) st.msaa = cycle(Settings.MSAA_VALUES, st.msaa, stepDir);
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Верт. синхр. (перезапуск)", st.vsync ? "увімкнено" : "вимкнено")) st.vsync = !st.vsync;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Контури блоків", st.outlines ? "увімкнено" : "вимкнено")) st.outlines = !st.outlines;
        st.clampAll();
        Gui.textCentered("Розмір текстур застосується при наступному відкритті світу. Власні PNG - тека textures/.", Gui.W / 2f, y0 + row[0] * (h + gap) + 6, 1.9f, 0.7f, 0.75f, 0.9f);
        if (g.button("ЗВУК", 330, 640, 300, 56)) { v.saveSettings(); show(Screen.SOUND); return; }
        if (g.button("ГОТОВО", 650, 640, 300, 56) || v.input.pressed(GLFW_KEY_ESCAPE)) { v.saveSettings(); show(Screen.MAIN); }
    }

    private void sound(Gui g) {
        Settings st = v.settings;
        Gui.textCentered("ЗВУК", Gui.W / 2f, 36, 5f, 1f, 0.85f, 0.3f);
        final float x = 250, w = 780, h = 50, gap = 10, y0 = 130;
        int[] row = {0};
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Загальна гучність", st.volMaster + "%")) st.volMaster += 10 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Блоки", st.volBlocks + "%")) st.volBlocks += 10 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Істоти", st.volMobs + "%")) st.volMobs += 10 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Оточення", st.volAmbient + "%")) st.volAmbient += 10 * stepDir;
        if (stepper(g, x, y0 + row[0]++ * (h + gap), w, h, "Інтерфейс", st.volUi + "%")) st.volUi += 10 * stepDir;
        st.clampAll();
        v.applySettings(); // гучність - одразу, щоб чути зміну
        Gui.textCentered(v.audioStatus(), Gui.W / 2f, y0 + row[0] * (h + gap) + 6, 1.9f, 0.7f, 0.75f, 0.9f);
        Gui.textCentered("Власні звуки (.wav) - тека sounds/, назва як у звуку гри (block.stone.break.wav).", Gui.W / 2f, y0 + row[0] * (h + gap) + 34, 1.9f, 0.7f, 0.75f, 0.9f);
        if (g.button("НАЗАД", 445, 640, 390, 56) || v.input.pressed(GLFW_KEY_ESCAPE)) { v.saveSettings(); show(Screen.SETTINGS); }
    }

    private int stepDir = 1;

    /** Рядок налаштування: назва ліворуч, «‹ значення ›» праворуч; true - натиснули (напрям у stepDir: -1 ліворуч, +1 праворуч). */
    private boolean stepper(Gui g, float x, float y, float w, float h, String label, String value) {
        Gui.rect(x, y, w, h, 0.14f, 0.16f, 0.28f, 0.95f);
        Gui.text(label, x + 14, y + 16, 2.5f, 1, 1, 1);
        float bw = 52, vx = x + w - 300;
        boolean left = g.button("<", vx, y + 4, bw, h - 8), right = g.button(">", x + w - bw - 4, y + 4, bw, h - 8);
        Gui.textCentered(value, ((vx + bw) + (x + w - bw - 4)) / 2f, y + 16, 2.5f, 1f, 0.9f, 0.5f);
        if (left) { stepDir = -1; return true; }
        if (right) { stepDir = 1; return true; }
        return false;
    }

    private static int cycle(int[] values, int cur, int dir) {
        int i = 0;
        for (int k = 0; k < values.length; k++) if (values[k] == cur) i = k;
        return values[Math.floorMod(i + dir, values.length)];
    }

    private void worlds(Gui g) {
        Gui.textCentered("ОБЕРИ СВІТ", Gui.W / 2f, 40, 5f, 1f, 0.85f, 0.3f);
        final int x = 340, y0 = 120, w = 600, rowH = 52, rows = 7;
        double sc = v.input.consumeScroll();
        if (sc != 0) scroll = Math.max(0, Math.min(Math.max(0, worlds.size() - rows), scroll - (int) Math.signum(sc)));
        if (worlds.isEmpty()) Gui.textCentered("Світів ще немає. Натисни НОВИЙ СВІТ.", Gui.W / 2f, 260, 2.6f, 0.8f, 0.8f, 0.85f);
        for (int i = 0; i < rows && scroll + i < worlds.size(); i++) {
            int idx = scroll + i;
            float y = y0 + i * (rowH + 4);
            boolean sel = idx == selected, hov = g.over(x, y, w, rowH);
            Gui.rect(x, y, w, rowH, sel ? 0.35f : (hov ? 0.22f : 0.14f), sel ? 0.3f : (hov ? 0.22f : 0.16f), sel ? 0.1f : 0.28f, 0.95f);
            Gui.frame(x, y, w, rowH, 2, sel ? 1f : 0.5f, sel ? 0.85f : 0.5f, sel ? 0.2f : 0.6f);
            Gui.text(worlds.get(idx), x + 16, y + 14, 3.2f, 1, 1, 1);
            if (hov && g.click) { if (selected == idx && System.currentTimeMillis() - lastClick < 350) { play(); return; } selected = idx; lastClick = System.currentTimeMillis(); g.click = false; }
        }
        boolean any = selectedName() != null;
        if (g.button("ГРАТИ", 245, 560, 190, 56, false, any) || (any && v.input.pressed(GLFW_KEY_ENTER))) { play(); return; }
        if (g.button("НОВИЙ СВІТ", 445, 560, 190, 56)) { show(Screen.CREATE); return; }
        if (g.button("ПЕРЕЙМЕНУВАТИ", 645, 560, 190, 56, false, any)) { show(Screen.RENAME); return; }
        if (g.button("НАЗАД", 845, 560, 190, 56) || v.input.pressed(GLFW_KEY_ESCAPE)) { show(Screen.MAIN); return; }
        if (v.input.pressed(GLFW_KEY_DOWN)) selected = Math.min(worlds.size() - 1, selected + 1);
        if (v.input.pressed(GLFW_KEY_UP)) selected = Math.max(0, selected - 1);
        if (selected < scroll) scroll = selected;
        if (selected >= scroll + rows) scroll = selected - rows + 1;
        if (hiddenOld > 0) Gui.textCentered("Старі світи (v0.0.3) не показано: у нового Voxelin інший формат. Файли не видалено.", Gui.W / 2f, 640, 1.9f, 0.7f, 0.7f, 0.5f);
        if (!status.isEmpty()) Gui.textCentered(status, Gui.W / 2f, 670, 2.2f, 1f, 0.5f, 0.4f);
    }

    private long lastClick;

    private void play() { String n = selectedName(); if (n != null) v.beginLoading(n); }

    private void nameField(Gui g, String hint) {
        v.input.updateText();
        Gui.textCentered(hint, Gui.W / 2f, 200, 3f, 0.8f, 0.8f, 0.85f);
        Gui.rect(390, 250, 500, 60, 0.05f, 0.05f, 0.1f, 1f);
        Gui.frame(390, 250, 500, 60, 2, 1, 1, 1);
        String t = v.input.text() + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        Gui.text(t, 406, 266, 3.6f, 1, 1, 1);
    }

    private void create(Gui g) {
        Gui.textCentered("НОВИЙ СВІТ", Gui.W / 2f, 60, 5f, 1f, 0.85f, 0.3f);
        nameField(g, "Назва світу");
        if (g.button("ЗВИЧАЙНИЙ", 390, 340, 245, 56, !flat, true)) flat = false;
        if (g.button("ПЛОСКИЙ", 645, 340, 245, 56, flat, true)) flat = true;
        boolean ok = g.button("СТВОРИТИ", 390, 440, 245, 60) || v.input.pressed(GLFW_KEY_ENTER);
        if (g.button("НАЗАД", 645, 440, 245, 60) || v.input.pressed(GLFW_KEY_ESCAPE)) { show(Screen.WORLDS); return; }
        if (ok) {
            String name = v.input.text().trim();
            if (name.isEmpty()) name = autoName();
            if (!WorldInfo.isValidName(name)) { status = "Недопустима назва"; }
            else if (new File(v.game.savesDir, name).exists()) { status = "Світ із такою назвою вже є"; }
            else {
                try {
                    v.game.createWorld(name, System.nanoTime() ^ (System.currentTimeMillis() << 20), flat ? WorldInfo.Type.FLAT : WorldInfo.Type.NORMAL);
                    show(Screen.WORLDS); select(name); return;
                } catch (IOException e) { status = "Не вдалося створити: " + e.getMessage(); }
            }
        }
        if (!status.isEmpty()) Gui.textCentered(status, Gui.W / 2f, 540, 2.4f, 1f, 0.5f, 0.4f);
    }

    private void rename(Gui g) {
        Gui.textCentered("ПЕРЕЙМЕНУВАННЯ", Gui.W / 2f, 60, 5f, 1f, 0.85f, 0.3f);
        nameField(g, "Нова назва для: " + renameFrom);
        boolean ok = g.button("ЗБЕРЕГТИ", 390, 360, 245, 60) || v.input.pressed(GLFW_KEY_ENTER);
        if (g.button("НАЗАД", 645, 360, 245, 60) || v.input.pressed(GLFW_KEY_ESCAPE)) { show(Screen.WORLDS); return; }
        if (ok && renameFrom != null) {
            String name = v.input.text().trim();
            File from = v.game.worldDir(renameFrom), to = new File(v.game.savesDir, name);
            if (!WorldInfo.isValidName(name)) status = "Недопустима назва";
            else if (name.equals(renameFrom)) { show(Screen.WORLDS); return; }
            else if (to.exists()) status = "Світ із такою назвою вже є";
            else if (!from.renameTo(to)) status = "Не вдалося перейменувати";
            else {
                try { WorldInfo i = WorldInfo.load(to); i.name = name; i.save(to); } catch (IOException e) { /* назва лише в теці - не критично */ }
                show(Screen.WORLDS); select(name); return;
            }
        }
        if (!status.isEmpty()) Gui.textCentered(status, Gui.W / 2f, 460, 2.4f, 1f, 0.5f, 0.4f);
    }
}
