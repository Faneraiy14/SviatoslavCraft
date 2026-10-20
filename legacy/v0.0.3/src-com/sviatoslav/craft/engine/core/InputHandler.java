package com.sviatoslav.craft.engine.core;

import static org.lwjgl.glfw.GLFW.*;

public class InputHandler {
    private final long windowHandle;
    private boolean[] keys = new boolean[GLFW_KEY_LAST + 1];
    private boolean[] mouseButtons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    // "Щойно натиснуто" (для ламання/постановки блоків - один клік = одна
    // дія, а не дія на кожен кадр, поки кнопка затиснута) - заповнюється в
    // самому callback'у, тому НЕ пропустить клік, навіть якщо він стався й
    // відпустився між двома кадрами гри.
    private boolean[] mouseButtonsJustPressed = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - E "не з першого разу, не з
    // третього"): F3/E перевірялись через isKeyPressed() (стан "затиснуто
    // ЗАРАЗ"), опитуваний лише раз на кадр гри. Швидкий тап клавіші -
    // натискання Й відпускання між двома такими перевірками - робив
    // натискання НЕВИДИМИМ для isKeyPressed узагалі (на момент перевірки
    // клавіша вже знову "не затиснута"), а не просто затримувалось. Той
    // самий принцип, що вже є для кнопок миші (mouseButtonsJustPressed) -
    // заповнюється в САМОМУ callback'у, тому не залежить від того, коли
    // саме гра встигне запитати стан.
    private boolean[] keysJustPressed = new boolean[GLFW_KEY_LAST + 1];
    private double mouseX = 0, mouseY = 0;
    private double deltaX = 0, deltaY = 0;
    private boolean firstMouse = true;
    // Накопичується в callback'у (може прийти кілька "нотчів" за кадр),
    // "з'їдається" викликачем за той самий принцип, що й JustPressed вище.
    private double scrollDelta = 0;

    // РЕАЛЬНА фіча (Sviatoslav попросив - перейменування світу, назва при
    // створенні): досі клавіатура в грі оброблялась ЛИШЕ через ФІЗИЧНІ
    // клавіші (keys[]/keysJustPressed[] - код клавіші, не символ). Для
    // реального ТЕКСТУ потрібен окремий GLFW char-callback (враховує
    // розкладку/shift сам, на відміну від key-callback) - активний ЛИШЕ
    // коли якийсь екран явно ввімкнув textInputActive (щоб набір символів
    // не заважав звичайному керуванню грою). Обмежено ASCII
    // літерами/цифрами/пробілом - GLBitmapFont кирилицю не вміє малювати
    // взагалі, символи поза цим просто мовчки ігноруються.
    private StringBuilder textBuffer = new StringBuilder();
    private boolean textInputActive = false;
    private static final int MAX_TEXT_LEN = 16;
    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "за 1 натискання видаляє
    // 2 букви"): попередня версія перевіряла ЛИШЕ "клавіша затиснута І
    // минуло >60мс від останнього видалення" - на ПЕРШЕ фізичне
    // натискання це вже true (60мс з моменту активації поля майже завжди
    // давно минули), а якщо сам клік триває довше за ті ж 60мс (людина
    // не встигає відпустити миттєво - цілком звичайна тривалість кліку),
    // умова спрацьовує ЩЕ РАЗ у тому самому фізичному натисканні. Тепер -
    // окремо: ПЕРШЕ видалення - через consumeKeyJustPressed (рівно один
    // раз, як і inventory/F3), а автоповтор (для затиснутої клавіші)
    // стартує лише ПІСЛЯ окремої початкової затримки, той самий принцип,
    // що й у звичайних текстових полях ОС.
    private long backspaceHeldSinceMs = 0;
    private long lastBackspaceRepeatMs = 0;
    private static final long BACKSPACE_INITIAL_DELAY_MS = 400;
    private static final long BACKSPACE_REPEAT_MS = 50;

    public InputHandler(long windowHandle) {
        this.windowHandle = windowHandle;
        setupCallbacks();
    }

