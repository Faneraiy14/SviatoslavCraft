package voxelin.client.render;

import voxelin.client.ui.Batch2D;
import org.lwjgl.system.MemoryUtil;
import voxelin.core.*;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Іконки інтерфейсу: усі текстури (ті самі пікселі, що в масиві текстур - з урахуванням розміру 16/32/64 і власних PNG) в одній 2D-текстурі.
 * Малюється текстурованими квадами у фіксованому конвеєрі інтерфейсу. Блоки-предмети - «ізометричний» кубик (верх + два боки), решта - пласка іконка.
 */
public final class IconAtlas {
    private static final int COLS = 16;
    private final int id, tile, rows;
    private final TextureArray tex;
    private final World world;
    private final Map<String, int[]> cache = new HashMap<>(); // назва предмета -> {режим 0 пласка/1 куб/2 колір, шар1, шар2}

    public IconAtlas(TextureArray tex, World world) {
        this.tex = tex; this.world = world; this.tile = tex.size;
        rows = (tex.count + COLS - 1) / COLS;
        int w = COLS * tile, h = rows * tile;
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int layer = (y / tile) * COLS + (x / tile);
                int c = layer < tex.count ? tex.pixels(layer)[(x % tile) + (y % tile) * tile] : 0;
                buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
            }
            buf.flip();
            id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally { MemoryUtil.memFree(buf); }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void close() { glDeleteTextures(id); }

    /** Як малювати предмет: {0 пласка | 1 куб | 2 лише колір, шар або верх, бік}. */
    private int[] mode(String item) {
        return cache.computeIfAbsent(item, k -> {
            ItemType t = world.itemTypes.get(k);
            if (t == null) return new int[]{2, 0, 0};
            if (t.placesBlock != null) {
                BlockType b = world.blocks.byName(t.placesBlock);
                if (b != null && !(b.shape != null && !"table".equals(b.shape) && t.texture != null)) { // дошка, смолоскип - пласка іконка; стіл - кубиком
                    String top = b.texTop != null ? b.texTop : b.texSide, side = b.texSide != null ? b.texSide : b.texTop;
                    if (top != null) return new int[]{1, tex.layer(top), tex.layer(side)};
                }
            }
            if (t.texture != null) return new int[]{0, tex.layer(t.texture), 0};
            return new int[]{2, 0, 0};
        });
    }

    /** Намалювати предмет у квадраті size x size з лівим верхнім кутом (x,y). */
    public void draw(String item, float x, float y, float size) {
        int[] m = mode(item);
        if (m[0] == 2) {
            ItemType t = world.itemTypes.get(item);
            int rgb = t == null ? 0xFF00FF : t.color;
            Batch2D.rect(x + size * 0.15f, y + size * 0.15f, size * 0.7f, size * 0.7f, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
            return;
        }
        if (m[0] == 0) quad(m[1], 1f, x, y, x + size, y, x + size, y + size, x, y + size);
        else {
            float cx = x + size / 2, h = size * 0.5f, q = size * 0.25f, y0 = y + size * 0.06f;
            // верх (ромб), лівий і правий боки; текстурні координати - паралелограми
            quadIso(m[1], 1.0f, cx, y0, x + size, y0 + q, cx, y0 + h, x, y0 + q);
            quad(m[2], 0.78f, x, y0 + q, cx, y0 + h, cx, y0 + h + h, x, y0 + q + h);
            quad(m[2], 0.55f, cx, y0 + h, x + size, y0 + q, x + size, y0 + q + h, cx, y0 + h + h);
        }
    }

    private float u(int layer, float f) { return ((layer % COLS) + f) / COLS; }
    private float v(int layer, float f) { return ((layer / COLS) + f) / rows; }

    /** Квад із вершинами (за годинниковою від лівого верхнього) і текстурою [0,1]^2. */
    private void quad(int layer, float shade, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        Batch2D.quad(id, x0, y0, u(layer, 0), v(layer, 0), x1, y1, u(layer, 1), v(layer, 0), x2, y2, u(layer, 1), v(layer, 1), x3, y3, u(layer, 0), v(layer, 1), shade, shade, shade, 1f);
    }

    /** Верхня грань: ромб із вершинами T,R,B,L; текстура лягає «ромбом» (лівий верх - у T). */
    private void quadIso(int layer, float shade, float tx, float ty, float rx, float ry, float bx, float by, float lx, float ly) {
        Batch2D.quad(id, tx, ty, u(layer, 0), v(layer, 0), rx, ry, u(layer, 1), v(layer, 0), bx, by, u(layer, 1), v(layer, 1), lx, ly, u(layer, 0), v(layer, 1), shade, shade, shade, 1f);
    }
}
