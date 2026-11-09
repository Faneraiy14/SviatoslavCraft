package voxelin.client.ui;

import org.lwjgl.BufferUtils;
import voxelin.client.render.Shader;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Пакетне малювання інтерфейсу: прямокутники, текст, іконки й мінікарта складаються в один масив вершин і йдуть на відеокарту
 * одним glDrawArrays (VBO + маленький шейдер), а не тисячами glRectf/glBegin. Раніше інтерфейс брав ~4 мс кадру на Intel HD 4400
 * (іконки сердець - це сотні «пікселів»-прямокутників).
 *
 * Порядок малювання зберігається: пакет скидається (flush) при зміні текстури, у Gui.end() і перед будь-яким прямим GL
 * (модель скіна в меню, поворот сплеша) - Gui.flush(). Матриці беруться з фіксованого конвеєра в момент flush
 * (gl_ModelViewProjectionMatrix), тож хто міняє матрицю посеред інтерфейсу, мусить спершу викликати Gui.flush().
 */
public final class Batch2D {
    private Batch2D() {}

    /** x, y, u, v, r, g, b, a, слот (0 - лише колір, 1 - поточна текстура на блоці 0, 2 - шрифт на блоці 1) */
    private static final int FLOATS = 9, MAX_VERTS = 6 * 8192;
    private static FloatBuffer buf;
    /** Шрифт сидить на окремому текстурному блоці постійно: іконки й підписи (цифри кількості) чергуються без скидання пакета. */
    private static int verts, texture, fontTexture, vao, vbo, uSampler, uFont;
    private static float slot;
    private static Shader shader;
    /** Скільки разів за кадр пакет пішов на відеокарту (для F3/бенча). */
    public static int flushes, lastFlushes;

    private static final String VS = """
            #version 120
            attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor; attribute float aSlot;
            varying vec2 vUv; varying vec4 vColor; varying float vSlot;
            void main() { vUv = aUv; vColor = aColor; vSlot = aSlot; gl_Position = gl_ModelViewProjectionMatrix * vec4(aPos, 0.0, 1.0); }
            """;
    private static final String FS = """
            #version 120
            uniform sampler2D uSampler; uniform sampler2D uFont;
            varying vec2 vUv; varying vec4 vColor; varying float vSlot;
            void main() { vec4 t = vSlot < 0.5 ? vec4(1.0) : vSlot < 1.5 ? texture2D(uSampler, vUv) : texture2D(uFont, vUv); gl_FragColor = t * vColor; }
            """;

    private static void init() {
        if (shader != null) return;
        shader = new Shader(VS, FS, "aPos", "aUv", "aColor", "aSlot");
        uSampler = shader.uniform("uSampler"); uFont = shader.uniform("uFont");
        buf = BufferUtils.createFloatBuffer(MAX_VERTS * FLOATS);
        vao = glGenVertexArrays(); vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTS * FLOATS * 4, GL_STREAM_DRAW);
        int stride = FLOATS * 4;
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 8);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 16);
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 32);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Текстура шрифту (GLBitmapFont реєструє один раз). */
    public static void setFont(int tex) { flush(); fontTexture = tex; }

    /** Перейти на текстуру (0 - лише колір, шрифт - свій блок). Інша звичайна текстура - спершу скинути накопичене. */
    private static void use(int tex) {
        if (tex == 0) slot = 0;
        else if (tex == fontTexture) slot = 2;
        else { if (tex != texture) { flush(); texture = tex; } slot = 1; }
        if (verts + 6 > MAX_VERTS) flush();
    }

    private static void vertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        buf.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a).put(slot);
        verts++;
    }

    /** Чотирикутник (вершини за годинниковою від лівого верхнього) з текстурними координатами; tex 0 - без текстури. */
    public static void quad(int tex, float x0, float y0, float u0, float v0, float x1, float y1, float u1, float v1,
                            float x2, float y2, float u2, float v2, float x3, float y3, float u3, float v3, float r, float g, float b, float a) {
        if (buf == null) init();
        use(tex);
        vertex(x0, y0, u0, v0, r, g, b, a); vertex(x1, y1, u1, v1, r, g, b, a); vertex(x2, y2, u2, v2, r, g, b, a);
        vertex(x0, y0, u0, v0, r, g, b, a); vertex(x2, y2, u2, v2, r, g, b, a); vertex(x3, y3, u3, v3, r, g, b, a);
    }

    /** Прямокутник кольором. */
    public static void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        quad(0, x, y, 0, 0, x + w, y, 0, 0, x + w, y + h, 0, 0, x, y + h, 0, 0, r, g, b, a);
    }

    /** Прямокутник із частиною текстури (u0,v0)-(u1,v1). */
    public static void image(int tex, float x, float y, float w, float h, float u0, float v0, float u1, float v1, float r, float g, float b, float a) {
        quad(tex, x, y, u0, v0, x + w, y, u1, v0, x + w, y + h, u1, v1, x, y + h, u0, v1, r, g, b, a);
    }

    /** Трикутник кольором. */
    public static void triangle(float x0, float y0, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        if (buf == null) init();
        use(0);
        if (verts + 3 > MAX_VERTS) flush();
        vertex(x0, y0, 0, 0, r, g, b, a); vertex(x1, y1, 0, 0, r, g, b, a); vertex(x2, y2, 0, 0, r, g, b, a);
    }

    /** Віддати накопичене відеокарті одним викликом. Стан GL після - як до (програма 0, VAO 0, текстура 0). */
    public static void flush() {
        if (verts == 0) return;
        buf.flip();
        glUseProgram(shader.id);
        glUniform1i(uSampler, 0); glUniform1i(uFont, 1);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, fontTexture);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW); // новий буфер рівно під дані - драйвер не чекає, поки GPU дочитає старий
        glDrawArrays(GL_TRIANGLES, 0, verts);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, 0); glActiveTexture(GL_TEXTURE0);
        glUseProgram(0);
        buf.clear();
        verts = 0;
        flushes++;
    }

    /** Кінець кадру: лічильник скидань за кадр. */
    public static void endFrame() { flush(); lastFlushes = flushes; flushes = 0; }
}
