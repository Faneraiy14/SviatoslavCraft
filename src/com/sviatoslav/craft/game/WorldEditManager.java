package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

// Заготовка (Sviatoslav попросив - "поки що просто 1 кнопка 'назад', а
// все оформимо пізніше"): екран з'являється з кнопки EDIT WORLD на
// WorldSelectManager, поки не робить нічого крім повернення назад - сюди
// пізніше додадуться реальні налаштування обраного світу.
public final class WorldEditManager {
    private final Window window;
    private final InputHandler input;
    private final int width, height;
    private String worldName = "";

    private static final int BW = 300, BH = 60;

    public WorldEditManager(Window window, InputHandler input, int width, int height) {
        this.window = window; this.input = input; this.width = width; this.height = height;
    }

    public void open(String worldName) {
        this.worldName = worldName;
        window.setMouseGrabbed(false);
    }

    public String update() {
        double mx = input.getMouseX(), my = input.getMouseY();
        int bx = (width - BW) / 2, by = height - 150;
        if (input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            if (mx >= bx && mx <= bx + BW && my >= by && my <= by + BH) return "BACK";
        }
        return "NONE";
    }

    public void render() {
        glClearColor(0.12f, 0.12f, 0.16f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        String title = "EDIT WORLD";
        GLBitmapFont.draw(title, width / 2f - title.length() * 6 * 3 / 2f, 100, 3, 1, 1, 1);
        GLBitmapFont.draw(worldName, width / 2f - worldName.length() * 6 * 2 / 2f, 180, 2, 0.8f, 0.8f, 0.8f);

        int bx = (width - BW) / 2, by = height - 150;
        glColor3f(0.5f, 0.5f, 0.5f);
        glRectf(bx, by, bx + BW, by + BH);
        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(bx, by); glVertex2f(bx + BW, by);
        glVertex2f(bx + BW, by + BH); glVertex2f(bx, by + BH);
        glEnd();
        String label = "BACK";
        float labelWidth = label.length() * 6 * 3f;
        GLBitmapFont.draw(label, bx + (BW - labelWidth) / 2f, by + BH / 2f - 12, 3f, 1, 1, 1);

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }
}
