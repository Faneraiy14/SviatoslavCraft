package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;
import voxelin.core.*;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Частинки (лише клієнт, лише вигляд): уламки блока при ламанні й ударах - шматочки його ж текстури; іскри удару по істоті;
 * вогник і дим над смолоскипами, лавою й палаючими істотами; бризки дощу. Фізика проста: гравітація, опір повітря, зупинка
 * на суцільному блоці. Малювання - один буфер на кадр, квадрати повернуті до камери (CPU), шейдер бере шар масиву текстур.
 * Координати - як у WorldRenderer: блоки відносно чанка камери, тож числа малі на будь-якій відстані.
 */
public final class Particles {
    public static final int MAX = 3000;
    private static final int FLOATS = 9; // x y z, u v, шар, світло, колір (RGBA у float-бітах), альфа-відсікання
    private static final String VS = """
            #version 130
            in vec3 aPos; in vec2 aUv; in float aLayer; in float aLight; in vec4 aColor;
            uniform mat4 uProjView; uniform float uScale; uniform vec3 uCam;
            out vec2 vUv; flat out float vLayer; out float vLight; out vec4 vColor; out float vDist;
            void main() { vUv = aUv; vLayer = aLayer + 0.5; vLight = aLight; vColor = aColor; vec3 w = aPos * uScale; vDist = length(w - uCam); gl_Position = uProjView * vec4(w, 1.0); }
            """;
    private static final String FS = """
            #version 130
            in vec2 vUv; flat in float vLayer; in float vLight; in vec4 vColor; in float vDist;
            uniform sampler2DArray uTex; uniform vec3 uFogColor; uniform float uFogStart; uniform float uFogEnd;
            out vec4 fragColor;
            void main() {
                vec4 t = texture(uTex, vec3(vUv, floor(vLayer)));
                if (t.a < 0.5) discard;
                vec4 c = t * vColor;
                float fog = clamp((vDist - uFogStart) / max(1.0, uFogEnd - uFogStart), 0.0, 1.0);
                fragColor = vec4(mix(c.rgb * vLight, uFogColor, fog), c.a);
            }
            """;

    /** Вид частинки: як рухається й малюється. */
    public enum Kind { DEBRIS, SPARK, FLAME, SMOKE, RAIN_SPLASH }

    // структура масивів (без об'єкта на частинку)
    private final double[] x = new double[MAX], y = new double[MAX], z = new double[MAX], vx = new double[MAX], vy = new double[MAX], vz = new double[MAX];
    private final long[] cx = new long[MAX], cz = new long[MAX];
    private final float[] life = new float[MAX], maxLife = new float[MAX], size = new float[MAX], u0 = new float[MAX], v0 = new float[MAX], uvSize = new float[MAX], light = new float[MAX];
    private final int[] layer = new int[MAX], color = new int[MAX];
    private final Kind[] kind = new Kind[MAX];
    private int n;
    private final Random rng = new Random();
    private final World world;
    private final TextureArray tex;
    private final Shader shader;
    private final int vao, vbo, uProjView, uScale, uCam, uTex, uFogColor, uFogStart, uFogEnd;
    private float[] verts = new float[4096];
    /** Небесне світло зараз (множник), ставить PlayScreen щокадру. */
    public float sky = 1f, ambient = 0.04f;
    /** Налаштування «Частинки»: 0 - жодних, 1 - лише уламки й удари, 2 - усі (вогники, дим, бризки). */
    public int level = 2;
    public int drawn;

