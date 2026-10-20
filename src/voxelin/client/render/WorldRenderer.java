package voxelin.client.render;

import org.lwjgl.system.MemoryUtil;
import voxelin.core.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Малює світ: одна пара буферів GPU (непрозорий + напівпрозорий прохід) на секцію 16^3, один виклик малювання на секцію.
 * Мешинг (ChunkMesher) - у фонових потоках (ядра-1); головний потік лише вантажить готові меші з лімітом часу за кадр,
 * тож поява чанків не дає фризів. Блок, змінений гравцем, перебудовується одразу на головному потоці (без затримки).
 * Секції поза пірамідою видимості не малюються. Контури блоків і туман - у шейдері (геометрія злита, а контур лишається на кожному блоці).
 */
public final class WorldRenderer implements World.Listener {
    private static final String VS = """
            #version 130
            in vec3 aPos; in vec4 aColor; in float aFace;
            uniform mat4 uProjView; uniform vec3 uOffset; uniform float uScale; uniform vec3 uCam;
            out vec4 vColor; out vec3 vBlock; flat out int vFace; out float vDist;
            void main() {
                vec3 b = aPos + uOffset;
                vec3 w = b * uScale;
                vBlock = b; vColor = aColor; vFace = int(aFace + 0.5); vDist = length(w - uCam);
                gl_Position = uProjView * vec4(w, 1.0);
            }
            """;
    private static final String FS = """
            #version 130
            in vec4 vColor; in vec3 vBlock; flat in int vFace; in float vDist;
            uniform vec3 uFogColor; uniform float uFogStart; uniform float uFogEnd; uniform float uOutline;
            out vec4 fragColor;
            void main() {
                vec2 q = vFace < 2 ? vBlock.yz : (vFace < 4 ? vBlock.zx : vBlock.xy);
                vec2 f = abs(fract(q) - 0.5);
                float m = max(f.x, f.y);
                float e = 0.5 - m;
                float aa = fwidth(m) + 0.0001;
                float line = 1.0 - smoothstep(0.025, 0.025 + aa, e);
                vec3 c = vColor.rgb * mix(1.0, 0.72, line * uOutline);
                float fog = clamp((vDist - uFogStart) / max(1.0, uFogEnd - uFogStart), 0.0, 1.0);
                fragColor = vec4(mix(c, uFogColor, fog), vColor.a);
            }
            """;

    private static final class SectionMesh {
        final int cx, sy, cz;
        int vaoS, vboS, quadsS, vaoT, vboT, quadsT;
        SectionMesh(int cx, int sy, int cz) { this.cx = cx; this.sy = sy; this.cz = cz; }
    }

    private record Result(long key, ChunkMesher.Mesh mesh, long nanos) {}

    private final World world;
    private final ChunkMesher.Props props;
    private final Map<Long, SectionMesh> meshes = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>(), urgent = new LinkedHashSet<>(), inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ExecutorService pool;
    private final int poolSize;
    private final ThreadLocal<ChunkMesher> mesher = ThreadLocal.withInitial(ChunkMesher::new);
    private final ThreadLocal<short[]> padded = ThreadLocal.withInitial(() -> new short[ChunkMesher.P * ChunkMesher.P * ChunkMesher.P]);
    private final Shader shader;
    private final int uProjView, uOffset, uScale, uCam, uFogColor, uFogStart, uFogEnd, uOutline;
    private int ebo, eboQuads;
    private boolean closed;

    public int renderDistance = 12;
    public boolean outlines = true;
    public float[] fogColor = {0.53f, 0.71f, 0.92f};
    /** Статистика (F3, --bench). */
    public int visibleSections, drawnQuads;
    public long meshNanos, meshCount, uploads;

