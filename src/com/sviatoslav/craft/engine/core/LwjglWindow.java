package com.sviatoslav.craft.engine.core;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class LwjglWindow implements Window {
    private long handle;
    private int width, height;

    @Override
    public void create(String title, int width, int height) {
        this.width = width;
        this.height = height;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW не ініціалізовано");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        // РЕАЛЬНА ОПТИМІЗАЦІЯ: 4x MSAA коштовне на слабкій/інтегрованій
        // графіці (кожен піксель рахується вчетверо) - Sviatoslav попросив
        // "тягнуло навіть на сміттєвому відрі", 2x лишає прийнятне
        // згладжування країв за половину вартості.
        glfwWindowHint(GLFW_SAMPLES, 2);
        // РЕАЛЬНИЙ ФІКС (був відсутній у чернетці DeepSeek): рендерер увесь
        // на immediate-mode (glBegin/glVertex3f), який існує ЛИШЕ в
        // compatibility-профілі. Без явної версії GLFW бере "яку дасть
        // драйвер" - на новіших Mesa-драйверах Linux дефолт може виявитись
        // core-профілем без підтримки immediate-mode взагалі (вікно
        // відкриється, але весь рендеринг мовчки не намалює нічого).
        // 2.1 - остання версія, де core/compat профілів ще не існувало
        // взагалі (введені лише в 3.2) - гарантовано working fixed-function
        // pipeline на будь-якому реалістичному драйвері.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) throw new RuntimeException("Не вдалося створити вікно");

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        glfwSwapInterval(1);

        glClearColor(0.53f, 0.71f, 0.92f, 1.0f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        // РЕАЛЬНИЙ ФІКС (баг у чернетці DeepSeek): цей callback ховав і
        // захоплював курсор миші ПРИ БУДЬ-ЯКОМУ отриманні фокусу вікном -
        // спрацьовувало навіть У МЕНЮ (одразу після створення вікна),
        // забиваючи спробу MenuManager показати курсор для кліку по
        // кнопках "Грати"/"Вихід". Видимість курсора тепер керується ЛИШЕ
        // явно, з ігрового стану (SviatoslavCraft.update() викликає
        // setMouseGrabbed(true/false) саме в момент переходу меню<->гра),
        // не сирими подіями фокуса ОС.

        glfwSetWindowSizeCallback(handle, (window, newWidth, newHeight) -> {
            this.width = newWidth;
            this.height = newHeight;
            glViewport(0, 0, newWidth, newHeight);
        });
    }

    @Override
    public void swapBuffers() {
        glfwSwapBuffers(handle);
        glfwPollEvents();
    }

    @Override
    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    @Override
    public void destroy() { glfwDestroyWindow(handle); glfwTerminate(); }
    @Override
    public long getHandle() { return handle; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    @Override
    public void setMouseGrabbed(boolean grabbed) {
        glfwSetInputMode(handle, GLFW_CURSOR, grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
    }
}
