package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.core.*;
import com.sviatoslav.craft.engine.graphics.*;
import com.sviatoslav.craft.engine.world.*;
import static org.lwjgl.glfw.GLFW.*;

public class SviatoslavCraft {
    // РЕАЛЬНА фіча (Sviatoslav попросив - екран вибору світу "як у майні"):
    // WORLD_SELECT (список світів + BACK/CREATE/EDIT) і WORLD_EDIT (поки
    // заглушка з однією кнопкою BACK) - між головним меню й самою грою.
    private enum GameState { MENU, WORLD_SELECT, WORLD_EDIT, PLAYING }
    private Window window;
    private InputHandler input;
    private Renderer renderer;
    private GameLoop gameLoop;
    private DebugOverlay debug;
    private InventoryUI inventoryUI;
    private GameState state = GameState.MENU;
    private boolean running = true;
    private World world;
    private Player player;
    private Inventory inventory;
    private MenuManager menu;
    private WorldSelectManager worldSelect;
    private WorldEditManager worldEdit;

    public void start() {
        window = new LwjglWindow();
        window.create("SviatoslavCraft", 1280, 720);
        input = new InputHandler(window.getHandle());
        renderer = new Renderer();
        debug = new DebugOverlay();
        inventoryUI = new InventoryUI();
        menu = new MenuManager(window, input, 1280, 720);
        worldSelect = new WorldSelectManager(window, input, 1280, 720);
        worldEdit = new WorldEditManager(window, input, 1280, 720);
        gameLoop = new GameLoop();
        gameLoop.start(this::update, this::render, this::shouldClose);
        if (world != null) { world.shutdown(); world.saveAllChunks(); }
        window.destroy();
    }

    private void update(double deltaTime) {
        if (state == GameState.MENU) {
            String action = menu.update();
            // "PLAY" (перший пункт меню, тепер підписаний "SINGLEPLAYER")
            // веде на екран вибору світу, а не одразу в гру.
            if (action.equals("PLAY")) { state = GameState.WORLD_SELECT; worldSelect.refresh(); }
            else if (action.equals("EXIT")) running = false;
        } else if (state == GameState.WORLD_SELECT) {
            String action = worldSelect.update();
            if (action.equals("BACK")) state = GameState.MENU;
            else if (action.equals("PLAY")) { state = GameState.PLAYING; window.setMouseGrabbed(true); initGame(worldSelect.getSelectedWorld()); }
            else if (action.equals("EDIT")) { worldEdit.open(worldSelect.getSelectedWorld()); state = GameState.WORLD_EDIT; }
        } else if (state == GameState.WORLD_EDIT) {
            String action = worldEdit.update();
            if (action.equals("BACK")) state = GameState.WORLD_SELECT;
        } else updateGame(deltaTime);
    }

