package voxelin.client;

import voxelin.client.render.IconAtlas;
import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Екран інвентарю: слоти з іконками, перенос стопок курсором (ліва - взяти/покласти, права - половина/по одному, shift - перекласти,
 * подвійний клік - зібрати), клавіші 1-9 над слотом - обмін із хотбаром, Q - викинути (Ctrl+Q - стек), пошук (решта слотів тьмяніє),
 * підсвітка однакових предметів, підказка (тип, шкода, захист, міцність, скільки всього), картка стану гравця, кнопка «сортувати»,
 * клік поза вікном викидає стек з курсора.
 * Уся логіка кліків - у core.Menu (тестується без вікна); тут лише малювання й переклад миші в кліки.
 */
final class InventoryScreen {
    private static final float SIZE = 62, GAP = 8;
    /** Верхня частина вікна (сітка крафту, піч): висота й малювання; повертає номер слота під мишею або -1. */
    interface Top { float height(); int draw(InventoryScreen s, Gui g, float x0, float top); void onClose(); }

    private final Menu menu;
    private final String title;
    private final Top top;
    private final Inventory inv;
    private final World world;
    private final IconAtlas icons;
    private final Consumer<Inventory.Stack> dropper;
    private int lastSlot = -1;
    private long lastClick;
    /** Здоров'я/голод для картки стану (ставить PlayScreen; null - картки нема). */
    voxelin.core.entity.Vitals vitals;
    private String query = "";
    private boolean typing;
    private Input input;

    /** Зараз іде ввід у пошук - гра не має реагувати на клавіші (E, T, Esc закривають лише пошук). */
    boolean typing() { return typing; }

    /** Esc під час пошуку: лише вийти з поля. */
    void stopTyping() { if (typing) { typing = false; if (input != null) input.endText(); } }

    // кольори вікна (темна тема, як меню)
    private static final float[] PANEL = {0.13f, 0.14f, 0.2f}, PANEL_HI = {0.3f, 0.32f, 0.44f}, PANEL_LO = {0.05f, 0.05f, 0.08f}, LABEL = {0.62f, 0.66f, 0.8f};

    /** Вікно зі сіткою крафту (інвентар 2x2, верстак 3x3). */
    static InventoryScreen craft(String title, CraftMenu menu, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        return new InventoryScreen(title, menu, new CraftTop(menu), inv, world, icons, dropper);
    }

    /** Вікно скрині: 27 слотів зверху. */
    static InventoryScreen container(String title, ChestMenu menu, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        return new InventoryScreen(title, menu, new ChestTop(menu), inv, world, icons, dropper);
    }

    /** Творчий інвентар: вкладки з усіма предметами гри. */
    static InventoryScreen creative(CreativeMenu menu, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        return new InventoryScreen("ТВОРЧИЙ", menu, new CreativeTop(menu), inv, world, icons, dropper);
    }

    /** Вікно печі. */
    static InventoryScreen furnace(FurnaceMenu menu, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        return new InventoryScreen("ПІЧ", menu, new FurnaceTop(menu), inv, world, icons, dropper);
    }

