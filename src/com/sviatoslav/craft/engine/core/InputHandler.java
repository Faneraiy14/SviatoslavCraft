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
    private double mouseX = 0, mouseY = 0;
    private double deltaX = 0, deltaY = 0;
    private boolean firstMouse = true;

    public InputHandler(long windowHandle) {
        this.windowHandle = windowHandle;
        setupCallbacks();
    }

    private void setupCallbacks() {
        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (key >= 0 && key < keys.length) {
                if (action == GLFW_PRESS) keys[key] = true;
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
    }

    public boolean isKeyPressed(int key) {
        return key >= 0 && key < keys.length && keys[key];
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
}
