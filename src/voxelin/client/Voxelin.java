package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.*;
import voxelin.loader.Game;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.lwjgl.opengl.GL11.*;

/**
 * Клієнт Voxelin: вікно, цикл (логіка 20 тіків/с + малювання на повній швидкості), меню, завантаження й гра.
 * Усе ігрове живе в ядрі (voxelin.core) - тут лише показ і ввід.
 *
 * Параметри (для запуску й перевірок): --saves ТЕКА --mods ТЕКА --render-distance N --msaa N --no-vsync --size ШxВ
 *   --autoworld НАЗВА[:flat] [--seed N]   одразу зайти у світ (створити, якщо нема)
 *   --menu worlds|create|rename            показати екран меню (з --screenshot)
 *   --tp X,Z,YAW,PITCH,ВИСОТА             поставити гравця (блоки) · --cmd "/fill ...;/setblock ..."   команди після завантаження
 *   --debug  --freeze                     показати F3 одразу / без фізики гравця (для знімків) · --screenshot файл.png   знімок і вихід · --bench СЕКУНД [--bench-out файл.json]   тест швидкодії і вихід
 */
public final class Voxelin {
    public static final String VERSION = "0.1.0-dev";
    public static final double TICK = 0.05;

    static final class Options {
        File saves = new File("saves"), mods = new File("mods");
        int renderDistance = 12, msaa = 2, width = 1280, height = 720;
        boolean vsync = true;
        long seed = 12345L;
        String autoWorld, screenshot, benchOut = "bench-result.json";
        boolean autoFlat, debug, freeze;
        String menuScreen;
        double benchSeconds;
        float[] tp;
        final List<String> cmds = new ArrayList<>();

        static Options parse(String[] a) {
            Options o = new Options();
            for (int i = 0; i < a.length; i++) {
                String k = a[i];
                try {
                    switch (k) {
                        case "--saves" -> o.saves = new File(a[++i]);
                        case "--mods" -> o.mods = new File(a[++i]);
                        case "--render-distance" -> o.renderDistance = Math.max(2, Math.min(32, Integer.parseInt(a[++i])));
                        case "--msaa" -> o.msaa = Math.max(0, Math.min(8, Integer.parseInt(a[++i])));
                        case "--no-vsync" -> o.vsync = false;
                        case "--debug" -> o.debug = true;
                        case "--freeze" -> o.freeze = true;
                        case "--menu" -> o.menuScreen = a[++i];
                        case "--size" -> { String[] s = a[++i].split("x"); o.width = Math.max(320, Integer.parseInt(s[0])); o.height = Math.max(240, Integer.parseInt(s[1])); }
                        case "--seed" -> o.seed = Long.parseLong(a[++i]);
                        case "--autoworld" -> { String s = a[++i]; o.autoFlat = s.endsWith(":flat"); o.autoWorld = o.autoFlat ? s.substring(0, s.length() - 5) : s; }
                        case "--tp" -> { String[] s = a[++i].split(","); o.tp = new float[5]; for (int j = 0; j < 5; j++) o.tp[j] = j < s.length ? Float.parseFloat(s[j]) : 0; }
                        case "--cmd" -> { for (String c : a[++i].split(";")) if (!c.trim().isEmpty()) o.cmds.add(c.trim()); }
                        case "--screenshot" -> o.screenshot = a[++i];
                        case "--bench" -> o.benchSeconds = Double.parseDouble(a[++i]);
                        case "--bench-out" -> o.benchOut = a[++i];
                        default -> System.out.println("[Voxelin] невідомий параметр " + k);
                    }
                } catch (RuntimeException e) { System.out.println("[Voxelin] ⚠️ поганий параметр " + k + ": " + e); }
            }
            return o;
        }
    }

    private enum State { MENU, LOADING, PLAYING }

    final Options opts;
    final Game game;
    Window window;
    Input input;
    Gui gui;
    private Menus menus;
    private State state = State.MENU;
    private boolean running = true;
    private PlayScreen play;
    private World loading;
    private long loadingSince;
    private int loadCx, loadCz;
    private String loadError;
    long frames;
    int fps;
    double frameDt;

    private Voxelin(Options opts) {
        this.opts = opts;
        this.game = new Game(opts.saves, opts.mods);
        System.out.println("[Voxelin] " + VERSION + ", блоків: " + game.blocks.size() + ", модів: " + game.mods.mods().size() + ", saves=" + opts.saves.getAbsolutePath());
    }

    public static void main(String[] args) {
        Options o = Options.parse(args);
        Voxelin v = null;
        try {
            v = new Voxelin(o);
            v.run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        } finally {
            if (v != null) v.shutdown();
        }
    }

    void quit() { running = false; }

