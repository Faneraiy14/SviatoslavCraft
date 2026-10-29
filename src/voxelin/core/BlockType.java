package voxelin.core;

import voxelin.api.BlockRef;
import voxelin.api.BlockSettings;
import voxelin.api.ToolKind;

import java.util.List;

/** Зареєстрований блок: незмінні властивості, які рушій читає щокадру (тому прості поля, без гетерів-обгорток). */
public final class BlockType implements BlockRef {
    public final int id;
    public final String name;
    public final int color;
    public final boolean solid, opaque, liquid, translucent;
    public final int light;
    /** Скільки рівнів світла гасить (15 - непрозорий). */
    public final int absorb;
    public final float hardness;
    public final String texTop, texSide, texBottom;
    /** Яким інструментом швидше, і з якого рівня блок взагалі дає дроп. */
    public final ToolKind tool;
    public final int minTier;
    /** Що випадає (порожньо - нічого). Типово - сам предмет-блок. */
    public final List<BlockSettings.Drop> drops;
    public final boolean hasItem;
    /** Вікно, що відкривається правою кнопкою (workbench, furnace) або null. */
    public final String interact;
    /** Матеріал звуку (кроки, ламання, ставлення). */
    public final String sound;
    /** Форма (null - повний куб): "torch". */
    public final String shape;

    BlockType(int id, String name, BlockSettings s) {
        this.id = id; this.name = name;
        color = s.color(); solid = s.isSolid(); opaque = s.isOpaque(); liquid = s.isLiquid(); translucent = s.isTranslucent(); light = s.lightLevel(); hardness = s.hardnessValue();
        absorb = s.absorbValue() >= 0 ? s.absorbValue() : (s.isOpaque() ? 15 : (s.isLiquid() ? 2 : (s.isSolid() && !s.isTranslucent() ? 1 : 0)));
        interact = s.interactKind();
        tool = s.toolKind(); minTier = s.minTierValue(); hasItem = !s.hasNoItem() && id > 1; // повітря й «невідомий» предмета не мають
        drops = s.hasNoDrops() ? List.of() : (s.dropList() != null ? List.copyOf(s.dropList()) : (hasItem ? List.of(new BlockSettings.Drop(name, 1, 1, 1f)) : List.of()));
        texTop = s.textureTop(); texSide = s.textureSide(); texBottom = s.textureBottom();
        sound = s.soundValue() != null ? s.soundValue() : guessSound(name, s);
        shape = s.shapeValue();
    }

    /** Здогадка матеріалу звуку за назвою й інструментом (моди можуть задати явно: BlockSettings.sound). */
    static String guessSound(String name, BlockSettings s) {
        String n = name.substring(name.indexOf(':') + 1);
        if (n.contains("glass") || n.equals("ice") || n.contains("portal") || n.contains("rift")) return "glass";
        if (n.contains("snow")) return "snow";
        if (n.contains("sand") || n.contains("gravel")) return "sand";
        if (n.contains("grass") || n.contains("leaves") || n.contains("bloom") || n.contains("growth")) return "grass";
        if (n.contains("iron") || n.contains("gold") || n.contains("copper") || n.contains("anchor")) return "metal";
        if (s.toolKind() == ToolKind.AXE || n.contains("log") || n.contains("plank") || n.contains("thread")) return "wood";
        if (s.toolKind() == ToolKind.SHOVEL || n.contains("dirt") || n.contains("clay")) return "dirt";
        return "stone";
    }

    @Override public String name() { return name; }
    @Override public int id() { return id; }
    public boolean isAir() { return id == 0; }
    @Override public String toString() { return name; }
}
