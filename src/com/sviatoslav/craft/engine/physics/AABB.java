package com.sviatoslav.craft.engine.physics;

public class AABB {
    public float minX, minY, minZ, maxX, maxY, maxZ;

    public AABB(float x, float y, float z, float w, float h) {
        minX = x - w/2; minY = y - h/2; minZ = z - w/2;
        maxX = x + w/2; maxY = y + h/2; maxZ = z + w/2;
    }

    public boolean intersects(AABB o) {
        return minX < o.maxX && maxX > o.minX &&
               minY < o.maxY && maxY > o.minY &&
               minZ < o.maxZ && maxZ > o.minZ;
    }
}
