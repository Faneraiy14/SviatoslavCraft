package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.graphics.Camera;
import com.sviatoslav.craft.engine.physics.AABB;
import com.sviatoslav.craft.engine.world.*;

public class Player {
    private Camera camera;
    private World world;
    // РЕАЛЬНИЙ БАГ (знайдено Sviatoslav'ом живцем - "я висотою 1 блок, а
    // маю бути в 2"): AABB(camera.getX(), camera.getY(), ..., width,
    // height) - camera.getY() це РІВЕНЬ ОЧЕЙ, а AABB ЦЕНТРУЄТЬСЯ навколо
    // переданого Y (minY=y-h/2, maxY=y+h/2). Тобто хітбокс розтягувався
    // порівну ВГОРУ і ВНИЗ від очей - при height=1.7 від ніг до очей
    // виходило лише 1.7/2=0.85 (майже "1 блок"), а мало бути ~1.6 (очі
    // біля верху тіла, не посередині). EYE_HEIGHT - скільки від ніг до
    // очей; EYE_OFFSET - наскільки центр AABB нижче рівня очей, щоб ноги
    // й верх голови опинились у правильних місцях (порахований нижче).
    // HEIGHT трохи МЕНШЕ за 2.0 (Sviatoslav попросив) - інакше в
    // рівно-2-блокових проходах верх хітбоксу впирається в 3-й блок над
    // головою навіть коли там мало бути прохідно. EYE_HEIGHT лишається
    // 1.6 (реалістичний рівень очей), тому "запас" (2.0-1.8=0.2) береться
    // зверху голови, не знизу від ніг.
    // Усі константи нижче помножені на Chunk.BLOCK_SIZE - Sviatoslav
    // попросив "блоки більшими" (World.BLOCK_SIZE), і щоб гравець/фізика
    // не "розсинхронізувались" із новим масштабом (інакше зріст/швидкість/
    // стрибок лишились би підігнані під старий розмір блока 1.0, відносно
    // нового виглядало б чи то замало, чи то занадто повільно), той самий
    // множник застосований і тут - усі відносні пропорції (зріст ~1.8
    // блока, стрибок на 1 блок, запас 0.2 над головою) лишаються ТОЧНО
    // такими самими, лише в нових, більших абсолютних одиницях.
    private static final float HEIGHT = 1.8f * Chunk.BLOCK_SIZE;
    private static final float EYE_HEIGHT = 1.6f * Chunk.BLOCK_SIZE;
    private static final float EYE_OFFSET = HEIGHT / 2f - EYE_HEIGHT; // від'ємне - центр AABB нижче очей
    private float width = 0.6f * Chunk.BLOCK_SIZE, speed = 8.0f * Chunk.BLOCK_SIZE, jumpSpeed = 9.0f * Chunk.BLOCK_SIZE;
    private float verticalVelocity = 0;
    private boolean onGround = false;

    public Player(Camera camera, World world) {
        this.camera = camera; this.world = world;
        this.camera.setY(20.0f);
    }

    // Центр AABB (не рівень очей) для заданого Y камери.
    private float boxCenterY(float eyeY) { return eyeY + EYE_OFFSET; }

    public void moveForward(float d) { float nx = camera.getX() + (float)Math.sin(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() - (float)Math.cos(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveBackward(float d) { float nx = camera.getX() - (float)Math.sin(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() + (float)Math.cos(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveLeft(float d) { float nx = camera.getX() - (float)Math.cos(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() - (float)Math.sin(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }
    public void moveRight(float d) { float nx = camera.getX() + (float)Math.cos(Math.toRadians(camera.getYaw()))*d; float nz = camera.getZ() + (float)Math.sin(Math.toRadians(camera.getYaw()))*d; moveTo(nx, nz); }

    private void moveTo(float nx, float nz) {
        AABB testX = new AABB(nx, boxCenterY(camera.getY()), camera.getZ(), width, HEIGHT);
        if (!collidesWithWorld(testX)) camera.setX(nx);
        AABB testZ = new AABB(camera.getX(), boxCenterY(camera.getY()), nz, width, HEIGHT);
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
        verticalVelocity += -25.0f * Chunk.BLOCK_SIZE * dt;
        float ny = camera.getY() + verticalVelocity * dt;
        AABB testY = new AABB(camera.getX(), boxCenterY(ny), camera.getZ(), width, HEIGHT);
        if (collidesWithWorld(testY)) {
            boolean wasFalling = verticalVelocity < 0;
            verticalVelocity = 0;
            onGround = wasFalling;
        } else {
            camera.setY(ny);
            onGround = false;
        }
    }

    // РЕАЛЬНИЙ БАГ (знайдено Sviatoslav'ом - "самозамурування не має бути,
    // бо це суперечить колізії гравця"): постановка блока НІЯК не
    // перевірялась проти власного хітбоксу гравця - можна було поставити
    // блок, що перетинається із самим собою, хоча та сама колізія
    // (collidesWithWorld) не дає ЗАЙТИ у вже наявний блок. Непослідовно:
    // блок і гравець одночасно займали один простір. Використовує ТОЙ
    // САМИЙ AABB, що й рух/фізика.
    public boolean wouldOverlapPlacedBlock(int blockX, int blockY, int blockZ) {
        AABB playerBox = new AABB(camera.getX(), boxCenterY(camera.getY()), camera.getZ(), width, HEIGHT);
        AABB blockBox = gridBlockBox(blockX, blockY, blockZ);
        return playerBox.intersects(blockBox);
    }

    // grid-координата блока (ціла) -> AABB у СВІТОВИХ float-одиницях,
    // той самий масштаб (Chunk.BLOCK_SIZE), що й рендер у World -
    // інакше хітбокс блока залишався б розміром 1.0 у світі, де блоки
    // рендеряться розміром BLOCK_SIZE=1.5 - видима й реальна межа
    // розійшлися б.
    private static AABB gridBlockBox(int x, int y, int z) {
        return new AABB((x+0.5f)*Chunk.BLOCK_SIZE, (y+0.5f)*Chunk.BLOCK_SIZE, (z+0.5f)*Chunk.BLOCK_SIZE,
            Chunk.BLOCK_SIZE, Chunk.BLOCK_SIZE);
    }

    private boolean collidesWithWorld(AABB box) {
        // box - у світових float-одиницях (масштаб BLOCK_SIZE); переводимо
        // межі в ЦІЛОЧИСЕЛЬНІ grid-координати (діленням на BLOCK_SIZE),
        // бо саме в них зберігається й шукається World.getBlock().
        int minX = (int) Math.floor(box.minX / Chunk.BLOCK_SIZE), maxX = (int) Math.floor(box.maxX / Chunk.BLOCK_SIZE);
        int minY = (int) Math.floor(box.minY / Chunk.BLOCK_SIZE), maxY = (int) Math.floor(box.maxY / Chunk.BLOCK_SIZE);
        int minZ = (int) Math.floor(box.minZ / Chunk.BLOCK_SIZE), maxZ = (int) Math.floor(box.maxZ / Chunk.BLOCK_SIZE);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = world.getBlock(x, y, z);
                    if (b != null && b.getType() != Block.Type.AIR) {
                        if (box.intersects(gridBlockBox(x, y, z))) return true;
                    }
                }
        return false;
    }

    public float getSpeed() { return speed; }
    public float getX() { return camera.getX(); } public float getY() { return camera.getY(); } public float getZ() { return camera.getZ(); }
    public float getYaw() { return camera.getYaw(); } public float getPitch() { return camera.getPitch(); }
}