    private void render() {
        if (state == GameState.MENU) menu.render();
        else if (state == GameState.WORLD_SELECT) worldSelect.render();
        else if (state == GameState.WORLD_EDIT) worldEdit.render();
        else {
            Camera camera = renderer.getCamera();
            // РЕАЛЬНИЙ БАГ (Sviatoslav попросив - "камера не могла
            // дивитись крізь блоки"): позиція камери в 2/3-й особі
            // рахувалась як ФІКСОВАНИЙ зсув від гравця, без перевірки, чи
            // є щось суцільне на шляху - тому позаду/попереду стіни
            // камера просто опинялась УСЕРЕДИНІ неї. Camera сама не має
            // доступу до World (інший пакет), тому перевірку робимо тут -
            // той самий DDA, що й для прицілу (BlockRayCast), лише в
            // напрямку зсуву камери (позаду гравця для THIRD_BACK,
            // попереду для THIRD_FRONT - той самий кут, що й розворот
            // погляду камери в цьому режимі), і РЕЗУЛЬТАТ (безпечна
            // дистанція) віддаємо назад у Camera перед applyView().
            if (player != null && world != null && camera.getViewMode() != Camera.ViewMode.FIRST_PERSON) {
                boolean back = camera.getViewMode() == Camera.ViewMode.THIRD_BACK;
                float rayYaw = back ? player.getYaw() + 180f : player.getYaw();
                float rayPitch = back ? -player.getPitch() : player.getPitch();
                float maxDist = camera.getThirdPersonMaxDistance();
                float safeDist = BlockRayCast.castDistance(player.getX(), player.getAimY(), player.getZ(), rayYaw, rayPitch, world, maxDist);
                // Невеликий запас (0.3), щоб камера не лягала прямо на
                // поверхню стіни (z-fighting/впритул до неї).
                camera.setThirdPersonDistance(Math.max(0f, safeDist - 0.3f));
            }

            renderer.prepare();
            camera.updateProjection();
            camera.applyView();
            if (world != null) world.render(renderer);

            // Контур навколо блока, на який дивишся - той самий raycast, що
            // й для ламання/постановки, лише для відображення (реальна
            // фіча, якої в чернетці не було взагалі). Рахуємо ОДИН раз тут
            // і віддаємо той самий hit у F3-оверлей нижче (TARGET: x y z
            // тип) - раніше debug.render() рахував (чи то й не рахував
            // узагалі) окремо, тепер курсор і цифри на екрані завжди про
            // ОДИН і той самий блок, зручно звіряти "чи дотягнувся туди,
            // куди не мав би" по РЕАЛЬНИХ координатах, а не на око.
            BlockRayCast.Hit hit = null;
            if (player != null && world != null) {
                hit = BlockRayCast.cast(player.getX(), player.getAimY(), player.getZ(), player.getYaw(), player.getPitch(), world);
                // grid-координата hit -> світова float-позиція (той самий
                // масштаб Chunk.BLOCK_SIZE, що й рендер блоків у World).
                if (hit != null) renderer.renderWireCube(
                    (hit.hitX() + 0.5f) * Chunk.BLOCK_SIZE, (hit.hitY() + 0.5f) * Chunk.BLOCK_SIZE, (hit.hitZ() + 0.5f) * Chunk.BLOCK_SIZE,
                    Chunk.BLOCK_SIZE * 1.02f);
            }

            // "Тіло" гравця (F5, 3-я особа) - лише коробка-заглушка, у грі
            // нема справжньої моделі. ЛИШЕ коли не від першої особи -
            // інакше коробка малювалась би прямо перед/навколо камери.
            if (player != null && camera.getViewMode() != Camera.ViewMode.FIRST_PERSON) {
                renderer.renderBox(player.getX(), player.getBodyCenterY(), player.getZ(),
                    player.getWidth() / 2f, player.getBodyHeight() / 2f, 0.85f, 0.7f, 0.55f);
            }

            // Приціл по центру екрана (Sviatoslav попросив) - без нього
            // незрозуміло, куди саме дивишся, коли в межах досяжності
            // немає жодного блока (renderWireCube тоді взагалі не малюється).
            // ЛИШЕ від першої особи - у 3-й особі приціл у центрі екрана не
            // відповідав би реальній точці прицілювання (raycast і далі
            // йде від СПРАВЖНЬОГО ока гравця, не від відсунутої камери,
            // інакше ламання/постановка блоків цілились би не туди, куди
            // дивиться сама камера) - той самий підхід, що й у Minecraft
            // (приціл ховається поза 1-ю особою).
            if (camera.getViewMode() == Camera.ViewMode.FIRST_PERSON) {
                renderer.renderCrosshair(1280, 720);
            }

            // Хотбар (Sviatoslav попросив) - раніше перемикання 1-5 вже
            // працювало, але НІДЕ на екрані не було видно, що саме вибрано.
            // Повноекранне вікно (E) - поверх усього іншого, тому в самому
            // кінці рендеру.
            if (inventory != null) {
                inventoryUI.renderHotbar(inventory, 1280, 720);
                inventoryUI.renderFullScreen(inventory, 1280, 720);
            }

            if (debug.isVisible() && player != null) {
                String targetInfo;
                if (hit == null) {
                    targetInfo = "NONE";
                } else {
                    Block hb = world.getBlock(hit.hitX(), hit.hitY(), hit.hitZ());
                    // (grid+0.5)*BLOCK_SIZE - та сама світова позиція
                    // центру блока, що й для рендеру/прицільного куба вище.
                    float dist = (float) Math.sqrt(
                        Math.pow((hit.hitX() + 0.5f) * Chunk.BLOCK_SIZE - player.getX(), 2) +
                        Math.pow((hit.hitY() + 0.5f) * Chunk.BLOCK_SIZE - player.getY(), 2) +
                        Math.pow((hit.hitZ() + 0.5f) * Chunk.BLOCK_SIZE - player.getZ(), 2));
                    targetInfo = hit.hitX() + "," + hit.hitY() + "," + hit.hitZ()
                        + " " + (hb != null ? hb.getType() : "?") + " D=" + ((int)(dist*10))/10.0f;
                }
                debug.render(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                             gameLoop.getFPS(), world != null ? world.getChunks().values().stream().mapToInt(Chunk::getBlockCount).sum() : 0,
                             targetInfo);
            }
        }
        window.swapBuffers();
    }