    public Particles(World world, TextureArray tex) {
        this.world = world; this.tex = tex;
        shader = new Shader(VS, FS, "aPos", "aUv", "aLayer", "aLight", "aColor");
        uProjView = shader.uniform("uProjView"); uScale = shader.uniform("uScale"); uCam = shader.uniform("uCam"); uTex = shader.uniform("uTex");
        uFogColor = shader.uniform("uFogColor"); uFogStart = shader.uniform("uFogStart"); uFogEnd = shader.uniform("uFogEnd");
        vao = glGenVertexArrays(); vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int stride = FLOATS * 4;
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 20);
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 24);
        glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, true, stride, 28);
        glBindVertexArray(0);
    }

    public int count() { return n; }

    // ------------------------------------------------------------------ народження

    /** Чи народжувати частинку цього виду за налаштуванням. */
    private boolean allowed(Kind k) { return level >= 2 || (level == 1 && (k == Kind.DEBRIS || k == Kind.SPARK)); }

    private int spawn(Kind k, double bx, double by, double bz, double svx, double svy, double svz, float lifeSec, float sz) {
        int i;
        if (n < MAX) i = n++;
        else i = rng.nextInt(MAX); // повно - замінити випадкову (старі однаково скоро зникнуть)
        cx[i] = Math.floorDiv((long) Math.floor(bx), 16L); cz[i] = Math.floorDiv((long) Math.floor(bz), 16L);
        x[i] = bx - cx[i] * 16.0; y[i] = by; z[i] = bz - cz[i] * 16.0;
        vx[i] = svx; vy[i] = svy; vz[i] = svz;
        life[i] = maxLife[i] = lifeSec; size[i] = sz; kind[i] = k;
        layer[i] = 0; u0[i] = 0; v0[i] = 0; uvSize[i] = 1; color[i] = 0xFFFFFFFF;
        light[i] = lightAt(bx, by, bz);
        return i;
    }

    private float lightAt(double bx, double by, double bz) {
        long ix = (long) Math.floor(bx), iz = (long) Math.floor(bz);
        int iy = (int) Math.floor(by);
        Chunk c = world.chunk(Math.floorDiv(ix, 16L), Math.floorDiv(iz, 16L));
        if (c == null || iy < 0 || iy >= Chunk.HEIGHT) return Math.max(sky, ambient);
        int l = c.lightAt((int) Math.floorMod(ix, 16L), iy, (int) Math.floorMod(iz, 16L));
        return (float) Math.max(ambient, Math.max(Math.pow(0.8, 15 - (l >> 4)) * sky, Math.pow(0.8, 15 - (l & 15))));
    }

    private static int rgba(int rgb, int a) { return ((rgb >> 16) & 255) | (rgb & 0xFF00) | ((rgb & 255) << 16) | (a << 24); }

    private int sideLayer(BlockType b) {
        String t = b.texSide != null ? b.texSide : (b.texTop != null ? b.texTop : b.texBottom);
        return t == null ? 0 : tex.layer(t);
    }

    /** Уламки зламаного блока: 4x4x4 шматочки по всьому об'єму, розлітаються від центру. */
    public void blockBroken(BlockType b, long bx, int by, long bz) {
        if (!allowed(Kind.DEBRIS)) return;
        if (b == null || b.isAir()) return;
        int lay = sideLayer(b), col = lay == 0 ? rgba(b.color, 255) : 0xFFFFFFFF;
        for (int ix = 0; ix < 4; ix++) for (int iy = 0; iy < 4; iy++) for (int iz = 0; iz < 4; iz++) {
            if (rng.nextInt(3) != 0) continue; // ~21 з 64 - досить, щоб «розсипалось»
            double px = bx + (ix + 0.5) / 4, py = by + (iy + 0.5) / 4, pz = bz + (iz + 0.5) / 4;
            double dx = px - bx - 0.5, dy = py - by - 0.5, dz = pz - bz - 0.5;
            int i = spawn(Kind.DEBRIS, px, py, pz, dx * 3 + (rng.nextDouble() - 0.5), dy * 3 + 2.5 + rng.nextDouble() * 1.5, dz * 3 + (rng.nextDouble() - 0.5), 0.6f + rng.nextFloat() * 0.6f, 0.08f + rng.nextFloat() * 0.07f);
            debrisLook(i, lay, col);
        }
    }

    /** Удар по блоку (ламання ще триває): кілька уламків з грані, у яку влучили. face - як у Raycast (0..5) або -1. */
    public void blockHit(BlockType b, long bx, int by, long bz, double hx, double hy, double hz) {
        if (!allowed(Kind.DEBRIS)) return;
        if (b == null || b.isAir()) return;
        int lay = sideLayer(b), col = lay == 0 ? rgba(b.color, 255) : 0xFFFFFFFF;
        double cxB = bx + 0.5, cyB = by + 0.5, czB = bz + 0.5;
        for (int k = 0; k < 4; k++) {
            double px = hx + (rng.nextDouble() - 0.5) * 0.3, py = hy + (rng.nextDouble() - 0.5) * 0.3, pz = hz + (rng.nextDouble() - 0.5) * 0.3;
            double ox = px - cxB, oy = py - cyB, oz = pz - czB;
            int i = spawn(Kind.DEBRIS, px, py, pz, ox * 2 + (rng.nextDouble() - 0.5), oy * 2 + 1.5, oz * 2 + (rng.nextDouble() - 0.5), 0.4f + rng.nextFloat() * 0.4f, 0.08f + rng.nextFloat() * 0.06f);
            debrisLook(i, lay, col);
        }
    }

    private void debrisLook(int i, int lay, int col) {
        layer[i] = lay; color[i] = col;
        uvSize[i] = 0.25f; u0[i] = rng.nextInt(4) * 0.25f; v0[i] = rng.nextInt(4) * 0.25f;
    }

    /** Удар по істоті: короткі іскри (rgb) з місця удару. */
    public void sparks(double px, double py, double pz, int rgb, int count) {
        if (!allowed(Kind.SPARK)) return;
        for (int k = 0; k < count; k++) {
            double a = rng.nextDouble() * Math.PI * 2, up = rng.nextDouble();
            int i = spawn(Kind.SPARK, px, py, pz, Math.cos(a) * 2.5, 1.5 + up * 2.5, Math.sin(a) * 2.5, 0.3f + rng.nextFloat() * 0.3f, 0.07f + rng.nextFloat() * 0.05f);
            color[i] = rgba(rgb, 255);
            light[i] = 1f;
        }
    }

    /** Язичок полум'я (світиться сам) і інколи дим над ним. */
    public void flame(double px, double py, double pz, double spread) {
        if (!allowed(Kind.FLAME)) return;
        int i = spawn(Kind.FLAME, px + (rng.nextDouble() - 0.5) * spread, py, pz + (rng.nextDouble() - 0.5) * spread, (rng.nextDouble() - 0.5) * 0.1, 0.25 + rng.nextDouble() * 0.2, (rng.nextDouble() - 0.5) * 0.1, 0.5f + rng.nextFloat() * 0.3f, 0.09f + rng.nextFloat() * 0.04f);
        color[i] = rgba(rng.nextBoolean() ? 0xFFB040 : 0xFF7A20, 255);
        light[i] = 1f;
        if (rng.nextInt(3) == 0) smoke(px, py + 0.15, pz);
    }

    public void smoke(double px, double py, double pz) {
        if (!allowed(Kind.SMOKE)) return;
        int i = spawn(Kind.SMOKE, px + (rng.nextDouble() - 0.5) * 0.1, py, pz + (rng.nextDouble() - 0.5) * 0.1, (rng.nextDouble() - 0.5) * 0.15, 0.5 + rng.nextDouble() * 0.3, (rng.nextDouble() - 0.5) * 0.15, 1.2f + rng.nextFloat() * 0.8f, 0.07f + rng.nextFloat() * 0.05f);
        int g = 60 + rng.nextInt(50);
        color[i] = rgba(g << 16 | g << 8 | g, 255);
    }

    /** Бризки краплі дощу об землю. */
    public void splash(double px, double py, double pz) {
        if (!allowed(Kind.RAIN_SPLASH)) return;
        for (int k = 0; k < 2; k++) {
            int i = spawn(Kind.RAIN_SPLASH, px, py + 0.05, pz, (rng.nextDouble() - 0.5) * 1.2, 1 + rng.nextDouble() * 0.8, (rng.nextDouble() - 0.5) * 1.2, 0.25f + rng.nextFloat() * 0.15f, 0.05f);
            color[i] = rgba(0x9AB4E0, 255);
        }
    }

    // ------------------------------------------------------------------ рух

    /** Крок фізики на dt секунд. */
    public void update(double dt) {
        for (int i = 0; i < n; ) {
            life[i] -= dt;
            if (life[i] <= 0) { remove(i); continue; }
            Kind k = kind[i];
            double g = switch (k) { case DEBRIS -> 16; case SPARK -> 12; case RAIN_SPLASH -> 14; case FLAME -> -0.2; case SMOKE -> -0.3; };
            vy[i] -= g * dt;
            double drag = k == Kind.SMOKE || k == Kind.FLAME ? 0.96 : 0.985;
            double f = Math.pow(drag, dt * 60);
            vx[i] *= f; vz[i] *= f; if (k == Kind.SMOKE || k == Kind.FLAME) vy[i] *= f;
            double nx = x[i] + vx[i] * dt, ny = y[i] + vy[i] * dt, nz = z[i] + vz[i] * dt;
            if (k == Kind.DEBRIS || k == Kind.SPARK || k == Kind.RAIN_SPLASH) {
                long bx = cx[i] * 16 + (long) Math.floor(nx), bz = cz[i] * 16 + (long) Math.floor(nz);
                if (world.isSolid(bx, (int) Math.floor(ny), bz)) { // лягає на поверхню
                    if (vy[i] < 0 && !world.isSolid(bx, (int) Math.floor(y[i]), bz)) { ny = Math.floor(ny) + 1.001; vy[i] = 0; vx[i] *= 0.5; vz[i] *= 0.5; }
                    else { nx = x[i]; nz = z[i]; vx[i] = 0; vz[i] = 0; ny = y[i]; }
                }
            }
            x[i] = nx; y[i] = ny; z[i] = nz;
            i++;
        }
    }

    private void remove(int i) {
        int j = --n;
        if (i == j) return;
        x[i] = x[j]; y[i] = y[j]; z[i] = z[j]; vx[i] = vx[j]; vy[i] = vy[j]; vz[i] = vz[j]; cx[i] = cx[j]; cz[i] = cz[j];
        life[i] = life[j]; maxLife[i] = maxLife[j]; size[i] = size[j]; u0[i] = u0[j]; v0[i] = v0[j]; uvSize[i] = uvSize[j]; light[i] = light[j];
        layer[i] = layer[j]; color[i] = color[j]; kind[i] = kind[j];
    }

    public void clear() { n = 0; }

    // ------------------------------------------------------------------ малювання

    public void render(Camera cam, float[] fogColor, float fogStart, float fogEnd) {
        drawn = 0;
        if (n == 0) return;
        final float S = Units.BLOCK_SIZE;
        float[] m = cam.view.m;
        float rx = m[0], ry = m[4], rz = m[8], ux = m[1], uy = m[5], uz = m[9]; // вправо й угору екрана у світі
        final long rcx = cam.eye.cx, rcz = cam.eye.cz;
        int need = n * 4 * FLOATS;
        if (verts.length < need) verts = new float[Math.max(need, verts.length * 2)];
        int o = 0, q = 0;
        for (int i = 0; i < n; i++) {
            float px = (float) ((cx[i] - rcx) * 16 + x[i]), py = (float) y[i], pz = (float) ((cz[i] - rcz) * 16 + z[i]);
            float dxc = px - (float) cam.eye.x, dyc = py - (float) cam.eye.y, dzc = pz - (float) cam.eye.z;
            if (dxc * dxc + dyc * dyc + dzc * dzc > 96 * 96) continue;
            float t = life[i] / maxLife[i];
            float h = size[i] * (kind[i] == Kind.SMOKE ? 1.6f - t * 0.8f : kind[i] == Kind.FLAME ? 0.5f + t * 0.5f : 1f) * 0.5f;
            float l = light[i], lay = layer[i], cb = Float.intBitsToFloat(color[i]);
            float a0 = u0[i], b0 = v0[i], a1 = a0 + uvSize[i], b1 = b0 + uvSize[i];
            float[][] corner = {{-1, -1, a0, b1}, {1, -1, a1, b1}, {1, 1, a1, b0}, {-1, 1, a0, b0}};
            for (float[] c : corner) {
                verts[o++] = px + (rx * c[0] + ux * c[1]) * h; verts[o++] = py + (ry * c[0] + uy * c[1]) * h; verts[o++] = pz + (rz * c[0] + uz * c[1]) * h;
                verts[o++] = c[2]; verts[o++] = c[3]; verts[o++] = lay; verts[o++] = l; verts[o++] = cb; verts[o++] = 0;
            }
            q++;
        }
        drawn = q;
        if (q == 0) return;
        glUseProgram(shader.id);
        glUniformMatrix4fv(uProjView, false, cam.projView.m);
        glUniform1f(uScale, S);
        glUniform3f(uCam, (float) (cam.eye.x * S), (float) (cam.eye.y * S), (float) (cam.eye.z * S));
        glUniform3f(uFogColor, fogColor[0], fogColor[1], fogColor[2]);
        glUniform1f(uFogStart, fogStart); glUniform1f(uFogEnd, fogEnd);
        glActiveTexture(GL_TEXTURE0); tex.bind(); glUniform1i(uTex, 0);
        glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(o);
        try { fb.put(verts, 0, o).flip(); glBufferData(GL_ARRAY_BUFFER, fb, GL_STREAM_DRAW); } finally { MemoryUtil.memFree(fb); }
        glDrawArrays(GL_QUADS, 0, q * 4);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glUseProgram(0);
        glEnable(GL_CULL_FACE);
    }

    public void close() {
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo);
        glDeleteProgram(shader.id);
    }
}
