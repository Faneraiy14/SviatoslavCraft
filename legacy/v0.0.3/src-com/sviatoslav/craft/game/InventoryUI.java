package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.graphics.GLBitmapFont;
import com.sviatoslav.craft.engine.world.Block;
import static org.lwjgl.opengl.GL11.*;

// РЕАЛЬНА фіча (Sviatoslav попросив) - раніше НІДЕ на екрані не було
// видно, що взагалі вибрано в інвентарі (перемикання 1-5 клавішами вже
// працювало, але без жодного відображення результату). Хотбар - завжди
// видимий унизу; повноекранне вікно (E) - ті самі 5 слотів, лише більші,
// на сірій панелі й "вдавлених" слотах у стилі оригінального Minecraft
// (наскільки можна без текстур - самими прямокутниками різних відтінків
// сірого, що імітують фаску).
public final class InventoryUI {
    private boolean open = false;
    public boolean isOpen() { return open; }
    public void toggle() { open = !open; }
    public void close() { open = false; }

    private static final int SLOT_SIZE = 50;
    private static final int SLOT_GAP = 6;
    private static final int SLOT_SIZE_BIG = 90;
    private static final int SLOT_GAP_BIG = 12;

    public void renderHotbar(Inventory inventory, int screenWidth, int screenHeight) {
        render2D(screenWidth, screenHeight, () -> {
            int startX = centerX(inventory, screenWidth, SLOT_SIZE, SLOT_GAP);
            int y = screenHeight - SLOT_SIZE - 20;
            // Темна "рамка" хотбару позаду слотів - той самий прийом, що
            // й у справжньому Minecraft (слоти сидять У панелі, не самі
            // по собі в повітрі).
            int pad = 6;
            int totalWidth = inventory.getItems().length * (SLOT_SIZE + SLOT_GAP) - SLOT_GAP;
            glColor4f(0.1f, 0.1f, 0.1f, 0.6f);
            glRectf(startX - pad, y - pad, startX + totalWidth + pad, y + SLOT_SIZE + pad);
            drawSlots(inventory, startX, y, SLOT_SIZE, SLOT_GAP, false);
        });
    }

    public void renderFullScreen(Inventory inventory, int screenWidth, int screenHeight) {
        if (!open) return;
        render2D(screenWidth, screenHeight, () -> {
            glColor4f(0f, 0f, 0f, 0.5f);
            glRectf(0, 0, screenWidth, screenHeight);

            int count = inventory.getItems().length;
            int gridW = count * (SLOT_SIZE_BIG + SLOT_GAP_BIG) - SLOT_GAP_BIG;
            int panelPad = 40;
            int panelW = gridW + panelPad * 2;
            int panelH = SLOT_SIZE_BIG + panelPad * 2 + 70;
            int panelX = (screenWidth - panelW) / 2, panelY = (screenHeight - panelH) / 2;

            // Класична сіро-кам'яна панель Minecraft-меню (без текстур -
            // сама заливка + темна рамка), не просто чорне затемнення.
            glColor3f(0.55f, 0.55f, 0.55f);
            glRectf(panelX, panelY, panelX + panelW, panelY + panelH);
            drawBorder(panelX, panelY, panelW, panelH, 3f, 0.18f, 0.18f, 0.18f);

            GLBitmapFont.draw("INVENTORY", panelX + panelW/2f - 9*3*3, panelY + 20, 3, 0.2f, 0.2f, 0.2f);
            drawSlots(inventory, panelX + panelPad, panelY + panelH - panelPad - SLOT_SIZE_BIG,
                SLOT_SIZE_BIG, SLOT_GAP_BIG, true);
        });
    }

    private static int centerX(Inventory inventory, int screenWidth, int slotSize, int gap) {
        int count = inventory.getItems().length;
        int totalWidth = count * slotSize + (count - 1) * gap;
        return (screenWidth - totalWidth) / 2;
    }

