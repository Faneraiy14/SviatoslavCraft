package com.sviatoslav.craft.engine.graphics;

import static org.lwjgl.opengl.GL11.*;

public class Renderer {
    private Camera camera;

    public Renderer() { camera = new Camera(); }

    public void prepare() {
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "мікрофризи, коли бігаю і
    // повертаю камеру", ставали частішими саме під час бігу): раніше тут
    // був renderCube(...) - ОКРЕМИЙ glPushMatrix/glTranslatef/glBegin/
    // glEnd/glPopMatrix (і ще один begin/end на контур) на КОЖЕН окремий
    // блок. World.buildChunkGeometry викликав це для кожного видимого
    // блока чанка (сотні за раз) під час КОЖНОЇ перебудови display
    // list'а - а біг перетинає межі чанків (і тому перебудови) значно
    // частіше за ходьбу. Тепер - beginChunkSolid/addCubeQuads/
    // endChunkSolid: ОДИН спільний glBegin(GL_QUADS) на ВЕСЬ чанк одразу
    // (координати вершин рахуються тут напряму в АБСОЛЮТНИХ світових
    // одиницях, без per-блокової матриці - glPushMatrix/glTranslatef на
    // кожен блок якраз і були частиною зайвого навантаження). Той самий
    // принцип, що й пакетний GLBitmapFont.draw() вище.
    public void beginChunkSolid() { glBegin(GL_QUADS); }
    public void endChunkSolid() { glEnd(); }

    // РЕАЛЬНИЙ БАГ (чернетка DeepSeek, знайдено Sviatoslav'ом живцем -
    // "текстури не повні", листя дерев виглядало тонкими скалками):
    // порядок вершин TOP/BOTTOM був ЗВОРОТНИЙ відносно решти 4 граней
    // (перевірено векторним добутком - нормаль виходила в протилежний
    // бік). З увімкненим GL_CULL_FACE верх і низ КОЖНОГО блока в грі
    // були невидимі майже завжди (видно лише знизу вгору/зверху вниз,
    // чого в звичайній грі не буває) - лишались тільки бокові грані,
    // тому скупчення блоків (крони дерев) виглядали пласкими скалками.
    // Викликається ВСЕРЕДИНІ вже відкритого beginChunkSolid() - сам
    // begin/end не відкриває.
    public void addCubeQuads(float x, float y, float z, float size, float r, float g, float b,
                              boolean top, boolean bottom, boolean front, boolean back, boolean left, boolean right) {
        float h = size / 2;
        glColor3f(r, g, b);
        if (top) { glVertex3f(x-h,y+h,z-h); glVertex3f(x-h,y+h,z+h); glVertex3f(x+h,y+h,z+h); glVertex3f(x+h,y+h,z-h); }
        if (bottom) { glVertex3f(x-h,y-h,z-h); glVertex3f(x+h,y-h,z-h); glVertex3f(x+h,y-h,z+h); glVertex3f(x-h,y-h,z+h); }
        if (front) { glVertex3f(x-h,y-h,z+h); glVertex3f(x+h,y-h,z+h); glVertex3f(x+h,y+h,z+h); glVertex3f(x-h,y+h,z+h); }
        if (back) { glVertex3f(x-h,y-h,z-h); glVertex3f(x-h,y+h,z-h); glVertex3f(x+h,y+h,z-h); glVertex3f(x+h,y-h,z-h); }
        if (left) { glVertex3f(x-h,y-h,z-h); glVertex3f(x-h,y-h,z+h); glVertex3f(x-h,y+h,z+h); glVertex3f(x-h,y+h,z-h); }
        if (right) { glVertex3f(x+h,y-h,z-h); glVertex3f(x+h,y+h,z-h); glVertex3f(x+h,y+h,z+h); glVertex3f(x+h,y-h,z+h); }
    }

    // Контур граней (Sviatoslav попросив - "не слішком ядрьоні, але і не
    // дуже тонкі"): приглушений темно-сірий, ОДИН раз на весь чанк
    // (кольор/товщина лінії не змінюються між блоками, на відміну від
    // заливки). Лише для ВИДИМИХ граней - контур прихованої грані однаково
    // ніхто не побачить, лише зайве навантаження.
    public void beginChunkOutline() {
        glColor3f(0.06f, 0.06f, 0.06f);
        glLineWidth(1.0f);
        glBegin(GL_LINES);
    }
    public void endChunkOutline() { glEnd(); }

    // lh трохи більше за h (той самий прийом, що й у renderWireCube
    // нижче) - інакше лінія й заливка лежать РІВНО на одній глибині, і
    // яка з них "переможе" в буфері глибини непередбачувано мигтить
    // (z-fighting) кожен кадр. Викликається ВСЕРЕДИНІ вже відкритого
    // beginChunkOutline().
    public void addCubeOutline(float x, float y, float z, float size,
                                boolean top, boolean bottom, boolean front, boolean back, boolean left, boolean right) {
        float lh = size / 2 + 0.004f;
        if (top) outlineQuad(x-lh,y+lh,z-lh, x-lh,y+lh,z+lh, x+lh,y+lh,z+lh, x+lh,y+lh,z-lh);
        if (bottom) outlineQuad(x-lh,y-lh,z-lh, x+lh,y-lh,z-lh, x+lh,y-lh,z+lh, x-lh,y-lh,z+lh);
        if (front) outlineQuad(x-lh,y-lh,z+lh, x+lh,y-lh,z+lh, x+lh,y+lh,z+lh, x-lh,y+lh,z+lh);
        if (back) outlineQuad(x-lh,y-lh,z-lh, x-lh,y+lh,z-lh, x+lh,y+lh,z-lh, x+lh,y-lh,z-lh);
        if (left) outlineQuad(x-lh,y-lh,z-lh, x-lh,y-lh,z+lh, x-lh,y+lh,z+lh, x-lh,y+lh,z-lh);
        if (right) outlineQuad(x+lh,y-lh,z-lh, x+lh,y+lh,z-lh, x+lh,y+lh,z+lh, x+lh,y-lh,z+lh);
    }

    // 4 ребра однієї грані як GL_LINES (v0-v1, v1-v2, v2-v3, v3-v0) -
    // викликається ВСЕРЕДИНІ вже відкритого glBegin(GL_LINES), сам
    // begin/end не відкриває.
    private void outlineQuad(float x0,float y0,float z0, float x1,float y1,float z1,
                              float x2,float y2,float z2, float x3,float y3,float z3) {
        glVertex3f(x0,y0,z0); glVertex3f(x1,y1,z1);
        glVertex3f(x1,y1,z1); glVertex3f(x2,y2,z2);
        glVertex3f(x2,y2,z2); glVertex3f(x3,y3,z3);
        glVertex3f(x3,y3,z3); glVertex3f(x0,y0,z0);
    }

    // "Тіло" гравця в 3-й особі (F5) - справжньої моделі гравця в грі
    // нема, тому коробка-заглушка (не куб - ширина/глибина ≠ висота),
    // завжди всі 6 граней (окремий об'єкт, не частина сітки чанка - нема
    // сусідів, що ховали б грані). Той самий порядок вершин TOP/BOTTOM,
    // що й у виправленому renderCube вище - інакше знову зникли б згори/
    // знизу з GL_CULL_FACE.
    public void renderBox(float x, float y, float z, float halfWidth, float halfHeight, float r, float g, float b) {
        glPushMatrix();
        glTranslatef(x, y, z);
        glColor3f(r, g, b);
        float hw = halfWidth, hh = halfHeight;
        glBegin(GL_QUADS);
        glVertex3f(-hw,hh,-hw); glVertex3f(-hw,hh,hw); glVertex3f(hw,hh,hw); glVertex3f(hw,hh,-hw);
        glVertex3f(-hw,-hh,-hw); glVertex3f(hw,-hh,-hw); glVertex3f(hw,-hh,hw); glVertex3f(-hw,-hh,hw);
        glVertex3f(-hw,-hh,hw); glVertex3f(hw,-hh,hw); glVertex3f(hw,hh,hw); glVertex3f(-hw,hh,hw);
        glVertex3f(-hw,-hh,-hw); glVertex3f(-hw,hh,-hw); glVertex3f(hw,hh,-hw); glVertex3f(hw,-hh,-hw);
        glVertex3f(-hw,-hh,-hw); glVertex3f(-hw,-hh,hw); glVertex3f(-hw,hh,hw); glVertex3f(-hw,hh,-hw);
        glVertex3f(hw,-hh,-hw); glVertex3f(hw,hh,-hw); glVertex3f(hw,hh,hw); glVertex3f(hw,-hh,hw);
        glEnd();
        glPopMatrix();
    }

    // Контур навколо блока, на який дивишся (targeting-курсор) - GL_LINE_LOOP
    // по кожній грані, трохи більший за куб (offset), щоб не "воювати" з
    // кольором грані (z-fighting). БІЛИЙ, не чорний (був чорний - Sviatoslav
    // помітив живцем, що зливається з тепер теж темними контурами граней
    // renderCube на темних блоках типу каменю) - білий виділяється на будь-
    // якому кольорі блока, включно з темним.
    public void renderWireCube(float x, float y, float z, float size) {
        glPushMatrix();
        glTranslatef(x, y, z);
        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        // РЕАЛЬНИЙ БАГ (Sviatoslav помітив живцем - білий курсор видно не з
        // усіх боків): offset 0.005 був майже ТОЙ САМИЙ, що й 0.004 у
        // контурів граней renderCube - обидва малюються майже на одній
        // глибині й "воюють" у буфері глибини (z-fighting), тому окремі
        // ребра курсора мигтіли/зникали залежно від кута. 0.02 - помітно
        // далі від контуру граней, курсор завжди виграє.
        float h = size / 2 + 0.02f;
        glBegin(GL_LINE_LOOP);
        glVertex3f(-h,-h,-h); glVertex3f(h,-h,-h); glVertex3f(h,h,-h); glVertex3f(-h,h,-h);
        glEnd();
        glBegin(GL_LINE_LOOP);
        glVertex3f(-h,-h,h); glVertex3f(h,-h,h); glVertex3f(h,h,h); glVertex3f(-h,h,h);
        glEnd();
        glBegin(GL_LINES);
        glVertex3f(-h,-h,-h); glVertex3f(-h,-h,h);
        glVertex3f(h,-h,-h); glVertex3f(h,-h,h);
        glVertex3f(h,h,-h); glVertex3f(h,h,h);
        glVertex3f(-h,h,-h); glVertex3f(-h,h,h);
        glEnd();
        glPopMatrix();
    }

    // Приціл по центру екрана (Sviatoslav попросив - без нього незрозуміло,
    // куди саме дивишся, коли жоден блок не в межах досяжності, і на
    // короткій дистанції). 2D-накладка поверх 3D-сцени, як меню/F3 -
    // ортопроєкція, вимкнені depth-test/cull-face. Чорна "підкладка"
    // трохи товща ПІД білим хрестиком - щоб було видно на будь-якому фоні
    // (і на яскравому небі, і на темному камені).
    public void renderCrosshair(int screenWidth, int screenHeight) {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, screenWidth, screenHeight, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);

        float cx = screenWidth / 2f, cy = screenHeight / 2f;
        float arm = 8f;

        glColor3f(0f, 0f, 0f);
        glLineWidth(4f);
        glBegin(GL_LINES);
        glVertex2f(cx - arm, cy); glVertex2f(cx + arm, cy);
        glVertex2f(cx, cy - arm); glVertex2f(cx, cy + arm);
        glEnd();

        glColor3f(1f, 1f, 1f);
        glLineWidth(2f);
        glBegin(GL_LINES);
        glVertex2f(cx - arm, cy); glVertex2f(cx + arm, cy);
        glVertex2f(cx, cy - arm); glVertex2f(cx, cy + arm);
        glEnd();

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }

    public Camera getCamera() { return camera; }
}
