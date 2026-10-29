package voxelin.api;

/**
 * Опис блока як даних (а не коду). Будівник: {@code new BlockSettings().color(0x7F7F7F).hardness(1.5f)}.
 * Текстури граней - імена з реєстру текстур; колір - запасний варіант, коли текстури нема.
 */
public final class BlockSettings {
    int color = 0xFF00FF;
    String texTop, texSide, texBottom;
    boolean solid = true, opaque = true, liquid = false, translucent = false;
    int light = 0;
    float hardness = 1f;
    int absorb = -1;
    ToolKind tool = ToolKind.NONE;
    int minTier;
    boolean noItem, noDrops;
    String title, interact;
    java.util.List<Drop> drops;

    /** Один запис дропу: предмет, скільки (min..max) і шанс 0..1. */
    public static final class Drop {
        public final String item; public final int min, max; public final float chance;
        public Drop(String item, int min, int max, float chance) { this.item = item; this.min = Math.max(0, min); this.max = Math.max(this.min, max); this.chance = Float.isNaN(chance) ? 1f : Math.max(0f, Math.min(1f, chance)); }
    }

    /** RGB 0xRRGGBB. */
    public BlockSettings color(int rgb) { color = rgb & 0xFFFFFF; return this; }
    /** Одна текстура на всі грані. */
    public BlockSettings texture(String all) { texTop = texSide = texBottom = all; return this; }
    public BlockSettings textures(String top, String side, String bottom) { texTop = top; texSide = side; texBottom = bottom; return this; }
    /** Чи має колізію (крізь несуцільний блок можна пройти). */
    public BlockSettings solid(boolean v) { solid = v; return this; }
    /** Чи закриває сусідні грані (скло й листя - ні). */
    public BlockSettings opaque(boolean v) { opaque = v; return this; }
    /** Напівпрозорий (скло, вода): малюється окремим проходом зі змішуванням кольорів; закриває сусідні грані не повністю. */
    public BlockSettings translucent(boolean v) { translucent = v; if (v) opaque = false; return this; }
    public BlockSettings liquid(boolean v) { liquid = v; if (v) { solid = false; opaque = false; } return this; }
    /** Світло від блока 0..15. */
    public BlockSettings light(int level) { light = Math.max(0, Math.min(15, level)); return this; }
    /** Скільки рівнів світла гасить блок (0..15; 15 - повна перешкода). Типово: непрозорий 15, рідина 2, листя 1, решта 0. */
    public BlockSettings absorb(int a) { absorb = Math.max(0, Math.min(15, a)); return this; }
    /** Назва для людей (напр. «Камінь»). Без неї - з назви блока. */
    public BlockSettings title(String t) { title = t; return this; }
    /** Права кнопка по блоку відкриває вікно цього виду («workbench», «furnace»); присід - ставити блок. */
    public BlockSettings interact(String kind) { interact = kind; return this; }
    /** Яким інструментом ламати швидше. */
    public BlockSettings tool(ToolKind k) { tool = k == null ? ToolKind.NONE : k; return this; }
    /** Мінімальний рівень інструмента, щоб блок взагалі дав дроп (0 - дає завжди; руки не вистачає, якщо > 0). */
    public BlockSettings minTier(int t) { minTier = Math.max(0, t); return this; }
    /** Що випадає (можна кілька записів). Без жодного виклику - сам предмет-блок. */
    public BlockSettings drops(String item, int min, int max, float chance) { if (drops == null) drops = new java.util.ArrayList<>(); drops.add(new Drop(item, min, max, chance)); return this; }
    public BlockSettings drops(String item) { return drops(item, 1, 1, 1f); }
    /** Нічого не випадає (скло, лід). */
    public BlockSettings noDrops() { noDrops = true; return this; }
    /** Без предмета-блока (вода, корінна порода): його не можна мати в інвентарі. */
    public BlockSettings noItem() { noItem = true; return this; }
    /** Скільки ламати (етап «Виживання»); від'ємне - незламний (корінна порода). */
    public BlockSettings hardness(float h) { hardness = h < 0 ? -1f : h; return this; }

    String shape;
    /** Форма замість повного куба: "torch" - тонкий стовпчик 2x10 пікселів посередині клітинки (текстуру беруть ті самі пікселі). */
    public BlockSettings shape(String s) { shape = s; return this; }
    public String shapeValue() { return shape; }

    String sound;
    /** Матеріал для звуків (stone, wood, dirt, grass, sand, glass, metal, snow); без нього - здогадка з інструмента й назви. */
    public BlockSettings sound(String material) { sound = material; return this; }
    public String soundValue() { return sound; }

    /** Копія, де назви текстур без простору імен ("stone") отримують префікс мода ("voxelin:stone"). Викликає завантажувач. */
    public BlockSettings qualified(String modId) {
        BlockSettings b = new BlockSettings();
        b.color = color; b.solid = solid; b.opaque = opaque; b.liquid = liquid; b.translucent = translucent; b.light = light; b.hardness = hardness; b.absorb = absorb; b.title = title; b.interact = interact; b.tool = tool; b.minTier = minTier; b.noItem = noItem; b.noDrops = noDrops; b.sound = sound; b.shape = shape;
        if (drops != null) { b.drops = new java.util.ArrayList<>(); for (Drop d : drops) b.drops.add(new Drop(d.item.indexOf(':') >= 0 ? d.item : modId + ":" + d.item, d.min, d.max, d.chance)); }
        b.texTop = q(texTop, modId); b.texSide = q(texSide, modId); b.texBottom = q(texBottom, modId);
        return b;
    }

    private static String q(String t, String modId) { return t == null || t.indexOf(':') >= 0 ? t : modId + ":" + t; }

    public int color() { return color; }
    public String textureTop() { return texTop; }
    public String textureSide() { return texSide; }
    public String textureBottom() { return texBottom; }
    public boolean isSolid() { return solid; }
    public boolean isOpaque() { return opaque; }
    public boolean isLiquid() { return liquid; }
    public boolean isTranslucent() { return translucent; }
    public int lightLevel() { return light; }
    /** -1 - не задано (рахується типове). */
    public int absorbValue() { return absorb; }
    public float hardnessValue() { return hardness; }
    public String interactKind() { return interact; }
    public String titleText() { return title; }
    public ToolKind toolKind() { return tool; }
    public int minTierValue() { return minTier; }
    public boolean hasNoItem() { return noItem; }
    public boolean hasNoDrops() { return noDrops; }
    /** null - типовий дроп (сам блок). */
    public java.util.List<Drop> dropList() { return drops; }
    public boolean isUnbreakable() { return hardness < 0; }
}