    private void drawSlots(Inventory inventory, int startX, int y, int slotSize, int gap, boolean withLabel) {
        Inventory.Item[] items = inventory.getItems();
        int selected = inventory.getSelectedSlot();
        for (int i = 0; i < items.length; i++) {
            int x = startX + i * (slotSize + gap);
            drawSlot(x, y, slotSize, i == selected, items[i], withLabel, !withLabel ? i + 1 : 0);
        }
    }

    // "Вдавлений" слот у стилі Minecraft - темніша фаска зверху/зліва,
    // світліша знизу/справа (ілюзія заглибини), середньо-сіре тло. Без
    // текстур це найближче наближення до оригінального вигляду.
    private void drawSlot(int x, int y, int size, boolean isSelected, Inventory.Item item, boolean withLabel, int hotbarNumber) {
        glColor3f(0.13f, 0.13f, 0.13f);
        glRectf(x - 2, y - 2, x + size + 2, y + size + 2);

        glColor3f(0.35f, 0.35f, 0.35f); // темна фаска (верх+ліво = тінь заглибини)
        glRectf(x, y, x + size, y + size);
        glColor3f(0.62f, 0.62f, 0.62f); // світла фаска (низ+право)
        glRectf(x + 2, y + 2, x + size, y + size);
        glColor3f(0.5f, 0.5f, 0.5f); // саме "гніздо" слота
        glRectf(x + 2, y + 2, x + size - 2, y + size - 2);

        Inventory.Item slotItem = item;
        if (slotItem != null) {
            float[] c = Block.colorFor(slotItem.getBlockType());
            int pad = size / 5;
            glColor3f(c[0], c[1], c[2]);
            glRectf(x + pad, y + pad, x + size - pad, y + size - pad);
            // Кількість - у правому нижньому куті (як у Minecraft), не
            // зверху зліва.
            String countStr = String.valueOf(slotItem.getCount());
            GLBitmapFont.draw(countStr, x + size - countStr.length()*6*1.6f - 3, y + size - 18, 1.6f, 1, 1, 1);
            if (withLabel) {
                String name = slotItem.getBlockType().toString();
                GLBitmapFont.draw(name, x + size/2f - name.length()*6*1.4f/2f, y + size + 8, 1.4f, 0.85f, 0.85f, 0.85f);
            }
        }

        if (hotbarNumber > 0) {
            GLBitmapFont.draw(String.valueOf(hotbarNumber), x + 4, y + 4, 1.5f, 0.85f, 0.85f, 0.85f);
        }

        // Виділення вибраного слота - яскрава рамка ПОВЕРХ усього (той
        // самий білий акцент, що й у Minecraft на активному слоті
        // хотбару).
        if (isSelected) {
            glColor3f(1f, 1f, 1f);
            glLineWidth(3f);
            glBegin(GL_LINE_LOOP);
            glVertex2f(x - 2, y - 2); glVertex2f(x + size + 2, y - 2);
            glVertex2f(x + size + 2, y + size + 2); glVertex2f(x - 2, y + size + 2);
            glEnd();
        }
    }

    private void drawBorder(int x, int y, int w, int h, float lineWidth, float r, float g, float b) {
        glColor3f(r, g, b);
        glLineWidth(lineWidth);
        glBegin(GL_LINE_LOOP);
        glVertex2f(x, y); glVertex2f(x + w, y); glVertex2f(x + w, y + h); glVertex2f(x, y + h);
        glEnd();
    }

    private interface Draw { void run(); }

    private void render2D(int screenWidth, int screenHeight, Draw draw) {
        glMatrixMode(GL_PROJECTION); glPushMatrix(); glLoadIdentity(); glOrtho(0, screenWidth, screenHeight, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW); glPushMatrix(); glLoadIdentity(); glDisable(GL_DEPTH_TEST);
        // Той самий фікс, що й MenuManager/DebugOverlay - GL_CULL_FACE
        // з'їдав би заливку/текст у Y-перевернутій 2D-проєкції інакше.
        glDisable(GL_CULL_FACE);

        draw.run();

        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE);
        glPopMatrix(); glMatrixMode(GL_PROJECTION); glPopMatrix(); glMatrixMode(GL_MODELVIEW);
    }
}