    private void initGame(String worldName) {
        System.out.println("Генерація світу...");
        world = new World(worldName);
        renderer.getCamera().setY(50.0f);
        player = new Player(renderer.getCamera(), world);
        inventory = new Inventory();
        for (int i = 0; i < 20; i++) { world.update(player.getX(), player.getZ()); try { Thread.sleep(50); } catch (InterruptedException e) {} }
        boolean found = false;
        // player.getX()/getZ() - СВІТОВІ float-координати; getBlock()
        // очікує ЦІЛІ grid-координати, тому ділимо на BLOCK_SIZE перед
        // округленням - інакше на новому масштабі шукали б не в тій
        // клітинці світу взагалі.
        int gridX = (int) (player.getX() / Chunk.BLOCK_SIZE), gridZ = (int) (player.getZ() / Chunk.BLOCK_SIZE);
        for (int y = 50; y > 0; y--) {
            Block b = world.getBlock(gridX, y, gridZ);
            if (b != null && b.getType() != Block.Type.AIR) { renderer.getCamera().setY((y+2.0f) * Chunk.BLOCK_SIZE); player = new Player(renderer.getCamera(), world); found = true; break; }
        }
        if (!found) { renderer.getCamera().setY(50.0f); player = new Player(renderer.getCamera(), world); }
        System.out.println("Гра починається!");
    }

    private void updateGame(double deltaTime) {
        float dt = (float) deltaTime;
        // consumeKeyJustPressed - не isKeyPressed+sleep(200) (був REAL BUG:
        // isKeyPressed опитувався раз на кадр, швидкий тап міг статись і
        // скінчитись МІЖ двома опитуваннями, тому натискання іноді
        // взагалі не реєструвалось - "не з першого разу, не з третього").
        if (input.consumeKeyJustPressed(GLFW_KEY_F3)) debug.toggle();
        if (input.consumeKeyJustPressed(GLFW_KEY_F5)) renderer.getCamera().cycleViewMode();

        if (input.consumeKeyJustPressed(GLFW_KEY_E)) {
            inventoryUI.toggle();
            window.setMouseGrabbed(!inventoryUI.isOpen());
        }

        // Поки інвентар відкритий - гра на паузі (рух/огляд/клік), як і в
        // звичайних іграх з інвентарем поверх екрана.
        if (inventoryUI.isOpen()) {
            // consumeKeyJustPressed - той самий фікс, що й нижче/у
            // MenuManager: якщо лишити isKeyPressed, той самий Escape, що
            // закрив інвентар, міг би на НАСТУПНОМУ кадрі (ще затиснутий)
            // одразу й перекинути в меню теж.
            if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) { inventoryUI.close(); window.setMouseGrabbed(true); }
            // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "закрив інвентар,
            // дивлюсь геть в інший бік"): ранній return тут пропускав
            // resetMouse() нижче, тому весь рух миші, накопичений ЗА ВЕСЬ
            // час, поки інвентар відкритий, лишався в deltaX/deltaY - і в
            // момент закриття одним різким стрибком застосовувався до
            // камери. Скидати треба щокадру, незалежно від того, чи
            // взагалі застосовуємо рух до камери цього кадру.
            input.resetMouse();
            return;
        }

