package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;
import voxelin.core.*;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Дощ навколо камери: у кожній колонці в радіусі R - вертикальна смуга, повернута до камери, від неба (трохи вище камери)
 * до першого блока, що зупиняє краплі (суцільний, рідина, листя). Самі струмені - у шейдері: кілька доріжок на колонку, що
 * біжать донизу зі своїм зсувом, тож однакового візерунка не видно. Висоти колонок оновлюються раз на 0.25 с.
 * Бризки об землю - частинки (Particles.splash). Під дахом і в печері дощу не видно: колонка закінчується на даху.
 */
public final class RainRenderer {
    public static final int R = 10, SIDE = 2 * R + 1;
    private static final String VS = """
            #version 130
            in vec3 aPos; in float aU; in float aSeed;
            uniform mat4 uProjView; uniform float uScale;
            out float vU; out float vY; flat out float vSeed;
            void main() { vU = aU; vY = aPos.y; vSeed = aSeed; gl_Position = uProjView * vec4(aPos * uScale, 1.0); }
            """;
    private static final String FS = """
            #version 130
            in float vU; in float vY; flat in float vSeed;
            uniform float uTime; uniform float uStrength; uniform vec3 uColor;
            out vec4 fragColor;
            // дешевий хеш без sin (дощ малюється поверх великої частини екрана - кожна операція на піксель важить)
            float h(float n) { return fract((vSeed * 0.6180339 + n * 0.7548777) * (17.0 + n)); }
            void main() {
                float lanes = 3.0;
                float lane = floor(vU * lanes), x = fract(vU * lanes);
                float r0 = h(lane), r1 = h(lane + 1.0);
                if (r1 > 0.35 + 0.65 * uStrength) discard; // слабкий дощ - менше доріжок
                float thin = 1.0 - smoothstep(0.04, 0.10, abs(x - 0.5 + (r0 - 0.5) * 0.6));
                if (thin <= 0.0) discard;
                float f = fract((vY + uTime * (13.0 + 4.0 * r1) + r0 * 40.0) / (2.2 + r1 * 1.6));
                float drop = smoothstep(0.0, 0.05, f) * (1.0 - smoothstep(0.22, 0.32, f)); // струмінь ~ півблока
                float a = drop * thin * 0.55 * uStrength;
                if (a < 0.01) discard;
                fragColor = vec4(uColor, a);
            }
            """;

    private final World world;
    private final Shader shader;
    private final int vao, vbo, uProjView, uScale, uTime, uStrength, uColor;
    private final int[] tops = new int[SIDE * SIDE];
    private long topsX = Long.MIN_VALUE, topsZ;
    private int topsY;
    private double scanIn;
    private float[] verts = new float[SIDE * SIDE * 4 * 5];
    private final Random rng = new Random();

