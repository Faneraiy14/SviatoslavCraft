package voxelin.client;

import voxelin.client.render.Camera;
import voxelin.client.render.WorldRenderer;
import voxelin.client.ui.Gui;
import voxelin.content.VanillaContent;
import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUseProgram;

/**
 * Гра у відкритому світі. Логіка - 20 тіків/с (tick), малювання й ввід миші - щокадру (frame) з інтерполяцією між тіками.
 * Усі дії зі світом (ламання, постановка, команди) йдуть через PlayerActions - тобто так само піде мультиплеєр пізніше.
 */
final class PlayScreen {
    private static final class Line { final String text; final long at; Line(String t) { text = t; at = System.currentTimeMillis(); } }

    private final Voxelin v;
    final World world;
    final WorldRenderer rend;
    final Camera cam = new Camera();
    final PlayerEntity player;
    final Inventory inv = new Inventory();
    private final PlayerActions actions;
    private final Deque<Line> chat = new ArrayDeque<>();
    private final Pos spawn;
    private boolean invOpen, chatOpen, debug, hud = true, grabbed;
    private Raycast.Hit hit;
    private long ticks;
    private final BenchRun bench;
    long updNs, renNs, guiNs;
    private int quietFrames;
    private boolean cmdsDone, shotDone;

    PlayScreen(Voxelin v, World world) {
        this.v = v; this.world = world;
        rend = new WorldRenderer(world, v.textures);
        rend.renderDistance = v.opts.renderDistance;
        actions = new LocalActions(world, v.game.commands, Sources.player("Player"));
        spawn = findSpawn(0, 0);
        player = new PlayerEntity(world::isSolid, spawn);
        WorldInfo i = world.info;
        if (i.hasPlayer) {
            player.teleport(i.playerPos()); player.yaw = i.playerYaw; player.pitch = i.playerPitch;
            Pos p = player.pos;
            long fx = p.blockX(), fz = p.blockZ();
            int feet = (int) Math.floor(p.y - PlayerEntity.EYE + 0.05);
            if (world.isSolid(fx, feet, fz) || world.isSolid(fx, p.blockY(), fz)) player.teleport(findSpawn(fx, fz)); // світ змінився - не лишати гравця в блоці
        }
        for (String s : VanillaContent.STARTER) inv.add(s, 64);
        if (v.opts.hasTp) { // X, Z (long, блоки), кути, висота над землею
            Pos sp = findSpawn(v.opts.tpX, v.opts.tpZ);
            sp.add(0, v.opts.tpH, 0);
            player.teleport(sp);
            player.yaw = v.opts.tpYaw; player.pitch = v.opts.tpPitch;
        }
        bench = v.opts.benchSeconds > 0 ? new BenchRun(v.opts.benchSeconds) : null;
        debug = v.opts.debug;
        grab(!v.opts.quiet()); // автоперевірки не захоплюють справжній курсор користувача
        say("Voxelin " + Voxelin.VERSION + ". T або / - команди, E - інвентар, F3 - відладка, F5 - камера.");
    }

    /** Місце появи над землею в колонці (bx,bz): перший вільний прохід на 2 блоки вгору від поверхні. */
    private Pos findSpawn(long bx, long bz) {
        int y = Math.max(1, world.surfaceY(bx, bz) + 1);
        while (y < Chunk.HEIGHT - 3 && (world.isSolid(bx, y, bz) || world.isSolid(bx, y + 1, bz))) y++;
        return Pos.ofBlock(bx, y + PlayerEntity.EYE + 0.02, bz);
    }

    private void grab(boolean g) { grabbed = g; v.window.setMouseGrabbed(g); v.input.resetMouse(); }

    private void say(String s) { chat.addLast(new Line(s)); while (chat.size() > 60) chat.removeFirst(); }

    // ------------------------------------------------------------------ тік

