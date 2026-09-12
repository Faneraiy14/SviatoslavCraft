package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import com.sviatoslav.craft.engine.world.Chunk;
import com.sviatoslav.craft.engine.world.World;
import java.io.File;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

// РЕАЛЬНА фіча (Sviatoslav попросив - "коли нажимаєш CREATE WORLD має
// вилізати наступний екран створення: плаский, звичайний і налаштування
// світу, і кнопка назад"): окремий екран замість миттєвого створення
// світу з екрану вибору. Назва - поле вводу тексту (перший реальний
// текстовий ввід у грі, InputHandler.setTextInputActive), попередньо
// заповнене автоназвою; тип рельєфу - перемикач NORMAL/FLAT
// (Chunk.WorldType). Сама папка й метафайл типу створюються ЛИШЕ по
// натисканню CREATE (World.writeWorldMeta) - BACK нічого на диску не
// лишає.
public final class WorldCreateManager {
    private final Window window;
    private final InputHandler input;
    private final int width, height;
    private Chunk.WorldType selectedType = Chunk.WorldType.NORMAL;

    private static final String SAVES_DIR = "saves";
    private static final int TYPE_BW = 300, TYPE_BH = 70, TYPE_Y = 300, TYPE_GAP = 40;
    private static final int CREATE_BW = 300, BACK_BW = 300, BOTTOM_BH = 60, BOTTOM_Y = 600;
    private final int normalX, flatX, createX, backX;

    public WorldCreateManager(Window window, InputHandler input, int width, int height) {
        this.window = window; this.input = input; this.width = width; this.height = height;
        int totalTypeW = TYPE_BW * 2 + TYPE_GAP;
        normalX = (width - totalTypeW) / 2;
        flatX = normalX + TYPE_BW + TYPE_GAP;
        int totalBottomW = CREATE_BW + BACK_BW + TYPE_GAP;
        backX = (width - totalBottomW) / 2;
        createX = backX + BACK_BW + TYPE_GAP;
    }

    // Викликається щоразу при вході на екран - автоназва рахується
    // наново (список світів міг змінитись), тип рельєфу скидається на
    // NORMAL за замовчуванням.
    public void open() {
        window.setMouseGrabbed(false);
        selectedType = Chunk.WorldType.NORMAL;
        input.setTextInput(nextAutoName());
        input.setTextInputActive(true);
    }

    private String nextAutoName() {
        int n = 1;
        String name;
        do { name = "World" + n; n++; } while (new File(SAVES_DIR + "/" + name).exists());
        return name;
    }

    public String update() {
        input.updateTextInputBackspace();
        // Escape - "назад", навіть посеред набору тексту (Sviatoslav
        // попросив - "у любому випадку"): сам символ Escape не
        // друкований, char-callback його не чіпає, тому це безпечно й
        // тут.
        if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) { input.setTextInputActive(false); return "BACK"; }

        double mx = input.getMouseX(), my = input.getMouseY();
        if (!input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) return "NONE";

        if (isOver(normalX, TYPE_Y, TYPE_BW, TYPE_BH, mx, my)) { selectedType = Chunk.WorldType.NORMAL; return "NONE"; }
        if (isOver(flatX, TYPE_Y, TYPE_BW, TYPE_BH, mx, my)) { selectedType = Chunk.WorldType.FLAT; return "NONE"; }
        if (isOver(backX, BOTTOM_Y, BACK_BW, BOTTOM_BH, mx, my)) { input.setTextInputActive(false); return "BACK"; }
        if (isOver(createX, BOTTOM_Y, CREATE_BW, BOTTOM_BH, mx, my)) {
            String name = input.getTextInput().trim();
            if (name.isEmpty()) name = nextAutoName();
            // Ім'я вже зайняте (хтось інший світ так називається) -
            // тихо підставляємо вільну автоназву замість збою/перезапису
            // чужого світу.
            if (new File(SAVES_DIR + "/" + name).exists()) name = nextAutoName();
            World.writeWorldMeta(name, selectedType);
            input.setTextInputActive(false);
            createdWorldName = name;
            return "CREATED";
        }
        return "NONE";
    }

    private String createdWorldName;
    public String getCreatedWorldName() { return createdWorldName; }

    private boolean isOver(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public void render() {
        glClearColor(0.1f, 0.15f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        String title = "CREATE WORLD";
        GLBitmapFont.draw(title, width / 2f - title.length() * 6 * 3 / 2f, 40, 3, 1, 1, 1);

        // Поле вводу назви.
        String name = input.getTextInput();
        int fieldW = 500, fieldH = 60, fieldX = (width - fieldW) / 2, fieldY = 150;
        glColor3f(0.2f, 0.2f, 0.2f); glRectf(fieldX, fieldY, fieldX + fieldW, fieldY + fieldH);
        glColor3f(1f, 1f, 1f); glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(fieldX, fieldY); glVertex2f(fieldX + fieldW, fieldY);
        glVertex2f(fieldX + fieldW, fieldY + fieldH); glVertex2f(fieldX, fieldY + fieldH);
        glEnd();
        // Моргаючий курсор (Sviatoslav попросив - "щоб було зрозуміло де
        // курсор стоїть"): 500мс видно, 500мс нема - сам символ '|'
        // просто НЕ додається в рядок на "темну" половину циклу, не
        // якась окрема анімація.
        boolean cursorVisible = (System.currentTimeMillis() / 500) % 2 == 0;
        GLBitmapFont.draw(name + (cursorVisible ? "|" : ""), fieldX + 15, fieldY + fieldH / 2f - 10, 2.5f, 1, 1, 1);

        drawToggle(normalX, "NORMAL", selectedType == Chunk.WorldType.NORMAL);
        drawToggle(flatX, "FLAT", selectedType == Chunk.WorldType.FLAT);

        drawButton(backX, BOTTOM_Y, BACK_BW, "BACK");
        drawButton(createX, BOTTOM_Y, CREATE_BW, "CREATE");

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    private void drawToggle(int x, String label, boolean active) {
        glColor3f(active ? 0.3f : 0.22f, active ? 0.55f : 0.22f, active ? 0.35f : 0.28f);
        glRectf(x, TYPE_Y, x + TYPE_BW, TYPE_Y + TYPE_BH);
        glColor3f(1f, 1f, 1f);
        glLineWidth(active ? 3f : 1f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, TYPE_Y); glVertex2f(x + TYPE_BW, TYPE_Y);
        glVertex2f(x + TYPE_BW, TYPE_Y + TYPE_BH); glVertex2f(x, TYPE_Y + TYPE_BH);
        glEnd();
        float labelWidth = label.length() * 6 * 2.5f;
        GLBitmapFont.draw(label, x + (TYPE_BW - labelWidth) / 2f, TYPE_Y + TYPE_BH / 2f - 9, 2.5f, 1, 1, 1);
    }

    private void drawButton(int x, int y, int w, String label) {
        glColor3f(0.45f, 0.45f, 0.5f);
        glRectf(x, y, x + w, y + BOTTOM_BH);
        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, y); glVertex2f(x + w, y);
        glVertex2f(x + w, y + BOTTOM_BH); glVertex2f(x, y + BOTTOM_BH);
        glEnd();
        float labelWidth = label.length() * 6 * 2.2f;
        GLBitmapFont.draw(label, x + (w - labelWidth) / 2f, y + BOTTOM_BH / 2f - 8, 2.2f, 1, 1, 1);
    }
}
