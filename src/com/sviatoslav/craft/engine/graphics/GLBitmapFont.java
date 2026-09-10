package com.sviatoslav.craft.engine.graphics;

import java.util.HashMap;
import java.util.Map;
import static org.lwjgl.opengl.GL11.*;

// Растровий шрифт 5x7 через immediate-mode GL_QUADS (той самий підхід, що
// й Font5x7 у Echo Strategy, лише замальовується не через X11-протокол, а
// напряму OpenGL-квадратами). НАВІЩО ВЗАГАЛІ: чернетка DeepSeek у
// DebugOverlay малювала для кожного рядка суцільну білу смугу замість
// РЕАЛЬНИХ чисел (координат/FPS) - F3 нічого корисного насправді не
// показував. Тепер текст справжній.
public final class GLBitmapFont {
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put('0', new String[]{".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."});
        GLYPHS.put('1', new String[]{"..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."});
        GLYPHS.put('2', new String[]{".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"});
        GLYPHS.put('3', new String[]{"####.", "....#", "....#", "..##.", "....#", "....#", "####."});
        GLYPHS.put('4', new String[]{"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."});
        GLYPHS.put('5', new String[]{"#####", "#....", "####.", "....#", "....#", "#...#", ".###."});
        GLYPHS.put('6', new String[]{"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."});
        GLYPHS.put('7', new String[]{"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."});
        GLYPHS.put('8', new String[]{".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."});
        GLYPHS.put('9', new String[]{".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."});
        GLYPHS.put('-', new String[]{".....", ".....", ".....", "#####", ".....", ".....", "....."});
        GLYPHS.put('.', new String[]{".....", ".....", ".....", ".....", ".....", "..#..", "....."});
        GLYPHS.put(':', new String[]{".....", "..#..", ".....", ".....", "..#..", ".....", "....."});
        GLYPHS.put(' ', new String[]{".....", ".....", ".....", ".....", ".....", ".....", "....."});
        GLYPHS.put('X', new String[]{"#...#", ".#.#.", "..#..", "..#..", "..#..", ".#.#.", "#...#"});
        GLYPHS.put('Y', new String[]{"#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..", "..#.."});
        GLYPHS.put('Z', new String[]{"#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"});
        GLYPHS.put('A', new String[]{".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"});
        GLYPHS.put('W', new String[]{"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"});
        GLYPHS.put('F', new String[]{"#####", "#....", "#....", "###..", "#....", "#....", "#...."});
        GLYPHS.put('P', new String[]{"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."});
        GLYPHS.put('S', new String[]{".####", "#....", "#....", ".###.", "....#", "....#", "####."});
        GLYPHS.put('B', new String[]{"####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."});
        GLYPHS.put('L', new String[]{"#....", "#....", "#....", "#....", "#....", "#....", "#####"});
        GLYPHS.put('O', new String[]{".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."});
        GLYPHS.put('C', new String[]{".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."});
        GLYPHS.put('K', new String[]{"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"});
        GLYPHS.put('I', new String[]{".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."});
        GLYPHS.put('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."});
        GLYPHS.put('H', new String[]{"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"});
        GLYPHS.put('V', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."});
        GLYPHS.put('R', new String[]{"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"});
        GLYPHS.put('E', new String[]{"#####", "#....", "#....", "###..", "#....", "#....", "#####"});
        GLYPHS.put('G', new String[]{".###.", "#....", "#.###", "#...#", "#...#", "#...#", ".###."});
        GLYPHS.put('D', new String[]{"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."});
        GLYPHS.put('N', new String[]{"#...#", "##..#", "#.#.#", "#.#.#", "#..##", "#...#", "#...#"});
        GLYPHS.put('M', new String[]{"#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"});
        GLYPHS.put(',', new String[]{".....", ".....", ".....", ".....", ".....", "..#..", ".#..."});
        GLYPHS.put('=', new String[]{".....", ".....", "#####", ".....", "#####", ".....", "....."});
    }

    // 2D-текст в ортографічній проєкції (викликач вже мусить бути в
    // ортопроєкції, як DebugOverlay нижче) - кожна "точка" гліфа - окремий
    // невеликий GL_QUADS.
    public static void draw(String text, float x, float y, float scale, float r, float g, float b) {
        glColor3f(r, g, b);
        float cursorX = x;
        for (char raw : text.toCharArray()) {
            char c = Character.toUpperCase(raw);
            String[] rows = GLYPHS.get(c);
            if (rows == null) { cursorX += 6 * scale; continue; }
            for (int row = 0; row < rows.length; row++) {
                String line = rows[row];
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) != '#') continue;
                    float px = cursorX + col * scale;
                    float py = y + row * scale;
                    glBegin(GL_QUADS);
                    glVertex2f(px, py);
                    glVertex2f(px + scale, py);
                    glVertex2f(px + scale, py + scale);
                    glVertex2f(px, py + scale);
                    glEnd();
                }
            }
            cursorX += 6 * scale;
        }
    }
}
