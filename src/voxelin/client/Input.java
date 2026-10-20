package voxelin.client;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Клавіатура й миша через колбеки GLFW. «Щойно натиснуто» фіксується в самому колбеку (consume...) - швидкий тап між
 * двома кадрами не губиться (баг старої версії). Координати миші для інтерфейсу - у віртуальних 1280x720 (масштаб від розміру вікна).
 * Текстовий ввід: назви світів (короткий, лише літери/цифри/пробіл) і рядок команд (довгий, будь-які друковані символи).
 */
public final class Input {
    public static final float UI_W = 1280, UI_H = 720;
    private final boolean[] keys = new boolean[GLFW_KEY_LAST + 1], keysJust = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] buttons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1], buttonsJust = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private double mouseX, mouseY, dX, dY, scroll;
    private boolean firstMouse = true;
    private final StringBuilder text = new StringBuilder();
    private int textMax;
    private boolean textCommand;
    private boolean textActive;
    private long bsHeld, bsLast;
    private final Window window;

    public Input(Window window) {
        this.window = window;
        long h = window.handle();
        glfwSetKeyCallback(h, (w, key, sc, action, mods) -> {
            if (key < 0 || key >= keys.length) return;
            if (action == GLFW_PRESS) { keys[key] = true; keysJust[key] = true; }
            else if (action == GLFW_RELEASE) keys[key] = false;
        });
        glfwSetCursorPosCallback(h, (w, x, y) -> {
            if (firstMouse) { mouseX = x; mouseY = y; firstMouse = false; return; }
            dX += x - mouseX; dY += mouseY - y; mouseX = x; mouseY = y;
        });
        glfwSetMouseButtonCallback(h, (w, b, action, mods) -> {
            if (b < 0 || b >= buttons.length) return;
            if (action == GLFW_PRESS) { buttons[b] = true; buttonsJust[b] = true; }
            else if (action == GLFW_RELEASE) buttons[b] = false;
        });
        glfwSetScrollCallback(h, (w, x, y) -> scroll += y);
        glfwSetCharCallback(h, (w, cp) -> {
            if (!textActive || text.length() >= textMax || cp > 0xFFFF) return;
            char c = (char) cp;
            boolean ok = textCommand ? (c >= 32 && c != 127) : ((cp < 128 || (cp >= 0x0400 && cp <= 0x04FF)) && (Character.isLetterOrDigit(c) || c == ' '));
            if (ok) text.append(c);
        });
    }

    public boolean down(int key) { return key >= 0 && key < keys.length && keys[key]; }
    public boolean pressed(int key) { if (key < 0 || key >= keysJust.length) return false; boolean v = keysJust[key]; keysJust[key] = false; return v; }
    public boolean buttonDown(int b) { return b >= 0 && b < buttons.length && buttons[b]; }
    public boolean clicked(int b) { if (b < 0 || b >= buttonsJust.length) return false; boolean v = buttonsJust[b]; buttonsJust[b] = false; return v; }
    /** Скинути всі «щойно натиснуто» (при зміні екрана, щоб натиск не «перелетів» на наступний). */
    public void clearPressed() { java.util.Arrays.fill(keysJust, false); java.util.Arrays.fill(buttonsJust, false); }

    public double mouseUiX() { return mouseX * UI_W / Math.max(1, window.width()); }
    public double mouseUiY() { return mouseY * UI_H / Math.max(1, window.height()); }
    public double mouseDX() { return dX; }
    public double mouseDY() { return dY; }
    public void resetMouse() { dX = 0; dY = 0; }
    public double consumeScroll() { double v = scroll; scroll = 0; return v; }

    /** Почати текстовий ввід. command=true - рядок команди (до 256 будь-яких символів), інакше назва (до 16 літер/цифр/пробілу). */
    public void beginText(String initial, boolean command) {
        textCommand = command; textMax = command ? 256 : 16; textActive = true; bsHeld = 0;
        text.setLength(0);
        if (initial != null) text.append(initial.length() > textMax ? initial.substring(0, textMax) : initial);
    }
    public void endText() { textActive = false; }
    public boolean textActive() { return textActive; }
    public String text() { return text.toString(); }

    /** Backspace з автоповтором (виклик щокадру, поки йде ввід). */
    public void updateText() {
        if (!textActive) return;
        long now = System.currentTimeMillis();
        if (pressed(GLFW_KEY_BACKSPACE)) { erase(); bsHeld = now; bsLast = now; return; }
        if (!down(GLFW_KEY_BACKSPACE)) { bsHeld = 0; return; }
        if (bsHeld != 0 && now - bsHeld > 400 && now - bsLast > 50) { erase(); bsLast = now; }
    }
    private void erase() { if (text.length() > 0) text.deleteCharAt(text.length() - 1); }
}