    private void setupCallbacks() {
        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_PRESS) { keys[key] = true; keysJustPressed[key] = true; }
                else if (action == GLFW_RELEASE) keys[key] = false;
            }
        });

        glfwSetCursorPosCallback(windowHandle, (window, xpos, ypos) -> {
            if (firstMouse) { mouseX = xpos; mouseY = ypos; firstMouse = false; return; }
            deltaX += xpos - mouseX;
            deltaY += mouseY - ypos;
            mouseX = xpos;
            mouseY = ypos;
        });

        glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            if (button >= 0 && button < mouseButtons.length) {
                if (action == GLFW_PRESS) { mouseButtons[button] = true; mouseButtonsJustPressed[button] = true; }
                else if (action == GLFW_RELEASE) mouseButtons[button] = false;
            }
        });

        // yoffset: +1 за "нотч" вгору (від себе), -1 вниз (до себе) -
        // стандартна конвенція GLFW/більшості мишей.
        glfwSetScrollCallback(windowHandle, (window, xoffset, yoffset) -> scrollDelta += yoffset);

        glfwSetCharCallback(windowHandle, (window, codepoint) -> {
            if (!textInputActive) return;
            // РЕАЛЬНА фіча (Sviatoslav попросив - кирилиця в назві
            // світу): раніше тут був жорсткий "codepoint > 127" фільтр
            // (лишав ЛИШЕ ASCII) - GLBitmapFont тепер (текстурний атлас)
            // вміє малювати й кирилицю, тому фільтр розширено на
            // кириличний блок Unicode (U+0400-U+04FF, покриває й
            // українські Ґ/Є/І/Ї). Усе поза ASCII+кирилицею (емодзі,
            // ієрогліфи тощо) і далі відсіюється - шрифт їх усе одно не
            // намалює.
            boolean ascii = codepoint < 128;
            boolean cyrillic = codepoint >= 0x0400 && codepoint <= 0x04FF;
            if (!ascii && !cyrillic) return;
            // Регістр більше НЕ форсується (Sviatoslav попросив - "щоб
            // могли бути маленькі") - зберігається точно так, як
            // надрукували (шрифт тепер реальний, розрізняє великі/малі).
            char c = (char) codepoint;
            if ((Character.isLetterOrDigit(c) || c == ' ') && textBuffer.length() < MAX_TEXT_LEN) {
                textBuffer.append(c);
            }
        });
    }

    public boolean isKeyPressed(int key) {
        return key >= 0 && key < keys.length && keys[key];
    }
    // Той самий принцип, що й consumeMouseButtonJustPressed нижче - для
    // перемикачів (E/F3), не для утримуваного руху (WASD/SPACE, там і
    // далі потрібен саме isKeyPressed).
    public boolean consumeKeyJustPressed(int key) {
        if (key < 0 || key >= keysJustPressed.length) return false;
        boolean v = keysJustPressed[key];
        keysJustPressed[key] = false;
        return v;
    }
    public boolean isMouseButtonPressed(int button) {
        return button >= 0 && button < mouseButtons.length && mouseButtons[button];
    }
    // Один раз true - одразу після кліку; викликач сам "з'їдає" прапорець
    // через consumeMouseButtonJustPressed(), щоб не спрацювати вдруге.
    public boolean consumeMouseButtonJustPressed(int button) {
        if (button < 0 || button >= mouseButtonsJustPressed.length) return false;
        boolean v = mouseButtonsJustPressed[button];
        mouseButtonsJustPressed[button] = false;
        return v;
    }
    public double getMouseX() { return mouseX; }
    public double getMouseY() { return mouseY; }
    public double getMouseDX() { return deltaX; }
    public double getMouseDY() { return deltaY; }
    public void resetMouse() { deltaX = 0; deltaY = 0; }

    public double consumeScrollDelta() {
        double v = scrollDelta;
        scrollDelta = 0;
        return v;
    }

    // Екран (WorldCreateManager/WorldEditManager) вмикає це на вхід,
    // вимикає на вихід - лишень тоді char-callback узагалі щось пише.
    public void setTextInputActive(boolean active) {
        textInputActive = active;
        // Скидаємо стан затиснутого backspace при активації - інакше
        // затиснута клавіша, що лишилась із ПОПЕРЕДНЬОГО екрана
        // текстового вводу, могла б одразу порахуватись "давно затиснутою"
        // на новому екрані.
        if (active) backspaceHeldSinceMs = 0;
    }
    public String getTextInput() { return textBuffer.toString(); }
    public void setTextInput(String s) {
        textBuffer.setLength(0);
        if (s != null) textBuffer.append(s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s);
    }
    // Перше натискання - ОДРАЗУ одне видалення (consumeKeyJustPressed,
    // той самий edge-detected принцип, що й E/F3/Escape). Затиснута
    // клавіша довше за BACKSPACE_INITIAL_DELAY_MS - починає повторювати
    // видалення кожні BACKSPACE_REPEAT_MS, доки не відпустять (звичайна
    // поведінка текстових полів у будь-якій ОС).
    public void updateTextInputBackspace() {
        if (!textInputActive) return;
        int key = org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
        if (consumeKeyJustPressed(key)) {
            if (textBuffer.length() > 0) textBuffer.deleteCharAt(textBuffer.length() - 1);
            long now = System.currentTimeMillis();
            backspaceHeldSinceMs = now;
            lastBackspaceRepeatMs = now;
            return;
        }
        if (!isKeyPressed(key)) { backspaceHeldSinceMs = 0; return; }
        if (backspaceHeldSinceMs == 0) return;
        long now = System.currentTimeMillis();
        if (now - backspaceHeldSinceMs > BACKSPACE_INITIAL_DELAY_MS && now - lastBackspaceRepeatMs > BACKSPACE_REPEAT_MS) {
            if (textBuffer.length() > 0) textBuffer.deleteCharAt(textBuffer.length() - 1);
            lastBackspaceRepeatMs = now;
        }
    }
}
