package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL30.*;

/**
 * Масив текстур (GL_TEXTURE_2D_ARRAY): кожна текстура - окремий шар. Чому не атлас: у шейдері текстура повторюється для злитих граней (greedy meshing),
 * а міпмапи атласа «підмішують» сусідні плитки по краях; масив цього не має. Шар 0 - біла плитка (блоки без текстури множаться на власний колір),
 * шар 1 - «текстуру не знайдено» (рожево-чорна шашка). Типові текстури - з реєстру (малюються кодом, 16x16), потім перекриваються PNG
 * із теки textures/<простір>/<назва>.png; усе приводиться до size x size (найближчий піксель), тож size = 32/64 дає чіткіші власні пакети.
 */
public final class TextureArray {
    public final int id;
    public final int size;
    private final Map<String, Integer> layers = new HashMap<>();
    public final int count;
    public final List<String> overridden = new ArrayList<>();
    private final List<int[]> pixelData = new ArrayList<>();

    public TextureArray(Map<String, int[][]> textures, int size, File dir) {
        this.size = size;
        List<int[]> pixels = new ArrayList<>();
        pixels.add(scale(voxelin.content.TextureGen.white(), 16, 16, size));
        int[] missing = new int[256];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) missing[x + y * 16] = ((x / 8 + y / 8) % 2 == 0) ? 0xFFFF00FF : 0xFF000000;
        pixels.add(scale(missing, 16, 16, size));
        for (Map.Entry<String, int[][]> e : textures.entrySet()) {
            int[] px = scale(e.getValue()[0], 16, 16, size);
            int[] custom = loadPng(dir, e.getKey());
            if (custom != null) { px = custom; overridden.add(e.getKey()); }
            layers.put(e.getKey(), pixels.size());
            pixels.add(px);
        }
        count = pixels.size();
        pixelData.addAll(pixels);
        ByteBuffer buf = MemoryUtil.memAlloc(size * size * 4 * count);
        try {
            for (int[] px : pixels) for (int c : px) { buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24)); }
            buf.flip();
            id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, id);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, size, size, count, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally { MemoryUtil.memFree(buf); }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR); // чіткі пікселі зблизька, без мерехтіння здалеку
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        System.out.println("[Voxelin] текстури: " + count + " шарів " + size + "x" + size + (overridden.isEmpty() ? "" : ", власні PNG: " + overridden));
    }

    /** Шар для назви текстури; null - біла плитка (блок без текстури); невідома назва - «не знайдено». */
    public int layer(String name) {
        if (name == null) return 0;
        Integer l = layers.get(name);
        if (l == null) { System.out.println("[Voxelin] ⚠️ текстуру не знайдено: " + name); return 1; }
        return l;
    }

    /** Піксельні дані шару (ARGB, size x size) - для атласа іконок інтерфейсу. */
    public int[] pixels(int layer) { return pixelData.get(layer); }

    public void bind() { glBindTexture(GL_TEXTURE_2D_ARRAY, id); }

    public void close() { glDeleteTextures(id); }

    private static int[] scale(int[] src, int sw, int sh, int size) {
        if (sw == size && sh == size) return src;
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) out[x + y * size] = src[Math.min(sw - 1, x * sw / size) + Math.min(sh - 1, y * sh / size) * sw];
        return out;
    }

    /** textures/<простір>/<назва>.png -> size x size ARGB, або null, якщо файла нема чи він битий. */
    private int[] loadPng(File dir, String name) {
        if (dir == null) return null;
        int c = name.indexOf(':');
        String ns = c < 0 ? "voxelin" : name.substring(0, c), n = c < 0 ? name : name.substring(c + 1);
        if (!ns.matches("[a-z0-9_]+") || !n.matches("[a-z0-9_/]+") || n.contains("..")) return null;
        File f = new File(new File(dir, ns), n + ".png");
        if (!f.isFile()) return null;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) throw new java.io.IOException("не PNG");
            int w = img.getWidth(), h = img.getHeight();
            int[] src = img.getRGB(0, 0, w, h, null, 0, w);
            return scale(src, w, h, size);
        } catch (Exception e) {
            System.out.println("[Voxelin] ⚠️ не вдалось прочитати " + f + ": " + e);
            return null;
        }
    }
}