    public WorldRenderer(World world) {
        this.world = world;
        BlockType[] all = world.blocks.all();
        boolean[] opaque = new boolean[all.length], trans = new boolean[all.length], air = new boolean[all.length];
        int[] color = new int[all.length];
        for (BlockType t : all) { opaque[t.id] = t.opaque; trans[t.id] = t.translucent; air[t.id] = t.isAir(); color[t.id] = t.color; }
        props = new ChunkMesher.Props(opaque, trans, air, color);
        poolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        pool = Executors.newFixedThreadPool(poolSize, r -> { Thread t = new Thread(r, "voxelin-mesh"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
        shader = new Shader(VS, FS, "aPos", "aColor", "aFace");
        uProjView = shader.uniform("uProjView"); uOffset = shader.uniform("uOffset"); uScale = shader.uniform("uScale"); uCam = shader.uniform("uCam");
        uFogColor = shader.uniform("uFogColor"); uFogStart = shader.uniform("uFogStart"); uFogEnd = shader.uniform("uFogEnd"); uOutline = shader.uniform("uOutline");
        ebo = glGenBuffers();
        ensureIndices(4096);
        world.setListener(this);
        for (Chunk c : world.loadedChunks()) chunkLoaded(c);
    }

    // ------------------------------------------------------------------ ключі секцій

    static long key(int cx, int sy, int cz) { return SectionKey.of(cx, sy, cz); }
    static int kcx(long k) { return SectionKey.cx(k); }
    static int kcz(long k) { return SectionKey.cz(k); }
    static int ksy(long k) { return SectionKey.sy(k); }

    // ------------------------------------------------------------------ події світу (головний потік)

    @Override public void chunkLoaded(Chunk c) {
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) if (c.section(sy) != null) dirty.add(key(c.cx, sy, c.cz));
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : nb) {
            Chunk n = world.chunk(c.cx + d[0], c.cz + d[1]);
            if (n == null) continue;
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) if (n.section(sy) != null) dirty.add(key(n.cx, sy, n.cz));
        }
    }