    private void run() {
        window = new Window();
        window.create("Voxelin", opts.width, opts.height, opts.msaa, opts.vsync);
        input = new Input(window);
        gui = new Gui(window, input);
        menus = new Menus(this);
        if (opts.autoWorld != null) autoWorld();

        long last = System.nanoTime(), fpsAt = last;
        double acc = 0;
        int frameCount = 0;
        while (running && !window.shouldClose()) {
            long now = System.nanoTime();
            frameDt = (now - last) / 1e9;
            last = now;
            acc = Math.min(0.25, acc + frameDt);
            if (state == State.PLAYING) {
                int steps = 0;
                while (acc >= TICK && steps < 5) { play.tick(); acc -= TICK; steps++; }
                if (steps == 5) acc = 0;
            } else acc = 0;
            frame((float) (acc / TICK));
            window.swapBuffers();
            frames++; frameCount++;
            if (now - fpsAt >= 1_000_000_000L) { fps = frameCount; frameCount = 0; fpsAt = now; }
        }
    }

    private void autoWorld() {
        try {
            if (!WorldInfo.exists(new File(opts.saves, opts.autoWorld))) game.createWorld(opts.autoWorld, opts.seed, opts.autoFlat ? WorldInfo.Type.FLAT : WorldInfo.Type.NORMAL);
            beginLoading(opts.autoWorld);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ автосвіт: " + e); }
    }

    // ------------------------------------------------------------------ кадр

    private void frame(float alpha) {
        switch (state) {
            case MENU -> {
                if (opts.menuScreen != null && frames == 2) { // dev: --menu worlds|create|rename
                    switch (opts.menuScreen) { case "worlds" -> menus.show(Menus.Screen.WORLDS); case "create" -> menus.show(Menus.Screen.CREATE); case "rename" -> { menus.show(Menus.Screen.WORLDS); menus.show(Menus.Screen.RENAME); } default -> { } }
                }
                glViewport(0, 0, window.fbWidth(), window.fbHeight());
                glClearColor(0.08f, 0.11f, 0.22f, 1f);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                gui.begin();
                menus.frame();
                gui.end();
                if (opts.screenshot != null && frames > 30) { Screenshot.save(window, opts.screenshot); running = false; }
            }
            case LOADING -> loadingFrame();
            case PLAYING -> { if (!play.frame(alpha)) leaveWorld(); }
        }
    }

    void beginLoading(String name) {
        try {
            loading = game.openWorld(name);
        } catch (IOException | RuntimeException e) {
            System.out.println("[Voxelin] ⚠️ не вдалося відкрити світ " + name + ": " + e);
            menus.show(Menus.Screen.WORLDS);
            return;
        }
        WorldInfo i = loading.info;
        loadCx = i.hasPlayer ? Units.toBlock(i.playerX) >> 4 : 0;
        loadCz = i.hasPlayer ? Units.toBlock(i.playerZ) >> 4 : 0;
        if (opts.tp != null) { loadCx = Math.round(opts.tp[0]) >> 4; loadCz = Math.round(opts.tp[1]) >> 4; }
        loadingSince = System.currentTimeMillis();
        loadError = null;
        state = State.LOADING;
        input.clearPressed();
    }

    private void loadingFrame() {
        final int r = 2;
        loading.update(loadCx, loadCz, r + 1);
        int have = 0, need = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r + r) continue;
            need++;
            if (loading.chunk(loadCx + dx, loadCz + dz) != null) have++;
        }
        glViewport(0, 0, window.fbWidth(), window.fbHeight());
        glClearColor(0.08f, 0.11f, 0.22f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        gui.begin();
        Gui.textCentered("ЗАВАНТАЖЕННЯ СВІТУ", Gui.W / 2f, 300, 4.5f, 1f, 0.85f, 0.3f);
        Gui.rect(440, 380, 400, 18, 0.05f, 0.05f, 0.1f, 1f);
        Gui.rect(442, 382, 396 * have / (float) Math.max(1, need), 14, 0.3f, 0.8f, 0.4f, 1f);
        Gui.frame(440, 380, 400, 18, 2, 1, 1, 1);
        gui.end();
        if (have >= need) {
            try {
                play = new PlayScreen(this, loading);
                state = State.PLAYING;
                input.clearPressed();
            } catch (Throwable t) {
                t.printStackTrace();
                loading.close(); loading = null;
                state = State.MENU; menus.show(Menus.Screen.WORLDS);
            }
        } else if (System.currentTimeMillis() - loadingSince > 90_000) {
            System.out.println("[Voxelin] ⚠️ світ не завантажився за 90 с");
            loading.close(); loading = null;
            state = State.MENU; menus.show(Menus.Screen.WORLDS);
        }
    }

    private void leaveWorld() {
        String name = play.world.info.name;
        play.close();
        play = null; loading = null;
        if (opts.autoWorld != null || opts.screenshot != null || opts.benchSeconds > 0) { running = false; return; }
        state = State.MENU;
        menus.show(Menus.Screen.WORLDS);
        menus.select(name);
    }

    private void shutdown() {
        try { if (play != null) { play.close(); play = null; } else if (loading != null) { loading.close(); loading = null; } }
        catch (Throwable t) { System.out.println("[Voxelin] ⚠️ при закритті: " + t); }
        try { if (window != null) window.destroy(); } catch (Throwable ignored) { /* вікно вже закрите */ }
    }
}
