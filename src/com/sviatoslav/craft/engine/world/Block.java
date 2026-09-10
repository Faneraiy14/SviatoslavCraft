package com.sviatoslav.craft.engine.world;

import java.io.Serializable;

public class Block implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Type { AIR, GRASS, DIRT, STONE, WOOD, LEAVES }
    private Type type;
    private int x, y, z;

    public Block(Type type, int x, int y, int z) {
        this.type = type; this.x = x; this.y = y; this.z = z;
    }
    public Type getType() { return type; }
    public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }

    // Винесено з World.buildChunkGeometry (де кольори дублювались лише для
    // рендеру кубів) - тепер спільне з хотбаром/інвентарем (InventoryUI),
    // щоб іконка слота в UI ЗАВЖДИ збігалась із реальним кольором блока
    // у світі, без ризику розсинхронізувати дві окремі копії тієї самої
    // таблиці кольорів.
    public static float[] colorFor(Type t) {
        switch (t) {
            case GRASS: return new float[]{0.2f, 0.7f, 0.1f};
            case DIRT: return new float[]{0.5f, 0.3f, 0.1f};
            case STONE: return new float[]{0.5f, 0.5f, 0.5f};
            case WOOD: return new float[]{0.4f, 0.2f, 0.05f};
            case LEAVES: return new float[]{0.0f, 0.5f, 0.0f};
            default: return new float[]{0.2f, 0.6f, 0.2f};
        }
    }
}
