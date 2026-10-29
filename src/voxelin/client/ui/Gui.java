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

    public boolean button(String label, float x, float y, float w, float h, boolean selected, boolean enabled) {
        boolean hov = enabled && over(x, y, w, h);
        float k = !enabled ? 0.25f : (hov || selected) ? 1f : 0.45f;
        rect(x, y, w, h, k, (enabled ? (k > 0.9f ? 0.85f : 0.45f) : 0.25f), enabled ? (k > 0.9f ? 0.2f : 0.65f) : 0.28f, 0.95f);
        frame(x, y, w, h, 2, 1, 1, 1);
        float scale = Math.min(3.2f, (w - 24) / Math.max(1, label.length() * 6f));
        textCentered(label, x + w / 2f, y + h / 2f - 3.5f * scale, scale, enabled ? 1 : 0.6f, enabled ? 1 : 0.6f, enabled ? 1 : 0.6f);
        if (hov && click) { click = false; onClick.run(); return true; }
        return false;
    }

    public boolean button(String label, float x, float y, float w, float h) { return button(label, x, y, w, h, false, true); }
}
