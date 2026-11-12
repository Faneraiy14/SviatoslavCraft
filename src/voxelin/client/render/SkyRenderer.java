package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;
import voxelin.core.DayCycle;
import voxelin.core.Units;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Небо верхнього світу: градієнт від горизонту (колір туману) до зеніту, сонце, місяць, зірки - один трикутник на весь екран,
 * напрям погляду рахує шейдер (без геометрії неба). Хмари - пласкі «блокові» на висоті CLOUD_Y: одна площина навколо камери,
 * візерунок - з маленької текстури (GL_NEAREST - чіткі клітинки), пливуть за ігровим часом (у всіх гравців однаково).
 * Небо й хмари - після непрозорого світу: небо лише на вільних пікселях (дальня площина), хмари - з глибиною в системі світу.
 */
public final class SkyRenderer {
    public static final int CLOUD_Y = 192, CLOUD_CELL = 12, CLOUD_TEX = 256;
    /** Блоків за тік: хмари пливуть на схід (+X), ~0.6 блока/с. */
    public static final double CLOUD_SPEED = 0.03;

    private static final String SKY_VS = """
            #version 130
            in vec2 aPos;
            out vec2 vNdc;
            void main() { vNdc = aPos; gl_Position = vec4(aPos, 1.0, 1.0); } // на дальній площині: малюється лише там, де світу нема
            """;
    private static final String SKY_FS = """
            #version 130
            in vec2 vNdc;
            uniform vec3 uRight; uniform vec3 uUp; uniform vec3 uBack; uniform vec2 uTan;
            uniform vec3 uHorizon; uniform vec3 uZenith; uniform vec3 uSun; uniform float uNight; uniform float uGlow; uniform float uTime; uniform float uCloudy;
            out vec4 fragColor;
            float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
            // зірки: клітинки на гранях куба навколо камери; у кожній - можливо одна зірка (точка з м'яким краєм)
            float stars(vec3 d) {
                vec3 a = abs(d);
                vec2 uv; float face;
                if (a.x >= a.y && a.x >= a.z) { uv = d.yz / a.x; face = d.x > 0.0 ? 0.0 : 1.0; }
                else if (a.y >= a.z) { uv = d.xz / a.y; face = d.y > 0.0 ? 2.0 : 3.0; }
                else { uv = d.xy / a.z; face = d.z > 0.0 ? 4.0 : 5.0; }
                float n = 90.0;
                vec2 c = floor(uv * n);
                float h = hash(vec3(c, face * 31.0 + 0.5));
                if (h < 0.982) return 0.0;
                vec2 ctr = (c + 0.25 + 0.5 * vec2(hash(vec3(c, face + 7.1)), hash(vec3(c, face + 3.3)))) / n;
                float dist = length(uv - ctr) * n;
                float tw = 0.7 + 0.3 * sin(uTime * (1.5 + 3.0 * hash(vec3(c, face + 1.9))) + h * 40.0);
                return smoothstep(0.30, 0.04, dist) * (0.35 + 0.65 * (h - 0.982) / 0.018) * tw;
            }
            void main() {
                vec3 d = normalize(uRight * vNdc.x * uTan.x + uUp * vNdc.y * uTan.y - uBack);
                float up = d.y;
                // градієнт: біля горизонту - колір туману (світ зливається з небом), угорі - глибший (sqrt - дешевше за pow)
                vec3 col = mix(uHorizon, uZenith, sqrt(clamp(up, 0.0, 1.0)));
                // захід/схід: тепле сяйво з боку сонця біля горизонту - лише коли воно є і лише низько
                if (uGlow > 0.01 && up < 0.6) {
                    vec2 hs = normalize(uSun.xz + 1e-5);
                    float toward = max(dot(normalize(d.xz + 1e-5), hs), 0.0);
                    toward *= toward; toward *= toward;
                    col += vec3(0.95, 0.45, 0.18) * uGlow * toward * max(0.0, 1.0 - abs(up) * 3.0) * 0.55;
                }
                if (uNight > 0.01 && up > -0.05) {
                    // зірки обертаються разом з небом: система координат, де сонце завжди на осі X
                    vec3 sx = uSun, sy = normalize(cross(vec3(0.0, 0.0, 1.0), sx)), sz = cross(sx, sy);
                    vec3 cel = vec3(dot(d, sx), dot(d, sy), dot(d, sz));
                    float s = stars(cel) * uNight * (1.0 - uCloudy) * smoothstep(-0.05, 0.12, up);
                    col += vec3(0.9, 0.92, 1.0) * s;
                }
                float cs = dot(d, uSun);
                if (cs > 0.75) { // сонце: диск + ореол (поза цим конусом обидва ~0)
                    float sunDisk = smoothstep(0.99955, 0.99975, cs);
                    float c2 = cs * cs, c4 = c2 * c2, c8 = c4 * c4, c12 = c8 * c4;
                    float halo = exp2((cs - 1.0) * 430.0) * 0.6 + c12 * 0.12;
                    float sunVis = smoothstep(-0.08, 0.04, uSun.y) * (1.0 - 0.85 * uCloudy);
                    col += (vec3(1.0, 0.95, 0.75) * sunDisk * 1.6 + vec3(1.0, 0.8, 0.5) * halo * (0.4 + uGlow)) * sunVis;
                }
                float cm = -cs;
                if (cm > 0.99) { // місяць: блідий диск з темнішими плямами (кратерами) і слабке сяйво
                    vec3 moon = -uSun;
                    float moonVis = smoothstep(-0.08, 0.04, moon.y);
                    float moonDisk = smoothstep(0.99975, 0.99988, cm);
                    if (moonDisk > 0.0) {
                        vec3 mx = normalize(cross(vec3(0.0, 1.0, 0.0), moon)), my = cross(moon, mx);
                        vec2 m = vec2(dot(d, mx), dot(d, my)) * 140.0;
                        float spots = 0.82 + 0.18 * sin(m.x * 2.1 + 1.3) * sin(m.y * 1.7 - 0.4) - 0.12 * step(0.55, fract(sin(dot(floor(m * 1.5), vec2(12.9898, 78.233))) * 43758.5));
                        col = mix(col, vec3(0.86, 0.88, 0.95) * spots, moonDisk * moonVis * (1.0 - 0.85 * uCloudy));
                    }
                    col += vec3(0.6, 0.65, 0.8) * exp2((cm - 1.0) * 577.0) * 0.25 * uNight * moonVis;
                }
                fragColor = vec4(col, 1.0);
            }
            """;
    private static final String CLOUD_VS = """
            #version 130
            in vec2 aPos;
            uniform mat4 uProjView; uniform mat4 uWorldProjView; uniform vec3 uCenter; uniform float uRadius; uniform float uScale;
            out vec2 vRel; out vec4 vClip;
            void main() {
                vRel = aPos * uRadius;
                vec3 w = (uCenter + vec3(vRel.x, 0.0, vRel.y)) * uScale;
                gl_Position = uProjView * vec4(w, 1.0);   // своя далека площина - хмари видно за дальністю огляду
                vClip = uWorldProjView * vec4(w, 1.0);    // глибина - у системі світу (далі за світ - 1.0)
            }
            """;
    private static final String CLOUD_FS = """
            #version 130
            in vec2 vRel; in vec4 vClip;
            uniform sampler2D uTex; uniform vec2 uOrigin; uniform float uCell; uniform float uSize; uniform float uRadius;
            uniform vec3 uColor; uniform float uAlpha; uniform vec3 uHorizonC;
            out vec4 fragColor;
            void main() {
                vec2 b = uOrigin + vRel;
                float c = texture(uTex, b / (uCell * uSize)).r;
                if (c < 0.5) discard;
                float dist = length(vRel);
                float fade = 1.0 - smoothstep(uRadius * 0.55, uRadius * 0.98, dist);
                if (fade <= 0.0) discard;
                vec3 col = mix(uColor, uHorizonC, smoothstep(uRadius * 0.3, uRadius * 0.95, dist) * 0.7); // далекі хмари тануть у серпанку
                fragColor = vec4(col, uAlpha * fade);
                gl_FragDepth = min(vClip.z / vClip.w * 0.5 + 0.5, 1.0);
            }
            """;

