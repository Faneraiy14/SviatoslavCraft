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
 * Параметри (для запуску й перевірок): --saves ТЕКА --mods ТЕКА --render-distance N --texture-size 16|32|64 --msaa N --no-vsync --size ШxВ
 *   --autoworld НАЗВА[:flat] [--seed N]   одразу зайти у світ (створити, якщо нема)
 *   --menu worlds|create|rename            показати екран меню (з --screenshot)
 *   --tp X,Z,YAW,PITCH,ВИСОТА (X,Z - цілі до ±9e18, можна 1e15)             поставити гравця (блоки) · --cmd "/fill ...;/setblock ..."   команди після завантаження
 *   --debug  --freeze                     показати F3 одразу / без фізики гравця (для знімків) · --screenshot файл.png   знімок і вихід · --bench СЕКУНД [--bench-out файл.json]   тест швидкодії і вихід
 */
public final class Voxelin {
    public static final String VERSION = "0.1.0-dev";
    public static final double TICK = 0.05;

    static final class Options {
        File saves = new File("saves"), mods = new File("mods");
        int renderDistance = 12, msaa = 2, width = 1280, height = 720, textureSize = 0; // 0 - авто (за відеокартою) або з налаштувань
        /** Прапорці, задані в командному рядку: вони перекривають settings.properties. */
        final java.util.Set<String> explicit = new java.util.HashSet<>();
        boolean vsync = true;
        float sky = -1f; // --sky 0..1: примусова яскравість неба (інакше - за часом доби)
        String dimension; // --dimension end: стартувати автосвіт у вимірі (для знімків)
        String open; // --open inventory: відкрити вікно на старті (для знімків)
        float showProgress = -1f; // --show-progress 0..1: показати тріщини цілі (для знімків)
        double dayMinutes = -1; // --day-minutes N: довжина доби (інакше - зі світу, типово 30)
        long seed = 12345L;
        String autoWorld, screenshot, benchOut = "bench-result.json";
        boolean autoFlat, debug, freeze;
        String menuScreen;
        /** Автоматичні перевірки (бенчмарк, знімок, заморожений гравець, показ меню): не захоплювати курсор, не відбирати фокус, не слухати мишу. */
        boolean quiet() { return benchSeconds > 0 || screenshot != null || freeze || menuScreen != null; }
        double benchSeconds;
        boolean hasTp;
        long tpX, tpZ;
        float tpYaw, tpPitch, tpH;
        final List<String> cmds = new ArrayList<>();

