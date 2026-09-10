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
        glBegin(GL_QUADS);
        if (top) { glVertex3f(-h,h,-h); glVertex3f(h,h,-h); glVertex3f(h,h,h); glVertex3f(-h,h,h); }
        if (bottom) { glVertex3f(-h,-h,-h); glVertex3f(-h,-h,h); glVertex3f(h,-h,h); glVertex3f(h,-h,-h); }
        if (front) { glVertex3f(-h,-h,h); glVertex3f(h,-h,h); glVertex3f(h,h,h); glVertex3f(-h,h,h); }
        if (back) { glVertex3f(-h,-h,-h); glVertex3f(-h,h,-h); glVertex3f(h,h,-h); glVertex3f(h,-h,-h); }
        if (left) { glVertex3f(-h,-h,-h); glVertex3f(-h,-h,h); glVertex3f(-h,h,h); glVertex3f(-h,h,-h); }
        if (right) { glVertex3f(h,-h,-h); glVertex3f(h,h,-h); glVertex3f(h,h,h); glVertex3f(h,-h,h); }
        glEnd();
        glPopMatrix();
    }

    // Тонкий чорний контур навколо блока, на який дивишся (targeting-курсор)
    // - GL_LINE_LOOP по кожній грані, трохи більший за куб (offset), щоб не
    // "воювати" з текстурою/кольором грані (z-fighting).
    public void renderWireCube(float x, float y, float z, float size) {
        glPushMatrix();
        glTranslatef(x, y, z);
        glColor3f(0f, 0f, 0f);
        glLineWidth(2f);
        float h = size / 2 + 0.005f;
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

    public Camera getCamera() { return camera; }
}
