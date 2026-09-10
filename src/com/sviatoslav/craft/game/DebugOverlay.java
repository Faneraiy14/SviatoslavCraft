package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import static org.lwjgl.opengl.GL11.*;

public class DebugOverlay {
    private boolean visible = false;
    public void toggle() { visible = !visible; }
    public boolean isVisible() { return visible; }

    // РЕАЛЬНИЙ ФІКС (баг у чернетці DeepSeek): параметри x/y/z/yaw/pitch/
    // fps/blockCount приймались, але НІКОЛИ не використовувались - метод
    // малював лише суцільні білі смуги замість чисел. Тепер текст реальний
    // (GLBitmapFont), F3 справді щось показує.
    public void render(float x, float y, float z, float yaw, float pitch, int fps, int blockCount, String targetInfo) {
        if (!visible) return;
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0,1280,720,0,-1,1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        // Той самий фікс, що й у MenuManager - без цього фон і текст F3
        // ставали б невидимі так само (GL_CULL_FACE відкидав би GL_QUADS
        // у Y-перевернутій 2D-проєкції).
        glDisable(GL_CULL_FACE);

        glColor4f(0,0,0,0.7f); glRectf(10,10,520,175);

        GLBitmapFont.draw("XYZ: " + fmt(x) + " " + fmt(y) + " " + fmt(z), 20, 25, 2, 1, 1, 1);
        GLBitmapFont.draw("YAW: " + fmt(yaw) + " PITCH: " + fmt(pitch), 20, 55, 2, 1, 1, 1);
        GLBitmapFont.draw("FPS: " + fps, 20, 85, 2, 0.4f, 1f, 0.4f);
        GLBitmapFont.draw("BLOCKS: " + blockCount, 20, 115, 2, 1, 1, 1);
        // Точні координати блока під прицілом - щоб перевіряти "дотягується
        // раптом до чогось не того" по РЕАЛЬНИХ числах, а не на око під кутом.
        if (targetInfo != null) GLBitmapFont.draw("TARGET: " + targetInfo, 20, 145, 2, 1f, 0.85f, 0.2f);

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    private static String fmt(float v) {
        return String.valueOf((int)(v * 10) / 10.0f);
    }
}
