package voxelin.client;

import voxelin.client.render.IconAtlas;
import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Екран інвентарю: слоти з іконками, перенос стопок курсором (ліва - взяти/покласти, права - половина/по одному, shift - перекласти,
 * подвійний клік - зібрати), підказка з назвою й міцністю, кнопка «сортувати», клік поза вікном викидає стек з курсора.
 * Уся логіка кліків - у core.Menu (тестується без вікна); тут лише малювання й переклад миші в кліки.
 */
final class InventoryScreen {
    private static final float SIZE = 62, GAP = 8;
    private final PlayerMenu menu;
    private final Inventory inv;
    private final World world;
    private final IconAtlas icons;
    private final Consumer<Inventory.Stack> dropper;
    private int lastSlot = -1;
    private long lastClick;

    InventoryScreen(PlayerMenu menu, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        this.menu = menu; this.inv = inv; this.world = world; this.icons = icons; this.dropper = dropper;
    }

    /** Іконка, смужка міцності й кількість у слоті розміром size з лівим верхнім кутом (x,y) - спільне для хотбару й інвентарю. */
    void drawStack(Inventory.Stack st, float x, float y, float size) {
        if (st == null) return;
        float pad = size * 0.1f;
        icons.draw(st.item, x + pad, y + pad, size - 2 * pad);
        ItemType it = world.itemTypes.get(st.item);
        if (it != null && it.durability > 0 && st.damage > 0) {
            float f = Math.max(0f, 1f - st.damage / (float) it.durability);
            Gui.rect(x + 6, y + size - 9, size - 12, 4, 0, 0, 0, 0.9f);
            Gui.rect(x + 6, y + size - 9, (size - 12) * f, 4, 1f - f, f, 0.1f, 1f);
        }
        if (st.count > 1) {
            String c = String.valueOf(st.count);
            float w = Gui.textWidth(c, 1.8f);
            Gui.text(c, x + size - w - 2, y + size - 15, 1.8f, 0, 0, 0);
            Gui.text(c, x + size - w - 3, y + size - 16, 1.8f, 1, 1, 1);
        }
    }

    void slotBox(float x, float y, float size, boolean selected, boolean hov) {
        Gui.rect(x - 2, y - 2, size + 4, size + 4, selected ? 1f : 0.08f, selected ? 1f : 0.08f, selected ? 1f : 0.08f, 0.9f);
        Gui.rect(x, y, size, size, hov ? 0.42f : 0.3f, hov ? 0.42f : 0.3f, hov ? 0.45f : 0.33f, 0.92f);
    }

    static String titleOf(World w, String item) { ItemType t = w.itemTypes.get(item); return t != null ? t.title : ItemType.prettify(item); }

    void draw(Gui g, Input in) {
        float total = 9 * (SIZE + GAP) - GAP, x0 = (Gui.W - total) / 2f, y0 = 150;
        float px = x0 - 24, py = y0 - 60, pw = total + 48, ph = 4 * (SIZE + GAP) + 110;
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.5f);
        Gui.rect(px, py, pw, ph, 0.5f, 0.5f, 0.52f, 0.97f);
        Gui.frame(px, py, pw, ph, 3, 0.15f, 0.15f, 0.15f);
        Gui.text("ІНВЕНТАР", x0 - 8, y0 - 44, 3.2f, 0.15f, 0.15f, 0.15f);
        if (g.button("СОРТУВАТИ", px + pw - 180, py + 10, 160, 38)) inv.sortMain();
        boolean shift = in.down(GLFW_KEY_LEFT_SHIFT) || in.down(GLFW_KEY_RIGHT_SHIFT);
        boolean left = g.click, right = in.clicked(GLFW_MOUSE_BUTTON_RIGHT);
        int hovered = -1;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int idx = row < 3 ? 9 + row * 9 + col : col; // три рядки сховища, нижче - хотбар
            float x = x0 + col * (SIZE + GAP), y = y0 + row * (SIZE + GAP) + (row == 3 ? 14 : 0);
            boolean hov = g.over(x, y, SIZE, SIZE);
            if (hov) hovered = idx;
            slotBox(x, y, SIZE, idx == inv.selected(), hov);
            drawStack(menu.get(idx), x, y, SIZE);
        }
        long now = System.currentTimeMillis();
        boolean inside = g.over(px, py, pw, ph);
        if (hovered >= 0) {
            if (left) {
                g.click = false;
                if (hovered == lastSlot && now - lastClick < 350 && menu.cursor() != null && !shift) { menu.doubleClick(hovered); lastClick = 0; }
                else { menu.click(hovered, 0, shift); lastSlot = hovered; lastClick = now; }
            } else if (right) menu.click(hovered, 1, shift);
        } else if (!inside && menu.cursor() != null) { // клік поза вікном - викинути стек (права - один)
            if (left) { g.click = false; dropper.accept(menu.takeCursor(false)); }
            else if (right) dropper.accept(menu.takeCursor(true));
        }
        Inventory.Stack under = hovered >= 0 ? menu.get(hovered) : null;
        if (under != null && menu.cursor() == null) tooltip(g, under);
        if (menu.cursor() != null) drawStack(menu.cursor(), (float) g.mx - 27, (float) g.my - 27, 54);
    }

    private void tooltip(Gui g, Inventory.Stack st) {
        ItemType it = world.itemTypes.get(st.item);
        String l1 = titleOf(world, st.item);
        String l2 = it != null && it.durability > 0 ? "Міцність: " + (it.durability - st.damage) + " / " + it.durability : null;
        String l3 = it != null && it.food > 0 ? "Їжа: +" + it.food : null;
        int lines = 1 + (l2 != null ? 1 : 0) + (l3 != null ? 1 : 0);
        float w = Math.max(Gui.textWidth(l1, 2.2f), Math.max(l2 == null ? 0 : Gui.textWidth(l2, 1.8f), l3 == null ? 0 : Gui.textWidth(l3, 1.8f))) + 20;
        float x = Math.min((float) g.mx + 16, Gui.W - w - 4), y = Math.min((float) g.my + 14, Gui.H - 30 - lines * 22);
        Gui.rect(x, y, w, 12 + lines * 24, 0.06f, 0.02f, 0.12f, 0.94f);
        Gui.frame(x, y, w, 12 + lines * 24, 2, 0.35f, 0.2f, 0.55f);
        Gui.text(l1, x + 10, y + 8, 2.2f, 1, 1, 1);
        float yy = y + 8 + 24;
        if (l2 != null) { Gui.text(l2, x + 10, yy, 1.8f, 0.7f, 0.9f, 0.7f); yy += 22; }
        if (l3 != null) Gui.text(l3, x + 10, yy, 1.8f, 0.9f, 0.8f, 0.5f);
    }

    /** Закрити вікно: курсорний стек - назад в інвентар, що не влізло - на землю. */
    void close() {
        Inventory.Stack left = menu.closeCursor(inv);
        if (left != null) dropper.accept(left);
        lastSlot = -1;
    }
}
