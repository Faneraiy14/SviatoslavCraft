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
    /** Назва для людей. */
    public final String title;
    /** Назва блока, який ставить предмет, або null. */
    public final String placesBlock;

    ItemType(String name, ItemSettings s) {
        this.name = name;
        title = s.titleText() != null ? s.titleText() : prettify(name);
        durability = s.durabilityValue(); maxStack = durability > 0 ? 1 : s.maxStackValue(); tier = s.tierValue(); speed = s.speedValue(); tool = s.toolKind();
        food = s.foodValue(); color = s.colorValue(); texture = s.textureName(); placesBlock = s.placesBlockName();
    }

    /** «voxelin:iron_pickaxe» -> «Iron pickaxe» (запасна назва). */
    public static String prettify(String name) {
        String n = name.substring(name.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
        return n.isEmpty() ? name : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    @Override public String name() { return name; }
    @Override public String toString() { return name; }
}
