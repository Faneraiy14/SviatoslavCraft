package voxelin.client;

import voxelin.client.code.CodeBuffer;
import voxelin.client.code.Highlight;
import voxelin.client.ui.Gui;
import voxelin.loader.ScriptHost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Вікно коду (K): ліворуч скрипти з теки scripts/, посередині редактор main.nx із підсвіткою, унизу консоль скрипта. Ctrl+S зберігає й
 * одразу перезапускає скрипт (той самий hot reload, що й для зовнішнього редактора). Велика IDE в гру не вбудовується (рішення
 * 29.10.2026) - це «невелике вікно коду з консоллю», рівень 3 у DESIGN.md; серйозні моди - у NyxilumIDE.
 */
final class CodeScreen {
    static final float X = 250, Y = 64, W = 1014, H = 452, SCALE = 1.7f, CW = 6 * SCALE, LH = 17, GUTTER = 52;
    static final int ROWS = (int) ((H - 12) / LH), COLS = (int) ((W - GUTTER - 16) / CW);

    private final Voxelin v;
    private final ScriptHost host;
    private List<String> ids = List.of();
    private String current;
    private CodeBuffer buf = new CodeBuffer("");
    private int scrollRow, scrollCol;
    private String status = "";
    private long statusAt;
    /** Перше Esc/перемикання з незбереженим - лише попередження; друге - без збереження. */
    private String pendingLeave;
    /** Ввід назви нового скрипта (null - не вводимо). */
    private StringBuilder naming;
    private boolean dragging;
    boolean close;
    /** Відкрити редактор блоків для цього скрипта (PlayScreen перемикає екран). */
    String openBlocks;

    CodeScreen(Voxelin v, ScriptHost host, String open) {
        this.v = v; this.host = host;
        v.input.beginEdit();
        refreshIds();
        if (open != null && ids.contains(open)) load(open);
        else if (!ids.isEmpty()) load(ids.get(0));
    }

    private void refreshIds() { if (host != null) ids = host.ids(); }

    private void load(String id) {
        try { buf = new CodeBuffer(Files.readString(host.mainFile(id).toPath(), StandardCharsets.UTF_8)); }
        catch (IOException e) { buf = new CodeBuffer(""); say("Не вдалось прочитати " + id + "/main.nx: " + e.getMessage()); }
        current = id; scrollRow = scrollCol = 0; pendingLeave = null;
    }

    private void save() {
        if (current == null) return;
        try {
            host.save(current, buf.text());
            buf.markSaved();
            say("Збережено - скрипт «" + current + "» перезапускається");
        } catch (IOException e) { say("Не збережено: " + e.getMessage()); }
    }

    private void say(String s) { status = s; statusAt = System.currentTimeMillis(); }

    /** Перейти до іншого скрипта / закрити: з незбереженими змінами - спершу попередження. */
    private boolean leave(String what) {
        if (!buf.modified() || what.equals(pendingLeave)) { pendingLeave = null; return true; }
        pendingLeave = what;
        say("Є незбережені зміни: Ctrl+S - зберегти, або ще раз - вийти без збереження");
        return false;
    }

    void end() { v.input.endEdit(); }

    // ------------------------------------------------------------------ кадр

