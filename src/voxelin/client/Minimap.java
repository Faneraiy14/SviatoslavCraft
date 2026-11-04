package voxelin.client;

import org.lwjgl.system.MemoryUtil;
import voxelin.core.BlockType;
import voxelin.core.Chunk;
import voxelin.core.World;
import voxelin.core.WorldInfo;
import voxelin.core.entity.Mob;
import voxelin.client.ui.Gui;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Мінікарта (клієнт-функція з FL Client, переписана нативно): вид згори на N x N клітинок навколо гравця, одна клітинка - ZOOMS[zoom]
 * блоків. Колір - середній колір верхньої текстури найвищого блока, тінь за перепадом висоти з північним сусідом (схили видно), вода
 * темніша з глибиною. Під землею (над головою нема неба) і в Незері - «печерний» зріз: підлога нижче рівня очей, стіни темні.
 * <p>
 * Буфер кільцевий і прив'язаний до світу: клітинка (tx, tz) живе в індексі (tx mod N, tz mod N), тож при ході дораховуються лише нові
 * смуги, а картинка зсувається плавно (GL_REPEAT + дробовий зсув UV). За кадр - не більше BUDGET стовпців; решта бюджету по колу
 * оновлює старі клітинки, щоб зламані й поставлені блоки з'являлись на карті.
 */
public final class Minimap {
    public static final int N = 128, BUDGET = 3000, REFRESH = 600;
    /** Блоків на клітинку для рівнів наближення. */
    public static final int[] ZOOMS = {1, 2, 4};
    static final int UNKNOWN = 0xFF14141C, VOID = 0xFF07070C;

    private final World world;
    private final int[] palette;
    final int[] argb = new int[N * N];
    private final int[] height = new int[N * N];
    private final long[] keyX = new long[N * N], keyZ = new long[N * N];
    private final boolean[] known = new boolean[N * N];
    private int zoom = -1, cursor;
    private boolean cave, dirty;
    private int texture;
    /** Скільки стовпців пораховано за останній кадр (F3, тест). */
    public int lastWork;

    /** palette[id] - колір блока на карті (ARGB; альфа 0 - прозорий, дивимось нижче). */
    public Minimap(World world, int[] palette) { this.world = world; this.palette = palette; }

    /** Палітра з текстур: середнє непрозорих пікселів верхньої грані; блоки без текстури - власний колір. */
    public static int[] palette(World world, java.util.function.Function<String, int[]> texturePixels) {
        BlockType[] all = world.blocks.all();
        int[] pal = new int[all.length];
        for (BlockType t : all) {
            if (t.isAir()) continue;
            String top = t.texTop != null ? t.texTop : t.texSide;
            int c = 0xFF000000 | t.color;
            int[] px = top == null ? null : texturePixels.apply(top);
            if (px != null) {
                long r = 0, g = 0, b = 0, n = 0;
                for (int p : px) if ((p >>> 24) > 100) { r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++; }
                // переважно прозора текстура (скло) не ховає того, що під нею; листя з дірками - видно (дерева на карті)
                if (n * 2 >= px.length) c = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
                else c = 0;
            }
            pal[t.id] = c;
        }
        return pal;
    }

    /** Оновити буфер навколо гравця (bx, bz - блок гравця, headY - висота очей). */
    public void update(long bx, int headY, long bz, int zoomLevel) {
        zoomLevel = Math.max(0, Math.min(ZOOMS.length - 1, zoomLevel));
        boolean caveNow = caveView(bx, headY, bz);
        if (zoomLevel != zoom || caveNow != cave) { java.util.Arrays.fill(known, false); zoom = zoomLevel; cave = caveNow; }
        int s = ZOOMS[zoom];
        long ctx = Math.floorDiv(bx, s), ctz = Math.floorDiv(bz, s), x0 = ctx - N / 2, z0 = ctz - N / 2;
        int work = 0;
        for (int j = 0; j < N && work < BUDGET; j++) {
            long tz = z0 + Math.floorMod(j - z0, N);
            for (int i = 0; i < N && work < BUDGET; i++) {
                int k = i + j * N;
                long tx = x0 + Math.floorMod(i - x0, N);
                if (known[k] && keyX[k] == tx && keyZ[k] == tz) continue;
                compute(k, tx, tz, s, headY);
                work++;
            }
        }
        for (int r = 0; r < REFRESH && work < BUDGET + REFRESH; r++) { // по колу освіжаємо вже відомі клітинки
            int k = cursor; cursor = (cursor + 1) % (N * N);
            if (!known[k]) continue;
            int i = k % N, j = k / N;
            compute(k, x0 + Math.floorMod(i - x0, N), z0 + Math.floorMod(j - z0, N), s, headY);
            work++;
        }
        lastWork = work;
        if (work > 0) dirty = true;
    }

