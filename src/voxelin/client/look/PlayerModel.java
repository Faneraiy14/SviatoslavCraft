package voxelin.client.look;

import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Модель гравця з кубоїдів у пікселях скіна (зріст 32 px; ноги з 0, голова 24..32), обличчям до -Z, як істоти. Текстура - скін
 * Minecraft 64x64 з другим шаром (каптур, куртка, рукави, штанини - трохи більші й з прозорістю). Плащ і крила Шву - окремо.
 * Малює фіксованим конвеєром; викликач ставить матрицю (переніс у ноги, масштаб пікселя, поворот тіла).
 */
public final class PlayerModel implements AutoCloseable {
    private int skinTex, capeTex;
    private boolean hasCape;
    private PlayerLook loaded;
    private final File skinsDir, capesDir;
    /** Скін, переданий лаунчером (--skin); показується в списку як «з лаунчера». */
    public File launcherSkin;

    public PlayerModel(File skinsDir, File capesDir) { this.skinsDir = skinsDir; this.capesDir = capesDir; }

    /** Поза: куди дивиться голова (відносно тіла), фаза кроку й сила розмаху, крила, нахил плаща. */
    public static final class Pose {
        public float headPitch, headYaw, limbPhase, limbAmount, capeLift, light = 1f;
        public boolean wings, gliding;
    }

    /** Перезавантажити текстури, якщо вигляд змінився (перший виклик - завжди). */
    public void apply(PlayerLook look) {
        if (look.equals(loaded)) return;
        loaded = look.copy();
        int[] skin = null;
        if (look.skin.equals("launcher") && launcherSkin != null) skin = SkinImages.readSkin(launcherSkin);
        else if (look.skin.startsWith("file:")) skin = SkinImages.readSkin(new File(skinsDir, look.skin.substring(5)));
        else if (look.skin.startsWith("builtin:")) skin = SkinImages.builtin(look.skin.substring(8));
        if (skin == null) skin = SkinImages.builtin("traveler");
        skinTex = upload(skinTex, skin, 64, 64);
        int[] cape = null;
        if (look.cape.startsWith("file:")) cape = SkinImages.readCape(new File(capesDir, look.cape.substring(5)));
        else if (!look.cape.equals("none")) cape = SkinImages.cape(look.cape);
        hasCape = cape != null;
        if (hasCape) capeTex = upload(capeTex, cape, 64, 32);
    }