    @Override public void chunkUnloaded(Chunk c) {
        for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
            long k = key(c.cx, sy, c.cz);
            dirty.remove(k); urgent.remove(k);
            SectionMesh m = meshes.remove(k);
            if (m != null) free(m);
        }
    }

    @Override public void blockChanged(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4, sy = y >> 4, lx = x & 15, ly = y & 15, lz = z & 15;
        urgent.add(key(cx, sy, cz));
        if (lx == 0) urgent.add(key(cx - 1, sy, cz));
        if (lx == 15) urgent.add(key(cx + 1, sy, cz));
        if (lz == 0) urgent.add(key(cx, sy, cz - 1));
        if (lz == 15) urgent.add(key(cx, sy, cz + 1));
        if (ly == 0 && sy > 0) urgent.add(key(cx, sy - 1, cz));
        if (ly == 15 && sy < Chunk.SECTIONS - 1) urgent.add(key(cx, sy + 1, cz));
    }

    // ------------------------------------------------------------------ мешинг

    private ChunkMesher.Mesh meshNow(int cx, int sy, int cz) {
        short[] p = padded.get();
        Chunk[] around = new Chunk[9];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) around[(dx + 1) + (dz + 1) * 3] = world.chunk(cx + dx, cz + dz);
        int baseY = sy * 16;
        for (int y = -1; y <= 16; y++) {
            int wy = baseY + y;
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) {
                int id;
                if (wy < 0) id = 1;
                else if (wy >= Chunk.HEIGHT) id = 0;
                else {
                    Chunk c = around[((x >> 4) + 1) + ((z >> 4) + 1) * 3];
                    id = c == null ? 1 : c.get(x & 15, wy, z & 15); // незавантажений сусід - як непрозорий (без дір на межі)
                }
                p[ChunkMesher.index(x, y, z)] = (short) id;
            }
        }
        return mesher.get().build(p, props);
    }

    /** Раз на кадр: термінові секції - одразу, решта - у фон за відстанню; вивантаження готових мешів на GPU з лімітом. */
    public void update(float camX, float camZ) {
        int ccx = Units.toBlock(camX) >> 4, ccz = Units.toBlock(camZ) >> 4;
        int n = 0;
        for (Iterator<Long> it = urgent.iterator(); it.hasNext() && n < 12; n++) {
            long k = it.next(); it.remove(); dirty.remove(k);
            if (world.chunk(kcx(k), kcz(k)) == null) continue;
            long t0 = System.nanoTime();
            ChunkMesher.Mesh m = meshNow(kcx(k), ksy(k), kcz(k));
            meshNanos += System.nanoTime() - t0; meshCount++;
            upload(k, m);
        }
        if (!dirty.isEmpty() && inFlight.size() < poolSize * 3) {
            List<Long> cand = new ArrayList<>();
            int rd2 = (renderDistance + 1) * (renderDistance + 1);
            for (Iterator<Long> it = dirty.iterator(); it.hasNext(); ) {
                long k = it.next();
                int dx = kcx(k) - ccx, dz = kcz(k) - ccz;
                if (dx * dx + dz * dz > rd2 || world.chunk(kcx(k), kcz(k)) == null) { it.remove(); continue; } // далеко або вже вивантажено
                if (!inFlight.contains(k)) cand.add(k);
            }
            cand.sort(Comparator.comparingInt(k -> { int dx = kcx(k) - ccx, dz = kcz(k) - ccz; return dx * dx + dz * dz; }));
            for (long k : cand) {
                if (inFlight.size() >= poolSize * 3) break;
                dirty.remove(k); inFlight.add(k);
                final int cx = kcx(k), sy = ksy(k), cz = kcz(k);
                pool.execute(() -> {
                    long t0 = System.nanoTime();
                    ChunkMesher.Mesh m = null;
                    try { m = meshNow(cx, sy, cz); } catch (Throwable t) { System.out.println("[Voxelin] ⚠️ мешинг " + cx + "," + sy + "," + cz + ": " + t); }
                    results.add(new Result(k, m, System.nanoTime() - t0));
                });
            }
        }
        long deadline = System.nanoTime() + 3_000_000; // не більше ~3 мс на кадр
        for (Result r; System.nanoTime() < deadline && (r = results.poll()) != null; ) {
            inFlight.remove(r.key);
            meshNanos += r.nanos; meshCount++;
            if (r.mesh == null || world.chunk(kcx(r.key), kcz(r.key)) == null) continue;
            upload(r.key, r.mesh);
        }
    }

    private void upload(long k, ChunkMesher.Mesh m) {
        SectionMesh s = meshes.get(k);
        if (m.isEmpty()) { if (s != null) { meshes.remove(k); free(s); } return; }
        if (s == null) meshes.put(k, s = new SectionMesh(kcx(k), ksy(k), kcz(k)));
        ensureIndices(Math.max(m.solidQuads, m.translucentQuads));
        int[] r = fill(s.vaoS, s.vboS, m.solid, m.solidQuads); s.vaoS = r[0]; s.vboS = r[1]; s.quadsS = m.solidQuads;
        r = fill(s.vaoT, s.vboT, m.translucent, m.translucentQuads); s.vaoT = r[0]; s.vboT = r[1]; s.quadsT = m.translucentQuads;
        uploads++;
    }

    private int[] fill(int vao, int vbo, float[] data, int quads) {
        if (quads == 0) { if (vao != 0) { glDeleteVertexArrays(vao); glDeleteBuffers(vbo); } return new int[]{0, 0}; }
        if (vao == 0) {
            vao = glGenVertexArrays(); vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            int stride = ChunkMesher.FLOATS_PER_VERTEX * 4;
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, stride, 12);
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 16);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        } else {
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
        }
        FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
        try { fb.put(data).flip(); glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW); }
        finally { MemoryUtil.memFree(fb); }
        glBindVertexArray(0);
        return new int[]{vao, vbo};
    }

    /** Спільний буфер індексів (0,1,2, 2,3,0 на кожен чотирикутник) - росте за потреби. */
    private void ensureIndices(int quads) {
        if (quads <= eboQuads) return;
        int q = Math.max(quads, eboQuads * 2);
        IntBuffer ib = MemoryUtil.memAllocInt(q * 6);
        try {
            for (int i = 0; i < q; i++) { int b = i * 4; ib.put(b).put(b + 1).put(b + 2).put(b + 2).put(b + 3).put(b); }
            ib.flip();
            glBindVertexArray(0);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        } finally { MemoryUtil.memFree(ib); }
        eboQuads = q;
    }

    private void free(SectionMesh m) {
        if (m.vaoS != 0) { glDeleteVertexArrays(m.vaoS); glDeleteBuffers(m.vboS); }
        if (m.vaoT != 0) { glDeleteVertexArrays(m.vaoT); glDeleteBuffers(m.vboT); }
    }

    // ------------------------------------------------------------------ малювання

    public void render(Camera cam) {
        float far = renderDistance * 16 * Units.BLOCK_SIZE;
        float[][] planes = cam.projView.frustumPlanes();
        float s16 = 16 * Units.BLOCK_SIZE;
        List<SectionMesh> vis = new ArrayList<>(), trans = new ArrayList<>();
        int ccx = Units.toBlock(cam.rx) >> 4, ccz = Units.toBlock(cam.rz) >> 4;
        for (SectionMesh m : meshes.values()) {
            int dx = m.cx - ccx, dz = m.cz - ccz;
            if (dx * dx + dz * dz > renderDistance * renderDistance) continue;
            float x0 = m.cx * s16, y0 = m.sy * s16, z0 = m.cz * s16;
            if (!Mat4.boxVisible(planes, x0, y0, z0, x0 + s16, y0 + s16, z0 + s16)) continue;
            if (m.quadsS > 0) vis.add(m);
            if (m.quadsT > 0) trans.add(m);
        }
        visibleSections = vis.size() + trans.size();
        int quads = 0;
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glCullFace(GL_BACK);
        glUseProgram(shader.id);
        glUniformMatrix4fv(uProjView, false, cam.projView.m);
        glUniform1f(uScale, Units.BLOCK_SIZE);
        glUniform3f(uCam, cam.rx, cam.ry, cam.rz);
        glUniform3f(uFogColor, fogColor[0], fogColor[1], fogColor[2]);
        glUniform1f(uFogStart, far * 0.65f); glUniform1f(uFogEnd, far * 0.98f);
        glUniform1f(uOutline, outlines ? 1f : 0f);
        for (SectionMesh m : vis) {
            glUniform3f(uOffset, m.cx * 16, m.sy * 16, m.cz * 16);
            glBindVertexArray(m.vaoS);
            glDrawElements(GL_TRIANGLES, m.quadsS * 6, GL_UNSIGNED_INT, 0);
            quads += m.quadsS;
        }
        if (!trans.isEmpty()) { // напівпрозоре - від дальнього до ближнього, без запису глибини
            trans.sort(Comparator.comparingDouble(m -> -dist2(m, cam)));
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false); glDisable(GL_CULL_FACE);
            for (SectionMesh m : trans) {
                glUniform3f(uOffset, m.cx * 16, m.sy * 16, m.cz * 16);
                glBindVertexArray(m.vaoT);
                glDrawElements(GL_TRIANGLES, m.quadsT * 6, GL_UNSIGNED_INT, 0);
                quads += m.quadsT;
            }
            glDepthMask(true); glDisable(GL_BLEND); glEnable(GL_CULL_FACE);
        }
        glBindVertexArray(0);
        glUseProgram(0);
        drawnQuads = quads;
    }

    private static double dist2(SectionMesh m, Camera c) {
        double s = 16 * Units.BLOCK_SIZE, x = (m.cx + 0.5) * s - c.rx, y = (m.sy + 0.5) * s - c.ry, z = (m.cz + 0.5) * s - c.rz;
        return x * x + y * y + z * z;
    }

    public int meshCountLoaded() { return meshes.size(); }
    public int pending() { return dirty.size() + inFlight.size() + urgent.size(); }

    public void close() {
        if (closed) return;
        closed = true;
        world.setListener(null);
        pool.shutdownNow();
        for (SectionMesh m : meshes.values()) free(m);
        meshes.clear();
        glDeleteBuffers(ebo);
        glDeleteProgram(shader.id);
    }
}
