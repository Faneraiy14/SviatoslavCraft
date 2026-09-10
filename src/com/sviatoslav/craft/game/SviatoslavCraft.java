package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.*;
import com.sviatoslav.craft.engine.world.*;
import static org.lwjgl.glfw.GLFW.*;

public class SviatoslavCraft {
    private enum GameState { MENU, PLAYING }
    private Window window;
    private InputHandler input;
    private Renderer renderer;
    private GameLoop gameLoop;
    private DebugOverlay debug;
    private GameState state = GameState.MENU;
    private boolean running = true;
    private World world;
    private Player player;
    private Inventory inventory;
    private MenuManager menu;

    public void start() {
        window = new LwjglWindow();
        window.create("SviatoslavCraft", 1280, 720);
        input = new InputHandler(window.getHandle());
        renderer = new Renderer();
        debug = new DebugOverlay();
        menu = new MenuManager(window, input, 1280, 720);
        gameLoop = new GameLoop();
        gameLoop.start(this::update, this::render, this::shouldClose);
        if (world != null) { world.shutdown(); world.saveAllChunks(); }
        window.destroy();
    }

    private void update(double deltaTime) {
        if (state == GameState.MENU) {
            String action = menu.update();
            if (action.equals("PLAY")) { state = GameState.PLAYING; window.setMouseGrabbed(true); initGame(); }
            else if (action.equals("EXIT")) running = false;
        } else updateGame(deltaTime);
    }

    private void render() {
        if (state == GameState.MENU) menu.render();
        else {
            renderer.prepare();
            renderer.getCamera().updateProjection();
            renderer.getCamera().applyView();
            if (world != null) world.render(renderer);

            // Контур навколо блока, на який дивишся - той самий raycast, що
            // й для ламання/постановки, лише для відображення (реальна
            // фіча, якої в чернетці не було взагалі).
            if (player != null && world != null) {
                var hit = BlockRayCast.cast(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), world);
                if (hit != null) renderer.renderWireCube(hit.hitX() + 0.5f, hit.hitY() + 0.5f, hit.hitZ() + 0.5f, 1.02f);
            }

            if (debug.isVisible() && player != null) {
                debug.render(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                             gameLoop.getFPS(), world != null ? world.getChunks().values().stream().mapToInt(c -> c.getBlocks().size()).sum() : 0);
            }
        }
        window.swapBuffers();
    }

    private void initGame() {
        System.out.println("Генерація світу...");
        world = new World();
        renderer.getCamera().setY(50.0f);
        player = new Player(renderer.getCamera(), world);
        inventory = new Inventory();
        for (int i = 0; i < 20; i++) { world.update(player.getX(), player.getZ()); try { Thread.sleep(50); } catch (InterruptedException e) {} }
        boolean found = false;
        for (int y = 50; y > 0; y--) {
            Block b = world.getBlock((int)player.getX(), y, (int)player.getZ());
            if (b != null && b.getType() != Block.Type.AIR) { renderer.getCamera().setY(y+2.0f); player = new Player(renderer.getCamera(), world); found = true; break; }
        }
        if (!found) { renderer.getCamera().setY(50.0f); player = new Player(renderer.getCamera(), world); }
        System.out.println("Гра починається!");
    }

    private void updateGame(double deltaTime) {
        float dt = (float) deltaTime;
        renderer.getCamera().rotate((float)input.getMouseDX()*0.12f, (float)-input.getMouseDY()*0.12f);
        input.resetMouse();
        if (input.isKeyPressed(GLFW_KEY_F3)) { debug.toggle(); sleep(200); }
        for (int i = 0; i < 5; i++) if (input.isKeyPressed(GLFW_KEY_1 + i)) inventory.selectSlot(i);
        player.updatePhysics(dt);
        if (input.isKeyPressed(GLFW_KEY_SPACE)) player.jump();
        float speed = player.getSpeed() * dt;
        if (input.isKeyPressed(GLFW_KEY_W)) player.moveForward(speed);
        if (input.isKeyPressed(GLFW_KEY_S)) player.moveBackward(speed);
        if (input.isKeyPressed(GLFW_KEY_A)) player.moveLeft(speed);
        if (input.isKeyPressed(GLFW_KEY_D)) player.moveRight(speed);
        world.update(player.getX(), player.getZ());

        // РЕАЛЬНА фіча - ламання (ЛКМ) і постановка (ПКМ) блоків. У
        // чернетці DeepSeek таблиця можливостей стверджувала, що "логіка
        // є, лише не прив'язана до кліку" - насправді жодного raycast'а
        // взагалі не існувало ніде в коді.
        if (input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            var hit = BlockRayCast.cast(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), world);
            if (hit != null) world.setBlock(hit.hitX(), hit.hitY(), hit.hitZ(), new Block(Block.Type.AIR, 0, 0, 0));
        }
        if (input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            var hit = BlockRayCast.cast(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), world);
            Block.Type selected = inventory.getSelectedBlockType();
            if (hit != null && selected != null) {
                world.setBlock(hit.placeX(), hit.placeY(), hit.placeZ(), new Block(selected, 0, 0, 0));
                inventory.removeSelectedItem();
            }
        }

        if (player.getY() < -10) { renderer.getCamera().setY(50.0f); player = new Player(renderer.getCamera(), world); }
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) { state = GameState.MENU; window.setMouseGrabbed(false); }
    }

    private boolean shouldClose() { return window.shouldClose() || !running; }
    private void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
    public static void main(String[] args) { new SviatoslavCraft().start(); }
}