        static Options parse(String[] a) {
            Options o = new Options();
            for (int i = 0; i < a.length; i++) {
                String k = a[i];
                if (k.equals("--render-distance") || k.equals("--texture-size") || k.equals("--msaa") || k.equals("--no-vsync")) o.explicit.add(k);
                try {
                    switch (k) {
                        case "--saves" -> o.saves = new File(a[++i]);
                        case "--mods" -> o.mods = new File(a[++i]);
                        case "--render-distance" -> o.renderDistance = Math.max(2, Math.min(32, Integer.parseInt(a[++i])));
                        case "--texture-size" -> o.textureSize = Integer.parseInt(a[++i]) >= 64 ? 64 : (Integer.parseInt(a[i]) >= 32 ? 32 : 16);
                        case "--msaa" -> o.msaa = Math.max(0, Math.min(8, Integer.parseInt(a[++i])));
                        case "--no-vsync" -> o.vsync = false;
                        case "--open" -> o.open = a[++i];
                        case "--dimension" -> o.dimension = a[++i];
                        case "--show-progress" -> o.showProgress = Math.max(0f, Math.min(1f, Float.parseFloat(a[++i])));
                        case "--day-minutes" -> o.dayMinutes = Double.parseDouble(a[++i]);
                        case "--sky" -> o.sky = Math.max(0f, Math.min(1f, Float.parseFloat(a[++i])));
                        case "--debug" -> o.debug = true;
                        case "--freeze" -> o.freeze = true;
                        case "--menu" -> o.menuScreen = a[++i];
                        case "--size" -> { String[] s = a[++i].split("x"); o.width = Math.max(320, Integer.parseInt(s[0])); o.height = Math.max(240, Integer.parseInt(s[1])); }
                        case "--seed" -> o.seed = Long.parseLong(a[++i]);
                        case "--autoworld" -> { String s = a[++i]; o.autoFlat = s.endsWith(":flat"); o.autoWorld = o.autoFlat ? s.substring(0, s.length() - 5) : s; }
                        case "--tp" -> { // X,Z (цілі, можна 1e15), YAW, PITCH, ВИСОТА над землею
                            String[] s = a[++i].split(",");
                            o.tpX = new java.math.BigDecimal(s[0].trim()).longValueExact(); o.tpZ = new java.math.BigDecimal(s[1].trim()).longValueExact();
                            o.tpYaw = s.length > 2 ? Float.parseFloat(s[2]) : 0; o.tpPitch = s.length > 3 ? Float.parseFloat(s[3]) : 0; o.tpH = s.length > 4 ? Float.parseFloat(s[4]) : 0;
                            o.hasTp = true;
                        }
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
    voxelin.client.render.TextureArray textures;
    private State state = State.MENU;
    private boolean running = true;
    private PlayScreen play;
    private World loading;
    private long loadingSince;
    private long loadCx, loadCz;
    private String loadError;
    long frames;
    int fps;
    double frameDt;
    /** Час фаз кадру (нс) - для розбору мікрозависань у --bench. */
    long tickNs, swapNs;

    Settings settings;
    File settingsFile;

    private Voxelin(Options opts) {
        this.opts = opts;
        File base = opts.saves.getAbsoluteFile().getParentFile();
        settingsFile = new File(base == null ? new File(".") : base, "settings.properties");
        settings = Settings.load(settingsFile);
        applySettings();
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

    /** Налаштування з файлу -> Options (прапорці командного рядка мають перевагу). */
    void applySettings() {
        if (!opts.explicit.contains("--render-distance")) opts.renderDistance = settings.renderDistance;
        if (!opts.explicit.contains("--texture-size")) opts.textureSize = settings.textureSize;
        if (!opts.explicit.contains("--msaa")) opts.msaa = settings.msaa;
        if (!opts.explicit.contains("--no-vsync")) opts.vsync = settings.vsync;
    }

    /** Зберегти налаштування й застосувати те, що діє одразу; розмір текстур - при наступному відкритті світу. */
    void saveSettings() { settings.save(settingsFile); applySettings(); }

    private void run() {
        window = new Window();
        window.create("Voxelin", opts.width, opts.height, opts.msaa, opts.vsync, !opts.quiet());
        input = new Input(window);
        gui = new Gui(window, input);
        textures = new voxelin.client.render.TextureArray(game.textures, textureSizeNow(), new File("textures"));
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
            tickNs = 0;
            if (state == State.PLAYING) {
                int steps = 0;
                long t0 = System.nanoTime();
                while (acc >= TICK && steps < 5) { play.tick(); acc -= TICK; steps++; }
                if (steps == 5) acc = 0;
                tickNs = System.nanoTime() - t0;
            } else acc = 0;
            frame((float) (acc / TICK));
            long sw0 = System.nanoTime();
            window.swapBuffers();
            swapNs = System.nanoTime() - sw0;
            frames++; frameCount++;
            if (now - fpsAt >= 1_000_000_000L) { fps = frameCount; frameCount = 0; fpsAt = now; }
        }
    }

    int textureSizeNow() { return opts.textureSize == 0 ? Settings.autoTextureSize(window.glRenderer()) : opts.textureSize; }

    /** Пересобрати масив текстур, якщо розмір змінили в налаштуваннях (викликається перед відкриттям світу). */
    private void refreshTextures() {
        int want = textureSizeNow();
        if (textures != null && textures.size != want) { textures.close(); textures = new voxelin.client.render.TextureArray(game.textures, want, new File("textures")); }
    }

    private void autoWorld() {
        try {
            if (!WorldInfo.exists(new File(opts.saves, opts.autoWorld))) game.createWorld(opts.autoWorld, opts.seed, opts.autoFlat ? WorldInfo.Type.FLAT : WorldInfo.Type.NORMAL);
            if ("end".equals(opts.dimension)) {
                World end = game.openDimension(opts.autoWorld, "end");
                end.info.setPlayer(Pos.ofBlock(voxelin.core.gen.EndGenerator.PLATFORM_X, voxelin.core.gen.EndGenerator.TOP + 1 + voxelin.core.entity.PlayerEntity.EYE + 0.02, voxelin.core.gen.EndGenerator.ARRIVAL_Z), 0f, 0f);
                refreshTextures();
                beginLoadingWorld(end);
            } else beginLoading(opts.autoWorld);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ автосвіт: " + e); }
    }

    // ------------------------------------------------------------------ кадр

    private void frame(float alpha) {
        switch (state) {
            case MENU -> {
                if (opts.menuScreen != null && frames == 2) { // dev: --menu worlds|create|rename
                    switch (opts.menuScreen) { case "worlds" -> menus.show(Menus.Screen.WORLDS); case "create" -> menus.show(Menus.Screen.CREATE); case "rename" -> { menus.show(Menus.Screen.WORLDS); menus.show(Menus.Screen.RENAME); } case "settings" -> menus.show(Menus.Screen.SETTINGS); default -> { } }
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
            case PLAYING -> { if (!play.frame(alpha)) leaveWorld(); else if (pendingTravel != null) doTravel(); }
        }
    }

    private String pendingTravel;

    /** Перехід між вимірами (portal): виконується між кадрами. to: "end" або "overworld". */
    void requestTravel(String to) { if (pendingTravel == null) pendingTravel = to; }

    private void doTravel() {
        String to = pendingTravel;
        pendingTravel = null;
        World cur = play.world;
        String base = cur.info.name;
        boolean toEnd = to.equals("end");
        long rx = play.player.pos.blockX(), rz = play.player.pos.blockZ();
        int ry = (int) Math.floor(play.player.pos.y - voxelin.core.entity.PlayerEntity.EYE + 0.05);
        WorldInfo curInfo = cur.info;
        play.close(); // зберігає поточний вимір (інвентар теж)
        play = null; loading = null;
        try {
            World target = toEnd ? game.openDimension(base, "end") : game.openWorld(base);
            target.info.copyPlayerFrom(curInfo);
            if (toEnd) {
                target.info.hasReturn = true; target.info.returnX = rx; target.info.returnY = ry; target.info.returnZ = rz;
                target.info.setPlayer(Pos.ofBlock(voxelin.core.gen.EndGenerator.PLATFORM_X, voxelin.core.gen.EndGenerator.TOP + 1 + voxelin.core.entity.PlayerEntity.EYE + 0.02, voxelin.core.gen.EndGenerator.ARRIVAL_Z), 0f, 0f);
            } else if (curInfo.hasReturn) {
                target.info.setPlayer(Pos.ofBlock(curInfo.returnX, curInfo.returnY + voxelin.core.entity.PlayerEntity.EYE + 0.02, curInfo.returnZ), 0f, 0f);
            }
            beginLoadingWorld(target);
        } catch (IOException | RuntimeException e) {
            System.out.println("[Voxelin] ⚠️ перехід у вимір " + to + " не вдався: " + e);
            state = State.MENU; menus.show(Menus.Screen.WORLDS);
        }
    }

    void beginLoading(String name) {
        refreshTextures();
        try {
            beginLoadingWorld(game.openWorld(name));
        } catch (IOException | RuntimeException e) {
            System.out.println("[Voxelin] ⚠️ не вдалося відкрити світ " + name + ": " + e);
            menus.show(Menus.Screen.WORLDS);
        }
    }

    private void beginLoadingWorld(World w) {
        loading = w;
        WorldInfo i = loading.info;
        long[] land = loading.findLand(0, 0); // новий світ: з'являємось на суші, а не в морі
        loadCx = i.hasPlayer ? i.playerCx : Math.floorDiv(land[0], 16L);
        loadCz = i.hasPlayer ? i.playerCz : Math.floorDiv(land[1], 16L);
        if (opts.hasTp) { loadCx = Math.floorDiv(opts.tpX, 16L); loadCz = Math.floorDiv(opts.tpZ, 16L); }
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
            if (dx * dx + dz * dz > r * r + r || !World.inRange(loadCx + dx, loadCz + dz)) continue; // поза математичним краєм long чанків нема
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
