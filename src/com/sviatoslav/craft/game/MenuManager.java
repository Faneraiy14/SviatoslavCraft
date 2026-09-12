package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

public class MenuManager {
    private Window window;
    private InputHandler input;
    private int width, height;
    private int selected = 0;
    // Латиницею, не "Грати"/"Вихід" - GLBitmapFont (як і Font5x7 в Echo
    // Strategy) свідомо без кирилиці: рукописний растровий шрифт без живої
    // перевірки на екрані ризиковано малювати кирилицею, могла вийти
    // нечитабельна мазня непоміченою.
    // "SINGLEPLAYER" замість "PLAY" (Sviatoslav попросив - одна кнопка
    // "одиночна гра", що веде на екран вибору світу, замість одразу
    // старту в захардкожений світ). Токен, який повертає update()
    // ("PLAY"), навмисно НЕ змінено - SviatoslavCraft лише переходить на
    // WORLD_SELECT замість initGame() напряму, семантика назви лишається
    // "той самий перший пункт меню".
    private String[] options = {"SINGLEPLAYER", "EXIT"};
    // bw розширено (340->400) під більший текст (Sviatoslav попросив -
    // "зроби текст трохи більше") - інакше "SINGLEPLAYER" на scale 4.5
    // (замість 4) впирався б у краї кнопки.
    private int bw = 400, bh = 60, bx, by1, by2;
    // РЕАЛЬНИЙ ФІКС (баг у чернетці DeepSeek): навігація клавіатурою робила
    // Thread.sleep(150) ПРЯМО у update(), яку викликає ігровий цикл - це
    // блокувало ВЕСЬ застосунок (рендер+ввід) на 150мс щоразу, як хтось
    // тиснув стрілку в меню. Тепер - таймстемп-дебаунс без сну.
    private long lastNavTimeMs = 0;
    private static final long NAV_COOLDOWN_MS = 150;

    public MenuManager(Window window, InputHandler input, int width, int height) {
        this.window = window; this.input = input; this.width = width; this.height = height;
        window.setMouseGrabbed(false);
        bx = (width - bw) / 2;
        by1 = 280; by2 = 370;
    }

    public String update() {
        long now = System.currentTimeMillis();
        boolean canNav = now - lastNavTimeMs > NAV_COOLDOWN_MS;
        if (canNav && (input.isKeyPressed(GLFW_KEY_UP) || input.isKeyPressed(GLFW_KEY_W))) { selected = 0; lastNavTimeMs = now; }
        if (canNav && (input.isKeyPressed(GLFW_KEY_DOWN) || input.isKeyPressed(GLFW_KEY_S))) { selected = 1; lastNavTimeMs = now; }
        if (input.isKeyPressed(GLFW_KEY_ENTER) || input.isKeyPressed(GLFW_KEY_SPACE)) return selected == 0 ? "PLAY" : "EXIT";
        // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "Escape з гри кидає в
        // меню, а потім гра взагалі закривається"): тут стояв isKeyPressed
        // (утримується ЗАРАЗ) - той самий фізичний натиск Escape, яким
        // SviatoslavCraft.updateGame() щойно перевів стан гра->меню, ще
        // лишався "затиснутим" на наступному кадрі (людина не встигає
        // відпустити клавішу за 1/60с) - і ЦЕЙ метод бачив ту саму Escape
        // ще раз і одразу трактував як "Вихід" із застосунку. consumeKeyJustPressed
        // "з'їдає" прапорець - хто перевірить першим (тут чи updateGame),
        // той його й використає, другий уже не побачить того самого натиску.
        if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) return "EXIT";

        double mx = input.getMouseX(), my = input.getMouseY();
        if (isOver(0, mx, my)) selected = 0;
        if (isOver(1, mx, my)) selected = 1;
        if (input.isMouseButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            if (isOver(0, mx, my)) return "PLAY";
            if (isOver(1, mx, my)) return "EXIT";
        }
        return "NONE";
    }

    private boolean isOver(int i, double mx, double my) {
        int y = i == 0 ? by1 : by2;
        return mx >= bx && mx <= bx+bw && my >= y && my <= y+bh;
    }

    public void render() {
        glClearColor(0.1f, 0.15f, 0.3f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, width, height, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        // РЕАЛЬНИЙ ФІКС: GL_CULL_FACE лишався увімкненим із 3D-налаштувань
        // вікна (LwjglWindow) - у Y-перевернутій 2D ортопроєкції порядок
        // вершин glRectf/GL_QUADS стає "заднім" і GL_CULL_FACE його просто
        // відкидав (контур GL_LINE_LOOP не зачіпало - лінії відсіченню
        // граней не підлягають узагалі, тому й лишався видимим сам).
        glDisable(GL_CULL_FACE);

        // РЕАЛЬНИЙ ФІКС (баг у чернетці DeepSeek): тут стояв лише кольоровий
        // прямокутник ("// Заголовок") замість справжнього напису, і жодних
        // букв на самих кнопках - "PLAY"/"EXIT" зберігались у масиві
        // options[], але ніколи насправді не малювались. Тепер - реальний
        // текст через GLBitmapFont (той самий підхід, що й DebugOverlay).
        // Sviatoslav попросив "зроби текст трохи більше" - заголовок
        // 3->4, кнопки 4->4.5 (bw вище вже розширено під це). Заголовок
        // тепер центрується розрахунком, не жорсткою координатою x=420 -
        // інакше на більшому scale з'їхав би вбік від жовтої панелі.
        String title = "SVIATOSLAVCRAFT";
        float titleScale = 4f, titleWidth = title.length() * 6 * titleScale;
        glColor3f(1f,0.8f,0.2f); glRectf(400,80,880,160);
        GLBitmapFont.draw(title, (400+880)/2f - titleWidth/2f, 110, titleScale, 0.2f, 0.15f, 0.05f);

        int[] ys = {by1, by2};
        for (int i = 0; i < options.length; i++) {
            int y = ys[i];
            glColor3f(i == selected ? 1f : 0.4f, i == selected ? 0.9f : 0.4f, i == selected ? 0.2f : 0.6f);
            glRectf(bx, y, bx+bw, y+bh);
            glColor3f(1,1,1); glLineWidth(2);
            glBegin(GL_LINE_LOOP); glVertex2f(bx,y); glVertex2f(bx+bw,y); glVertex2f(bx+bw,y+bh); glVertex2f(bx,y+bh); glEnd();
            String label = options[i];
            float labelScale = 4.5f;
            float labelWidth = label.length() * 6 * labelScale;
            GLBitmapFont.draw(label, bx + (bw - labelWidth) / 2f, y + bh/2f - 16, labelScale, 1, 1, 1);
        }
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }
}
