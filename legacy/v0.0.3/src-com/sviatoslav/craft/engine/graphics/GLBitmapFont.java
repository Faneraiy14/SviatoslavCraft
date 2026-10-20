package com.sviatoslav.craft.engine.graphics;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.FontMetrics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;

// РЕАЛЬНА фіча (Sviatoslav попросив - "може бути кирилиця в назві
// світу", і "якщо JDK буде виєбуватись - завали їй ебало і заставь
// працювати"): раніше тут був рукописний растровий шрифт (GL_QUADS по
// одному пікселю на гліф), навмисно БЕЗ кирилиці - додавати кожну нову
// літеру означало вручну малювати її 5x7-сітку. Для 33 українських
// літер це вже нерозумно. Замість малювання вручну - СПРАВЖНІЙ системний
// шрифт (java.awt.Font, який на Linux має повне покриття кирилиці через
// DejaVu Sans/аналог), розтеризований ОДИН РАЗ у текстурний атлас
// (BufferedImage -> OpenGL-текстура), і далі кожен символ - це просто
// текстурований квад, не сотні окремих пікселів.
//
// Публічний API (draw(text,x,y,scale,r,g,b)) НЕ ЗМІНЕНО навмисно - усі
// існуючі виклики (F3, меню, інвентар, екрани світів) лишаються
// робочими без жодної правки. Крок символу (6*scale ширина, 7*scale
// висота) теж лишився ТИМ САМИМ, що й у старому шрифті - інакше всі
// розрахунки позицій/центрування в інших класах (наприклад
// `label.length()*6*scale`) довелось би перераховувати заново.
public final class GLBitmapFont {
    private static final int CELL = 32;
    // Латиниця (ВЕЛИКА й МАЛА - Sviatoslav попросив "щоб ще могли бути
    // маленькі", не лише капс)/цифри/пунктуація + повний великий І малий
    // український алфавіт (включно з Ґ/Є/І/Ї, яких у стандартному
    // "просто кирилиця" наборі часто нема).
    private static final String CHARS =
        " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:-=_|" +
        "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ" +
        "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя";
    private static final int COLS = 12;

    private static int textureId = -1;
    private static int atlasW, atlasH;
    private static final Map<Character, int[]> CELL_INDEX = new HashMap<>();

    // Лінива ініціалізація (не в static-блоці) - побудова текстури
    // потребує ДІЙСНОГО OpenGL-контексту (glGenTextures/glTexImage2D), а
    // клас може завантажитись JVM ДО того, як вікно/контекст узагалі
    // створені. Перший виклик draw() завжди йде вже з циклу рендеру,
    // контекст точно активний - той самий принцип, що й лінива побудова
    // display list'ів чанків у World.
    private static void ensureInitialized() {
        if (textureId != -1) return;

        int rows = (CHARS.length() + COLS - 1) / COLS;
        atlasW = COLS * CELL;
        atlasH = rows * CELL;

        BufferedImage img = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "в F3 нічого не
        // розбереш, все крихітне"): 0.75 лишало забагато порожніх полів
        // навколо букви в комірці - на малих scale (F3 використовує
        // scale=2, глиф стискається до ~12x14 екранних пікселів) той
        // запас "з'їдав" половину й так небагатьох реальних пікселів
        // літери. 0.92 - товщі літери, ближче до жирного вигляду
        // старого рукописного шрифту.
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int) (CELL * 0.92));
        g.setFont(font);
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < CHARS.length(); i++) {
            char c = CHARS.charAt(i);
            int col = i % COLS, row = i / COLS;
            CELL_INDEX.put(c, new int[]{col, row});
            if (c == ' ') continue;
            String s = String.valueOf(c);
            int tw = fm.stringWidth(s);
            int cx = col * CELL + (CELL - tw) / 2;
            int cy = row * CELL + (CELL - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(s, cx, cy);
        }
        g.dispose();

        // BufferedImage рядки йдуть згори вниз (Java2D) - завантажуємо
        // РІВНО в тому самому порядку й рахуємо UV так само (v=0 -
        // перший завантажений рядок = верх зображення); жодного
        // перевертання не треба, доки завантаження й вибірка узгоджені
        // між собою.
        int[] pixels = img.getRGB(0, 0, atlasW, atlasH, null, 0, atlasW);
        ByteBuffer buf = BufferUtils.createByteBuffer(atlasW * atlasH * 4);
        for (int p : pixels) {
            buf.put((byte) ((p >> 16) & 0xFF));
            buf.put((byte) ((p >> 8) & 0xFF));
            buf.put((byte) (p & 0xFF));
            buf.put((byte) ((p >> 24) & 0xFF));
        }
        buf.flip();

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "в F3 нічого не
        // розбереш"): на малих scale атлас (32px на комірку) стискається
        // в рендері в рази (F3 - до ~12-14px) - GL_LINEAR без mipmap-ів
        // на такому сильному стисненні семплить лише 2x2 текселі,
        // ігноруючи решту, тонкі штрихи букв просто губились/мерехтіли.
        // Mipmap-и (LINEAR_MIPMAP_LINEAR) усереднюють ЦІЛУ комірку перед
        // стисненням - той самий принцип, що в будь-якій грі для дрібного
        // тексту/текстур здаля.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasW, atlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        org.lwjgl.opengl.GL30.glGenerateMipmap(GL_TEXTURE_2D);
    }

    // 2D-текст в ортографічній проєкції (викликач вже мусить бути в
    // ортопроєкції, як DebugOverlay/меню/екрани світів). r/g/b фарбує
    // гліфи (атлас сам - білий на прозорому, GL_MODULATE - дефолтний
    // texture env mode - множить колір і альфу текстури на glColor).
    public static void draw(String text, float x, float y, float scale, float r, float g, float b) {
        ensureInitialized();
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glColor4f(r, g, b, 1f);

        float glyphW = 6 * scale, glyphH = 7 * scale;
        float cursorX = x;
        glBegin(GL_QUADS);
        for (char c : text.toCharArray()) {
            // Регістр тепер зберігається (Sviatoslav попросив) - шрифт
            // реальний, малі й великі літери мають ОКРЕМІ клітинки в
            // атласі, примусового toUpperCase тут більше нема.
            int[] cell = CELL_INDEX.get(c);
            if (cell == null) { cursorX += glyphW; continue; }
            float u0 = cell[0] * (float) CELL / atlasW, v0 = cell[1] * (float) CELL / atlasH;
            float u1 = u0 + (float) CELL / atlasW, v1 = v0 + (float) CELL / atlasH;
            glTexCoord2f(u0, v0); glVertex2f(cursorX, y);
            glTexCoord2f(u1, v0); glVertex2f(cursorX + glyphW, y);
            glTexCoord2f(u1, v1); glVertex2f(cursorX + glyphW, y + glyphH);
            glTexCoord2f(u0, v1); glVertex2f(cursorX, y + glyphH);
            cursorX += glyphW;
        }
        glEnd();

        glDisable(GL_BLEND);
        glDisable(GL_TEXTURE_2D);
    }
}
