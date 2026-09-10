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
}