    private InventoryScreen(String title, Menu menu, Top top, Inventory inv, World world, IconAtlas icons, Consumer<Inventory.Stack> dropper) {
        this.title = title; this.menu = menu; this.top = top; this.inv = inv; this.world = world; this.icons = icons; this.dropper = dropper;
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

    /** «Утоплений» слот: темний край зверху-зліва, світлий знизу-справа; вибраний (хотбар) - золота рамка. */
    void slotBox(float x, float y, float size, boolean selected, boolean hov) {
        Gui.rect(x - 2, y - 2, size + 4, size + 4, 0.04f, 0.04f, 0.06f, 0.92f);
        Gui.rect(x, y + size - 2, size, 2, 0.32f, 0.34f, 0.44f, 0.9f);
        Gui.rect(x + size - 2, y, 2, size, 0.32f, 0.34f, 0.44f, 0.9f);
        float k = hov ? 0.29f : 0.2f;
        Gui.rect(x, y, size - 2, size - 2, k, k + 0.01f, k + 0.05f, 0.94f);
        if (selected) Gui.frame(x - 3, y - 3, size + 6, size + 6, 3, 1f, 0.82f, 0.3f);
        else if (hov) Gui.frame(x, y, size, size, 2, 0.85f, 0.88f, 1f);
    }

    /** Вікно з об'ємним краєм. */
    static void panel(float x, float y, float w, float h) {
        Gui.rect(x - 3, y - 3, w + 6, h + 6, 0, 0, 0, 0.85f);
        Gui.rect(x, y, w, h, PANEL[0], PANEL[1], PANEL[2], 0.98f);
        Gui.rect(x, y, w, 2, PANEL_HI[0], PANEL_HI[1], PANEL_HI[2], 1f); Gui.rect(x, y, 2, h, PANEL_HI[0], PANEL_HI[1], PANEL_HI[2], 1f);
        Gui.rect(x, y + h - 2, w, 2, PANEL_LO[0], PANEL_LO[1], PANEL_LO[2], 1f); Gui.rect(x + w - 2, y, 2, h, PANEL_LO[0], PANEL_LO[1], PANEL_LO[2], 1f);
    }

    static void label(String t, float x, float y) { Gui.text(t, x, y, 1.7f, LABEL[0], LABEL[1], LABEL[2]); }

    /** Піксельний малюнок (рядки з 'X'). */
    static void pixels(String[] pat, float x, float y, float px, float r, float g, float b, float a) {
        for (int row = 0; row < pat.length; row++) for (int col = 0; col < pat[row].length(); col++)
            if (pat[row].charAt(col) == 'X') Gui.rect(x + col * px, y + row * px, px, px, r, g, b, a);
    }

    // силуети порожніх слотів броні: шолом, нагрудник, поножі, черевики
    private static final String[][] ARMOR_GHOST = {
            {"..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", "XX.....XX", "XX.....XX", "........."},
            {"XX.....XX", "XXX...XXX", "XXXXXXXXX", ".XXXXXXX.", ".XXXXXXX.", ".XXXXXXX.", ".XXXXXXX."},
            {"XXXXXXXXX", "XXXXXXXXX", "XXX...XXX", "XXX...XXX", "XXX...XXX", "XXX...XXX", "XXX...XXX"},
            {".........", "XXX...XXX", "XXX...XXX", "XXX...XXX", "XXXX.XXXX", "XXXX.XXXX"}};
    private static final String[] ARROW = {"....X....", "....XX...", "XXXXXXX..", "XXXXXXXX.", "XXXXXXX..", "....XX...", "....X...."};

    /** Чи підходить стек під рядок пошуку (назва українською або id). */
    private boolean matches(Inventory.Stack st) {
        if (query.isBlank() || st == null) return query.isBlank();
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return titleOf(world, st.item).toLowerCase(java.util.Locale.ROOT).contains(q) || st.item.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    /** Поверх слота: затемнити те, що не підходить під пошук; рамка однакових із тим, що під мишею. */
    void overlay(Inventory.Stack st, float x, float y, float size, String sameAs) {
        if (!query.isBlank()) {
            if (!matches(st)) Gui.rect(x - 2, y - 2, size + 4, size + 4, 0.02f, 0.02f, 0.04f, 0.7f);
            else if (st != null) Gui.frame(x - 2, y - 2, size + 4, size + 4, 2, 1f, 0.82f, 0.3f);
        } else if (sameAs != null && st != null && st.item.equals(sameAs)) Gui.frame(x + 1, y + 1, size - 4, size - 4, 2, 0.55f, 0.8f, 1f);
    }

    static String titleOf(World w, String item) { ItemType t = w.itemTypes.get(item); return t != null ? t.title : ItemType.prettify(item); }

    void draw(Gui g, Input in) {
        input = in;
        float total = 9 * (SIZE + GAP) - GAP, x0 = (Gui.W - total) / 2f;
        float craftTop = 64, sy = craftTop + top.height() + 34; // відносно верху вікна
        float pw = total + 48, ph = sy + 4 * (SIZE + GAP) + 18 + 40, px = x0 - 24, py = Math.max(8, (Gui.H - ph) / 2f);
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.5f);
        panel(px, py, pw, ph);
        Gui.text(title, x0 - 4, py + 18, 3.2f, 1f, 0.85f, 0.35f);
        if (g.button("СОРТУВАТИ", px + pw - 176, py + 12, 152, 36)) inv.sortMain();
        // пошук
        float qx = px + pw - 176 - 12 - 230, qy = py + 12;
        boolean qHov = g.over(qx, qy, 230, 36);
        Gui.rect(qx, qy, 230, 36, 0.06f, 0.06f, 0.09f, 1f);
        Gui.frame(qx, qy, 230, 36, 2, typing ? 1f : 0.35f, typing ? 0.82f : 0.37f, typing ? 0.3f : 0.48f);
        if (typing) { in.updateText(); query = in.text(); if (in.pressed(GLFW_KEY_ENTER)) stopTyping(); }
        String shown = typing ? query + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : (query.isBlank() ? "пошук..." : query);
        Gui.text(shown, qx + 10, qy + 11, 2f, query.isBlank() && !typing ? 0.45f : 1f, query.isBlank() && !typing ? 0.47f : 1f, query.isBlank() && !typing ? 0.55f : 1f);
        if (!query.isBlank() && !typing && g.over(qx + 200, qy, 30, 36)) { Gui.text("x", qx + 210, qy + 10, 2.2f, 1f, 0.5f, 0.5f); if (g.click) { g.click = false; query = ""; } }
        if (g.click && qHov && !typing) { g.click = false; typing = true; in.beginText(query, false); }
        else if (g.click && !qHov && typing) stopTyping();

        boolean shift = in.down(GLFW_KEY_LEFT_SHIFT) || in.down(GLFW_KEY_RIGHT_SHIFT), ctrl = in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL);
        boolean left = g.click, right = in.clicked(GLFW_MOUSE_BUTTON_RIGHT);
        int hovered = top.draw(this, g, x0, py + craftTop);
        label("СХОВИЩЕ", x0, py + sy - 20);
        String sameAs = null;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) { // що під мишею - щоб підсвітити однакові
            int idx = row < 3 ? 9 + row * 9 + col : col;
            float x = x0 + col * (SIZE + GAP), y = py + sy + row * (SIZE + GAP) + (row == 3 ? 18 : 0);
            if (g.over(x, y, SIZE, SIZE) && menu.get(idx) != null) sameAs = menu.get(idx).item;
        }
        if (hovered >= 0 && menu.get(hovered) != null) sameAs = menu.get(hovered).item;
        if (menu.cursor() != null) sameAs = menu.cursor().item;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 9; col++) {
            int idx = row < 3 ? 9 + row * 9 + col : col; // три рядки сховища, нижче - хотбар
            float x = x0 + col * (SIZE + GAP), y = py + sy + row * (SIZE + GAP) + (row == 3 ? 18 : 0);
            boolean hov = g.over(x, y, SIZE, SIZE);
            if (hov) hovered = idx;
            slotBox(x, y, SIZE, idx == inv.selected(), hov);
            drawStack(menu.get(idx), x, y, SIZE);
            if (row == 3) Gui.text(String.valueOf(col + 1), x + 4, y + 3, 1.5f, 0.5f, 0.52f, 0.62f);
            overlay(menu.get(idx), x, y, SIZE, hov ? null : sameAs);
        }
        float hy = py + sy + 3 * (SIZE + GAP) + 9;
        Gui.rect(x0, hy - 1, total, 2, PANEL_LO[0], PANEL_LO[1], PANEL_LO[2], 1f); // межа сховища й хотбару
        Gui.rect(x0, hy + 1, total, 1, PANEL_HI[0], PANEL_HI[1], PANEL_HI[2], 0.6f);
        Gui.textCentered("ПКМ - половина   Shift - перенести   1-9 - у хотбар   Q - викинути", Gui.W / 2f, py + ph - 26, 1.4f, LABEL[0] * 0.8f, LABEL[1] * 0.8f, LABEL[2] * 0.8f);
        long now = System.currentTimeMillis();
        boolean inside = g.over(px, py, pw, ph);
        if (hovered >= 0) {
            if (left) {
                g.click = false;
                if (hovered == lastSlot && now - lastClick < 350 && menu.cursor() != null && !shift) { menu.doubleClick(hovered); lastClick = 0; }
                else { menu.click(hovered, 0, shift); lastSlot = hovered; lastClick = now; }
            } else if (right) menu.click(hovered, 1, shift);
            if (!typing) {
                for (int k = 0; k < 9; k++) if (in.pressed(GLFW_KEY_1 + k)) menu.swapHotbar(hovered, k);
                if (in.pressed(GLFW_KEY_Q) && menu.cursor() == null) { Inventory.Stack d = menu.drop(hovered, ctrl); if (d != null) dropper.accept(d); }
            }
        } else if (!inside && menu.cursor() != null) { // клік поза вікном - викинути стек (права - один)
            if (left) { g.click = false; dropper.accept(menu.takeCursor(false)); }
            else if (right) dropper.accept(menu.takeCursor(true));
        }
        Inventory.Stack under = hovered >= 0 ? menu.get(hovered) : null;
        if (under != null && menu.cursor() == null) tooltip(g, under);
        if (menu.cursor() != null) drawStack(menu.cursor(), (float) g.mx - 27, (float) g.my - 27, 54);
    }

    private static final String[] TOOL_NAMES = {"", "кирка", "сокира", "лопата"};

    private void tooltip(Gui g, Inventory.Stack st) {
        ItemType it = world.itemTypes.get(st.item);
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<float[]> colors = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, float[]> add = (t, c) -> { lines.add(t); colors.add(c); };
        String kind = it == null ? null : it.armorSlot != null ? "Броня" : it.tool != null && it.tool != voxelin.api.ToolKind.NONE ? "Інструмент: " + TOOL_NAMES[it.tool.ordinal()] + (it.tier > 0 ? ", рівень " + it.tier : "")
                : it.food > 0 ? "Їжа" : it.placesBlock != null ? "Блок" : it.attack > 1 ? "Зброя" : "Матеріал";
        if (kind != null) add.accept(kind, new float[]{0.6f, 0.65f, 0.85f});
        if (it != null && it.attack > 1) add.accept("Шкода: " + fmt(it.attack), new float[]{1f, 0.6f, 0.5f});
        if (it != null && it.armorPoints > 0) add.accept("Захист: +" + it.armorPoints, new float[]{0.7f, 0.85f, 1f});
        if (it != null && it.durability > 0) {
            float f = 1f - st.damage / (float) it.durability;
            add.accept("Міцність: " + (it.durability - st.damage) + " / " + it.durability, new float[]{1f - f * 0.5f, 0.5f + f * 0.45f, 0.45f});
        }
        if (it != null && it.food > 0) add.accept("Їжа: +" + it.food, new float[]{0.95f, 0.75f, 0.45f});
        int all = menu.total(st.item);
        if (all > st.count) add.accept("Усього у вікні: " + all, new float[]{0.75f, 0.75f, 0.8f});
        String l1 = titleOf(world, st.item);
        float w = Gui.textWidth(l1, 2.2f);
        for (String l : lines) w = Math.max(w, Gui.textWidth(l, 1.8f));
        w += 20;
        float h = 14 + 24 + lines.size() * 21;
        float x = Math.min((float) g.mx + 16, Gui.W - w - 4), y = Math.min((float) g.my + 14, Gui.H - h - 4);
        Gui.rect(x, y, w, h, 0.06f, 0.02f, 0.12f, 0.95f);
        Gui.frame(x, y, w, h, 2, 0.35f, 0.2f, 0.55f);
        Gui.text(l1, x + 10, y + 8, 2.2f, 1, 1, 1);
        float yy = y + 8 + 26;
        for (int i = 0; i < lines.size(); i++) { float[] c = colors.get(i); Gui.text(lines.get(i), x + 10, yy, 1.8f, c[0], c[1], c[2]); yy += 21; }
    }

    private static String fmt(float v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v); }

    /** Закрити вікно: курсорний стек - назад в інвентар, що не влізло - на землю. */
    void close() {
        stopTyping();
        Inventory.Stack left = menu.closeCursor(inv);
        if (left != null) dropper.accept(left);
        for (Inventory.Stack st : menu.drainExtra()) { int rest = inv.add(st.item, st.count, st.damage); if (rest > 0) dropper.accept(new Inventory.Stack(st.item, rest, st.damage)); }
        top.onClose();
        lastSlot = -1;
    }

    /** Сітка крафту n x n + стрілка + результат. */
    private static final class CraftTop implements Top {
        private final CraftMenu m;
        CraftTop(CraftMenu m) { this.m = m; }
        public float height() { return Math.max(m.n * (SIZE + GAP), m.armorBase >= 0 ? 4 * 52 : 0); }
        private static final String[] HEART = {".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."};
        private static final String[] SHIELD = {"XX...XX", "XXXXXXX", "XXXXXXX", ".XXXXX.", ".XXXXX."};
        private static final String[] FOOD = {"...XXX.", "..XXXXX", "..XXXXX", ".XXXXX.", "XXX....", "XX....."};
        public void onClose() { m.refresh(); }
        public int draw(InventoryScreen s, Gui g, float x0, float top) {
            int n = m.n, hovered = -1;
            float gx = x0 + 3 * (SIZE + GAP), gy = top, rx = gx + n * (SIZE + GAP) + 80, ry = gy + (m.n * (SIZE + GAP) - GAP - SIZE) / 2f;
            if (m.armorBase >= 0) { // чотири слоти броні ліворуч (шолом, нагрудник, поножі, черевики)
                for (int k = 0; k < 4; k++) {
                    float ax = x0 + 20, ay = top + k * 52;
                    boolean hov = g.over(ax, ay, 46, 46);
                    if (hov) hovered = m.armorBase + k;
                    s.slotBox(ax, ay, 46, false, hov);
                    if (m.get(m.armorBase + k) == null) pixels(ARMOR_GHOST[k], ax + 9, ay + 10, 3f, 0.4f, 0.42f, 0.52f, 0.55f);
                    s.drawStack(m.get(m.armorBase + k), ax, ay, 46);
                    s.overlay(m.get(m.armorBase + k), ax, ay, 46, null);
                }
                label("БРОНЯ", x0 + 20, top - 18);
                if (s.vitals != null) { // картка стану під сіткою крафту
                    float cx = gx, cy = gy + n * (SIZE + GAP) + 4, cw = 9 * (SIZE + GAP) - GAP - (gx - x0);
                    Gui.rect(cx, cy, cw, 44, 0.06f, 0.06f, 0.09f, 0.9f);
                    Gui.frame(cx, cy, cw, 44, 1, 0.3f, 0.32f, 0.44f);
                    int armor = s.inv.armorPoints(s.world.itemTypes);
                    float colW = cw / 3f;
                    pixels(SHIELD, cx + 12, cy + 15, 2.5f, 0.72f, 0.8f, 0.95f, 1f);
                    Gui.text(armor + "/20", cx + 36, cy + 13, 2f, 0.8f, 0.87f, 1f);
                    pixels(HEART, cx + colW + 12, cy + 15, 2.5f, 0.9f, 0.15f, 0.18f, 1f);
                    Gui.text((int) Math.ceil(s.vitals.health()) + "/20", cx + colW + 36, cy + 13, 2f, 1f, 0.75f, 0.75f);
                    pixels(FOOD, cx + 2 * colW + 12, cy + 15, 2.5f, 0.78f, 0.5f, 0.2f, 1f);
                    Gui.text((int) Math.ceil(s.vitals.hunger()) + "/20", cx + 2 * colW + 36, cy + 13, 2f, 1f, 0.85f, 0.6f);
                }
            }
            label("КРАФТ", gx, top - 18);
            for (int k = 0; k < n * n; k++) {
                float x = gx + (k % n) * (SIZE + GAP), y = gy + (k / n) * (SIZE + GAP);
                boolean hov = g.over(x, y, SIZE, SIZE);
                if (hov) hovered = CraftMenu.GRID + k;
                s.slotBox(x, y, SIZE, false, hov);
                s.drawStack(m.gridStack(k), x, y, SIZE);
                s.overlay(m.gridStack(k), x, y, SIZE, null);
            }
            pixels(ARROW, gx + n * (SIZE + GAP) + 14, ry + SIZE / 2f - 14, 4f, m.result() != null ? 1f : 0.4f, m.result() != null ? 0.82f : 0.42f, m.result() != null ? 0.3f : 0.52f, 1f);
            boolean hovR = g.over(rx, ry, SIZE, SIZE);
            if (hovR) hovered = CraftMenu.RESULT;
            if (m.result() != null) Gui.rect(rx - 6, ry - 6, SIZE + 12, SIZE + 12, 1f, 0.82f, 0.3f, 0.25f); // готовий результат світиться
            s.slotBox(rx, ry, SIZE, false, hovR);
            s.drawStack(m.result(), rx, ry, SIZE);
            return hovered;
        }
    }

    /** Піч: вхід зверху, паливо знизу, вогник між ними, стрілка прогресу й вихід. */
    private static final class FurnaceTop implements Top {
        private final FurnaceMenu m;
        FurnaceTop(FurnaceMenu m) { this.m = m; }
        public float height() { return 2 * (SIZE + GAP); }
        public void onClose() {}
        public int draw(InventoryScreen s, Gui g, float x0, float top) {
            FurnaceEntity f = m.furnace;
            int hovered = -1;
            float ix = x0 + 3.2f * (SIZE + GAP), iy = top, fy = top + SIZE + GAP, ox = ix + 3 * (SIZE + GAP), oy = top + (SIZE + GAP) / 2f;
            boolean hi = g.over(ix, iy, SIZE, SIZE), hf = g.over(ix, fy, SIZE, SIZE), ho = g.over(ox, oy, SIZE, SIZE);
            if (hi) hovered = FurnaceMenu.INPUT;
            if (hf) hovered = FurnaceMenu.FUEL;
            if (ho) hovered = FurnaceMenu.OUTPUT;
            s.slotBox(ix, iy, SIZE, false, hi); s.drawStack(m.get(FurnaceMenu.INPUT), ix, iy, SIZE);
            s.slotBox(ix, fy, SIZE, false, hf); s.drawStack(m.get(FurnaceMenu.FUEL), ix, fy, SIZE);
            s.slotBox(ox, oy, SIZE, false, ho); s.drawStack(m.get(FurnaceMenu.OUTPUT), ox, oy, SIZE);
            // вогник (скільки лишилось палива) між входом і паливом
            float fx = ix + SIZE + 18, fh = 34, fyy = top + SIZE - 4;
            Gui.rect(fx, fyy, 20, fh, 0.2f, 0.2f, 0.22f, 1f);
            float frac = f.burnTotal <= 0 ? 0 : Math.min(1f, f.burnLeft / (float) f.burnTotal);
            if (frac > 0) Gui.rect(fx, fyy + fh * (1 - frac), 20, fh * frac, 1f, 0.55f, 0.1f, 1f);
            // стрілка прогресу
            float ax = ix + SIZE + 70, ay = oy + SIZE / 2f - 8, aw = ox - ax - 14;
            Gui.rect(ax, ay, aw, 16, 0.2f, 0.2f, 0.22f, 1f);
            float pf = f.cookTotal <= 0 ? 0 : Math.min(1f, f.progress / (float) f.cookTotal);
            if (pf > 0) Gui.rect(ax, ay, aw * pf, 16, 0.95f, 0.95f, 0.95f, 1f);
            return hovered;
        }
    }

    /** Творчий: рядок вкладок і палітра 5x9 (колесо миші гортає; клік з предметом у курсорі - викинути його). */
    private static final class CreativeTop implements Top {
        private final CreativeMenu m;
        CreativeTop(CreativeMenu m) { this.m = m; }
        public float height() { return 70 + CreativeMenu.ROWS * (SIZE + GAP) - GAP; }
        public void onClose() {}
        public int draw(InventoryScreen s, Gui g, float x0, float top) {
            int hovered = -1;
            CreativeMenu.Tab hoverTab = null;
            CreativeMenu.Tab[] tabs = CreativeMenu.Tab.values();
            float total = 9 * (SIZE + GAP) - GAP, tw = (total + 6) / tabs.length - 6;
            for (int t = 0; t < tabs.length; t++) {
                float tx = x0 + t * (tw + 6), ty = top - 8;
                boolean on = m.tab() == tabs[t], hov = g.over(tx, ty, tw, 40);
                Gui.rect(tx, ty, tw, 40, on ? 0.3f : hov ? 0.22f : 0.08f, on ? 0.26f : hov ? 0.23f : 0.08f, on ? 0.14f : hov ? 0.3f : 0.12f, 1f);
                Gui.frame(tx, ty, tw, 40, 2, on ? 1f : 0.3f, on ? 0.82f : 0.32f, on ? 0.3f : 0.44f);
                if (s.world.itemTypes.get(tabs[t].icon) != null) s.icons.draw(tabs[t].icon, tx + tw / 2f - 16, ty + 4, 32); // картинка замість слова
                else Gui.textCentered(tabs[t].shortName, tx + tw / 2f, ty + 13, 1.4f, 1f, 0.9f, 0.6f);
                if (hov) hoverTab = tabs[t];
                if (hov && g.click) { g.click = false; m.tab(tabs[t]); }
            }
            float gy = top + 66;
            label(m.tab().title.toUpperCase() + "   (" + m.items(m.tab()).size() + ")", x0, gy - 20);
            boolean overGrid = g.over(x0, gy, total, CreativeMenu.ROWS * (SIZE + GAP));
            if (overGrid && s.input != null) { double sc = s.input.consumeScroll(); if (sc != 0) m.scrollBy(sc > 0 ? -1 : 1); }
            for (int k = 0; k < CreativeMenu.ROWS * CreativeMenu.COLS; k++) {
                float x = x0 + (k % 9) * (SIZE + GAP), y = gy + (k / 9) * (SIZE + GAP);
                boolean hov = g.over(x, y, SIZE, SIZE);
                if (hov) hovered = CreativeMenu.PALETTE + k;
                s.slotBox(x, y, SIZE, false, hov);
                s.drawStack(m.get(CreativeMenu.PALETTE + k), x, y, SIZE);
                s.overlay(m.get(CreativeMenu.PALETTE + k), x, y, SIZE, null);
            }
            if (hoverTab != null) { // назва вкладки під мишею
                float w = Gui.textWidth(hoverTab.title, 1.8f) + 16, hx = Math.min((float) g.mx + 14, Gui.W - w - 4), hy = (float) g.my + 16;
                Gui.rect(hx, hy, w, 28, 0.06f, 0.02f, 0.12f, 0.95f);
                Gui.frame(hx, hy, w, 28, 2, 0.35f, 0.2f, 0.55f);
                Gui.text(hoverTab.title, hx + 8, hy + 7, 1.8f, 1, 1, 1);
            }
            if (m.maxScroll() > 0) { // смуга прокрутки праворуч
                float bx = x0 + total + 6, bh = CreativeMenu.ROWS * (SIZE + GAP) - GAP, kh = bh * CreativeMenu.ROWS / (float) (CreativeMenu.ROWS + m.maxScroll());
                Gui.rect(bx, gy, 8, bh, 0.06f, 0.06f, 0.09f, 1f);
                Gui.rect(bx, gy + (bh - kh) * m.scroll() / m.maxScroll(), 8, kh, 0.55f, 0.58f, 0.72f, 1f);
            }
            return hovered;
        }
    }

    /** Скриня: три ряди по дев'ять слотів. */
    private static final class ChestTop implements Top {
        private final ChestMenu m;
        ChestTop(ChestMenu m) { this.m = m; }
        public float height() { return 3 * (SIZE + GAP); }
        public void onClose() {}
        public int draw(InventoryScreen s, Gui g, float x0, float top) {
            int hovered = -1;
            for (int k = 0; k < ChestEntity.SIZE; k++) {
                float x = x0 + (k % 9) * (SIZE + GAP), y = top + (k / 9) * (SIZE + GAP);
                boolean hov = g.over(x, y, SIZE, SIZE);
                if (hov) hovered = ChestMenu.CHEST + k;
                s.slotBox(x, y, SIZE, false, hov);
                s.drawStack(m.get(ChestMenu.CHEST + k), x, y, SIZE);
            }
            return hovered;
        }
    }
}