        renderer.getCamera().rotate((float)input.getMouseDX()*0.12f, (float)-input.getMouseDY()*0.12f);
        input.resetMouse();
        for (int i = 0; i < 5; i++) if (input.isKeyPressed(GLFW_KEY_1 + i)) inventory.selectSlot(i);
        // Колесо миші (Sviatoslav попросив) - той самий вибір слота, що й
        // клавіші 1-5, лише прокруткою. Один "нотч" = один слот, знак
        // накопиченого зсуву визначає напрямок (кілька нотчів за кадр -
        // рідкість, але про всяк випадок рахуємо саме знак, не суму).
        double scroll = input.consumeScrollDelta();
        if (scroll > 0) inventory.previousSlot();
        else if (scroll < 0) inventory.nextSlot();
        player.updatePhysics(dt);
        if (input.isKeyPressed(GLFW_KEY_SPACE)) player.jump();
        boolean sprint = input.isKeyPressed(GLFW_KEY_LEFT_CONTROL) || input.isKeyPressed(GLFW_KEY_RIGHT_CONTROL);
        boolean crouch = input.isKeyPressed(GLFW_KEY_LEFT_SHIFT);
        player.updateMovement(dt,
            input.isKeyPressed(GLFW_KEY_W), input.isKeyPressed(GLFW_KEY_S),
            input.isKeyPressed(GLFW_KEY_A), input.isKeyPressed(GLFW_KEY_D), sprint, crouch);
        world.update(player.getX(), player.getZ());

        // РЕАЛЬНА фіча - ламання (ЛКМ) і постановка (ПКМ) блоків. У
        // чернетці DeepSeek таблиця можливостей стверджувала, що "логіка
        // є, лише не прив'язана до кліку" - насправді жодного raycast'а
        // взагалі не існувало ніде в коді.
        if (input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            var hit = BlockRayCast.cast(player.getX(), player.getAimY(), player.getZ(), player.getYaw(), player.getPitch(), world);
            if (hit != null) world.setBlock(hit.hitX(), hit.hitY(), hit.hitZ(), new Block(Block.Type.AIR, 0, 0, 0));
        }
        if (input.consumeMouseButtonJustPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            var hit = BlockRayCast.cast(player.getX(), player.getAimY(), player.getZ(), player.getYaw(), player.getPitch(), world);
            Block.Type selected = inventory.getSelectedBlockType();
            if (hit != null && selected != null
                    && !player.wouldOverlapPlacedBlock(hit.placeX(), hit.placeY(), hit.placeZ())) {
                world.setBlock(hit.placeX(), hit.placeY(), hit.placeZ(), new Block(selected, 0, 0, 0));
                inventory.removeSelectedItem();
            }
        }

        if (player.getY() < -10) { renderer.getCamera().setY(50.0f); player = new Player(renderer.getCamera(), world); }
        // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "Escape кидає в меню, а
        // потім гра закривається"): isKeyPressed тут не "з'їдав" натиск,
        // тому MenuManager.update() наступного кадру бачив ТОЙ САМИЙ ще
        // затиснутий Escape і трактував як "Вихід". consumeKeyJustPressed.
        if (input.consumeKeyJustPressed(GLFW_KEY_ESCAPE)) { state = GameState.MENU; window.setMouseGrabbed(false); }
    }

    private boolean shouldClose() { return window.shouldClose() || !running; }
    public static void main(String[] args) { new SviatoslavCraft().start(); }
}