    void tick() {
        ticks++;
        Input in = v.input;
        boolean active = grabbed && !chatOpen && !invOpen;
        if (bench != null && bench.active()) bench.move(player);
        else if (!v.opts.freeze) player.tick(Voxelin.TICK, active && in.down(GLFW_KEY_W), active && in.down(GLFW_KEY_S), active && in.down(GLFW_KEY_A), active && in.down(GLFW_KEY_D),
                active && in.down(GLFW_KEY_SPACE), active && (in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL)), active && in.down(GLFW_KEY_LEFT_SHIFT));
        if (player.pos.y < -20) { player.teleport(spawn); say("Ти впав за межі світу - повернено на спавн."); }
        world.update(player.pos.cx, player.pos.cz, rend.renderDistance + 1);
        world.tick();
    }

    // ------------------------------------------------------------------ кадр

    /** Повертає false, коли треба вийти зі світу в меню. */
    boolean frame(float alpha) {
        Input in = v.input;
        if (in.pressed(GLFW_KEY_ESCAPE)) {
            if (chatOpen) closeChat(); else if (invOpen) { invOpen = false; grab(true); } else return false;
        }
        if (in.pressed(GLFW_KEY_F3)) debug = !debug;
        if (in.pressed(GLFW_KEY_F1)) hud = !hud;
        if (in.pressed(GLFW_KEY_F5)) cam.cycle();
        if (!chatOpen) {
            if (in.pressed(GLFW_KEY_E)) { invOpen = !invOpen; grab(!invOpen); }
            if (in.pressed(GLFW_KEY_T) || in.pressed(GLFW_KEY_SLASH)) { boolean slash = in.down(GLFW_KEY_SLASH); openChat(slash ? "/" : ""); }
        }
        if (chatOpen) { in.updateText(); if (in.pressed(GLFW_KEY_ENTER)) sendChat(); } // не залежить від F1 (HUD)
        boolean play = grabbed && !chatOpen && !invOpen;
        if (play) {
            player.rotate((float) in.mouseDX() * 0.12f, (float) -in.mouseDY() * 0.12f);
            for (int i = 0; i < 9; i++) if (in.pressed(GLFW_KEY_1 + i)) inv.select(i);
            double sc = in.consumeScroll();
            if (sc > 0) inv.scroll(-1); else if (sc < 0) inv.scroll(1);
        } else in.consumeScroll();
        in.resetMouse();

        // камера з інтерполяцією між тіками (усе відносно чанка гравця - малі числа)
        Pos pp = player.pos;
        cam.pos.set(pp);
        cam.pos.x = lerp(player.prev.relX(pp.cx), pp.x, alpha);
        cam.pos.z = lerp(player.prev.relZ(pp.cz), pp.z, alpha);
        cam.pos.y = lerp(player.prev.y + player.prevEyeOffset, pp.y + player.eyeOffset, alpha);
        cam.pos.normalize();
        float yaw = player.yaw, pitch = player.pitch;
        cam.yaw = yaw; cam.pitch = pitch;
        if (cam.mode != Camera.Mode.FIRST) {
            boolean back = cam.mode == Camera.Mode.THIRD_BACK;
            double d = Raycast.distance(cam.pos, back ? yaw + 180f : yaw, back ? -pitch : pitch, Camera.THIRD_MAX, this::solidAt);
            cam.thirdDistance = Math.max(0.2, d - 0.2);
        }
        cam.update(v.window.fbWidth(), v.window.fbHeight(), (rend.renderDistance + 1) * 16 * Units.BLOCK_SIZE);

        hit = play || bench != null ? Raycast.cast(cam.pos, yaw, pitch, Raycast.MAX_REACH, this::solidAt) : null;
        if (play && hit != null) {
            if (in.clicked(GLFW_MOUSE_BUTTON_LEFT)) {
                int id = world.blockId(hit.x(), hit.y(), hit.z());
                String name = id > 0 ? world.blocks.get(id).name : null;
                if (name != null && actions.breakBlock(hit.x(), hit.y(), hit.z())) inv.add(name, 1);
            } else if (in.clicked(GLFW_MOUSE_BUTTON_RIGHT)) {
                Inventory.Stack st = inv.selectedStack();
                if (st != null && !player.overlapsBlock(hit.px(), hit.py(), hit.pz()) && actions.placeBlock(hit.px(), hit.py(), hit.pz(), st.item)) inv.takeSelected();
            }
        } else if (play) { in.clicked(GLFW_MOUSE_BUTTON_LEFT); in.clicked(GLFW_MOUSE_BUTTON_RIGHT); }

        // світ
        glViewport(0, 0, v.window.fbWidth(), v.window.fbHeight());
        glClearColor(rend.fogColor[0], rend.fogColor[1], rend.fogColor[2], 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        long t0 = System.nanoTime();
        rend.update(cam);
        long t1 = System.nanoTime();
        rend.render(cam);
        overlay3d();
        long t2 = System.nanoTime();
        updNs = t1 - t0; renNs = t2 - t1;

        // інтерфейс
        v.gui.begin();
        if (hud) hud(v.gui);
        v.gui.end();
        guiNs = System.nanoTime() - t2;
        devHooks();
        return true;
    }

    private boolean solidAt(long x, int y, long z) { int id = world.blockId(x, y, z); return id > 0 && world.blocks.get(id).solid; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ------------------------------------------------------------------ контур цілі й тіло гравця (фіксований конвеєр поверх шейдерного світу)

    private void overlay3d() {
        glUseProgram(0);
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadMatrixf(cam.proj.m);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadMatrixf(cam.view.m);
        glEnable(GL_DEPTH_TEST);
        final long rcx = cam.eye.cx * 16, rcz = cam.eye.cz * 16; // начало малювання - чанк камери
        final float s = Units.BLOCK_SIZE;
        if (hit != null) {
            float e = 0.012f, x0 = (hit.x() - rcx - e) * s, y0 = (hit.y() - e) * s, z0 = (hit.z() - rcz - e) * s, x1 = (hit.x() - rcx + 1 + e) * s, y1 = (hit.y() + 1 + e) * s, z1 = (hit.z() - rcz + 1 + e) * s;
            glColor4f(1f, 1f, 1f, 1f); glLineWidth(2f);
            glBegin(GL_LINES);
            float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
            int[][] ed = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] k : ed) { glVertex3f(c[k[0]][0], c[k[0]][1], c[k[0]][2]); glVertex3f(c[k[1]][0], c[k[1]][1], c[k[1]][2]); }
            glEnd();
        }
        if (cam.mode != Camera.Mode.FIRST) { // заглушка тіла: коробка розміром із хітбокс
            float hw = (float) (PlayerEntity.WIDTH / 2 * s), hh = (float) (PlayerEntity.HEIGHT / 2 * s);
            float px = (float) (player.pos.relX(cam.eye.cx) * s), cy = (float) (player.bodyCenterY() * s), pz = (float) (player.pos.relZ(cam.eye.cz) * s);
            glDisable(GL_CULL_FACE);
            glBegin(GL_QUADS);
            float[][] shade = {{0.85f, 0.7f, 0.55f}, {0.7f, 0.58f, 0.45f}, {0.78f, 0.64f, 0.5f}};
            for (int a = 0; a < 3; a++) for (int sgn = -1; sgn <= 1; sgn += 2) {
                glColor3f(shade[a][0], shade[a][1], shade[a][2]);
                for (int q = 0; q < 4; q++) {
                    float u = (q == 0 || q == 3) ? -1 : 1, w = (q < 2) ? -1 : 1;
                    float[] p = new float[3];
                    p[a] = sgn; p[(a + 1) % 3] = u; p[(a + 2) % 3] = w;
                    glVertex3f(px + p[0] * hw, cy + p[1] * hh, pz + p[2] * hw);
                }
            }
            glEnd();
            glEnable(GL_CULL_FACE);
        }
        glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW); glPopMatrix();
    }

    // ------------------------------------------------------------------ чат і команди

    private void openChat(String initial) { chatOpen = true; v.input.beginText(initial, true); grab(false); }

    private void closeChat() { chatOpen = false; v.input.endText(); grab(!invOpen); }

    private void sendChat() {
        String line = v.input.text().trim();
        closeChat();
        if (line.isEmpty()) return;
        if (line.startsWith("/")) {
            say("> " + line);
            long[] o = origin();
            for (String r : actions.command(line, o)) say(r);
        } else say("<Гравець> " + line);
    }

    /** Блок під ногами гравця {x, y, z} - для ~ у командах. */
    private long[] origin() { Pos p = player.pos; return new long[]{p.blockX(), (long) Math.floor(p.y - PlayerEntity.EYE + 0.05), p.blockZ()}; }

    // ------------------------------------------------------------------ HUD

    private void hud(Gui g) {
        Input in = v.input;
        if (cam.mode == Camera.Mode.FIRST && !invOpen && !chatOpen) {
            Gui.rect(Gui.W / 2f - 12, Gui.H / 2f - 1, 24, 2, 1, 1, 1, 0.85f);
            Gui.rect(Gui.W / 2f - 1, Gui.H / 2f - 12, 2, 24, 1, 1, 1, 0.85f);
        }
        hotbar(g);
        long now = System.currentTimeMillis();
        List<Line> shown = new ArrayList<>();
        for (Line l : chat) if (chatOpen || now - l.at < 9000) shown.add(l);
        int from = Math.max(0, shown.size() - (chatOpen ? 16 : 8));
        float y = Gui.H - 130 - (shown.size() - from) * 24;
        for (int i = from; i < shown.size(); i++) {
            float a = chatOpen ? 1f : Math.max(0.15f, Math.min(1f, (9000 - (now - shown.get(i).at)) / 1500f));
            Gui.rect(8, y - 2, Math.min(1100, Gui.textWidth(shown.get(i).text, 2.2f) + 12), 24, 0, 0, 0, 0.45f * a);
            Gui.text(shown.get(i).text, 12, y + 2, 2.2f, 1, 1, 1);
            y += 24;
        }
        if (chatOpen) {
            Gui.rect(8, Gui.H - 112, 1264, 30, 0, 0, 0, 0.7f);
            Gui.text("> " + in.text() + ((now / 500) % 2 == 0 ? "_" : ""), 14, Gui.H - 107, 2.4f, 1, 1, 0.8f);
        }
        if (invOpen) inventory(g);
        if (debug) debugText();
    }

    private int colorOf(String item) { BlockType t = world.blocks.byName(item); return t == null ? 0xFF00FF : t.color; }

    private void slot(Gui g, float x, float y, float size, Inventory.Stack st, boolean selected, boolean hov) {
        Gui.rect(x - 2, y - 2, size + 4, size + 4, selected ? 1f : 0.08f, selected ? 1f : 0.08f, selected ? 1f : 0.08f, 0.9f);
        Gui.rect(x, y, size, size, hov ? 0.42f : 0.3f, hov ? 0.42f : 0.3f, hov ? 0.45f : 0.33f, 0.92f);
        if (st == null) return;
        float pad = size * 0.2f;
        Gui.rect(x + pad, y + pad, size - 2 * pad, size - 2 * pad, colorOf(st.item), 1f);
        Gui.frame(x + pad, y + pad, size - 2 * pad, size - 2 * pad, 1.5f, 0, 0, 0);
        String c = String.valueOf(st.count);
        Gui.text(c, x + size - Gui.textWidth(c, 1.8f) - 3, y + size - 15, 1.8f, 1, 1, 1);
    }

    private void hotbar(Gui g) {
        float size = 54, gap = 6, total = 9 * (size + gap) - gap, x0 = (Gui.W - total) / 2f, y = Gui.H - size - 14;
        Gui.rect(x0 - 8, y - 8, total + 16, size + 16, 0, 0, 0, 0.45f);
        for (int i = 0; i < 9; i++) slot(g, x0 + i * (size + gap), y, size, inv.get(i), i == inv.selected(), false);
        Inventory.Stack st = inv.selectedStack();
        if (st != null && !invOpen) Gui.textCentered(st.item.substring(st.item.indexOf(':') + 1).replace('_', ' '), Gui.W / 2f, y - 26, 2.2f, 1, 1, 1);
    }

    private void inventory(Gui g) {
        float size = 62, gap = 8, total = 9 * (size + gap) - gap, x0 = (Gui.W - total) / 2f, y0 = 170;
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.5f);
        Gui.rect(x0 - 24, y0 - 60, total + 48, 4 * (size + gap) + 120, 0.5f, 0.5f, 0.52f, 0.97f);
        Gui.frame(x0 - 24, y0 - 60, total + 48, 4 * (size + gap) + 120, 3, 0.15f, 0.15f, 0.15f);
        Gui.text("ІНВЕНТАР", x0 - 8, y0 - 44, 3.2f, 0.15f, 0.15f, 0.15f);
        String tip = null;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int idx = row < 3 ? 9 + row * 9 + col : col; // три рядки сховища, нижче - хотбар
            float x = x0 + col * (size + gap), y = y0 + row * (size + gap) + (row == 3 ? 14 : 0);
            boolean hov = g.over(x, y, size, size);
            slot(g, x, y, size, inv.get(idx), idx == inv.selected(), hov);
            if (hov) {
                Inventory.Stack st = inv.get(idx);
                if (st != null) tip = st.item;
                if (g.click) {
                    g.click = false;
                    if (idx < Inventory.HOTBAR) inv.select(idx);
                    else { Inventory.Stack a = inv.get(idx), b = inv.get(inv.selected()); inv.set(idx, b); inv.set(inv.selected(), a); }
                }
            }
        }
        if (tip != null) Gui.text(tip, x0 - 8, y0 + 4 * (size + gap) + 30, 2.4f, 0.1f, 0.1f, 0.1f);
        Gui.text("Крафт і новий інвентар - етап «Виживання»", x0 - 8, y0 + 4 * (size + gap) + 58, 1.8f, 0.3f, 0.3f, 0.3f);
    }

    private void debugText() {
        Pos p = player.pos;
        String[] lines = {
                "Voxelin " + Voxelin.VERSION,
                "XYZ: " + p.fmtX() + " " + String.format(Locale.ROOT, "%.1f", p.y - PlayerEntity.EYE) + " " + p.fmtZ() + "  (блоки)",
                String.format(Locale.ROOT, "Кут: %.0f / %.0f   FPS: %d   чанк: %d %d", player.yaw, player.pitch, v.fps, p.cx, p.cz),
                "Чанків: " + world.loadedChunks().size() + "  мешів: " + rend.meshCountLoaded() + "  у черзі: " + rend.pending(),
                "Видно секцій: " + rend.visibleSections + "  граней: " + rend.drawnQuads,
                String.format(Locale.ROOT, "Мешинг: %.2f мс/секція   Генерація: %.2f мс/чанк", rend.meshCount == 0 ? 0 : rend.meshNanos / 1e6 / rend.meshCount, world.genCount == 0 ? 0 : world.genNanos / 1e6 / world.genCount),
                "Дальність: " + rend.renderDistance + " чанків   " + v.window.glRenderer(),
                "Ціль: " + (hit == null ? "-" : hit.x() + " " + hit.y() + " " + hit.z() + "  " + world.blocks.get(Math.max(0, world.blockId(hit.x(), hit.y(), hit.z()))).name)
        };
        for (int i = 0; i < lines.length; i++) {
            Gui.rect(8, 8 + i * 26, Gui.textWidth(lines[i], 2.1f) + 12, 24, 0, 0, 0, 0.5f);
            Gui.text(lines[i], 12, 12 + i * 26, 2.1f, i == 7 ? 1f : 0.9f, i == 7 ? 0.85f : 1f, i == 7 ? 0.3f : 0.9f);
        }
    }

    // ------------------------------------------------------------------ dev: команди, знімок, тест швидкодії

    private void devHooks() {
        boolean settled = rend.pending() == 0 && world.loadedChunks().size() > 4;
        quietFrames = settled ? quietFrames + 1 : 0;
        if (!cmdsDone && quietFrames > 20 && !v.opts.cmds.isEmpty()) {
            cmdsDone = true; quietFrames = 0;
            long t0 = System.nanoTime();
            for (String c : v.opts.cmds) { for (String r : actions.command(c, origin())) { say(r); System.out.println("[cmd] " + c + " -> " + r); } }
            System.out.println("[cmd] виконано за " + (System.nanoTime() - t0) / 1_000_000 + " мс");
        }
        boolean cmdsReady = v.opts.cmds.isEmpty() || cmdsDone;
        if (v.opts.screenshot != null && !shotDone && cmdsReady && (quietFrames > 40 || v.frames > 60 * 60)) {
            shotDone = true; Screenshot.save(v.window, v.opts.screenshot); v.quit();
        }
        if (bench != null) bench.frame(this, v);
    }

    int ticks() { return (int) ticks; }

    /** Зберегти позицію й закрити (виклик при виході зі світу чи гри). */
    void close() {
        world.info.setPlayer(player.pos, player.yaw, player.pitch);
        rend.close();
        world.close();
        grab(false);
        v.input.endText();
    }
}
