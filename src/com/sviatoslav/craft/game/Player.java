package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.graphics.Camera;
import com.sviatoslav.craft.engine.physics.AABB;
import com.sviatoslav.craft.engine.world.*;

public class Player {
    private Camera camera;
    private World world;
    private float height = 1.7f, width = 0.6f, speed = 8.0f, jumpSpeed = 9.0f;
    private float verticalVelocity = 0;
    private boolean onGround = false;

    public Player(Camera camera, World world) {
        this.camera = camera; this.world = world;
        this.camera.setY(20.0f);
    }

    public void moveForward(float d) { float nx = camera.getX() + (float)Math.sin(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() - (float)Math.cos(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveBackward(float d) { float nx = camera.getX() - (float)Math.sin(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() + (float)Math.cos(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveLeft(float d) { float nx = camera.getX() - (float)Math.cos(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() - (float)Math.sin(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveRight(float d) { float nx = camera.getX() + (float)Math.cos(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() + (float)Math.sin(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }

    private void moveTo(float nx, float nz) {
        AABB testX = new AABB(nx, camera.getY(), camera.getZ(), width, height);
        if (!collidesWithWorld(testX)) camera.setX(nx);
        AABB testZ = new AABB(camera.getX(), camera.getY(), nz, width, height);
        if (!collidesWithWorld(testZ)) camera.setZ(nz);
    }

    public void jump() { if (onGround) { verticalVelocity = jumpSpeed; onGround = false; } }

    // РЕАЛЬНИЙ ФІКС (баг у чернетці DeepSeek): onGround рахувався ПІСЛЯ
    // того, як verticalVelocity вже занулили - тому ставав true і при
    // приземленні, і при ударі головою об стелю під час стрибка. Наслідок:
    // стрибнув під низьку стелю - і можна стрибати знову прямо в повітрі,
    // впершись у неї. Тепер напрямок (падав/летів угору) фіксується ДО
    // занулення швидкості - "на землі" стає true лише коли справді впав.
    public void updatePhysics(float dt) {
        verticalVelocity += -25.0f * dt;
        float ny = camera.getY() + verticalVelocity * dt;
        AABB testY = new AABB(camera.getX(), ny, camera.getZ(), width, height);
        if (collidesWithWorld(testY)) {
            boolean wasFalling = verticalVelocity < 0;
            verticalVelocity = 0;
            onGround = wasFalling;
        } else {
            camera.setY(ny);
            onGround = false;
        }
    }

    private boolean collidesWithWorld(AABB box) {
        for (int x = (int)Math.floor(box.minX); x <= (int)Math.floor(box.maxX); x++)
            for (int y = (int)Math.floor(box.minY); y <= (int)Math.floor(box.maxY); y++)
                for (int z = (int)Math.floor(box.minZ); z <= (int)Math.floor(box.maxZ); z++) {
                    Block b = world.getBlock(x, y, z);
                    if (b != null && b.getType() != Block.Type.AIR) {
                        AABB blockBox = new AABB(x+0.5f, y+0.5f, z+0.5f, 1.0f, 1.0f);
                        if (box.intersects(blockBox)) return true;
                    }
                }
        return false;
    }

    public float getSpeed() { return speed; }
    public float getX() { return camera.getX(); } public float getY() { return camera.getY(); } public float getZ() { return camera.getZ(); }
    public float getYaw() { return camera.getYaw(); } public float getPitch() { return camera.getPitch(); }
}
