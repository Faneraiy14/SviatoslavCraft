package voxelin.core;

import voxelin.api.ToolKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Творчий інвентар (прохання Святослава 29.10.2026): усі блоки й предмети гри (і модів) по вкладках, нескінченні.
 * Слоти 0..35 - інвентар гравця, 36.. - палітра поточної вкладки (3 x 9, гортається колесом). Клік по палітрі: ліва - повна стопка в курсор,
 * права - один, shift - повна стопка одразу в інвентар, 1-9 - у слот хотбару; клік по палітрі з чимось у курсорі - викинути це (кошик).
 */
public final class CreativeMenu extends Menu {
    public enum Tab {
        BUILD("Будова", "Будівельні блоки", "bricks"), NATURE("Природа", "Природа", "grass"), ORES("Руди", "Руди й матеріали", "diamond"),
        USEFUL("Корисне", "Корисні блоки", "workbench"), TOOLS("Знаряддя", "Інструменти й зброя", "iron_pickaxe"), ARMOR("Броня", "Броня", "iron_chestplate"),
        FOOD("Їжа", "Їжа", "apple"), SEAM("Шов", "Шов і виміри", "liminite_shard");
        /** Іконка вкладки - предмет (рішення Святослава: картинка замість слова); shortName - підказка, title - над палітрою. */
        public final String shortName, title, icon;
        Tab(String s, String t, String i) { shortName = s; title = t; icon = "voxelin:" + i; }
    }

    public static final int PALETTE = Inventory.SIZE, ROWS = 3, COLS = 9;
    private final List<List<String>> byTab = new ArrayList<>();
    private Tab tab = Tab.BUILD;
    private int scroll;

    public CreativeMenu(ItemRegistry items, BlockRegistry blocks, Inventory inv) {
        super(items);
        addInventory(inv);
        for (int t = 0; t < Tab.values().length; t++) byTab.add(new ArrayList<>());
        for (ItemType it : items.all()) byTab.get(category(it, it.placesBlock == null ? null : blocks.byName(it.placesBlock)).ordinal()).add(it.name);
        for (int k = 0; k < ROWS * COLS; k++) {
            final int idx = k;
            slots.add(new Slot() {
                public Inventory.Stack get() { String n = at(idx); return n == null ? null : new Inventory.Stack(n, 1); }
                public void set(Inventory.Stack s) {}
                public boolean takeOnly() { return true; }
            });
        }
    }

    private static final Set<String> NATURAL = Set.of("grass", "dirt", "stone", "deep_stone", "sand", "gravel", "clay", "snow", "ice", "cactus", "mud", "pale_grass", "water");
    private static final String[] SEAM_WORDS = {"seam", "threshold", "end_", "obsidian", "cinder", "ash", "ember", "fortress", "nether", "liminite", "petrified", "dune_glass", "sintered", "monolith", "eye", "anchor", "heart", "wings", "dragon"};

    /** Вкладка предмета за його властивостями (працює й для предметів модів). */
    public static Tab category(ItemType it, BlockType b) {
        String n = it.name.substring(it.name.indexOf(':') + 1);
        if (it.armorSlot != null) return Tab.ARMOR;
        if (it.food > 0) return Tab.FOOD;
        boolean seam = false;
        for (String w : SEAM_WORDS) if (n.contains(w)) seam = true;
        if (b != null) {
            if (b.interact != null || b.light > 0 || "torch".equals(b.shape)) return seam ? Tab.SEAM : Tab.USEFUL;
            if (seam) return Tab.SEAM;
            if (n.endsWith("_ore")) return Tab.ORES;
            if (NATURAL.contains(n) || n.endsWith("_log") || n.endsWith("_leaves") || n.endsWith("_bloom")) return Tab.NATURE;
            return Tab.BUILD;
        }
        if ((it.tool != null && it.tool != ToolKind.NONE) || it.attack > 1 || n.equals("bow") || n.equals("arrow") || n.equals("flint_and_steel")) return seam ? Tab.SEAM : Tab.TOOLS;
        if (seam) return Tab.SEAM;
        if (n.endsWith("_board")) return Tab.BUILD;
        return Tab.ORES;
    }

    public Tab tab() { return tab; }
    public void tab(Tab t) { tab = t; scroll = 0; }
    public List<String> items(Tab t) { return byTab.get(t.ordinal()); }
    public int scroll() { return scroll; }
    /** Скільки рядків можна прогорнути. */
    public int maxScroll() { return Math.max(0, (items(tab).size() + COLS - 1) / COLS - ROWS); }
    public void scrollBy(int rows) { scroll = Math.max(0, Math.min(maxScroll(), scroll + rows)); }

    private String at(int k) { int i = scroll * COLS + k; List<String> l = items(tab); return i < l.size() ? l.get(i) : null; }

    private Inventory.Stack full(String n) { return new Inventory.Stack(n, maxStack(n)); }

    @Override public void click(int i, int button, boolean shift) {
        if (i < PALETTE) { super.click(i, button, shift); return; }
        String n = at(i - PALETTE);
        if (cursor() != null && !shift) { setCursor(null); return; } // кошик
        if (n == null) return;
        if (shift) { Inventory.Stack s = full(n); for (int k = 0; k < Inventory.SIZE && s.count > 0; k++) if (get(k) == null) { slots.get(k).set(s); s = null; break; } return; }
        setCursor(button == 1 ? new Inventory.Stack(n, 1) : full(n));
    }

    @Override public void swapHotbar(int i, int hot) {
        if (i < PALETTE) { super.swapHotbar(i, hot); return; }
        String n = at(i - PALETTE);
        if (n != null && cursor() == null && hot >= 0 && hot < Inventory.HOTBAR) slots.get(hot).set(full(n));
    }

    @Override public void doubleClick(int i) { if (i < PALETTE) super.doubleClick(i); }
}
