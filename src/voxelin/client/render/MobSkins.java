package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;
import voxelin.api.MobSettings;
import voxelin.core.MobType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Текстури істот: кожна частина моделі отримує плитку 16x16, згенеровану з її кольору й візерунка (стабільно для виду - зерно з назви),
 * головна частина голови - ще й плитку обличчя (очі, рот) для передньої грані. Усе в одній 2D-текстурі (атлас).
 * Свій PNG замінює згенеровану плитку: textures/<простір>/mobs/<моб>_<номер частини>.png, обличчя - <моб>_<номер>_face.png
 * (номер - порядок частини в описі, з 0). Генерація (generate) - чиста функція без GL, тож тестується окремо.
 */
public final class MobSkins {
    public static final int T = 16, COLS = 32;
    private final int id, rows;
    /** Вид -> для кожної частини {плитка, плитка обличчя або -1}. */
    private final Map<MobType, int[][]> tiles = new IdentityHashMap<>();

    public MobSkins(Collection<MobType> types, File dir) {
        List<int[]> pix = new ArrayList<>();
        for (MobType t : types) {
            int[][] tt = new int[t.parts.size()][];
            for (int i = 0; i < t.parts.size(); i++) {
                MobSettings.Part p = t.parts.get(i);
                int[] own = load(dir, t.name, i, false);
                pix.add(own != null ? own : generate(t, i, false));
                int face = -1;
                if (p.face) { int[] of = load(dir, t.name, i, true); pix.add(of != null ? of : generate(t, i, true)); face = pix.size() - 1; }
                tt[i] = new int[]{face >= 0 ? pix.size() - 2 : pix.size() - 1, face};
            }
            tiles.put(t, tt);
        }
        rows = Math.max(1, (pix.size() + COLS - 1) / COLS);
        int w = COLS * T, h = rows * T;
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int k = (y / T) * COLS + x / T;
                int c = k < pix.size() ? pix.get(k)[(x % T) + (y % T) * T] : 0;
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

    public void bind() { glBindTexture(GL_TEXTURE_2D, id); }
    public void close() { glDeleteTextures(id); }

    /** Плитка частини (face - плитка обличчя) або -1 (вид з'явився після створення атласа). */
    public int tile(MobType t, int part, boolean face) {
        int[][] tt = tiles.get(t);
        if (tt == null || part >= tt.length) return -1;
        return tt[part][face ? 1 : 0];
    }

    /** Текстурні координати плитки: {u0, v0, u1, v1} для частки (fw, fh) 0..1 її розміру (від лівого верхнього кута). */
    public void uv(int tile, float fw, float fh, float[] out) {
        float tw = 1f / COLS, th = 1f / rows;
        float u0 = (tile % COLS) * tw, v0 = (tile / COLS) * th;
        out[0] = u0; out[1] = v0; out[2] = u0 + tw * fw; out[3] = v0 + th * fh;
    }

    // ------------------------------------------------------------------ генерація (без GL)

    /** Плитка 16x16 ARGB для частини part виду t (face - обличчя на ній). */
    public static int[] generate(MobType t, int part, boolean face) {
        MobSettings.Part p = t.parts.get(part);
        Random r = new Random(t.name.hashCode() * 31L + part * 7919L); // обличчя - на тій самій шкірі
        int[] px = new int[T * T];
        String pat = p.pattern == null ? "" : p.pattern;
        for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
            double k;
            switch (pat) {
                case "fur": k = 1 + (r.nextDouble() - 0.5) * 0.24 - ((x * 7 + y / 3) % 5 == 0 ? 0.12 : 0); break; // ворс - темніші смужки
                case "skin": k = 1 + (r.nextDouble() - 0.5) * 0.08; break;
                case "bone": k = 1 + (r.nextDouble() - 0.5) * 0.06 - (y % 5 == 4 ? 0.18 : 0) - (x == 0 || x == 15 ? 0.08 : 0); break; // поперечні шви
                case "cloth": k = 1 + ((x + y) % 2 == 0 ? 0.05 : -0.05) + (r.nextDouble() - 0.5) * 0.04 - (y == 15 ? 0.2 : 0); break; // переплетення й підгин
                case "leather": k = 1 + (r.nextDouble() - 0.5) * 0.14 - ((x == 1 || x == 14 || y == 1 || y == 14) && (x + y) % 2 == 0 ? 0.25 : 0); break; // шов по краю
                case "scale": { int row = y / 3, sx = (x + (row % 2) * 2) % 4; k = 1 + (r.nextDouble() - 0.5) * 0.06 - (y % 3 == 2 || sx == 0 ? 0.2 : 0) + (sx == 2 && y % 3 == 0 ? 0.1 : 0); break; }
                case "shell": k = 1 + (r.nextDouble() - 0.5) * 0.06 + ((x % 5 == 0 || y % 5 == 0) ? -0.25 : 0.06); break; // пластини
                case "stone": k = 1 + (r.nextDouble() - 0.5) * 0.2 - (r.nextInt(9) == 0 ? 0.2 : 0); break;
                case "glow": k = 0.9 + 0.25 * Math.sin((x + y) * 0.8) * Math.sin(y * 0.6) + (r.nextDouble() - 0.5) * 0.06; break;
                default: k = 1 + (r.nextDouble() - 0.5) * 0.06;
            }
            px[x + y * T] = shade(p.rgb, k);
        }
        if (face) drawFace(px, p.rgb, t.eyes);
        return px;
    }

    /** Очі (2x2 з білим відблиском) і рот на плитці (верх плитки - верх голови). */
    private static void drawFace(int[] px, int skin, int eyes) {
        int eye = eyes < 0 ? 0x1A1A1A : eyes, white = eyes < 0 ? 0xF0F0F0 : shade(eyes, 1.4);
        for (int[] e : new int[][]{{3, 6}, {11, 6}}) {
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) px[(e[0] + dx) + (e[1] + dy) * T] = 0xFF000000 | eye;
            px[(e[0] + (e[0] < 8 ? 1 : 0)) + e[1] * T] = 0xFF000000 | white; // відблиск зсередини
        }
        int mouth = shade(skin, 0.45);
        for (int x = 6; x <= 9; x++) px[x + 11 * T] = mouth;
        px[5 + 11 * T] = shade(skin, 0.7); px[10 + 11 * T] = shade(skin, 0.7); // рівний рот, без усмішки
    }

    private static int shade(int rgb, double k) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * k)), g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * k)), b = (int) Math.max(0, Math.min(255, (rgb & 255) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Свій PNG частини (будь-який розмір - масштабується до 16x16) або null. */
    public static int[] load(File dir, String mob, int part, boolean face) {
        if (dir == null) return null;
        int c = mob.indexOf(':');
        String ns = c < 0 ? "voxelin" : mob.substring(0, c), n = c < 0 ? mob : mob.substring(c + 1);
        if (!ns.matches("[a-z0-9_]+") || !n.matches("[a-z0-9_]+")) return null;
        File f = new File(new File(new File(dir, ns), "mobs"), n + "_" + part + (face ? "_face" : "") + ".png");
        if (!f.isFile()) return null;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) throw new java.io.IOException("не PNG");
            int w = img.getWidth(), h = img.getHeight();
            int[] src = img.getRGB(0, 0, w, h, null, 0, w), out = new int[T * T];
            for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) out[x + y * T] = src[Math.min(w - 1, x * w / T) + Math.min(h - 1, y * h / T) * w];
            return out;
        } catch (Exception e) {
            System.out.println("[Voxelin] ⚠️ не вдалось прочитати " + f + ": " + e);
            return null;
        }
    }
}
