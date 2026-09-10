package com.sviatoslav.craft.engine.graphics;

import static org.lwjgl.opengl.GL11.*;

public class Renderer {
    private Camera camera;

    public Renderer() { camera = new Camera(); }

    public void prepare() {
        glClearColor(0.53f, 0.81f, 0.92f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    public void renderCube(float x, float y, float z, float size, float r, float g, float b,
                           boolean top, boolean bottom, boolean front, boolean back, boolean left, boolean right) {
        glPushMatrix();
        glTranslatef(x, y, z);
        glColor3f(r, g, b);
        float h = size / 2;
        // РЕАЛЬНИЙ БАГ (чернетка DeepSeek, знайдено Sviatoslav'ом живцем -
        // "текстури не повні", листя дерев виглядало тонкими скалками):
        // порядок вершин TOP/BOTTOM був ЗВОРОТНИЙ відносно решти 4 граней
        // (перевірено векторним добутком - нормаль виходила в протилежний
        // бік). З увімкненим GL_CULL_FACE верх і низ КОЖНОГО блока в грі
        // були невидимі майже завжди (видно лише знизу вгору/зверху вниз,
        // чого в звичайній грі не буває) - лишались тільки бокові грані,
        // тому скупчення блоків (крони дерев) виглядали пласкими скалками.
        glBegin(GL_QUADS);
        if (top) { glVertex3f(-h,h,-h); glVertex3f(-h,h,h); glVertex3f(h,h,h); glVertex3f(h,h,-h); }
        if (bottom) { glVertex3f(-h,-h,-h); glVertex3f(h,-h,-h); glVertex3f(h,-h,h); glVertex3f(-h,-h,h); }
        if (front) { glVertex3f(-h,-h,h); glVertex3f(h,-h,h); glVertex3f(h,h,h); glVertex3f(-h,h,h); }
        if (back) { glVertex3f(-h,-h,-h); glVertex3f(-h,h,-h); glVertex3f(h,h,-h); glVertex3f(h,-h,-h); }
        if (left) { glVertex3f(-h,-h,-h); glVertex3f(-h,-h,h); glVertex3f(-h,h,h); glVertex3f(-h,h,-h); }
        if (right) { glVertex3f(h,-h,-h); glVertex3f(h,h,-h); glVertex3f(h,h,h); glVertex3f(h,-h,h); }
        glEnd();

        // Контур граней (Sviatoslav попросив - "не слішком ядрьоні, але і не
        // дуже тонкі"): приглушений темно-сірий (не чистий чорний - на
        // яскраво-зеленій траві/жовтуватому дереві це різало б очі), лінія
        // середньої товщини (1.3, тонше за прицільний renderWireCube нижче,
        // який навмисно жирніший, бо він один і сам по собі акцент). Лише
        // для ВИДИМИХ граней (ті самі top/bottom/... прапорці) - контур
        // прихованої грані однаково ніхто не побачить, лише зайве
        // навантаження.
        glColor3f(0.06f, 0.06f, 0.06f);
        glLineWidth(1.0f);
        // lh трохи більше за h (той самий прийом, що й у renderWireCube
        // нижче) - інакше лінія й заливка лежать РІВНО на одній глибині, і
        // яка з них "переможе" в буфері глибини непередбачувано мигтить
        // (z-fighting) кожен кадр.
        float lh = h + 0.004f;
        if (top) { glBegin(GL_LINE_LOOP); glVertex3f(-lh,lh,-lh); glVertex3f(-lh,lh,lh); glVertex3f(lh,lh,lh); glVertex3f(lh,lh,-lh); glEnd(); }
        if (bottom) { glBegin(GL_LINE_LOOP); glVertex3f(-lh,-lh,-lh); glVertex3f(lh,-lh,-lh); glVertex3f(lh,-lh,lh); glVertex3f(-lh,-lh,lh); glEnd(); }
        if (front) { glBegin(GL_LINE_LOOP); glVertex3f(-lh,-lh,lh); glVertex3f(lh,-lh,lh); glVertex3f(lh,lh,lh); glVertex3f(-lh,lh,lh); glEnd(); }
        if (back) { glBegin(GL_LINE_LOOP); glVertex3f(-lh,-lh,-lh); glVertex3f(-lh,lh,-lh); glVertex3f(lh,lh,-lh); glVertex3f(lh,-lh,-lh); glEnd(); }
        if (left) { glBegin(GL_LINE_LOOP); glVertex3f(-lh,-lh,-lh); glVertex3f(-lh,-lh,lh); glVertex3f(-lh,lh,lh); glVertex3f(-lh,lh,-lh); glEnd(); }
        if (right) { glBegin(GL_LINE_LOOP); glVertex3f(lh,-lh,-lh); glVertex3f(lh,lh,-lh); glVertex3f(lh,lh,lh); glVertex3f(lh,-lh,lh); glEnd(); }

        glPopMatrix();
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