    public RainRenderer(World world) {
        this.world = world;
        shader = new Shader(VS, FS, "aPos", "aU", "aSeed");
        uProjView = shader.uniform("uProjView"); uScale = shader.uniform("uScale"); uTime = shader.uniform("uTime"); uStrength = shader.uniform("uStrength"); uColor = shader.uniform("uColor");
        vao = glGenVertexArrays(); vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 20, 0);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, false, 20, 12);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, 20, 16);
        glBindVertexArray(0);
    }

    /** Чи зупиняє блок краплі. */
    private boolean stops(int id) {
        if (id < 0) return true; // чанк не завантажено
        if (id == 0) return false;
        BlockType b = world.blocks.get(id);
        return b.solid || b.liquid || b.opaque || b.name.endsWith("_leaves");
    }

    /** Висоти колонок навколо (bx, by, bz): верх першого блока зверху вниз у межах by-24..by+24, або by-25 - до самого низу. */
    private void scan(long bx, int by, long bz) {
        topsX = bx; topsZ = bz; topsY = by;
        for (int dz = -R; dz <= R; dz++) for (int dx = -R; dx <= R; dx++) {
            int top = by - 25;
            for (int y = Math.min(Chunk.HEIGHT - 1, by + 24); y >= Math.max(0, by - 24); y--) if (stops(world.blockId(bx + dx, y, bz + dz))) { top = y; break; }
            tops[(dx + R) + (dz + R) * SIDE] = top;
        }
    }

    /**
     * Кадр. strength 0..1 (Weather.rain у сухому біомі - 0). sky - яскравість неба для кольору крапель. particles - для бризок.
     */
    public void render(Camera cam, double strength, double sky, double seconds, double dt, Particles particles) {
        if (strength < 0.02) return;
        long bx = cam.eye.cx * 16 + (long) Math.floor(cam.eye.x), bz = cam.eye.cz * 16 + (long) Math.floor(cam.eye.z);
        int by = (int) Math.floor(cam.eye.y);
        scanIn -= dt;
        if (scanIn <= 0 || Math.abs(bx - topsX) > 2 || Math.abs(bz - topsZ) > 2 || Math.abs(by - topsY) > 3) { scan(bx, by, bz); scanIn = 0.25; }
        final long rcx = cam.eye.cx * 16, rcz = cam.eye.cz * 16;
        float ex = (float) cam.eye.x, ez = (float) cam.eye.z, ceil = by + 12;
        int o = 0, q = 0;
        for (int dz = -R; dz <= R; dz++) for (int dx = -R; dx <= R; dx++) {
            if (dx * dx + dz * dz > R * R) continue;
            long wx = topsX + dx, wz = topsZ + dz;
            float bottom = Math.max(tops[(dx + R) + (dz + R) * SIDE] + 1, by - 12); // нижче за 12 блоків під камерою не видно
            if (bottom >= ceil) continue;
            float cxr = (float) (wx - rcx) + 0.5f, czr = (float) (wz - rcz) + 0.5f;
            float vx = cxr - ex, vz = czr - ez, len = (float) Math.sqrt(vx * vx + vz * vz);
            float sx, sz;
            if (len < 1e-3f) { sx = 0.5f; sz = 0; } else { sx = -vz / len * 0.5f; sz = vx / len * 0.5f; }
            float seed = (float) ((wx * 73856093L ^ wz * 19349663L) & 1023);
            float[][] c = {{cxr - sx, bottom, czr - sz, 0}, {cxr + sx, bottom, czr + sz, 1}, {cxr + sx, ceil, czr + sz, 1}, {cxr - sx, ceil, czr - sz, 0}};
            for (float[] p : c) { verts[o++] = p[0]; verts[o++] = p[1]; verts[o++] = p[2]; verts[o++] = p[3]; verts[o++] = seed; }
            q++;
        }
        // бризки: кілька випадкових колонок за кадр, лише видимі з неба й близько по висоті
        int splashes = (int) (strength * dt * 60 * 6 + rng.nextDouble());
        for (int k = 0; k < splashes; k++) {
            int dx = rng.nextInt(SIDE) - R, dz = rng.nextInt(SIDE) - R;
            int top = tops[(dx + R) + (dz + R) * SIDE];
            if (top <= by - 25 || top + 1 >= ceil || Math.abs(top - by) > 12) continue;
            int id = world.blockId(topsX + dx, top, topsZ + dz);
            if (id > 0 && world.blocks.get(id).liquid) continue; // у воду - без бризок
            particles.splash(topsX + dx + rng.nextDouble(), top + 1, topsZ + dz + rng.nextDouble());
        }
        if (q == 0) return;
        float l = (float) Math.max(0.2, sky);
        glUseProgram(shader.id);
        glUniformMatrix4fv(uProjView, false, cam.projView.m);
        glUniform1f(uScale, Units.BLOCK_SIZE);
        glUniform1f(uTime, (float) (seconds % 1000.0));
        glUniform1f(uStrength, (float) strength);
        glUniform3f(uColor, 0.62f * l, 0.70f * l, 0.85f * l);
        glEnable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(o);
        try { fb.put(verts, 0, o).flip(); glBufferData(GL_ARRAY_BUFFER, fb, GL_STREAM_DRAW); } finally { MemoryUtil.memFree(fb); }
        glDrawArrays(GL_QUADS, 0, q * 4);
        glBindVertexArray(0);
        glUseProgram(0);
        glDisable(GL_BLEND); glDepthMask(true); glEnable(GL_CULL_FACE);
    }

    public void close() {
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo);
        glDeleteProgram(shader.id);
    }
}