    private final Shader sky, clouds;
    private final int vaoSky, vboSky, vaoCloud, vboCloud, cloudTex;
    private final int uRight, uUp, uBack, uTan, uHorizon, uZenith, uSun, uNight, uGlow, uTime, uCloudy;
    private final int cProjView, cCenter, cRadius, cScale, cTex, cOrigin, cCell, cSize, cColor, cAlpha, cHorizon, cWorldPV;
    private final Mat4 farProj = new Mat4();

    public SkyRenderer() {
        sky = new Shader(SKY_VS, SKY_FS, "aPos");
        clouds = new Shader(CLOUD_VS, CLOUD_FS, "aPos");
        uRight = sky.uniform("uRight"); uUp = sky.uniform("uUp"); uBack = sky.uniform("uBack"); uTan = sky.uniform("uTan");
        uHorizon = sky.uniform("uHorizon"); uZenith = sky.uniform("uZenith"); uSun = sky.uniform("uSun"); uNight = sky.uniform("uNight");
        uGlow = sky.uniform("uGlow"); uTime = sky.uniform("uTime"); uCloudy = sky.uniform("uCloudy");
        cProjView = clouds.uniform("uProjView"); cCenter = clouds.uniform("uCenter"); cRadius = clouds.uniform("uRadius"); cScale = clouds.uniform("uScale");
        cTex = clouds.uniform("uTex"); cOrigin = clouds.uniform("uOrigin"); cCell = clouds.uniform("uCell"); cSize = clouds.uniform("uSize");
        cColor = clouds.uniform("uColor"); cAlpha = clouds.uniform("uAlpha"); cHorizon = clouds.uniform("uHorizonC"); cWorldPV = clouds.uniform("uWorldProjView");
        int[] a = quad(new float[]{-1, -1, 3, -1, -1, 3}); vaoSky = a[0]; vboSky = a[1];
        a = quad(new float[]{-1, -1, 1, -1, 1, 1, 1, 1, -1, 1, -1, -1}); vaoCloud = a[0]; vboCloud = a[1];
        cloudTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, cloudTex);
        ByteBuffer px = MemoryUtil.memAlloc(CLOUD_TEX * CLOUD_TEX);
        try {
            byte[] m = cloudMap(CLOUD_TEX, 0x5EEDC10DL);
            px.put(m).flip();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, CLOUD_TEX, CLOUD_TEX, 0, GL_RED, GL_UNSIGNED_BYTE, px);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        } finally { MemoryUtil.memFree(px); }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static int[] quad(float[] v) {
        int vao = glGenVertexArrays(), vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = MemoryUtil.memAllocFloat(v.length);
        try { fb.put(v).flip(); glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW); } finally { MemoryUtil.memFree(fb); }
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0);
        glBindVertexArray(0);
        return new int[]{vao, vbo};
    }

    /** Візерунок хмар size x size, безшовний по краях: 255 - хмара, 0 - чисто. Два шари шуму значень, поріг ~ третина неба. */
    static byte[] cloudMap(int size, long seed) {
        byte[] out = new byte[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double v = 0.65 * vnoise(x, y, 24, size, seed) + 0.35 * vnoise(x, y, 8, size, seed ^ 0x9E3779B97F4A7C15L);
            out[x + y * size] = (byte) (v > 0.56 ? 255 : 0);
        }
        return out;
    }

    private static double vnoise(int x, int y, int period, int size, long seed) {
        int n = size / period; // вузлів по стороні (повтор - безшовно)
        double fx = (double) x / period, fy = (double) y / period;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
        double a = h(x0 % n, y0 % n, seed), b = h((x0 + 1) % n, y0 % n, seed), c = h(x0 % n, (y0 + 1) % n, seed), d = h((x0 + 1) % n, (y0 + 1) % n, seed);
        return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
    }

    private static double h(int x, int y, long seed) {
        long z = seed + x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** Напрям на сонце (одиничний): схід +X, захід -X, полудень трохи на південь (+Z). */
    public static float[] sunDir(double phase) {
        double a = DayCycle.sunAngle(phase), tilt = 0.35;
        double x = Math.cos(a), y = Math.sin(a) * Math.cos(tilt), z = Math.sin(a) * Math.sin(tilt);
        return new float[]{(float) x, (float) y, (float) z};
    }

    /**
     * Небо (до світу; глибина вимкнена). horizon - колір туману; sky - яскравість неба 0.12..1; glow - сила заходу; cloudy - 0..1
     * (дощ ховає зірки й тьмянить сонце); seconds - для мерехтіння зірок.
     */
    public void renderSky(Camera cam, double phase, float[] horizon, double skyLevel, double glow, double cloudy, double seconds) {
        float[] m = cam.view.m, p = cam.proj.m, sun = sunDir(phase);
        float night = (float) Math.max(0, Math.min(1, (1 - skyLevel) / (1 - DayCycle.NIGHT_SKY)));
        // зеніт: удень глибша синява, уночі - майже чорний; захмарене - сіріше
        float day = 1 - night;
        float[] z = {horizon[0] * (0.55f + 0.1f * night) * day + 0.004f * night, horizon[1] * (0.70f) * day + 0.006f * night, Math.min(1f, horizon[2] * 1.02f) * day + 0.025f * night};
        float g = (float) cloudy * 0.7f, grey = (horizon[0] + horizon[1] + horizon[2]) / 3f;
        for (int i = 0; i < 3; i++) z[i] = z[i] + (grey * 0.9f - z[i]) * g;
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE); glDepthMask(false);
        glUseProgram(sky.id);
        glUniform3f(uRight, m[0], m[4], m[8]);
        glUniform3f(uUp, m[1], m[5], m[9]);
        glUniform3f(uBack, m[2], m[6], m[10]);
        glUniform2f(uTan, 1f / p[0], 1f / p[5]);
        glUniform3f(uHorizon, horizon[0], horizon[1], horizon[2]);
        glUniform3f(uZenith, z[0], z[1], z[2]);
        glUniform3f(uSun, sun[0], sun[1], sun[2]);
        glUniform1f(uNight, night);
        glUniform1f(uGlow, (float) glow);
        glUniform1f(uTime, (float) (seconds % 10000.0));
        glUniform1f(uCloudy, (float) cloudy);
        glBindVertexArray(vaoSky);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
        glDepthMask(true); glDepthFunc(GL_LESS); glEnable(GL_CULL_FACE);
    }

    /**
     * Хмари - після неба й непрозорого світу. Позиція - зі своєю далекою площиною (хмари видно за дальністю огляду), глибина -
     * у системі світу: гора перед хмарою закриває її, а з висоти хмари закривають землю під собою. worldTime - тіки (дрейф),
     * horizon - колір серпанку, skyLevel/glow - підфарбовка, cloudy - дощ: хмари сірі й щільніші.
     */
    public void renderClouds(Camera cam, long worldTime, float[] horizon, double skyLevel, double glow, double cloudy) {
        float radius = 720f;
        final float S = Units.BLOCK_SIZE;
        double size = (double) CLOUD_CELL * CLOUD_TEX; // період візерунка в блоках
        // абсолютна X/Z ока (блоки) по модулю періоду - точно, у long/double, без великих чисел у шейдері
        double ox = Math.floorMod(cam.eye.cx * 16, (long) size) + cam.eye.x + worldTime * CLOUD_SPEED, oz = Math.floorMod(cam.eye.cz * 16, (long) size) + cam.eye.z;
        ox %= size; oz %= size;
        // удень білі, уночі темні синювато-сірі (не світлі плями на чорному), на заході - теплі
        float day = (float) Math.max(0, Math.min(1, (skyLevel - DayCycle.NIGHT_SKY) / (1 - DayCycle.NIGHT_SKY)));
        float r = 0.07f + 0.93f * day, gg = 0.08f + 0.92f * day, b = 0.12f + 0.88f * day;
        float warm = (float) glow * 0.5f;
        r += (1.0f - r) * warm * 0.7f; gg += (0.70f - gg) * warm * 0.6f; b += (0.62f - b) * warm * 0.6f;
        float grey = (float) cloudy * 0.45f;
        r *= 1 - grey; gg *= 1 - grey; b *= 1 - grey * 0.9f;
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL);
        glDepthMask(false); glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(clouds.id);
        farProj.perspective(cam.fov, cam.proj.m[5] / cam.proj.m[0], 0.1f, 1200f * S).mul(cam.view);
        glUniformMatrix4fv(cProjView, false, farProj.m);
        glUniformMatrix4fv(cWorldPV, false, cam.projView.m);
        glUniform3f(cCenter, (float) cam.eye.x, CLOUD_Y, (float) cam.eye.z);
        glUniform1f(cRadius, radius);
        glUniform1f(cScale, S);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, cloudTex); glUniform1i(cTex, 0);
        glUniform2f(cOrigin, (float) ox, (float) oz);
        glUniform1f(cCell, CLOUD_CELL);
        glUniform1f(cSize, CLOUD_TEX);
        glUniform3f(cColor, Math.min(1f, r), Math.min(1f, gg), Math.min(1f, b));
        glUniform1f(cAlpha, (float) (0.78 + 0.17 * cloudy));
        glUniform3f(cHorizon, horizon[0], horizon[1], horizon[2]);
        glBindVertexArray(vaoCloud);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
        glDisable(GL_BLEND); glDepthMask(true); glDepthFunc(GL_LESS); glEnable(GL_CULL_FACE);
    }

    public void close() {
        glDeleteVertexArrays(vaoSky); glDeleteBuffers(vboSky);
        glDeleteVertexArrays(vaoCloud); glDeleteBuffers(vboCloud);
        glDeleteTextures(cloudTex);
        glDeleteProgram(sky.id); glDeleteProgram(clouds.id);
    }
}
