package voxelin.client.ui;

import voxelin.client.Input;
import voxelin.client.Window;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.opengl.GL11.*;

/**
 * Простий інтерфейс «негайного режиму» на віртуальному полотні 1280x720 (старий glBegin - сумісний контекст це дозволяє).
 * begin() готує кадр і зчитує клік; button() малює кнопку й повертає true на клік. Клік з'їдається першою кнопкою під курсором.
 */
public final class Gui {
    public static final int W = 1280, H = 720;
    private final Input in;
    private final Window win;
    public double mx, my;
    public boolean click;
    /** Що робити при натисканні кнопки (клієнт ставить звук клацання). */
    public Runnable onClick = () -> {};

    public Gui(Window win, Input in) { this.win = win; this.in = in; }

    public void begin() {
        mx = in.mouseUiX(); my = in.mouseUiY();
        click = in.clicked(GLFW_MOUSE_BUTTON_LEFT);
        glViewport(0, 0, win.fbWidth(), win.fbHeight());
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, W, H, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void end() {
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    public static void rect(float x, float y, float w, float h, float r, float g, float b, float a) { glColor4f(r, g, b, a); glRectf(x, y, x + w, y + h); }
    public static void rect(float x, float y, float w, float h, int rgb, float a) { rect(x, y, w, h, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, a); }

    public static void frame(float x, float y, float w, float h, float lw, float r, float g, float b) {
        glColor4f(r, g, b, 1f); glLineWidth(lw);
        glBegin(GL_LINE_LOOP); glVertex2f(x, y); glVertex2f(x + w, y); glVertex2f(x + w, y + h); glVertex2f(x, y + h); glEnd();
    }

    public static void text(String s, float x, float y, float scale, float r, float g, float b) { GLBitmapFont.draw(s, x, y, scale, r, g, b); }
    public static float textWidth(String s, float scale) { return s.length() * 6 * scale; }
    public static void textCentered(String s, float cx, float y, float scale, float r, float g, float b) { text(s, cx - textWidth(s, scale) / 2f, y, scale, r, g, b); }

    public boolean over(float x, float y, float w, float h) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }

    /** Кнопка з об'ємом: світлий верхній край, тінь знизу; наведення - світліша й золота рамка; selected - головна (золота). */
    public boolean button(String label, float x, float y, float w, float h, boolean selected, boolean enabled) {
        boolean hov = enabled && over(x, y, w, h);
        float r, g, b;
        if (!enabled) { r = 0.16f; g = 0.17f; b = 0.22f; }
        else if (selected) { r = hov ? 0.93f : 0.82f; g = hov ? 0.68f : 0.58f; b = hov ? 0.22f : 0.16f; }
        else { r = hov ? 0.36f : 0.24f; g = hov ? 0.39f : 0.26f; b = hov ? 0.6f : 0.42f; }
        rect(x - 2, y - 2, w + 4, h + 5, 0, 0, 0, 0.75f);                     // тінь і обвід
        rect(x, y, w, h, r, g, b, 0.97f);
        rect(x, y, w, 3, Math.min(1, r + 0.18f), Math.min(1, g + 0.18f), Math.min(1, b + 0.18f), 1f); // світлий верх
        rect(x, y + h - 4, w, 4, r * 0.6f, g * 0.6f, b * 0.6f, 1f);           // темний низ
        if (hov) frame(x - 2, y - 2, w + 4, h + 5, 2, 1f, 0.85f, 0.35f);
        float scale = Math.min(3.2f, (w - 24) / Math.max(1, label.length() * 6f)), dy = hov ? -1 : 0;
        float tc = !enabled ? 0.45f : 1f;
        textCentered(label, x + w / 2f + 1.5f, y + h / 2f - 3.5f * scale + 1.5f + dy, scale, 0, 0, 0); // тінь тексту
        textCentered(label, x + w / 2f, y + h / 2f - 3.5f * scale + dy, scale, tc, tc, selected && enabled ? 0.85f * tc : tc);
        if (hov && click) { click = false; onClick.run(); return true; }
        return false;
    }

    public boolean button(String label, float x, float y, float w, float h) { return button(label, x, y, w, h, false, true); }
}
