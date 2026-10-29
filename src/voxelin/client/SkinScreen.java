package voxelin.client;

import voxelin.client.look.PlayerLook;
import voxelin.client.look.PlayerModel;
import voxelin.client.look.SkinImages;
import voxelin.client.ui.Gui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * «Скін і косметика»: обертова 3D-модель гравця (тягни мишею) і вибір скіна та плаща. Відкривається з головного меню й з паузи - вигляд
 * змінюється, не виходячи з гри. Зберігає player.properties (той самий файл може писати лаунчер; перечитується при кожному відкритті).
 */
final class SkinScreen {
    private final Voxelin v;
    private PlayerLook edit;
    private float spin = 200, dragFrom = Float.NaN, spinFrom;
    private final long opened = System.nanoTime();
    private List<String> skins, capes;

    SkinScreen(Voxelin v) {
        this.v = v;
        if (v.lookFile.isFile()) v.look = PlayerLook.load(v.lookFile); // лаунчер міг змінити
        edit = v.look.copy();
        File dir = v.settingsFile.getAbsoluteFile().getParentFile();
        skins = new ArrayList<>();
        for (String b : SkinImages.BUILTIN) skins.add("builtin:" + b);
        if (v.opts.skin != null) skins.add("launcher");
        for (String f : SkinImages.list(new File(dir, "skins"))) skins.add("file:" + f);
        capes = new ArrayList<>(PlayerLook.CAPES);
        for (String f : SkinImages.list(new File(dir, "capes"))) capes.add("file:" + f);
        if (!skins.contains(edit.skin)) skins.add(edit.skin);
        if (!capes.contains(edit.cape)) capes.add(edit.cape);
    }

    static String skinTitle(String id) {
        if (id.equals("launcher")) return "з лаунчера";
        if (id.startsWith("builtin:")) return SkinImages.title(id.substring(8));
        return id.startsWith("file:") ? id.substring(5) : id;
    }

    private static String next(List<String> list, String cur, int dir) {
        int i = Math.max(0, list.indexOf(cur));
        return list.get(Math.floorMod(i + dir, list.size()));
    }

    /** Кадр екрана; false - закрито (збережено або скасовано). */
    boolean draw(Gui g) {
        Gui.rect(0, 0, Gui.W, Gui.H, 0.03f, 0.04f, 0.1f, 0.78f);
        Gui.textCentered("СКІН І КОСМЕТИКА", Gui.W / 2f, 34, 4.4f, 1f, 0.85f, 0.35f);
        // превʼю
        float px = 110, py = 100, pw = 420, ph = 500;
        InventoryScreen.panel(px, py, pw, ph);
        Gui.rect(px + 10, py + ph - 70, pw - 20, 40, 0.06f, 0.07f, 0.1f, 1f); // підставка
        if (g.over(px, py, pw, ph) && v.input.buttonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            if (Float.isNaN(dragFrom)) { dragFrom = (float) g.mx; spinFrom = spin; }
            spin = spinFrom + ((float) g.mx - dragFrom) * 0.8f;
        } else { dragFrom = Float.NaN; spin += 0.25f; }
        double t = (System.nanoTime() - opened) / 1e9;
        v.playerModel.apply(edit);
        PlayerModel.Pose pose = new PlayerModel.Pose();
        pose.limbPhase = (float) (t * 4); pose.limbAmount = 0.35f; pose.headPitch = (float) Math.sin(t * 0.7) * 10; pose.capeLift = 8 + (float) Math.sin(t * 2) * 4;
        model(px + pw / 2f, py + ph - 60, 11f, spin, pose);
        Gui.textCentered("тягни мишею - обернути", px + pw / 2f, py + ph - 22, 1.6f, 0.55f, 0.6f, 0.75f);
        // вибір
        float x = 580, w = 600, y = 120;
        y = row(g, "СКІН", skinTitle(edit.skin), x, y, w, d -> edit.skin = next(skins, edit.skin, d));
        y = row(g, "ПЛАЩ", PlayerLook.capeTitle(edit.cape), x, y + 20, w, d -> edit.cape = next(capes, edit.cape, d));
        Gui.rect(x, y + 30, w, 2, 0.3f, 0.32f, 0.44f, 1f);
        float ty = y + 50;
        for (String line : new String[]{
                "Свій скін: PNG 64x64 у форматі Minecraft",
                "(підійде той, що в лаунчері) - у теку skins/.",
                "Свій плащ: PNG 64x32 - у теку capes/.",
                "Лаунчер може задати вигляд у player.properties або --skin."}) {
            Gui.text(line, x, ty, 1.7f, 0.72f, 0.76f, 0.9f); ty += 26;
        }
        boolean done = g.button("ГОТОВО", x, 540, 290, 56, true, true) || v.input.pressed(GLFW_KEY_ENTER);
        boolean cancel = g.button("СКАСУВАТИ", x + 310, 540, 290, 56) || v.input.pressed(GLFW_KEY_ESCAPE);
        if (done) { v.look = edit.copy(); v.look.save(v.lookFile); v.playerModel.apply(v.look); return false; }
        if (cancel) { v.playerModel.apply(v.look); return false; }
        return true;
    }

    private interface Step { void go(int dir); }

    private float row(Gui g, String label, String value, float x, float y, float w, Step step) {
        Gui.text(label, x, y, 2f, 0.62f, 0.66f, 0.8f);
        float by = y + 24;
        if (g.button("<", x, by, 56, 52)) step.go(-1);
        Gui.rect(x + 66, by, w - 132, 52, 0.06f, 0.07f, 0.1f, 1f);
        Gui.frame(x + 66, by, w - 132, 52, 2, 0.3f, 0.32f, 0.44f);
        Gui.textCentered(value, x + w / 2f, by + 17, 2.4f, 1f, 1f, 1f);
        if (g.button(">", x + w - 56, by, 56, 52)) step.go(1);
        return by + 52;
    }

    /** Модель у 2D-інтерфейсі: власна ортопроєкція з глибиною, ноги в (cx, feetY), k пікселів екрана на піксель скіна. */
    private void model(float cx, float feetY, float k, float yaw, PlayerModel.Pose pose) {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, Gui.W, Gui.H, 0, -1000, 1000);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity();
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glTranslatef(cx, feetY, 0);
        glScalef(k, -k, k);
        glRotatef(12, 1, 0, 0); // трохи згори
        glRotatef(yaw, 0, 1, 0);
        v.playerModel.draw(pose, null);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE); // Gui малює без відсікання
        glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW); glPopMatrix();
    }
}
