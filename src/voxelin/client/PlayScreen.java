package voxelin.client;

import voxelin.client.render.Camera;
import voxelin.client.render.IconAtlas;
import voxelin.client.render.WorldRenderer;
import voxelin.client.ui.Gui;
import voxelin.content.VanillaContent;
import voxelin.core.*;
import voxelin.core.entity.Mob;
import voxelin.core.entity.PlayerEntity;
import voxelin.core.entity.Vitals;
import voxelin.api.Source;

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
    final Inventory inv;
    private final PlayerActions actions;
    private final Deque<Line> chat = new ArrayDeque<>();
    private static final float[] UNDER_FOG = {0.07f, 0.2f, 0.45f};
    private final Pos spawn;
    private InventoryScreen invScreen, benchScreen, activeScreen;
    private TradeScreen trade;
    private JournalScreen journal;
    private KeeperScreen keeper;
    private IconAtlas icons;
    private boolean invOpen, chatOpen, debug, hud = true, grabbed;
    private Raycast.Hit hit;
    private long ticks;
    private final BenchRun bench;
    long updNs, renNs, guiNs;
    private int quietFrames;
    private boolean cmdsDone, shotDone;
    private final Source playerSrc = Sources.player("Player");
    private final Vitals vt;
    private boolean leaving, deadHandled, portalLock = true;
    private PlayerTarget playerTarget;
    private Mob aimedMob;
    private double attackCd;
    private long openedX, openedZ; private int openedY; private boolean openedBlock; private String openedKind; // відкрите вікно блока (піч)
    // ламання блока: ціль, прогрес 0..1, пауза між блоками
    private long breakX, breakZ; private int breakY; private boolean breaking;
    private double breakProgress, breakCooldown;
    private long lastFrameNs = System.nanoTime();
    private double prevHealth = 20, hurtFlash;
    private long bannerDay = -1, bannerAt;

    final voxelin.client.audio.GameSounds sounds;
    private double hitSoundCd;

    PlayScreen(Voxelin v, World world) {
        this.v = v; this.world = world;
        sounds = new voxelin.client.audio.GameSounds(v.audio, world);
        world.sound = sounds;
        rend = new WorldRenderer(world, v.textures);
        rend.renderDistance = v.opts.renderDistance;
        rend.outlines = v.settings.outlines;
        cam.fov = v.settings.fov;
        if (v.opts.dayMinutes > 0) world.info.dayMinutes = Math.max(DayCycle.MIN_MINUTES, Math.min(DayCycle.MAX_MINUTES, v.opts.dayMinutes));
        actions = new LocalActions(world, v.game.commands, playerSrc);
        vt = world.vitals(playerSrc);
        inv = world.inventory(playerSrc);
        playerTarget = new PlayerTarget() {
            public Pos body() { return bodyPos(); }
            public boolean alive() { return !vt.dead(); }
            public boolean creative() { return world.creative(); }
            public void notify(String message) { say(message); }
            public void hurt(double damage, double kx, double kz) { hurt(damage, kx, kz, 0); }
            public void hurt(double damage, double kx, double kz, double pierce) {
                int pts = inv.armorPoints(world.itemTypes);
                double reduced = Inventory.reduce(damage, pts, pierce);
                double before = vt.health();
                vt.hurt(reduced, false);
                if (vt.health() < before && !world.creative()) inv.wearArmor(world.itemTypes, damage, new java.util.Random());
                player.knock(kx * 6, 4, kz * 6);
            }
            public void teleport(long x, int y, long z) { player.teleport(Pos.ofBlock(x, y + PlayerEntity.EYE + 0.02, z)); }
        };
        world.setPlayerTarget(playerTarget);
        if (world.dimension().equals("end")) { world.populateEnd(); world.unlockRecord("voxelin:end_arrival"); }
        if (world.dimension().equals(Dimensions.BETWEEN)) world.unlockRecord("voxelin:between");
        icons = new IconAtlas(v.textures, world);
        java.util.function.Consumer<Inventory.Stack> dropper = st -> { if (st != null) world.spawnItem(bodyPos(), st.item, st.count, st.damage, true); };
        invScreen = InventoryScreen.craft("ІНВЕНТАР", new CraftMenu(world.itemTypes, v.game.recipes, inv, 2), inv, world, icons, dropper);
        benchScreen = InventoryScreen.craft("ВЕРСТАК", new CraftMenu(world.itemTypes, v.game.recipes, inv, 3), inv, world, icons, dropper);
        activeScreen = invScreen;
        prevHealth = vt.health();
        long[] land = world.findLand(0, 0); // не з'являтись у морі
        spawn = findSpawn(land[0], land[1]);
        player = new PlayerEntity(world::isSolid, world::isLiquid, spawn);
        WorldInfo i = world.info;
        if (i.hasPlayer) {
            player.teleport(i.playerPos()); player.yaw = i.playerYaw; player.pitch = i.playerPitch;
            Pos p = player.pos;
            long fx = p.blockX(), fz = p.blockZ();
            int feet = (int) Math.floor(p.y - PlayerEntity.EYE + 0.05);
            if (world.isSolid(fx, feet, fz) || world.isSolid(fx, p.blockY(), fz)) player.teleport(findSpawn(fx, fz)); // світ змінився - не лишати гравця в блоці
        }
        if (world.creative() && inv.isEmpty()) for (String s : VanillaContent.STARTER) inv.add(s, 64);
        if (v.opts.hasTp) { // X, Z (long, блоки), кути, висота над землею
            Pos sp = findSpawn(v.opts.tpX, v.opts.tpZ);
            sp.add(0, v.opts.tpH, 0);
            player.teleport(sp);
            player.yaw = v.opts.tpYaw; player.pitch = v.opts.tpPitch;
        }
        bench = v.opts.benchSeconds > 0 ? new BenchRun(v.opts.benchSeconds) : null;
        debug = v.opts.debug;
        grab(!v.opts.quiet()); // автоперевірки не захоплюють справжній курсор користувача
        if ("inventory".equals(v.opts.open)) invOpen = true;
        if ("workbench".equals(v.opts.open)) { activeScreen = benchScreen; invOpen = true; }
        if ("chest".equals(v.opts.open)) { // лише для знімків: скриня з жеребом здобичі
            ChestEntity demo = new ChestEntity(0, 0, 0);
            LootTable lt = world.loot.get("dungeon");
            if (lt != null) lt.fill(demo, new java.util.Random(5), world.itemTypes);
            activeScreen = InventoryScreen.container("СКРИНЯ", new ChestMenu(world.itemTypes, inv, demo), inv, world, icons, st -> { });
            invOpen = true;
        }
        if ("trade".equals(v.opts.open)) { // лише для знімків: розмова з ковалем
            MobType smith = world.mobTypes.get("voxelin:villager_smith");
            if (smith != null) { trade = new TradeScreen(new Mob(smith, bodyPos()), world, inv, icons, new java.util.Random(3)); invOpen = true; }
        }
        if ("furnace".equals(v.opts.open)) { // лише для знімків: піч із вигаданим станом (у світі не існує)
            FurnaceEntity demo = new FurnaceEntity(0, 0, 0);
            demo.input = new Inventory.Stack("voxelin:raw_iron", 5); demo.fuel = new Inventory.Stack("voxelin:coal", 3); demo.output = new Inventory.Stack("voxelin:iron_ingot", 2);
            demo.burnLeft = 900; demo.burnTotal = 1600; demo.progress = 120; demo.cookTotal = 200;
            activeScreen = InventoryScreen.furnace(new FurnaceMenu(world.itemTypes, v.game.recipes, inv, demo), inv, world, icons, st -> { });
            invOpen = true;
        }
        say("Voxelin " + Voxelin.VERSION + ". T або / - команди, E - інвентар, F3 - відладка, F5 - камера.");
    }

    /** Центр тіла гравця - від нього рахується підбір і випадіння речей. */
    private Pos bodyPos() { Pos c = player.pos.copy(); c.y = player.bodyCenterY(); return c; }

    private ItemType held() { Inventory.Stack st = inv.selectedStack(); return st == null ? null : world.itemTypes.get(st.item); }

    /** Місце появи над землею в колонці (bx,bz): перший вільний прохід на 2 блоки вгору від поверхні. */
    /** Небо міжсвіття: сіро-фіолетове; темніше, поки у світі триває зараження, світліше після Ритуалу. */
    private float[] betweenSky() {
        if (world.info.overInf == WorldInfo.InfState.ACTIVE) return new float[]{0.20f, 0.17f, 0.25f};
        if (world.info.ritualStage >= 2) return new float[]{0.62f, 0.60f, 0.70f};
        return new float[]{0.42f, 0.40f, 0.48f};
    }

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
        boolean active = grabbed && !chatOpen && !invOpen && !vt.dead();
        player.wings = inv.canGlide(world.itemTypes);
        if (bench != null && bench.active()) bench.move(player);
        else if (!v.opts.freeze) player.tick(Voxelin.TICK, active && in.down(GLFW_KEY_W), active && in.down(GLFW_KEY_S), active && in.down(GLFW_KEY_A), active && in.down(GLFW_KEY_D),
                active && in.down(GLFW_KEY_SPACE), active && (in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL)), active && in.down(GLFW_KEY_LEFT_SHIFT));
        if (!vt.dead() && !v.opts.freeze) {
            boolean sprinting = active && in.down(GLFW_KEY_W) && (in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL));
            if (sprinting) vt.exhaust(0.1 * Math.hypot(player.pos.relX(player.prev.cx) - player.prev.x, player.pos.relZ(player.prev.cz) - player.prev.z));
            if (player.jumped) vt.exhaust(sprinting ? 0.2 : 0.05);
            double fell = player.takeLanded();
            sounds.land(player, fell);
            vt.land(fell);
            if (world.info.type == WorldInfo.Type.BETWEEN && Math.abs(player.pos.blockX() - voxelin.core.gen.BetweenGenerator.HOUSE_X) <= 3 && Math.abs(player.pos.blockZ() - voxelin.core.gen.BetweenGenerator.HOUSE_Z) <= 3
                    && player.pos.y > voxelin.core.gen.BetweenGenerator.TOP && player.pos.y < voxelin.core.gen.BetweenGenerator.TOP + 6) { world.unlockRecord("voxelin:keeper_home"); world.unlockRecord("voxelin:threads"); } // зайшов у Дім Сторожа
            double imp = player.takeImpact();
            if (imp > 0) vt.hurt(imp, false);
            if (player.gliding && world.tickCount() % 20 == 0 && !world.creative()) inv.wearWings(world.itemTypes);
            vt.tick(player.headInWater);
        }
        if (trade != null) trade.mob.talkTicks = 6; // житель слухає
        { // портал: ноги в блоці порталу -> перехід у інший вимір (після виходу з порталу замок знімається)
            BlockType feet = world.blocks.get(Math.max(0, world.blockId(player.pos.blockX(), (int) Math.floor(player.pos.y - PlayerEntity.EYE + 0.05), player.pos.blockZ())));
            boolean inPortal = "portal".equals(feet.interact), inRift = "rift".equals(feet.interact);
            if (!inPortal && !inRift) portalLock = false;
            else if (!portalLock && !vt.dead() && !v.opts.freeze) { portalLock = true; sounds.ui("voxelin:portal.travel"); v.requestTravel(inRift ? Dimensions.BETWEEN : Dimensions.portalTarget(world.dimension())); }
        }
        if (!vt.dead() && !v.opts.freeze && world.collectItems(bodyPos(), inv) > 0) sounds.self("voxelin:item.pickup", 0.6f);
        sounds.tick(player, !vt.dead());
        if (vt.dead() && !deadHandled && !world.creative()) {
            Pos at = bodyPos();
            for (Inventory.Stack st : inv.drain()) world.spawnItem(at, st.item, st.count, st.damage, true);
        }
        if (vt.dead() && !deadHandled) { deadHandled = true; hud = true; if (invOpen) closeInventory(); invOpen = false; chatOpen = false; v.input.endText(); grab(false); say("Ти загинув."); }
        if (player.pos.y < -20) { player.teleport(spawn); if (!vt.dead()) vt.damage(4); say("Ти впав за межі світу - повернено на спавн."); }
        world.update(player.pos.cx, player.pos.cz, rend.renderDistance + 1);
        world.tick();
    }

    // ------------------------------------------------------------------ кадр

    /** Повертає false, коли треба вийти зі світу в меню. */
    boolean frame(float alpha) {
        Input in = v.input;
        if (leaving) return false;
        if (invOpen && openedBlock && !openedKind.equals(world.blocks.get(Math.max(0, world.blockId(openedX, openedY, openedZ))).interact)) closeInventory(); // блок зламано/зник
        if (in.pressed(GLFW_KEY_ESCAPE) && !vt.dead()) {
            if (chatOpen) closeChat(); else if (invOpen) { closeInventory(); } else return false;
        }
        if (in.pressed(GLFW_KEY_F3)) debug = !debug;
        if (in.pressed(GLFW_KEY_F1)) hud = !hud;
        if (in.pressed(GLFW_KEY_F5)) cam.cycle();
        if (!chatOpen && !vt.dead()) {
            if (in.pressed(GLFW_KEY_E)) { if (invOpen) closeInventory(); else { activeScreen = invScreen; invOpen = true; grab(false); } }
            if (in.pressed(GLFW_KEY_J) && !invOpen) { journal = new JournalScreen(world); invOpen = true; grab(false); }
            if (in.pressed(GLFW_KEY_T) || in.pressed(GLFW_KEY_SLASH)) { boolean slash = in.down(GLFW_KEY_SLASH); openChat(slash ? "/" : ""); }
        }
        if (chatOpen) { in.updateText(); if (in.pressed(GLFW_KEY_ENTER)) sendChat(); } // не залежить від F1 (HUD)
        boolean play = grabbed && !chatOpen && !invOpen && !vt.dead();
        if (play) {
            float sens = v.settings.mouseSensitivity;
            player.rotate((float) in.mouseDX() * sens, (float) -in.mouseDY() * sens);
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
        boolean under = world.isLiquid(cam.pos.blockX(), cam.pos.blockY(), cam.pos.blockZ());
        rend.underwater = under;
        boolean endDim = world.dimension().equals("end"), betweenDim = world.dimension().equals(Dimensions.BETWEEN);
        double phase = world.dayPhase(), sky = v.opts.sky >= 0 ? Math.max(DayCycle.NIGHT_SKY, v.opts.sky) : (endDim || betweenDim ? world.skyLevel() : DayCycle.sky(phase));
        rend.skyBrightness = (float) sky;
        float[] horizon = endDim ? new float[]{0.10f, 0.05f, 0.16f} : betweenDim ? betweenSky() : DayCycle.skyColor(sky, v.opts.sky >= 0 ? 0 : DayCycle.glow(phase));
        if (under) { float k = (float) Math.max(0.25, sky); rend.fogColor = new float[]{UNDER_FOG[0] * k, UNDER_FOG[1] * k, UNDER_FOG[2] * k}; }
        else rend.fogColor = horizon;
        long dn = world.dayNumber();
        if (dn != bannerDay) { if (bannerDay >= 0) bannerAt = System.currentTimeMillis(); bannerDay = dn; }
        if (vt.health() < prevHealth - 0.01) { hurtFlash = 1; sounds.self("voxelin:player.hurt", 1f); }
        v.audio.listener(cam.pos, yaw);
        prevHealth = vt.health();
        hurtFlash = Math.max(0, hurtFlash - 0.03);
        cam.update(v.window.fbWidth(), v.window.fbHeight(), (rend.renderDistance + 1) * 16 * Units.BLOCK_SIZE);

        hit = play || bench != null || v.opts.showProgress >= 0 ? Raycast.cast(cam.pos, yaw, pitch, Raycast.MAX_REACH, this::solidAt) : null;
        long nowNs = System.nanoTime();
        double dt = Math.min(0.1, (nowNs - lastFrameNs) / 1e9);
        lastFrameNs = nowNs;
        if (breakCooldown > 0) breakCooldown -= dt;
        hitSoundCd -= dt;
        attackCd = Math.max(0, attackCd - dt);
        aimedMob = play ? Combat.raycast(world, cam.pos, yaw, pitch, 3.6, hit == null ? 99 : hit.distance()) : null;
        boolean pressed = play && in.clicked(GLFW_MOUSE_BUTTON_LEFT);
        if (pressed && aimedMob != null) attackMob(aimedMob);
        if (play && aimedMob == null && hit != null) updateBreaking(in, dt);
        else { breaking = false; breakProgress = 0; }
        if (play && in.clicked(GLFW_MOUSE_BUTTON_RIGHT)) useItem();
        else if (!play) { in.clicked(GLFW_MOUSE_BUTTON_RIGHT); }

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

    /** Утримання лівої кнопки: прогрес ламання цілі; скидається при зміні блока чи відпусканні. */
    private void updateBreaking(Input in, double dt) {
        in.clicked(GLFW_MOUSE_BUTTON_LEFT); // «щойно натиснуто» нам не потрібне - дивимось на утримання
        if (!in.buttonDown(GLFW_MOUSE_BUTTON_LEFT)) { breaking = false; breakProgress = 0; return; }
        int id = world.blockId(hit.x(), hit.y(), hit.z());
        if (id <= 0) { breaking = false; breakProgress = 0; return; }
        if (!breaking || breakX != hit.x() || breakY != hit.y() || breakZ != hit.z()) { breaking = true; breakX = hit.x(); breakY = hit.y(); breakZ = hit.z(); breakProgress = 0; }
        if (breakCooldown > 0) return;
        BlockType bt = world.blocks.get(id);
        ItemType tool = held();
        double secs = Mining.seconds(bt, tool, world.creative());
        if (secs == Double.POSITIVE_INFINITY) return;
        breakProgress = secs <= 0 ? 1 : breakProgress + dt / secs;
        if (breakProgress < 1) { if (hitSoundCd <= 0) { hitSoundCd = 0.25; sounds.block(bt, breakX, breakY, breakZ, "hit"); } return; }
        boolean ok = actions.breakBlock(breakX, breakY, breakZ, tool == null ? null : tool.name, !world.creative());
        breaking = false; breakProgress = 0; breakCooldown = 0.2;
        if (!ok) return;
        sounds.block(bt, breakX, breakY, breakZ, "break");
        vt.exhaust(0.005);
        if (!world.creative() && tool != null) inv.wearSelected(tool.durability);
    }

    /** Удар по істоті: шкода від предмета в руці (рука - 1), відкидання, зношення зброї, кулдаун 0.4 с. */
    private void attackMob(Mob m) {
        if (attackCd > 0) return;
        attackCd = 0.4;
        ItemType tool = held();
        double dmg = tool == null ? 1 : tool.attack;
        boolean hurt = Combat.attack(world, m, dmg, bodyPos());
        sounds.self(hurt ? "voxelin:player.attack" : "voxelin:player.swing", 0.8f);
        if (hurt) {
            vt.exhaust(0.1);
            if (!world.creative() && tool != null) inv.wearSelected(tool.durability);
        }
    }

    /** Права кнопка: з'їсти їжу (якщо голодний) або поставити блок з руки. */
    private void useItem() {
        boolean sneak = v.input.down(GLFW_KEY_LEFT_SHIFT) || v.input.down(GLFW_KEY_RIGHT_SHIFT);
        if (aimedMob != null && !sneak && aimedMob.type.talkable() && !vt.dead()) { // розмова з жителем
            if ("keeper".equals(aimedMob.type.dialog)) { keeper = new KeeperScreen(world, inv, this::say); invOpen = true; grab(false); return; }
            if (aimedMob.type.name.endsWith("villager_elder")) world.unlockRecord("voxelin:elder");
            trade = new TradeScreen(aimedMob, world, inv, icons, new java.util.Random());
            invOpen = true; grab(false);
            return;
        }
        BlockType target = hit == null ? null : world.blocks.get(Math.max(0, world.blockId(hit.x(), hit.y(), hit.z())));
        if (hit != null && !sneak) { // Ключ Порогу: розрив у міжсвіття
            int rifts = world.info.rifts.size();
            String msg = Rift.open(world, inv, hit.x(), hit.y(), hit.z(), world.creative());
            if (world.info.rifts.size() > rifts) sounds.play("voxelin:rift.open", Pos.ofBlock(hit.x(), hit.y() + 1.5, hit.z()), 1.2f, 1f);
            if (msg != null) { say(msg); return; }
        }
        if (target != null && target.name.equals(Infection.CORE)) { // ядро зараження: стан або зупинка (зілля, вогонь, лімініт)
            String msg = world.infection.use(inv, hit.x(), hit.y(), hit.z(), world.creative());
            if (msg != null) { say(msg); return; }
        }
        if (target != null && "end_frame".equals(target.interact)) { // Око Енду в рамку Шву
            String msg = EndPortal.insertEye(world, playerSrc, hit.x(), hit.y(), hit.z(), inv, world.creative());
            if (msg != null) { say(msg); if (msg.contains("відчинився")) world.unlockRecord("voxelin:gate"); }
            return;
        }
        if (target != null && target.interact != null && !"portal".equals(target.interact) && !"end_frame_eye".equals(target.interact) && !sneak) { // верстак, піч: права кнопка відкриває вікно (присід - ставити блок)
            if ("workbench".equals(target.interact)) { activeScreen = benchScreen; invOpen = true; grab(false); }
            else if ("chest".equals(target.interact)) {
                sounds.self("voxelin:chest.open", 0.8f);
                ChestEntity ce = world.chestAt(hit.x(), hit.y(), hit.z());
                if (ce != null) {
                    activeScreen = InventoryScreen.container("СКРИНЯ", new ChestMenu(world.itemTypes, inv, ce), inv, world, icons, st -> { if (st != null) world.spawnItem(bodyPos(), st.item, st.count, st.damage, true); });
                    openedX = hit.x(); openedY = hit.y(); openedZ = hit.z(); openedBlock = true; openedKind = "chest";
                    invOpen = true; grab(false);
                }
            }
            else if ("furnace".equals(target.interact)) {
                FurnaceEntity fe = world.furnaceAt(hit.x(), hit.y(), hit.z());
                if (fe != null) {
                    activeScreen = InventoryScreen.furnace(new FurnaceMenu(world.itemTypes, v.game.recipes, inv, fe), inv, world, icons, st -> { if (st != null) world.spawnItem(bodyPos(), st.item, st.count, st.damage, true); });
                    openedX = hit.x(); openedY = hit.y(); openedZ = hit.z(); openedBlock = true; openedKind = "furnace";
                    invOpen = true; grab(false);
                }
            }
            return;
        }
        Inventory.Stack st = inv.selectedStack();
        ItemType t = st == null ? null : world.itemTypes.get(st.item);
        if (t == null) return;
        if (t.armorSlot != null) { inv.equipSelected(world.itemTypes); return; } // ПКМ з бронею в руці - надягти
        if (t.food > 0) {
            if (vt.hunger() < Vitals.MAX && !vt.dead()) { vt.feed(t.food); sounds.self("voxelin:player.eat", 0.9f); if (!world.creative()) inv.takeSelected(); }
        } else if (hit != null && t.placesBlock != null && !player.overlapsBlock(hit.px(), hit.py(), hit.pz()) && actions.placeBlock(hit.px(), hit.py(), hit.pz(), t.placesBlock)) {
            sounds.block(world.blocks.get(Math.max(0, world.blockId(hit.px(), hit.py(), hit.pz()))), hit.px(), hit.py(), hit.pz(), "place");
            if (!world.creative()) inv.takeSelected();
        }
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
        drawCracks(rcx, rcz, s);
        drawItems(rcx, rcz, s);
        drawMobs(s);
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

    /** Тріщини на цілі: затемнення + ламані лінії на гранях, їх більшає з прогресом (детерміновано за позицією блока). */
    private void drawCracks(long rcx, long rcz, float s) {
        double prog = v.opts.showProgress >= 0 && hit != null ? v.opts.showProgress : (breaking ? breakProgress : 0);
        long bx = v.opts.showProgress >= 0 && hit != null ? hit.x() : breakX, bz = v.opts.showProgress >= 0 && hit != null ? hit.z() : breakZ;
        int by = v.opts.showProgress >= 0 && hit != null ? hit.y() : breakY;
        if (prog <= 0.001) return;
        float e = 0.006f, x0 = (bx - rcx - e) * s, y0 = (by - e) * s, z0 = (bz - rcz - e) * s, x1 = (bx - rcx + 1 + e) * s, y1 = (by + 1 + e) * s, z1 = (bz - rcz + 1 + e) * s;
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glColor4f(0f, 0f, 0f, (float) (0.12 + 0.38 * prog));
        glBegin(GL_QUADS);
        glVertex3f(x0, y1, z0); glVertex3f(x0, y1, z1); glVertex3f(x1, y1, z1); glVertex3f(x1, y1, z0); // верх
        glVertex3f(x0, y0, z0); glVertex3f(x1, y0, z0); glVertex3f(x1, y0, z1); glVertex3f(x0, y0, z1); // низ
        glVertex3f(x0, y0, z0); glVertex3f(x0, y1, z0); glVertex3f(x1, y1, z0); glVertex3f(x1, y0, z0);
        glVertex3f(x0, y0, z1); glVertex3f(x1, y0, z1); glVertex3f(x1, y1, z1); glVertex3f(x0, y1, z1);
        glVertex3f(x0, y0, z0); glVertex3f(x0, y0, z1); glVertex3f(x0, y1, z1); glVertex3f(x0, y1, z0);
        glVertex3f(x1, y0, z0); glVertex3f(x1, y1, z0); glVertex3f(x1, y1, z1); glVertex3f(x1, y0, z1);
        glEnd();
        glColor4f(0f, 0f, 0f, 0.85f); glLineWidth(2f);
        int steps = 2 + (int) (prog * 14);
        for (int face = 0; face < 6; face++) {
            java.util.Random r = new java.util.Random(bx * 73856093L ^ bz * 19349663L ^ by * 83492791L ^ face * 2654435761L);
            for (int branch = 0; branch < 2; branch++) {
                double u = 0.5, w = 0.5, ang = r.nextDouble() * Math.PI * 2;
                glBegin(GL_LINE_STRIP);
                for (int i = 0; i < steps; i++) {
                    float[] p = facePoint(face, u, w, x0, y0, z0, x1, y1, z1);
                    glVertex3f(p[0], p[1], p[2]);
                    ang += (r.nextDouble() - 0.5) * 1.6;
                    u = Math.max(0.02, Math.min(0.98, u + Math.cos(ang) * 0.11)); w = Math.max(0.02, Math.min(0.98, w + Math.sin(ang) * 0.11));
                }
                glEnd();
            }
        }
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private static float[] facePoint(int face, double u, double w, float x0, float y0, float z0, float x1, float y1, float z1) {
        float fu = (float) u, fw = (float) w;
        switch (face) {
            case 0: return new float[]{x0 + (x1 - x0) * fu, y1, z0 + (z1 - z0) * fw};
            case 1: return new float[]{x0 + (x1 - x0) * fu, y0, z0 + (z1 - z0) * fw};
            case 2: return new float[]{x0 + (x1 - x0) * fu, y0 + (y1 - y0) * fw, z0};
            case 3: return new float[]{x0 + (x1 - x0) * fu, y0 + (y1 - y0) * fw, z1};
            case 4: return new float[]{x0, y0 + (y1 - y0) * fu, z0 + (z1 - z0) * fw};
            default: return new float[]{x1, y0 + (y1 - y0) * fu, z0 + (z1 - z0) * fw};
        }
    }

    /** Істоти: коробчаті моделі з анімацією ходьби (ноги), червоним відтінком при ударі, нахилом на бік при смерті; світло - з рушія світла в точці. */
    private void drawMobs(float s) {
        java.util.List<Mob> list = world.mobs();
        if (list.isEmpty()) return;
        glDisable(GL_CULL_FACE);
        int drawn = 0;
        for (Mob m : list) {
            double dx = m.pos.relX(cam.eye.cx) - cam.eye.x, dz = m.pos.relZ(cam.eye.cz) - cam.eye.z, dy = m.pos.y - cam.eye.y;
            if (dx * dx + dz * dz + dy * dy > 64 * 64) continue;
            if (++drawn > 150) break;
            Chunk c = world.chunk(m.pos.cx, m.pos.cz);
            int l = c == null ? 0xF0 : c.lightAt((int) Math.floorMod(m.pos.blockX(), 16L), Math.min(255, m.pos.blockY() + 1), (int) Math.floorMod(m.pos.blockZ(), 16L));
            float light = (float) Math.max(0.18, Math.max(Math.pow(0.8, 15 - (l >> 4)) * rend.skyBrightness, Math.pow(0.8, 15 - (l & 15))));
            float tr = 1f, tg = 1f, tb = 1f;
            if (m.hurtTicks > 0) { tr = 1f; tg = 0.45f; tb = 0.45f; }
            else if (m.burning) { tr = 1f; tg = 0.75f; tb = 0.4f; }
            double speed = Math.hypot(m.vx, m.vz);
            float swing = (float) (Math.sin(m.walk * 3.2) * 38 * Math.min(1, speed / 1.5));
            glPushMatrix();
            glTranslatef((float) (m.pos.relX(cam.eye.cx) * s), (float) (m.pos.y * s), (float) (m.pos.relZ(cam.eye.cz) * s));
            glScalef(s, s, s);
            glRotatef(-m.yaw, 0, 1, 0);
            if (m.dying) { float roll = (float) Math.min(90, m.deathTicks / Mob.DEATH_TICKS * 90); glTranslatef((float) m.type.width / 2, 0, 0); glRotatef(-roll, 0, 0, 1); glTranslatef((float) -m.type.width / 2, 0, 0); }
            for (voxelin.api.MobSettings.Part p : m.type.parts) {
                float r = ((p.rgb >> 16) & 255) / 255f * light * tr, g = ((p.rgb >> 8) & 255) / 255f * light * tg, b = (p.rgb & 255) / 255f * light * tb;
                glPushMatrix();
                if (p.swing == 1 || p.swing == 2) { glTranslatef(p.cx, p.y0 + p.h, p.cz); glRotatef(p.swing == 1 ? swing : -swing, 1, 0, 0); glTranslatef(-p.cx, -(p.y0 + p.h), -p.cz); }
                else if (p.swing >= 3) { // крило: махає навколо осі вперед від внутрішнього краю
                    float px0 = p.cx - Math.signum(p.cx) * p.w / 2f, flap = (float) (Math.sin(m.age * 0.3 + (p.swing == 3 ? 0 : 0)) * 32);
                    glTranslatef(px0, p.y0 + p.h / 2, p.cz); glRotatef(p.swing == 3 ? -flap : flap, 0, 0, 1); glTranslatef(-px0, -(p.y0 + p.h / 2), -p.cz);
                }
                box(p.cx, p.y0, p.cz, p.w, p.h, p.d, r, g, b);
                glPopMatrix();
            }
            glPopMatrix();
        }
        glEnable(GL_CULL_FACE);
    }

    private static void box(float cx, float y0, float cz, float w, float h, float d, float r, float g, float b) {
        float x0 = cx - w / 2, x1 = cx + w / 2, y1 = y0 + h, z0 = cz - d / 2, z1 = cz + d / 2;
        glBegin(GL_QUADS);
        glColor3f(r, g, b); glVertex3f(x0, y1, z0); glVertex3f(x0, y1, z1); glVertex3f(x1, y1, z1); glVertex3f(x1, y1, z0); // верх
        glColor3f(r * 0.5f, g * 0.5f, b * 0.5f); glVertex3f(x0, y0, z0); glVertex3f(x1, y0, z0); glVertex3f(x1, y0, z1); glVertex3f(x0, y0, z1);
        glColor3f(r * 0.8f, g * 0.8f, b * 0.8f); glVertex3f(x0, y0, z0); glVertex3f(x0, y1, z0); glVertex3f(x1, y1, z0); glVertex3f(x1, y0, z0); // перед (-z)
        glVertex3f(x0, y0, z1); glVertex3f(x1, y0, z1); glVertex3f(x1, y1, z1); glVertex3f(x0, y1, z1);
        glColor3f(r * 0.65f, g * 0.65f, b * 0.65f); glVertex3f(x0, y0, z0); glVertex3f(x0, y0, z1); glVertex3f(x0, y1, z1); glVertex3f(x0, y1, z0);
        glVertex3f(x1, y0, z0); glVertex3f(x1, y1, z0); glVertex3f(x1, y1, z1); glVertex3f(x1, y0, z1);
        glEnd();
    }

    /** Предмети на землі: маленькі кольорові кубики, що крутяться й погойдуються (кольори - з предмета, затемнені вночі). */
    private void drawItems(long rcx, long rcz, float s) {
        java.util.List<voxelin.core.entity.ItemEntity> list = world.itemEntities();
        if (list.isEmpty()) return;
        float light = (float) Math.max(0.3, rend.skyBrightness);
        double t = System.currentTimeMillis() / 1000.0;
        float half = 0.14f * s;
        glDisable(GL_CULL_FACE);
        int drawn = 0;
        for (voxelin.core.entity.ItemEntity en : list) {
            double dx = en.pos.relX(cam.eye.cx) - cam.eye.x, dz = en.pos.relZ(cam.eye.cz) - cam.eye.z, dy = en.pos.y - cam.eye.y;
            if (dx * dx + dz * dz + dy * dy > 48 * 48) continue;
            if (++drawn > 300) break;
            ItemType it = world.itemTypes.get(en.item);
            int rgb = it == null ? 0xFF00FF : it.color;
            float r = ((rgb >> 16) & 255) / 255f * light, g = ((rgb >> 8) & 255) / 255f * light, b = (rgb & 255) / 255f * light;
            float px = (float) (en.pos.relX(cam.eye.cx) * s), pz = (float) (en.pos.relZ(cam.eye.cz) * s);
            float bob = (float) (Math.sin(t * 2.5 + (en.pos.x + en.pos.z)) * 0.04 + 0.1) * s;
            glPushMatrix();
            glTranslatef(px, (float) (en.pos.y * s) + (en.onGround ? bob : 0), pz);
            glRotatef((float) ((t * 70 + en.pos.x * 40) % 360), 0, 1, 0);
            glBegin(GL_QUADS);
            float[] sh = {1.0f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f};
            for (int f = 0; f < 6; f++) {
                glColor3f(r * sh[f], g * sh[f], b * sh[f]);
                int ax = f >> 1; float sg = (f & 1) == 0 ? half : -half;
                for (int q = 0; q < 4; q++) {
                    float u = (q == 0 || q == 3) ? -half : half, w = q < 2 ? -half : half;
                    float[] p = new float[3];
                    p[ax] = sg; p[(ax + 1) % 3] = u; p[(ax + 2) % 3] = w;
                    glVertex3f(p[0], p[1], p[2]);
                }
            }
            glEnd();
            glPopMatrix();
        }
        glEnable(GL_CULL_FACE);
    }

    // ------------------------------------------------------------------ чат і команди

    private void openChat(String initial) { chatOpen = true; v.input.beginText(initial, true); grab(false); }

    private void closeInventory() { if (trade != null || journal != null || keeper != null) { trade = null; journal = null; keeper = null; invOpen = false; grab(true); return; } openedBlock = false; activeScreen.close(); invOpen = false; grab(true); }

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
            Gui.rect(Gui.W / 2f - 13, Gui.H / 2f - 2, 26, 4, 0, 0, 0, 0.45f); // темний обвід - приціл видно і на небі, і на снігу
            Gui.rect(Gui.W / 2f - 2, Gui.H / 2f - 13, 4, 26, 0, 0, 0, 0.45f);
            Gui.rect(Gui.W / 2f - 12, Gui.H / 2f - 1, 24, 2, 1, 1, 1, 0.9f);
            Gui.rect(Gui.W / 2f - 1, Gui.H / 2f - 12, 2, 24, 1, 1, 1, 0.9f);
        }
        hotbar(g);
        vitalsHud();
        if (world.info.infState == WorldInfo.InfState.ACTIVE) { // відлік зараження в правому верхньому куті
            Infection.Phase ph = world.infection.phase();
            String t = "ШОВ: день " + Math.min(Infection.DAYS, world.infection.day() + 1) + "/" + Infection.DAYS;
            float r = ph == Infection.Phase.QUIET ? 0.75f : 1f, gg = ph == Infection.Phase.CRISIS ? 0.3f : 0.6f;
            Gui.text(t, Gui.W - 300, 12, 2.2f, r, gg, 1f);
        }
        int bars = 0;
        for (Mob boss : world.mobs()) if (boss.type.boss && boss.alive() && bars < 2) { // смуги босів вгорі екрана (до двох: Ткач і Розплітач)
            float w = 520, x = (Gui.W - w) / 2f, oy = bars * 52;
            Gui.text(boss.type.title + (boss.enraged ? "  (лютує)" : ""), x, 8 + oy, 2.4f, 0.95f, boss.enraged ? 0.45f : 0.8f, 1f);
            Gui.rect(x - 2, 34 + oy, w + 4, 16, 0, 0, 0, 0.7f);
            Gui.rect(x, 36 + oy, (float) (w * Math.max(0, boss.health / boss.type.health)), 12, boss.enraged ? 0.9f : 0.7f, 0.15f, boss.enraged ? 0.3f : 0.8f, 1f);
            bars++;
        }
        if (aimedMob != null && !invOpen && !chatOpen) {
            String t = aimedMob.type.title + "  " + Math.max(0, (int) Math.ceil(aimedMob.health)) + "/" + (int) aimedMob.type.health;
            float w = Gui.textWidth(t, 2.2f) + 16, x = (Gui.W - w) / 2f;
            Gui.rect(x, 26, w, 44, 0, 0, 0, 0.5f);
            Gui.text(t, x + 8, 32, 2.2f, 1, 1, 1);
            Gui.rect(x + 8, 56, w - 16, 8, 0.15f, 0.15f, 0.15f, 1f);
            Gui.rect(x + 8, 56, (w - 16) * (float) Math.max(0, aimedMob.health / aimedMob.type.health), 8, 0.85f, 0.2f, 0.2f, 1f);
        }
        if (breaking && breakProgress > 0.01 && cam.mode == Camera.Mode.FIRST) {
            Gui.rect(Gui.W / 2f - 31, Gui.H / 2f + 21, 62, 7, 0, 0, 0, 0.6f);
            Gui.rect(Gui.W / 2f - 30, Gui.H / 2f + 22, (float) (60 * Math.min(1, breakProgress)), 5, 1f, 1f, 1f, 0.95f);
        }
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
        if (invOpen) {
            if (journal != null) { journal.draw(g); if (journal.close) closeInventory(); }
            else if (keeper != null) { keeper.draw(g); if (keeper.close) closeInventory(); }
            else if (trade != null) { trade.draw(g); if (trade.close) closeInventory(); }
            else activeScreen.draw(g, v.input);
        }
        if (hurtFlash > 0) Gui.rect(0, 0, Gui.W, Gui.H, 0.8f, 0.05f, 0.05f, (float) (0.35 * hurtFlash));
        if (System.currentTimeMillis() - bannerAt < 4000 && bannerDay > 1) {
            float a = (float) Math.min(1, (4000 - (System.currentTimeMillis() - bannerAt)) / 1000.0);
            Gui.textCentered("ДЕНЬ " + bannerDay, Gui.W / 2f, 120, 5f, 1f, 0.9f, 0.4f * a + 0.2f);
        }
        if (vt.dead()) deathScreen(g);
        if (debug) debugText();
    }

    // ------------------------------------------------------------------ здоров'я, голод, повітря, смерть

    // Піксельні іконки 9 px: O - контур, X - основний колір, H - відблиск, S - тінь, B - кістка (у їжі)
    private static final String[] HEART = {".OO...OO.", "OHHO.OXXO", "OHXXOXXXO", "OXXXXXXSO", ".OXXXXSO.", "..OXXSO..", "...OSO...", "....O...."};
    private static final String[] FOOD = {".....OOO.", "....OXHXO", "...OXHXXO", "...OXXXSO", "..OBOXSO.", ".OBO.OO..", "OBBO.....", "OBBO.....", ".OO......"};
    private static final String[] SHIELD = {"OO.....OO", "OHOOOOOXO", "OHXXXXXXO", ".OHXXXXO.", ".OHXXXSO.", ".OXXXXSO.", ".OOOOOOO."};
    private static final String[] BUBBLE = {".OOOOO.", "OHHXXXO", "OHXXXXO", "OXXXXSO", "OXXXSSO", ".OOOOO."};
    private static final float[][] HEART_C = {{0.16f, 0.02f, 0.03f}, {0.86f, 0.1f, 0.12f}, {1f, 0.72f, 0.72f}, {0.55f, 0.04f, 0.07f}, {1, 1, 1}};
    private static final float[][] FOOD_C = {{0.17f, 0.08f, 0.03f}, {0.74f, 0.43f, 0.18f}, {0.95f, 0.7f, 0.42f}, {0.48f, 0.25f, 0.09f}, {0.94f, 0.9f, 0.8f}};
    private static final float[][] SHIELD_C = {{0.1f, 0.12f, 0.16f}, {0.72f, 0.77f, 0.86f}, {0.97f, 0.98f, 1f}, {0.45f, 0.5f, 0.6f}, {1, 1, 1}};
    private static final float[][] BUBBLE_C = {{0.1f, 0.25f, 0.5f}, {0.45f, 0.7f, 1f}, {0.92f, 0.97f, 1f}, {0.28f, 0.48f, 0.85f}, {1, 1, 1}};
    private static final float ICON_PX = 2.5f, ICON_STEP = 24f;

    /** full: 0 порожня (лише контур і темне нутро), 1 половина (ліва), 2 повна. outline - колір контуру замість звичайного (спалах болю) або null. */
    private static void icon(String[] pat, float x, float y, float px, int full, float[][] c, float[] outline) {
        for (int row = 0; row < pat.length; row++) for (int col = 0; col < pat[row].length(); col++) {
            char ch = pat[row].charAt(col);
            if (ch == '.') continue;
            float[] k = switch (ch) { case 'O' -> outline != null ? outline : c[0]; case 'H' -> c[2]; case 'S' -> c[3]; case 'B' -> c[4]; default -> c[1]; };
            boolean lit = ch == 'O' || full == 2 || (full == 1 && col <= pat[row].length() / 2);
            if (lit) Gui.rect(x + col * px, y + row * px, px, px, k[0], k[1], k[2], 1f);
            else Gui.rect(x + col * px, y + row * px, px, px, c[1][0] * 0.18f + 0.04f, c[1][1] * 0.18f + 0.04f, c[1][2] * 0.18f + 0.04f, 0.92f);
        }
    }

    /** Темна підкладка під рядом іконок (як під хотбаром). */
    private static void plate(float x, float y, float w, float h) {
        Gui.rect(x, y, w, h, 0, 0, 0, 0.45f);
        Gui.rect(x, y, w, 1, 1, 1, 1, 0.08f); // ледь світліший верхній край
    }

    private void vitalsHud() {
        if (vt.dead()) return;
        float size = 54, gap = 6, total = 9 * (size + gap) - gap, x0 = (Gui.W - total) / 2f, px = ICON_PX, step = ICON_STEP;
        float rowW = 9 * step + 9 * px, pad = 6, ph = 8 * px + 2 * pad, py = Gui.H - size - 14 - 8 - 8 - ph; // підкладки на 8 px вище плити хотбара
        long now = System.currentTimeMillis();
        int hp = (int) Math.ceil(vt.health()), food = (int) Math.ceil(vt.hunger());
        boolean low = vt.health() <= 6, hungry = vt.hunger() <= 6;
        float[] flash = hurtFlash > 0.4 && (now / 80) % 2 == 0 ? new float[]{1, 1, 1} : null; // щойно вдарили - контур сердець блимає
        plate(x0 - 8, py, rowW + 2 * pad, ph);
        for (int i = 0; i < 10; i++) {
            float wob = low ? (float) Math.sin(now / 90.0 + i * 1.7) * 1.5f : 0; // мало здоров'я - серця тремтять
            icon(HEART, x0 - 8 + pad + i * step, py + pad + wob, px, hp >= 2 * i + 2 ? 2 : (hp == 2 * i + 1 ? 1 : 0), HEART_C, flash);
        }
        float fx = x0 + total + 8 - rowW - 2 * pad;
        plate(fx, py, rowW + 2 * pad, ph);
        for (int i = 0; i < 10; i++) {
            float shake = hungry && (now / 120 + i * 7) % 11 == 0 ? 1.5f : 0; // голод: шматки зрідка смикаються
            icon(FOOD, fx + pad + (9 - i) * step, py + pad - 1 + shake, px, food >= 2 * i + 2 ? 2 : (food == 2 * i + 1 ? 1 : 0), FOOD_C, null); // справа наліво, як у Minecraft
        }
        int apts = inv.armorPoints(world.itemTypes);
        if (apts > 0) {
            float ay = py - ph - 4;
            plate(x0 - 8, ay, rowW + 2 * pad, ph);
            int sh = (apts + 1) / 2;
            for (int i = 0; i < 10; i++) icon(SHIELD, x0 - 8 + pad + i * step, ay + pad + px / 2, px, sh >= i + 1 ? (apts % 2 == 1 && sh == i + 1 ? 1 : 2) : 0, SHIELD_C, null);
        }
        if (vt.air() < Vitals.MAX_AIR) {
            float ay = py - ph - 4;
            int bubbles = (int) Math.ceil(Math.max(0, vt.air()) / Vitals.MAX_AIR * 10);
            plate(fx, ay, rowW + 2 * pad, ph);
            for (int i = 0; i < bubbles; i++) icon(BUBBLE, fx + pad + (9 - i) * step + px, ay + pad + px, px, 2, BUBBLE_C, null);
        }
    }

    /** Верх найвищої підкладки над хотбаром (броня/повітря або здоров'я) - щоб назва предмета не налазила. */
    private float vitalsTop() {
        float ph = 8 * ICON_PX + 12, py = Gui.H - 54 - 14 - 16 - ph;
        return inv.armorPoints(world.itemTypes) > 0 || vt.air() < Vitals.MAX_AIR ? py - ph - 4 : py;
    }

    private void deathScreen(Gui g) {
        Gui.rect(0, 0, Gui.W, Gui.H, 0.45f, 0.02f, 0.02f, 0.62f);
        Gui.textCentered("ТИ ЗАГИНУВ", Gui.W / 2f, 230, 7f, 1f, 0.9f, 0.9f);
        Gui.textCentered("День " + world.dayNumber(), Gui.W / 2f, 310, 2.8f, 0.9f, 0.8f, 0.8f);
        if (g.button("ВІДРОДИТИСЬ", 440, 380, 400, 64)) respawn();
        if (g.button("У МЕНЮ", 440, 466, 400, 64)) leaving = true;
    }

    private void respawn() {
        vt.respawn();
        player.teleport(spawn);
        deadHandled = false; prevHealth = vt.health(); hurtFlash = 0;
        grab(true);
    }

    private int lastSel = -1;
    private String lastSelItem;
    private long selShownAt;

    private void hotbar(Gui g) {
        float size = 54, gap = 6, total = 9 * (size + gap) - gap, x0 = (Gui.W - total) / 2f, y = Gui.H - size - 14;
        Gui.rect(x0 - 8, y - 8, total + 16, size + 16, 0, 0, 0, 0.45f);
        for (int i = 0; i < 9; i++) { float x = x0 + i * (size + gap); invScreen.slotBox(x, y, size, i == inv.selected(), false); invScreen.drawStack(inv.get(i), x, y, size); }
        Inventory.Stack st = inv.selectedStack();
        String name = st == null ? null : st.item;
        if (inv.selected() != lastSel || !java.util.Objects.equals(name, lastSelItem)) { lastSel = inv.selected(); lastSelItem = name; selShownAt = System.currentTimeMillis(); }
        long age = System.currentTimeMillis() - selShownAt;
        if (st != null && !invOpen && age < 2500) { // назва предмета - коротко після вибору, потім згасає (не висить постійно)
            float a = Math.min(1f, (2500 - age) / 500f), ty = (vt.dead() ? y - 8 : vitalsTop()) - 30;
            String t = InventoryScreen.titleOf(world, st.item);
            float w = Gui.textWidth(t, 2.2f) + 16;
            Gui.rect((Gui.W - w) / 2f, ty - 4, w, 26, 0, 0, 0, 0.4f * a);
            Gui.textCentered(t, Gui.W / 2f, ty + 2, 2.2f, a, a, a);
        }
    }

    private String lightLine() {
        Pos p = player.pos;
        Chunk c = world.chunk(p.cx, p.cz);
        if (c == null) return "Світло: -";
        int lx = p.blockX() >= 0 ? (int) (p.blockX() & 15) : (int) (p.blockX() & 15), lz = (int) (p.blockZ() & 15);
        int y = (int) Math.floor(p.y - PlayerEntity.EYE + 0.5), l = c.lightAt(lx, y, lz);
        return "Світло: небо " + (l >> 4) + "  блок " + (l & 15);
    }

    private void debugText() {
        Pos p = player.pos;
        String[] lines = {
                "Voxelin " + Voxelin.VERSION,
                "XYZ: " + p.fmtX() + " " + String.format(Locale.ROOT, "%.1f", p.y - PlayerEntity.EYE) + " " + p.fmtZ() + "  (блоки)",
                String.format(Locale.ROOT, "Кут: %.0f / %.0f   FPS: %d   чанк: %d %d", player.yaw, player.pitch, v.fps, p.cx, p.cz),
                lightLine(),
                "Режим: " + world.gameMode() + "   предметів на землі: " + world.itemEntities().size() + "   істот: " + world.mobs().size(),
                String.format(Locale.ROOT, "День %d, %s (%.0f%%)   небо %.2f   здоров'я %.1f  голод %.1f  повітря %d", world.dayNumber(), DayCycle.phaseName(world.dayPhase()), world.dayPhase() * 100, world.skyLevel(), vt.health(), vt.hunger(), vt.air()),
                "Біом: " + world.biomeAt(p.blockX(), p.blockZ()) + (player.inWater ? "  (у воді)" : ""),
                "Чанків: " + world.loadedChunks().size() + "  мешів: " + rend.meshCountLoaded() + "  у черзі: " + rend.pending(),
                "Видно секцій: " + rend.visibleSections + "  граней: " + rend.drawnQuads,
                String.format(Locale.ROOT, "Мешинг: %.2f мс/секція   Генерація: %.2f мс/чанк", rend.meshCount == 0 ? 0 : rend.meshNanos / 1e6 / rend.meshCount, world.genCount == 0 ? 0 : world.genNanos / 1e6 / world.genCount),
                "Дальність: " + rend.renderDistance + " чанків   " + v.window.glRenderer(),
                "Ціль: " + (hit == null ? "-" : hit.x() + " " + hit.y() + " " + hit.z() + "  " + world.blocks.get(Math.max(0, world.blockId(hit.x(), hit.y(), hit.z()))).name)
        };
        for (int i = 0; i < lines.length; i++) {
            Gui.rect(8, 8 + i * 26, Gui.textWidth(lines[i], 2.1f) + 12, 24, 0, 0, 0, 0.5f);
            Gui.text(lines[i], 12, 12 + i * 26, 2.1f, i == lines.length - 1 ? 1f : 0.9f, i == lines.length - 1 ? 0.85f : 1f, i == lines.length - 1 ? 0.3f : 0.9f);
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
        sounds.close();
        world.sound = voxelin.core.SoundSink.NONE;
        if (invOpen) activeScreen.close();
        if (vt.dead()) player.teleport(spawn); // збереження в момент смерті: гравець з'явиться на спавні
        world.info.setPlayer(player.pos, player.yaw, player.pitch);
        rend.close();
        icons.close();
        world.close();
        grab(false);
        v.input.endText();
    }
}
