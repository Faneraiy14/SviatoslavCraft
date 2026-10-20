package voxelin.core;

import voxelin.api.ItemRef;
import voxelin.api.ItemSettings;
import voxelin.api.ToolKind;

/** Зареєстрований предмет: незмінні властивості (назва «мод:назва»). */
public final class ItemType implements ItemRef {
    public final String name;
    public final int maxStack, durability, tier, food, color;
    public final float speed;
    public final ToolKind tool;
    public final String texture;
    /** Назва блока, який ставить предмет, або null. */
    public final String placesBlock;

    ItemType(String name, ItemSettings s) {
        this.name = name;
        durability = s.durabilityValue(); maxStack = durability > 0 ? 1 : s.maxStackValue(); tier = s.tierValue(); speed = s.speedValue(); tool = s.toolKind();
        food = s.foodValue(); color = s.colorValue(); texture = s.textureName(); placesBlock = s.placesBlockName();
    }

    @Override public String name() { return name; }
    @Override public String toString() { return name; }
}
