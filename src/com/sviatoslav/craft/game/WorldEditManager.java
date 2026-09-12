package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import java.io.File;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

// РЕАЛЬНА фіча (Sviatoslav попросив - "додати туди зміну назви світу, бо
// що ти по іншому будеш редагувати"): поле вводу з поточною назвою +
// кнопка RENAME (перейменовує саму ПАПКУ на диску - saves/<стара назва>
// -> saves/<нова назва>) + BACK. Решта налаштувань (крім назви) сюди
// додасться пізніше - Sviatoslav явно попросив лишити екран заготовкою
// поза перейменуванням.
public final class WorldEditManager {
    private final Window window;
    private final InputHandler input;
    private final int width, height;
    private String worldName = "";

    private static final int BW = 300, BH = 60;
    private static final int FIELD_W = 500, FIELD_H = 60, FIELD_Y = 220;
    private static final int RENAME_Y = 320, BACK_Y = 460;

    public WorldEditManager(Window window, InputHandler input, int width, int height) {
        this.window = window; this.input = input; this.width = width; this.height = height;
    }

    public void open(String worldName) {
        this.worldName = worldName;
        status = "";
        window.setMouseGrabbed(false);
        input.setTextInput(worldName);
        input.setTextInputActive(true);
    }

    // SviatoslavCraft перепитує це на виході (BACK), щоб оновити й
    // перевибрати правильний (можливо, вже перейменований) світ у
    // списку WorldSelectManager.
    public String getWorldName() { return worldName; }

    public String update() {
        input.updateTextInputBackspace();
        // Escape - "назад" (Sviatoslav попросив - "у любому випадку").
        if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) { input.setTextInputActive(false); return "BACK"; }

        double mx = input.getMouseX(), my = input.getMouseY();
        int bx = (width - BW) / 2;
        if (!input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) return "NONE";

        // РЕАЛЬНА фіча (Sviatoslav попросив - "коли нажимаєш ренейм то
        // має перекинути назад до списку світів"): УСПІШНЕ перейменування
        // одразу повертає "BACK" (як і сама кнопка BACK) - той самий
        // принцип, що й CREATE WORLD, де успіх теж одразу веде назад на
        // список. НЕуспішні спроби (порожньо/без змін/зайнято) лишають
        // на екрані - інакше не було б шансу побачити status і виправити.
        if (isOver(bx, RENAME_Y, mx, my)) {
            if (rename()) { input.setTextInputActive(false); return "BACK"; }
            return "NONE";
        }
        if (isOver(bx, BACK_Y, mx, my)) { input.setTextInputActive(false); return "BACK"; }
        return "NONE";
    }

    private boolean isOver(int x, int y, double mx, double my) {
        return mx >= x && mx <= x + BW && my >= y && my <= y + BH;
    }

    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "кнопка rename не
    // працює"): renameTo() насправді СПРАЦЬОВУВАВ коректно (перевірено
    // окремо), але екран після кліку виглядав ТОЧНІСІНЬКО так само в
    // обох випадках - успіху й тихої відмови (порожнє ім'я/без змін/
    // зайняте) - нічого на екрані не підказувало, що взагалі сталось.
    // Тепер status показує результат (для неуспіху) і повернення "BACK"
    // (для успіху) - разом досить, щоб завжди було зрозуміло, що сталось.
    private String status = "";

    private boolean rename() {
        String newName = input.getTextInput().trim();
        if (newName.isEmpty()) { status = "TYPE A NAME FIRST"; return false; }
        if (newName.equals(worldName)) { status = "ALREADY THIS NAME"; return false; }
        File oldDir = new File("saves/" + worldName);
        File newDir = new File("saves/" + newName);
        if (newDir.exists()) { status = "NAME TAKEN"; return false; }
        if (oldDir.renameTo(newDir)) { worldName = newName; status = "RENAMED"; return true; }
        status = "RENAME FAILED";
        return false;
    }

    public void render() {
        glClearColor(0.12f, 0.12f, 0.16f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        String title = "EDIT WORLD";
        GLBitmapFont.draw(title, width / 2f - title.length() * 6 * 3 / 2f, 100, 3, 1, 1, 1);

        String text = input.getTextInput();
        int fieldX = (width - FIELD_W) / 2;
        glColor3f(0.2f, 0.2f, 0.2f); glRectf(fieldX, FIELD_Y, fieldX + FIELD_W, FIELD_Y + FIELD_H);
        glColor3f(1f, 1f, 1f); glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(fieldX, FIELD_Y); glVertex2f(fieldX + FIELD_W, FIELD_Y);
        glVertex2f(fieldX + FIELD_W, FIELD_Y + FIELD_H); glVertex2f(fieldX, FIELD_Y + FIELD_H);
        glEnd();
        boolean cursorVisible = (System.currentTimeMillis() / 500) % 2 == 0;
        GLBitmapFont.draw(text + (cursorVisible ? "|" : ""), fieldX + 15, FIELD_Y + FIELD_H / 2f - 10, 2.5f, 1, 1, 1);

        int bx = (width - BW) / 2;
        drawButton(bx, RENAME_Y, "RENAME");
        drawButton(bx, BACK_Y, "BACK");
        if (!status.isEmpty()) {
            GLBitmapFont.draw(status, bx + BW + 20, RENAME_Y + BH / 2f - 8, 2f, 1f, 0.9f, 0.4f);
        }

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    private void drawButton(int x, int y, String label) {
        glColor3f(0.5f, 0.5f, 0.5f);
        glRectf(x, y, x + BW, y + BH);
        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, y); glVertex2f(x + BW, y);
        glVertex2f(x + BW, y + BH); glVertex2f(x, y + BH);
        glEnd();
        float labelWidth = label.length() * 6 * 3f;
        GLBitmapFont.draw(label, x + (BW - labelWidth) / 2f, y + BH / 2f - 12, 3f, 1, 1, 1);
    }
}
