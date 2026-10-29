package voxelin.client.look;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Малює Cosmetic фіксованим конвеєром у координатах моделі гравця. Bedrock-експорт Blockbench дзеркалить X (права рука на -X) і міняє
 * знак поворотів X та Y - тут це повертається назад у простір Blockbench (перед -Z, права сторона +X), тобто як у редакторі.
 */
public final class CosmeticRenderer implements AutoCloseable {
    private final Map<String, Integer> textures = new HashMap<>();

    private int texture(Cosmetic c) {
        Integer id = textures.get(c.id);
        if (id != null) return id;
        int t = glGenTextures();
        ByteBuffer buf = MemoryUtil.memAlloc(c.texW * c.texH * 4);
        try {
            for (int px : c.texture) buf.put((byte) (px >> 16)).put((byte) (px >> 8)).put((byte) px).put((byte) (px >>> 24));
            buf.flip();
            glBindTexture(GL_TEXTURE_2D, t);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, c.texW, c.texH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally { MemoryUtil.memFree(buf); }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        textures.put(c.id, t);
        return t;
    }

    /** Забути текстуру (косметику перезавантажено з диска). */
    public void forget(String id) { Integer t = textures.remove(id); if (t != null) glDeleteTextures(t); }

    public void draw(Cosmetic c, float light) {
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_ALPHA_TEST); glAlphaFunc(GL_GREATER, 0.1f);
        glBindTexture(GL_TEXTURE_2D, texture(c));
        Map<String, Cosmetic.Bone> byName = new HashMap<>();
        for (Cosmetic.Bone b : c.bones) byName.put(b.name, b);
        for (Cosmetic.Bone b : c.bones) {
            if (b.cubes.isEmpty()) continue;
            glPushMatrix();
            applyBone(b, byName, 0);
            for (Cosmetic.Cube q : b.cubes) cube(c, q, light);
            glPopMatrix();
        }
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_ALPHA_TEST);
        glDisable(GL_TEXTURE_2D);
    }

    /** Повороти кістки разом із батьківськими (спершу батько). */
    private static void applyBone(Cosmetic.Bone b, Map<String, Cosmetic.Bone> byName, int depth) {
        if (b.parent != null && depth < 32) { Cosmetic.Bone p = byName.get(b.parent); if (p != null && p != b) applyBone(p, byName, depth + 1); }
        rotate(b.pivot, b.rotation);
    }

    private static void rotate(float[] pivot, float[] rot) {
        if (rot == null || (rot[0] == 0 && rot[1] == 0 && rot[2] == 0)) return;
        float px = -pivot[0], py = pivot[1], pz = pivot[2];
        glTranslatef(px, py, pz);
        glRotatef(rot[2], 0, 0, 1); glRotatef(-rot[1], 0, 1, 0); glRotatef(-rot[0], 1, 0, 0);
        glTranslatef(-px, -py, -pz);
    }

    private static void cube(Cosmetic c, Cosmetic.Cube q, float light) {
        glPushMatrix();
        if (q.rotation != null) rotate(q.pivot != null ? q.pivot : new float[]{q.origin[0] + q.size[0] / 2, q.origin[1] + q.size[1] / 2, q.origin[2] + q.size[2] / 2}, q.rotation);
        float w = q.size[0], h = q.size[1], d = q.size[2], inf = q.inflate;
        float x0 = -(q.origin[0] + w) - inf, x1 = -q.origin[0] + inf, y0 = q.origin[1] - inf, y1 = q.origin[1] + h + inf, z0 = q.origin[2] - inf, z1 = q.origin[2] + d + inf;
        float tw = c.texW, th = c.texH;
        glBegin(GL_QUADS);
        // грані: {u0,v0,u1,v1} у пікселях; вершини як у PlayerModel.cuboid (перед -Z, схід = +X = права сторона)
        float[] n = uv(q, "north", d, d, w, h), s = uv(q, "south", 2 * d + w, d, w, h), e = uv(q, "east", 0, d, d, h), wv = uv(q, "west", d + w, d, d, h),
                up = uv(q, "up", d, 0, w, d), dn = uv(q, "down", d + w, 0, w, d);
        if (q.mirror && q.boxUv != null) { float[] t = e; e = wv; wv = t; n = flip(n); s = flip(s); e = flip(e); wv = flip(wv); up = flip(up); dn = flip(dn); }
        if (n != null) face(light * 0.85f, n, tw, th, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
        if (s != null) face(light * 0.85f, s, tw, th, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
        if (e != null) face(light * 0.7f, e, tw, th, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
        if (wv != null) face(light * 0.7f, wv, tw, th, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
        if (up != null) face(light, up, tw, th, x1, y1, z1, x0, y1, z1, x0, y1, z0, x1, y1, z0);
        if (dn != null) face(light * 0.5f, dn, tw, th, x1, y0, z0, x0, y0, z0, x0, y0, z1, x1, y0, z1);
        glEnd();
        glPopMatrix();
    }

    /** UV грані: з розгортки «коробкою» (зсув du,dv, розмір fw x fh) або з опису грані; null - грані нема (не намальована в Blockbench). */
    private static float[] uv(Cosmetic.Cube q, String face, float du, float dv, float fw, float fh) {
        if (q.boxUv != null) return new float[]{q.boxUv[0] + du, q.boxUv[1] + dv, q.boxUv[0] + du + fw, q.boxUv[1] + dv + fh};
        float[] f = q.faces.get(face);
        if (f == null) return null;
        return new float[]{f[0], f[1], f[0] + f[2], f[1] + f[3]};
    }

    private static float[] flip(float[] a) { return a == null ? null : new float[]{a[2], a[1], a[0], a[3]}; }

    private static void face(float k, float[] uv, float tw, float th, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        glColor3f(k, k, k);
        float e = 0.02f, u0 = (uv[0] + (uv[0] < uv[2] ? e : -e)) / tw, u1 = (uv[2] + (uv[2] > uv[0] ? -e : e)) / tw, v0 = (uv[1] + (uv[1] < uv[3] ? e : -e)) / th, v1 = (uv[3] + (uv[3] > uv[1] ? -e : e)) / th;
        glTexCoord2f(u0, v0); glVertex3f(ax, ay, az);
        glTexCoord2f(u1, v0); glVertex3f(bx, by, bz);
        glTexCoord2f(u1, v1); glVertex3f(cx, cy, cz);
        glTexCoord2f(u0, v1); glVertex3f(dx, dy, dz);
    }

    @Override public void close() { for (int t : textures.values()) glDeleteTextures(t); textures.clear(); }
}
