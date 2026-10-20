package voxelin.client;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Вікно GLFW + контекст OpenGL. Контекст СУМІСНИЙ (без core-профілю): світ малюється шейдерами й буферами GPU,
 * а старий інтерфейс (glBegin) працює далі, поки його не перенесено. Потрібен OpenGL 3.0+ (VAO, шейдери GLSL 1.30).
 */
public final class Window {
    private long handle;
    private int width, height, fbWidth, fbHeight;
    private String glVersion = "?", glRenderer = "?";

    public void create(String title, int w, int h, int msaa, boolean vsync, boolean takeFocus) {
        width = w; height = h;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW не ініціалізовано");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, Math.max(0, msaa));
        // dev-запуски (бенчмарк, знімки) не відбирають фокус у того, хто працює за комп'ютером
        glfwWindowHint(GLFW_FOCUSED, takeFocus ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, takeFocus ? GLFW_TRUE : GLFW_FALSE);
        handle = glfwCreateWindow(w, h, title, NULL, NULL);
        if (handle == NULL) throw new RuntimeException("Не вдалося створити вікно");
        glfwMakeContextCurrent(handle);
        var caps = GL.createCapabilities();
        glVersion = glGetString(GL_VERSION); glRenderer = glGetString(GL_RENDERER);
        System.out.println("[Voxelin] OpenGL " + glVersion + " / " + glRenderer);
        if (!caps.OpenGL30) throw new IllegalStateException("Потрібен OpenGL 3.0 або новіший, а відеокарта дає " + glVersion + ". Онови драйвер відеокарти.");
        glfwSwapInterval(vsync ? 1 : 0);
        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(handle, fw, fh);
        fbWidth = fw[0]; fbHeight = fh[0];
        glfwSetWindowSizeCallback(handle, (win, nw, nh) -> { width = nw; height = nh; });
        glfwSetFramebufferSizeCallback(handle, (win, nw, nh) -> { fbWidth = nw; fbHeight = nh; });
    }

    public void swapBuffers() { glfwSwapBuffers(handle); glfwPollEvents(); }
    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    public void requestClose() { glfwSetWindowShouldClose(handle, true); }
    public void destroy() { glfwDestroyWindow(handle); glfwTerminate(); }
    public long handle() { return handle; }
    public int width() { return width; }
    public int height() { return height; }
    public int fbWidth() { return Math.max(1, fbWidth); }
    public int fbHeight() { return Math.max(1, fbHeight); }
    public String glVersion() { return glVersion; }
    public String glRenderer() { return glRenderer; }
    public void setMouseGrabbed(boolean grabbed) { glfwSetInputMode(handle, GLFW_CURSOR, grabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL); }
}