    private static int upload(int id, int[] argb, int w, int h) {
        if (id == 0) id = glGenTextures();
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int c : argb) buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
            buf.flip();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally { MemoryUtil.memFree(buf); }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return id;
    }

    /** Намалювати в поточній матриці (одиниця = піксель скіна, початок - між ступнями). */
    public void draw(Pose p, java.util.function.Consumer<String> attach) {
        if (skinTex == 0) return;
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_ALPHA_TEST); glAlphaFunc(GL_GREATER, 0.1f);
        glDisable(GL_CULL_FACE);
        glBindTexture(GL_TEXTURE_2D, skinTex);
        float sw = (float) Math.sin(p.limbPhase) * 50f * p.limbAmount;
        // ноги (обертання в стегні y=12)
        limb(0, 16, 0, 32, 2, 12, sw, p.light, 64);    // права (+x)
        limb(16, 48, 0, 48, -2, 12, -sw, p.light, 64); // ліва
        // тулуб
        part(16, 16, 16, 32, -4, 12, -2, 8, 12, 4, p.light, 64);
        // руки (плече y=22)
        float armX = p.gliding ? 0 : 1;
        limbArm(40, 16, 40, 32, 6, -sw * armX, p.gliding ? -20 : 0, p.light);
        limbArm(32, 48, 48, 48, -6, sw * armX, p.gliding ? 20 : 0, p.light);
        // голова (шия y=24)
        glPushMatrix();
        glTranslatef(0, 24, 0); glRotatef(-p.headYaw, 0, 1, 0); glRotatef(-p.headPitch, 1, 0, 0); glTranslatef(0, -24, 0);
        cuboid(0, 0, -4, 24, -4, 8, 8, 8, 0, p.light, 64);
        cuboid(32, 0, -4, 24, -4, 8, 8, 8, 0.5f, p.light, 64);
        if (attach != null) attach.accept("head"); // косметика на голові (у координатах голови)
        glEnable(GL_TEXTURE_2D);
        glPopMatrix();
        if (hasCape) {
            glBindTexture(GL_TEXTURE_2D, capeTex);
            glPushMatrix();
            glTranslatef(0, 24, 2.3f); glRotatef(-(6 + p.capeLift), 1, 0, 0); glRotatef(180, 0, 1, 0); // плащ відходить від спини назад
            cuboid(0, 0, -5, -16, -1, 10, 16, 1, 0, p.light, 32);
            glPopMatrix();
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_ALPHA_TEST);
        if (attach != null) attach.accept("body");
        if (p.wings) wings(p);
        glEnable(GL_CULL_FACE);
    }

    private void part(int u, int v, int u2, int v2, float x, float y, float z, int w, int h, int d, float light, int texH) {
        cuboid(u, v, x, y, z, w, h, d, 0, light, texH);
        cuboid(u2, v2, x, y, z, w, h, d, 0.25f, light, texH);
    }

    private void limb(int u, int v, int u2, int v2, float cx, float pivot, float angle, float light, int texH) {
        glPushMatrix();
        glTranslatef(0, pivot, 0); glRotatef(angle, 1, 0, 0); glTranslatef(0, -pivot, 0);
        part(u, v, u2, v2, cx - 2, 0, -2, 4, 12, 4, light, texH);
        glPopMatrix();
    }

    private void limbArm(int u, int v, int u2, int v2, float cx, float angle, float roll, float light) {
        glPushMatrix();
        glTranslatef(cx, 22, 0); glRotatef(angle, 1, 0, 0); glRotatef(roll, 0, 0, 1); glTranslatef(-cx, -22, 0);
        part(u, v, u2, v2, cx - 2, 12, -2, 4, 12, 4, light, 64);
        glPopMatrix();
    }

    /**
     * Кубоїд з текстурою за розгорткою Minecraft. (x,y,z) - мінімальний кут у пікселях, w x h x d - розмір (x, y, z), inflate - роздуття
     * (другий шар). Перед - грань -Z; права сторона гравця - +X.
     */
    static void cuboid(int u, int v, float x, float y, float z, int w, int h, int d, float inflate, float light, int texH) {
        float x0 = x - inflate, y0 = y - inflate, z0 = z - inflate, x1 = x + w + inflate, y1 = y + h + inflate, z1 = z + d + inflate;
        float tw = 64f, th = texH;
        glBegin(GL_QUADS);
        // перед (-z): u зліва = +x (права рука гравця, ліворуч від глядача)
        face(light * 0.85f, (u + d) / tw, (v + d) / th, (u + d + w) / tw, (v + d + h) / th, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
        // зад (+z)
        face(light * 0.85f, (u + 2 * d + w) / tw, (v + d) / th, (u + 2 * d + 2 * w) / tw, (v + d + h) / th, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
        // права сторона (+x): правий край текстури - біля переду
        face(light * 0.7f, u / tw, (v + d) / th, (u + d) / tw, (v + d + h) / th, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
        // ліва сторона (-x)
        face(light * 0.7f, (u + d + w) / tw, (v + d) / th, (u + 2 * d + w) / tw, (v + d + h) / th, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
        // верх: нижній край області - біля переду
        face(light, (u + d) / tw, v / th, (u + d + w) / tw, (v + d) / th, x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0);
        // низ
        face(light * 0.5f, (u + d + w) / tw, v / th, (u + d + 2 * w) / tw, (v + d) / th, x1, y0, z0, x0, y0, z0, x0, y0, z1, x1, y0, z1);
        glEnd();
    }

    /** Чотирикутник: (u0,v0) - лівий верх текстури біля першої вершини, далі за годинниковою: верх-право, низ-право, низ-ліво. */
    private static void face(float k, float u0, float v0, float u1, float v1, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        glColor3f(k, k, k);
        float e = 0.02f / 64; // крихітний відступ: на межі області не брати сусідній піксель (інакше пунктир по краях другого шару)
        u0 += u0 < u1 ? e : -e; u1 += u1 > u0 ? -e : e; v0 += e; v1 -= e;
        glTexCoord2f(u0, v0); glVertex3f(ax, ay, az);
        glTexCoord2f(u1, v0); glVertex3f(bx, by, bz);
        glTexCoord2f(u1, v1); glVertex3f(cx, cy, cz);
        glTexCoord2f(u0, v1); glVertex3f(dx, dy, dz);
    }

    /** Крила Шву на спині: дві фіолетові перетинки; під час польоту розкриті ширше. */
    private void wings(Pose p) {
        float spread = p.gliding ? 70 : 20;
        for (int side = -1; side <= 1; side += 2) {
            glPushMatrix();
            glTranslatef(side * 1.5f, 22, 2.5f); glRotatef(side * spread, 0, 1, 0); glRotatef(side * 10, 0, 0, 1);
            box(side > 0 ? 0 : -14, -14, 0, 14, 16, 1, 0.36f * p.light, 0.2f * p.light, 0.55f * p.light);
            box(side > 0 ? 0 : -14, 0, 0, 14, 2, 1.4f, 0.75f * p.light, 0.5f * p.light, 1f * p.light);
            glPopMatrix();
        }
    }

    /** Одноколірна коробка (x,y,z - мінімальний кут) з тінню граней. */
    public static void box(float x, float y, float z, float w, float h, float d, float r, float g, float b) {
        float x1 = x + w, y1 = y + h, z1 = z + d;
        glBegin(GL_QUADS);
        glColor3f(r, g, b); glVertex3f(x, y1, z); glVertex3f(x, y1, z1); glVertex3f(x1, y1, z1); glVertex3f(x1, y1, z);
        glColor3f(r * 0.5f, g * 0.5f, b * 0.5f); glVertex3f(x, y, z); glVertex3f(x1, y, z); glVertex3f(x1, y, z1); glVertex3f(x, y, z1);
        glColor3f(r * 0.85f, g * 0.85f, b * 0.85f); glVertex3f(x, y, z); glVertex3f(x, y1, z); glVertex3f(x1, y1, z); glVertex3f(x1, y, z);
        glVertex3f(x, y, z1); glVertex3f(x1, y, z1); glVertex3f(x1, y1, z1); glVertex3f(x, y1, z1);
        glColor3f(r * 0.7f, g * 0.7f, b * 0.7f); glVertex3f(x, y, z); glVertex3f(x, y, z1); glVertex3f(x, y1, z1); glVertex3f(x, y1, z);
        glVertex3f(x1, y, z); glVertex3f(x1, y1, z); glVertex3f(x1, y1, z1); glVertex3f(x1, y, z1);
        glEnd();
    }

    public PlayerLook loaded() { return loaded; }

    @Override public void close() {
        if (skinTex != 0) glDeleteTextures(skinTex);
        if (capeTex != 0) glDeleteTextures(capeTex);
        skinTex = capeTex = 0; loaded = null;
    }
}
