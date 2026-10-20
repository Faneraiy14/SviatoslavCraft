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
    /** Скільки ламати (етап «Виживання»); від'ємне - незламний (корінна порода). */
    public BlockSettings hardness(float h) { hardness = h < 0 ? -1f : h; return this; }

    /** Копія, де назви текстур без простору імен ("stone") отримують префікс мода ("voxelin:stone"). Викликає завантажувач. */
    public BlockSettings qualified(String modId) {
        BlockSettings b = new BlockSettings();
        b.color = color; b.solid = solid; b.opaque = opaque; b.liquid = liquid; b.translucent = translucent; b.light = light; b.hardness = hardness; b.absorb = absorb;
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
    public boolean isUnbreakable() { return hardness < 0; }
}
