package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import java.io.File;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

// РЕАЛЬНА фіча (Sviatoslav попросив - екран вибору світу, "як у майні"):
// список папок у saves/ (кожна папка - окремий світ), клік по світу
// ВИБИРАЄ його (підсвічується), клік ЩЕ РАЗ по вже вибраному - грати.
// Кнопки внизу: BACK (у головне меню), CREATE WORLD (нова папка з
// автоматичною назвою - тексту вводити нема чим, клавіатура лише для
// руху/хотбару), EDIT WORLD (відкриває WorldEditManager, лише коли щось
// вибрано).
public final class WorldSelectManager {
    private final Window window;
    private final InputHandler input;
    private final int width, height;
    private final List<String> worlds = new ArrayList<>();
    private String selected = null;

    private static final String SAVES_DIR = "saves";
    private static final int ROW_HEIGHT = 50, ROW_GAP = 8, LIST_TOP = 130, LIST_WIDTH = 640;
    private static final int BW = 380, BH = 60, BTN_Y = 630, BTN_GAP = 20;
    private final int listX, backX, createX, editX;

    public WorldSelectManager(Window window, InputHandler input, int width, int height) {
        this.window = window; this.input = input; this.width = width; this.height = height;
        listX = (width - LIST_WIDTH) / 2;
        int totalBtnW = BW * 3 + BTN_GAP * 2;
        backX = (width - totalBtnW) / 2;
        createX = backX + BW + BTN_GAP;
        editX = createX + BW + BTN_GAP;
    }

    // Перескановуємо saves/ ЩОРАЗУ при вході на екран (не кешуємо між
    // заходами - список міг змінитись: створено/видалено світ).
    public void refresh() {
        window.setMouseGrabbed(false);
        worlds.clear();
        selected = null;
        File dir = new File(SAVES_DIR);
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File f : children) worlds.add(f.getName());
        }
    }

    public String getSelectedWorld() { return selected; }

    public String update() {
        // РЕАЛЬНА фіча (Sviatoslav попросив - "Escape працював всюди як
        // 'назад', в любому випадку"): цей екран узагалі не перевіряв
        // Escape - клавіша тут просто нічого не робила.
        if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) return "BACK";

        double mx = input.getMouseX(), my = input.getMouseY();
        // consumeMouseButtonJustPressed, НЕ isMouseButtonPressed -
        // РЕАЛЬНИЙ БАГ, якого тут НЕ мало бути (той самий клас, що вже
        // знайдено раніше з E/F3/Escape): held-стан, перевірюваний щокадру,
        // означав би, що ОДИН фізичний клік, затиснутий кілька кадрів
        // поспіль (людина не відпускає миттєво), спершу ВИБИРАЄ світ на
        // першому кадрі, а вже на ДРУГОМУ кадрі того самого затиснутого
        // кліку побачив би цей світ уже вибраним і одразу заходив би в
        // гру - "вибрати" й "зайти" злилися б в один клік замість двох
        // окремих.
        if (!input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) return "NONE";

        for (int i = 0; i < worlds.size(); i++) {
            int y = LIST_TOP + i * (ROW_HEIGHT + ROW_GAP);
            if (mx >= listX && mx <= listX + LIST_WIDTH && my >= y && my <= y + ROW_HEIGHT) {
                String name = worlds.get(i);
                if (name.equals(selected)) return "PLAY";
                selected = name;
                return "NONE";
            }
        }
        if (isOverButton(backX, mx, my)) return "BACK";
        // РЕАЛЬНА фіча (Sviatoslav попросив - "коли нажимаєш CREATE
        // WORLD має вилізати наступний екран створення"): раніше тут
        // одразу створювалась папка з автоназвою; тепер лише сигнал -
        // саме створення (з вибором типу рельєфу) робить
        // WorldCreateManager, а цей екран після повернення просто
        // оновить список через selectCreated().
        if (isOverButton(createX, mx, my)) return "CREATE";
        if (isOverButton(editX, mx, my) && selected != null) return "EDIT";
        return "NONE";
    }

    private boolean isOverButton(int x, double mx, double my) {
        return mx >= x && mx <= x + BW && my >= BTN_Y && my <= BTN_Y + BH;
    }

    // Викликається після повернення з WorldCreateManager - список уже
    // оновлено (refresh()), лишається лише підсвітити щойно створений
    // світ, якщо він справді з'явився на диску.
    public void selectIfExists(String name) {
        if (worlds.contains(name)) selected = name;
    }

    public void render() {
        glClearColor(0.1f, 0.15f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);

        String title = "SELECT WORLD";
        GLBitmapFont.draw(title, width / 2f - title.length() * 6 * 3 / 2f, 40, 3, 1, 1, 1);

        for (int i = 0; i < worlds.size(); i++) {
            String name = worlds.get(i);
            int y = LIST_TOP + i * (ROW_HEIGHT + ROW_GAP);
            boolean isSelected = name.equals(selected);
            glColor3f(isSelected ? 0.35f : 0.22f, isSelected ? 0.55f : 0.22f, isSelected ? 0.35f : 0.28f);
            glRectf(listX, y, listX + LIST_WIDTH, y + ROW_HEIGHT);
            glColor3f(1f, 1f, 1f);
            glLineWidth(isSelected ? 3f : 1f);
            glBegin(GL_LINE_LOOP);
            glVertex2f(listX, y); glVertex2f(listX + LIST_WIDTH, y);
            glVertex2f(listX + LIST_WIDTH, y + ROW_HEIGHT); glVertex2f(listX, y + ROW_HEIGHT);
            glEnd();
            GLBitmapFont.draw(name, listX + 15, y + ROW_HEIGHT / 2f - 10, 2.5f, 1, 1, 1);
        }
        if (worlds.isEmpty()) {
            String hint = "NO WORLDS - CREATE ONE";
            GLBitmapFont.draw(hint, width / 2f - hint.length() * 6, LIST_TOP + 10, 1.5f, 0.7f, 0.7f, 0.7f);
        }

        drawButton(backX, "BACK", true);
        drawButton(createX, "CREATE WORLD", true);
        // Притлумлена (не яскрава) - той самий візуальний сигнал
        // "неактивна", що й у Minecraft, коли нічого не вибрано.
        drawButton(editX, "EDIT WORLD", selected != null);

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    private void drawButton(int x, String label, boolean active) {
        glColor3f(active ? 0.45f : 0.25f, active ? 0.45f : 0.25f, active ? 0.5f : 0.25f);
        glRectf(x, BTN_Y, x + BW, BTN_Y + BH);
        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, BTN_Y); glVertex2f(x + BW, BTN_Y);
        glVertex2f(x + BW, BTN_Y + BH); glVertex2f(x, BTN_Y + BH);
        glEnd();
        float labelWidth = label.length() * 6 * 2.2f;
        GLBitmapFont.draw(label, x + (BW - labelWidth) / 2f, BTN_Y + BH / 2f - 8, 2.2f,
            active ? 1f : 0.6f, active ? 1f : 0.6f, active ? 1f : 0.6f);
    }
}