    /** Печерний зріз: Незер завжди; інакше - коли над головою не видно неба. */
    private boolean caveView(long bx, int headY, long bz) {
        if (world.info.type == WorldInfo.Type.NETHER) return true;
        Chunk c = world.chunk(Math.floorDiv(bx, 16L), Math.floorDiv(bz, 16L));
        if (c == null || headY >= Chunk.HEIGHT) return false;
        return c.sky((int) (bx & 15), Math.max(0, headY), (int) (bz & 15)) == 0;
    }

    private void compute(int k, long tx, long tz, int s, int headY) {
        long x = tx * s + s / 2, z = tz * s + s / 2; // середина клітинки
        Chunk c = world.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L));
        keyX[k] = tx; keyZ[k] = tz;
        known[k] = true; // незавантажений чанк теж «відомий»: його перевірить коло оновлення, а не кожен кадр (інакше з'їсть бюджет)
        if (c == null) { argb[k] = UNKNOWN; height[k] = -1; return; }
        int lx = (int) (x & 15), lz = (int) (z & 15);
        int top, y;
        if (cave) {
            top = Math.min(Chunk.HEIGHT - 1, headY + 1);
            if (solidAt(c, lx, top, lz)) { argb[k] = 0xFF2A2A30; height[k] = top; shade(k); return; } // стіна печери
            y = top - 1;
        } else {
            y = Chunk.HEIGHT - 1;
            while (y >= 0 && c.section(y >> 4) == null) y = (y & ~15) - 1; // порожні секції - одразу на 16 вниз
        }
        int water = 0, waterColor = 0, bottom = cave ? Math.max(0, headY - 48) : 0;
        for (; y >= bottom; y--) {
            int id = c.get(lx, y, lz);
            if (id <= 0) continue;
            BlockType t = world.blocks.get(id);
            if (t.liquid && water < 12 && !t.name.equals(World.LAVA)) { if (water++ == 0) waterColor = palette[id]; continue; }
            int col = palette[id];
            if (col == 0) continue;
            if (water > 0) col = mix(waterColor, col, Math.max(0.35f, 0.85f - water * 0.06f)); // крізь мілку воду видно дно
            argb[k] = col; height[k] = water > 0 ? y + water : y;
            shade(k);
            return;
        }
        argb[k] = water > 0 ? waterColor : VOID; height[k] = -1;
    }

    private boolean solidAt(Chunk c, int lx, int y, int lz) { int id = c.get(lx, y, lz); return id > 0 && world.blocks.get(id).solid; }

    /** Схил: вище за північного сусіда - світліше, нижче - темніше. */
    private void shade(int k) {
        int up = k - N < 0 ? k + N * (N - 1) : k - N;
        if (!known[up] || height[up] < 0 || height[k] < 0) return;
        int d = height[k] - height[up];
        float f = d > 0 ? 1.12f : d < 0 ? 0.82f : 1f;
        argb[k] = scale(argb[k], f);
    }

    static int mix(int a, int b, float t) { // t - частка a
        int r = (int) (((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t)), g = (int) (((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t)), bl = (int) ((a & 255) * t + (b & 255) * (1 - t));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    static int scale(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
        return (c & 0xFF000000) | r << 16 | g << 8 | b;
    }

    public boolean cave() { return cave; }

    /** Колір клітинки, у яку потрапляє блок (bx, bz) за поточного наближення (для тестів і відладки). */
    public int colorAt(long bx, long bz) {
        int sc = ZOOMS[Math.max(0, zoom)];
        return argb[(int) Math.floorMod(Math.floorDiv(bx, sc), (long) N) + (int) Math.floorMod(Math.floorDiv(bz, sc), (long) N) * N];
    }

    // ------------------------------------------------------------------ малювання (потрібен GL)

    private void upload() {
        if (texture == 0) {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            dirty = true;
        }
        if (!dirty) return;
        ByteBuffer buf = MemoryUtil.memAlloc(N * N * 4);
        try {
            for (int c : argb) buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 255);
            buf.flip();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, N, N, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        } finally { MemoryUtil.memFree(buf); }
        dirty = false;
    }

    /**
     * Намалювати квадрат size x size з верхнім лівим кутом (x, y) на полотні Gui: північ угорі, стрілка гравця за поглядом (yaw), точки
     * істот (червоні - вороги, білі - мирні, жовті - жителі й вартові). px, pz - точна позиція гравця в блоках (дробова частина важлива для
     * плавного зсуву), але передається як блок + дріб, бо координати бувають до 1e15.
     */
    public void draw(float x, float y, float size, long bx, double fx, long bz, double fz, float yaw) {
        upload();
        int s = ZOOMS[Math.max(0, zoom)];
        int shown = N - 4; // крайні клітинки кільця можуть бути ще старі - не показуємо
        // центр у клітинках (mod N) з дробом
        double cu = Math.floorMod(Math.floorDiv(bx, s), N) + (Math.floorMod(bx, s) + fx) / s, cv = Math.floorMod(Math.floorDiv(bz, s), N) + (Math.floorMod(bz, s) + fz) / s;
        float u0 = (float) ((cu - shown / 2.0) / N), v0 = (float) ((cv - shown / 2.0) / N), du = shown / (float) N;
        Gui.rect(x - 3, y - 3, size + 6, size + 6, 0, 0, 0, 0.65f);
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        glColor4f(1, 1, 1, 1);
        glBegin(GL_QUADS);
        glTexCoord2f(u0, v0); glVertex2f(x, y);
        glTexCoord2f(u0 + du, v0); glVertex2f(x + size, y);
        glTexCoord2f(u0 + du, v0 + du); glVertex2f(x + size, y + size);
        glTexCoord2f(u0, v0 + du); glVertex2f(x, y + size);
        glEnd();
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_TEXTURE_2D);
        Gui.frame(x - 1, y - 1, size + 2, size + 2, 2, cave ? 0.55f : 0.75f, cave ? 0.45f : 0.7f, cave ? 0.3f : 0.55f);
        float k = size / (shown * (float) s), cx = x + size / 2f, cy = y + size / 2f;
        double half = shown * s / 2.0;
        for (Mob m : world.mobs()) {
            if (!m.alive()) continue;
            double dx = (double) (m.pos.blockX() - bx) + (m.pos.x - Math.floor(m.pos.x)) - fx, dz = (double) (m.pos.blockZ() - bz) + (m.pos.z - Math.floor(m.pos.z)) - fz;
            if (Math.abs(dx) >= half - 1 || Math.abs(dz) >= half - 1) continue;
            float r, g, b;
            if (m.type.hostile() || m.type.boss) { r = 1f; g = 0.25f; b = 0.2f; }
            else if (m.type.name.contains("villager") || m.type.guard()) { r = 1f; g = 0.85f; b = 0.3f; }
            else { r = 0.95f; g = 0.95f; b = 0.95f; }
            float mx = cx + (float) dx * k, my = cy + (float) dz * k, d = m.type.boss ? 7 : 5;
            Gui.rect(mx - d / 2 - 1, my - d / 2 - 1, d + 2, d + 2, 0, 0, 0, 0.8f);
            Gui.rect(mx - d / 2, my - d / 2, d, d, r, g, b, 1f);
        }
        // гравець - стрілка за поглядом (yaw 0 - на північ, -Z; 90 - на схід)
        double a = Math.toRadians(yaw), sa = Math.sin(a), ca = Math.cos(a);
        float[][] tri = {{0, -9}, {6, 7}, {0, 3}, {-6, 7}};
        glBegin(GL_TRIANGLES);
        for (int pass = 0; pass < 2; pass++) {
            float grow = pass == 0 ? 1.45f : 1f;
            if (pass == 0) glColor4f(0, 0, 0, 0.85f); else glColor4f(1f, 0.95f, 0.6f, 1f);
            int[][] faces = {{0, 1, 2}, {0, 2, 3}};
            for (int[] f : faces) for (int idx : f) {
                float px = tri[idx][0] * grow, py = tri[idx][1] * grow + (pass == 0 ? 0.5f : 0);
                glVertex2f(cx + (float) (px * ca - py * sa), cy + (float) (px * sa + py * ca));
            }
        }
        glEnd();
        Gui.textCentered("Пн", cx, y + 4, 1.6f, 1f, 0.9f, 0.5f);
    }

    public void close() { if (texture != 0) glDeleteTextures(texture); texture = 0; }
}
