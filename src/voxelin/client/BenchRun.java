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
    private static final float RADIUS = 200f, SPEED = 40f, HEIGHT_ABOVE = 40f;
    private final double seconds;
    private enum Phase { WARMUP, MEASURE, DONE }
    private Phase phase = Phase.WARMUP;
    private long phaseStart = System.nanoTime();
    private float x0, z0, baseY;
    private double theta;
    private final List<Double> frameMs = new ArrayList<>();
    private int settledFrames;
    private long meshNanos0, meshCount0, genNanos0, genCount0, uploads0;

    BenchRun(double seconds) { this.seconds = seconds; }

    boolean active() { return phase == Phase.MEASURE; }

    void move(PlayerEntity p, World w) {
        p.prevX = p.x; p.prevY = p.y; p.prevZ = p.z; p.prevEyeOffset = p.eyeOffset;
        theta += SPEED / RADIUS * Voxelin.TICK;
        p.x = x0 + (float) ((Math.cos(theta) - 1) * RADIUS * Units.BLOCK_SIZE);
        p.z = z0 + (float) (Math.sin(theta) * RADIUS * Units.BLOCK_SIZE);
        p.y = baseY;
        p.yaw = (float) Math.toDegrees(theta) + 180f; p.pitch = 18f;
    }

    void frame(PlayScreen ps, Voxelin v) {
        long now = System.nanoTime();
        if (phase == Phase.WARMUP) {
            boolean settled = ps.rend.pending() == 0 && ps.world.loadedChunks().size() > 20;
            settledFrames = settled ? settledFrames + 1 : 0;
            if (settledFrames > 60 || (now - phaseStart) / 1e9 > 90) {
                phase = Phase.MEASURE; phaseStart = now;
                x0 = ps.player.x; z0 = ps.player.z;
                baseY = ps.player.y + Units.toWorld((int) HEIGHT_ABOVE);
                meshNanos0 = ps.rend.meshNanos; meshCount0 = ps.rend.meshCount; genNanos0 = ps.world.genNanos; genCount0 = ps.world.genCount; uploads0 = ps.rend.uploads;
                System.out.println("[bench] розігрів завершено, вимірювання " + seconds + " с");
            }
            return;
        }
        if (phase != Phase.MEASURE) return;
        frameMs.add(v.frameDt * 1000.0);
        if ((now - phaseStart) / 1e9 < seconds) return;
        phase = Phase.DONE;
        finish(ps, v);
    }

    private void finish(PlayScreen ps, Voxelin v) {
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