    void draw(Gui g) {
        Input in = v.input;
        Gui.rect(0, 0, Gui.W, Gui.H, 0.02f, 0.03f, 0.07f, 0.97f);
        Gui.text("КОД", 16, 18, 3.2f, 1f, 0.85f, 0.35f);
        if (host == null) {
            Gui.textCentered("Скрипти недоступні: гру запущено без теки модів.", Gui.W / 2f, 300, 2.4f, 1f, 0.7f, 0.6f);
            if (in.pressed(GLFW_KEY_ESCAPE) || g.button("ЗАКРИТИ", Gui.W / 2f - 140, 400, 280, 56)) { close = true; end(); }
            return;
        }
        if (naming != null) { nameDialog(g); return; }
        keys(in);
        if (close) return;
        sidebar(g);
        editor(g, in);
        console();
        // кнопки
        float by = 664;
        if (g.button("ЗБЕРЕГТИ (Ctrl+S)", X, by, 300, 44, true, current != null)) save();
        if (g.button("ЗУПИНИТИ", X + 316, by, 190, 44, false, current != null && host.info(current) != null && host.info(current).state == ScriptHost.State.RUNNING)) { host.stop(current); say("Скрипт «" + current + "» зупинено"); }
        if (g.button("БЛОКИ", X + 522, by, 160, 44, false, current != null) && leave("blocks")) { openBlocks = current; close = true; end(); }
        if (g.button("ЗАКРИТИ (Esc)", X + W - 240, by, 240, 44)) tryClose();
        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 6000) Gui.text(status, X + 160, 26, 1.6f, 0.95f, 0.9f, 0.6f);
    }

    private void tryClose() { if (leave("close")) { close = true; end(); } }

    private void keys(Input in) {
        String typed = in.pollTyped();
        boolean ctrl = in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL);
        if (current != null && !ctrl && !typed.isEmpty()) { buf.type(typed); follow(); }
        for (int[] e; (e = in.pollKey()) != null; ) {
            int key = e[0], mods = e[1];
            boolean c = (mods & GLFW_MOD_CONTROL) != 0, sh = (mods & GLFW_MOD_SHIFT) != 0;
            if (key == GLFW_KEY_ESCAPE) { tryClose(); if (close) return; continue; }
            if (c && key == GLFW_KEY_S) { save(); continue; }
            if (current == null) continue;
            if (buf.key(key, c, sh, c && key == GLFW_KEY_V ? in.clipboard() : null, in::setClipboard)) follow();
        }
        in.clearPressed(); // клавіші редактора не мають «протекти» в гру (E, J, M...)
    }

    /** Прокрутити, щоб курсор було видно. */
    private void follow() {
        if (buf.row() < scrollRow) scrollRow = buf.row();
        if (buf.row() >= scrollRow + ROWS) scrollRow = buf.row() - ROWS + 1;
        if (buf.col() < scrollCol) scrollCol = Math.max(0, buf.col() - 8);
        if (buf.col() >= scrollCol + COLS) scrollCol = buf.col() - COLS + 8;
    }

    private void sidebar(Gui g) {
        InventoryScreen.panel(16, Y, 220, H + 140);
        InventoryScreen.label("СКРИПТИ", 28, Y + 10);
        float y = Y + 34;
        for (String id : ids) {
            if (y > Y + H + 40) break;
            ScriptHost.Info info = host.info(id);
            boolean sel = id.equals(current);
            if (g.over(24, y, 204, 32) && g.click && !sel) { g.click = false; if (leave("open:" + id)) load(id); }
            Gui.rect(24, y, 204, 32, sel ? 0.22f : 0.08f, sel ? 0.24f : 0.09f, sel ? 0.36f : 0.14f, 1f);
            float[] dot = dot(info);
            Gui.rect(32, y + 12, 8, 8, dot[0], dot[1], dot[2], 1f);
            Gui.text(id + (sel && buf.modified() ? " *" : ""), 48, y + 9, 1.7f, 1f, 1f, 1f);
            y += 36;
        }
        if (ids.isEmpty()) Gui.text("(порожньо)", 32, y + 6, 1.6f, 0.6f, 0.62f, 0.72f);
        if (g.button("+ НОВИЙ", 24, Y + H + 92, 204, 40)) { naming = new StringBuilder(); in().pollTyped(); }
        Gui.text(host.hasNx() ? "" : "nx не знайдено!", 28, Y + H + 74, 1.5f, 1f, 0.5f, 0.4f);
    }

    private Input in() { return v.input; }

    private static float[] dot(ScriptHost.Info info) {
        if (info == null) return new float[]{0.45f, 0.45f, 0.5f};
        return switch (info.state) {
            case RUNNING -> new float[]{0.35f, 0.9f, 0.4f};
            case STOPPED -> new float[]{0.6f, 0.6f, 0.65f};
            default -> new float[]{1f, 0.35f, 0.3f};
        };
    }

    private void editor(Gui g, Input in) {
        InventoryScreen.panel(X, Y, W, H);
        if (current == null) {
            Gui.textCentered("Створи скрипт: «+ НОВИЙ» ліворуч.", X + W / 2f, Y + H / 2f - 10, 2.2f, 0.75f, 0.78f, 0.9f);
            return;
        }
        // миша: клік ставить курсор, тягнення виділяє, коліщатко гортає
        double sc = in.consumeScroll();
        if (sc != 0) scrollRow = Math.max(0, Math.min(Math.max(0, buf.lineCount() - ROWS / 2), scrollRow - (int) Math.signum(sc) * 3));
        boolean inside = g.over(X + GUTTER, Y + 6, W - GUTTER - 8, H - 12);
        if (inside && g.click) { g.click = false; dragging = true; buf.moveTo(rowAt(g.my), colAt(g.mx), in.down(GLFW_KEY_LEFT_SHIFT)); }
        else if (dragging && in.buttonDown(GLFW_MOUSE_BUTTON_LEFT)) buf.moveTo(rowAt(g.my), colAt(g.mx), true);
        else dragging = false;
        // виділення
        int[] s = buf.selection();
        for (int i = 0; i < ROWS && scrollRow + i < buf.lineCount(); i++) {
            int r = scrollRow + i;
            float ly = Y + 8 + i * LH;
            String num = String.valueOf(r + 1);
            Gui.text(num, X + GUTTER - 10 - num.length() * 6 * 1.4f, ly + 2, 1.4f, r == buf.row() ? 0.9f : 0.42f, r == buf.row() ? 0.85f : 0.45f, r == buf.row() ? 0.5f : 0.55f);
            String line = buf.line(r);
            if (s != null && r >= s[0] && r <= s[2]) {
                int a = r == s[0] ? s[1] : 0, b = r == s[2] ? s[3] : line.length() + 1;
                float x0 = colX(Math.max(a, scrollCol)), x1 = colX(Math.min(b, scrollCol + COLS));
                if (x1 > x0) Gui.rect(x0, ly, x1 - x0, LH, 0.25f, 0.35f, 0.6f, 0.55f);
            }
            for (Highlight.Span sp : Highlight.spans(line)) {
                int a = Math.max(sp.from(), scrollCol), b = Math.min(sp.to(), scrollCol + COLS);
                if (a >= b) continue;
                float[] c = Highlight.color(sp.kind());
                Gui.text(line.substring(a, b), colX(a), ly + 2, SCALE, c[0], c[1], c[2]);
            }
        }
        // курсор (блимає)
        if (buf.row() >= scrollRow && buf.row() < scrollRow + ROWS && (System.currentTimeMillis() / 530) % 2 == 0)
            Gui.rect(colX(buf.col()) - 1, Y + 8 + (buf.row() - scrollRow) * LH - 1, 2, LH, 1f, 0.9f, 0.4f, 1f);
        Gui.text("рядок " + (buf.row() + 1) + ", стовпець " + (buf.col() + 1) + "   " + buf.lineCount() + " рядків", X + W - 330, Y + H + 6, 1.4f, 0.55f, 0.58f, 0.7f);
        Gui.text(current + "/main.nx" + (buf.modified() ? "  (змінено)" : ""), X, Y + H + 6, 1.4f, 0.55f, 0.58f, 0.7f);
    }

    private float colX(int c) { return X + GUTTER + (c - scrollCol) * CW; }
    private int rowAt(double my) { return scrollRow + Math.max(0, (int) ((my - Y - 8) / LH)); }
    private int colAt(double mx) { return scrollCol + Math.max(0, (int) Math.round((mx - X - GUTTER) / CW)); }

    private void console() {
        float cy = Y + H + 30, ch = 120;
        Gui.rect(X, cy, W, ch, 0.01f, 0.01f, 0.03f, 0.95f);
        Gui.frame(X, cy, W, ch, 1, 0.25f, 0.27f, 0.36f);
        if (current == null) return;
        ScriptHost.Info info = host.info(current);
        float[] d = dot(info);
        String head = info == null ? "не запущено (збережи - запуститься)" : switch (info.state) {
            case RUNNING -> "працює"; case STOPPED -> "зупинено"; case CRASHED -> "впав"; case KILLED -> "зупинено грою"; case NO_NX -> "nx не знайдено";
        } + (info != null && info.message != null ? ": " + info.message : "");
        Gui.text("КОНСОЛЬ - " + head, X + 8, cy + 6, 1.5f, d[0], d[1], d[2]);
        List<String> c = host.console(current);
        int from = Math.max(0, c.size() - 5);
        float y = cy + 28;
        for (int i = from; i < c.size(); i++) {
            String l = c.get(i);
            int max = (int) ((W - 16) / (6 * 1.5f));
            if (l.length() > max) l = l.substring(0, max - 1) + "…";
            Gui.text(l, X + 8, y, 1.5f, 0.82f, 0.86f, 0.8f);
            y += 17;
        }
    }

    private void nameDialog(Gui g) {
        Input in = v.input;
        for (char c : in.pollTyped().toLowerCase(java.util.Locale.ROOT).toCharArray())
            if ((c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_') && naming.length() < 32) naming.append(c);
        boolean ok = false, cancel = false;
        for (int[] e; (e = in.pollKey()) != null; ) {
            if (e[0] == GLFW_KEY_BACKSPACE && naming.length() > 0) naming.setLength(naming.length() - 1);
            if (e[0] == GLFW_KEY_ENTER || e[0] == GLFW_KEY_KP_ENTER) ok = true;
            if (e[0] == GLFW_KEY_ESCAPE) cancel = true;
        }
        in.clearPressed();
        float w = 560, h = 230, x = (Gui.W - w) / 2f, y = 220;
        InventoryScreen.panel(x, y, w, h);
        Gui.text("НОВИЙ СКРИПТ", x + 20, y + 16, 2.4f, 1f, 0.85f, 0.35f);
        Gui.text("назва: малі латинські літери, цифри, _", x + 20, y + 52, 1.5f, 0.65f, 0.68f, 0.8f);
        Gui.rect(x + 20, y + 78, w - 40, 44, 0.04f, 0.05f, 0.08f, 1f);
        Gui.text(naming + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : ""), x + 32, y + 92, 2.2f, 1f, 1f, 1f);
        String id = naming.toString();
        boolean exists = ids.contains(id);
        if (exists) Gui.text("такий уже є", x + 20, y + 130, 1.5f, 1f, 0.5f, 0.4f);
        boolean good = ScriptHost.validId(id) && !exists;
        if (g.button("КОДОМ", x + 20, y + 160, 170, 50, true, good) || ok && good) {
            try { host.save(id, ScriptHost.template(id)); refreshIds(); load(id); say("Скрипт «" + id + "» створено й запущено: команда /" + id); }
            catch (IOException e) { say("Не створено: " + e.getMessage()); }
            naming = null;
        } else if (g.button("БЛОКАМИ", x + 200, y + 160, 170, 50, false, good)) { // порожня програма; збереже редактор блоків
            naming = null; openBlocks = id; close = true; end();
        } else if (g.button("СКАСУВАТИ", x + w - 170, y + 160, 150, 50) || cancel) naming = null;
    }

    /** Для тестів і знімків. */
    CodeBuffer buffer() { return buf; }
}
