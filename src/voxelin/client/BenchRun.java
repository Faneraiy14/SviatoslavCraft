package voxelin.client;

import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Тест швидкодії (--bench СЕКУНД): розігрів, поки світ не догрузиться, потім політ по колу радіусом 200 блоків зі швидкістю 40 блоків/с.
 * Один світ із фіксованим зерном - результати можна порівнювати між версіями. Друкує й пише bench-result.json.
 */
final class BenchRun {
    private static final double RADIUS = 200.0, SPEED = 40.0, HEIGHT_ABOVE = 40.0; // блоки
    private final double seconds;
    private enum Phase { WARMUP, MEASURE, DONE }
    private Phase phase = Phase.WARMUP;
    private long phaseStart = System.nanoTime();
    private Pos start = new Pos();
    private double baseY;
    private double theta;
    private final List<Double> frameMs = new ArrayList<>();
    private int settledFrames;
    private record Spike(double dt, double tick, double upd, double ren, double gui, double swap, long gc, long uploads, int chunks) {}
    private final List<Spike> spikes = new ArrayList<>();
    private double pTick, pUpd, pRen, pGui;
    private long pGc, pUploads; private int pChunks;
    private long meshNanos0, meshCount0, genNanos0, genCount0, uploads0;

    BenchRun(double seconds) { this.seconds = seconds; }

    boolean active() { return phase == Phase.MEASURE; }

    void move(PlayerEntity p) {
        p.prev.set(p.pos); p.prevEyeOffset = p.eyeOffset;
        theta += SPEED / RADIUS * Voxelin.TICK;
        p.pos.set(start);
        p.pos.x += (Math.cos(theta) - 1) * RADIUS;
        p.pos.z += Math.sin(theta) * RADIUS;
        p.pos.y = baseY;
        p.pos.normalize();
        p.yaw = (float) Math.toDegrees(theta) + 180f; p.pitch = 18f;
    }

    void frame(PlayScreen ps, Voxelin v) {
        long now = System.nanoTime();
        if (phase == Phase.WARMUP) {
            boolean settled = ps.rend.pending() == 0 && ps.world.loadedChunks().size() > 20;
            settledFrames = settled ? settledFrames + 1 : 0;
            if (settledFrames > 60 || (now - phaseStart) / 1e9 > 90) {
                phase = Phase.MEASURE; phaseStart = now;
                start.set(ps.player.pos);
                baseY = ps.player.pos.y + HEIGHT_ABOVE;
                meshNanos0 = ps.rend.meshNanos; meshCount0 = ps.rend.meshCount; genNanos0 = ps.world.genNanos; genCount0 = ps.world.genCount; uploads0 = ps.rend.uploads;
                System.out.println("[bench] розігрів завершено, вимірювання " + seconds + " с");
            }
            return;
        }
        if (phase != Phase.MEASURE) return;
        double dtMs = v.frameDt * 1000.0;
        frameMs.add(dtMs);
        long gc = gcMillis(); int chunks = ps.world.loadedChunks().size();
        spikes.add(new Spike(dtMs, pTick, pUpd, pRen, pGui, v.swapNs / 1e6, gc - pGc, ps.rend.uploads - pUploads, chunks - pChunks));
        pTick = v.tickNs / 1e6; pUpd = ps.updNs / 1e6; pRen = ps.renNs / 1e6; pGui = ps.guiNs / 1e6; pGc = gc; pUploads = ps.rend.uploads; pChunks = chunks;
        if ((now - phaseStart) / 1e9 < seconds) return;
        phase = Phase.DONE;
        finish(ps, v);
    }

    private static long gcMillis() {
        long t = 0;
        for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, b.getCollectionTime());
        return t;
    }

    private void finish(PlayScreen ps, Voxelin v) {
        List<Spike> worst = new ArrayList<>(spikes);
        worst.sort((a, b) -> Double.compare(b.dt, a.dt));
        System.out.println("[bench] найгірші кадри (мс): повний | логіка | мешинг-завантаження | малювання світу | інтерфейс | swap | GC мс | завантажено секцій | +чанків");
        for (int i = 0; i < Math.min(8, worst.size()); i++) { Spike s = worst.get(i); System.out.printf(Locale.ROOT, "[bench]   %6.1f | %5.1f | %5.1f | %5.1f | %5.1f | %6.1f | %d | %d | %d%n", s.dt, s.tick, s.upd, s.ren, s.gui, s.swap, s.gc, s.uploads, s.chunks); }
        int over20 = 0; for (double d : frameMs) if (d > 20) over20++;
        System.out.println("[bench] кадрів довших за 20 мс: " + over20 + " із " + frameMs.size());
        List<Double> sorted = new ArrayList<>(frameMs);
        Collections.sort(sorted);
        int n = sorted.size();
        double sum = 0; for (double d : sorted) sum += d;
        double avg = sum / n, p99 = sorted.get(Math.min(n - 1, (int) (n * 0.99))), max = sorted.get(n - 1), p50 = sorted.get(n / 2);
        double meshMs = ps.rend.meshCount > meshCount0 ? (ps.rend.meshNanos - meshNanos0) / 1e6 / (ps.rend.meshCount - meshCount0) : 0;
        double genMs = ps.world.genCount > genCount0 ? (ps.world.genNanos - genNanos0) / 1e6 / (ps.world.genCount - genCount0) : 0;
        String json = String.format(Locale.ROOT,
                "{\n  \"version\": \"%s\",\n  \"renderDistance\": %d,\n  \"seconds\": %.1f,\n  \"frames\": %d,\n  \"avgFps\": %.1f,\n  \"medianFrameMs\": %.2f,\n  \"avgFrameMs\": %.2f,\n  \"p99FrameMs\": %.2f,\n  \"onePercentLowFps\": %.1f,\n  \"maxFrameMs\": %.2f,\n  \"meshMsPerSection\": %.3f,\n  \"genMsPerChunk\": %.3f,\n  \"sectionsUploaded\": %d,\n  \"loadedChunks\": %d,\n  \"meshes\": %d,\n  \"gpu\": \"%s\",\n  \"cpus\": %d\n}\n",
                Voxelin.VERSION, v.opts.renderDistance, seconds, n, 1000.0 / avg, p50, avg, p99, 1000.0 / p99, max, meshMs, genMs, ps.rend.uploads - uploads0,
                ps.world.loadedChunks().size(), ps.rend.meshCountLoaded(), v.window.glRenderer().replace('"', '\''), Runtime.getRuntime().availableProcessors());
        System.out.print("[bench]\n" + json);
        try (FileWriter w = new FileWriter(new File(v.opts.benchOut))) { w.write(json); } catch (IOException e) { System.out.println("[bench] не вдалось записати " + v.opts.benchOut + ": " + e); }
        v.quit();
    }
}
