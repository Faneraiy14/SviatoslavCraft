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
}
