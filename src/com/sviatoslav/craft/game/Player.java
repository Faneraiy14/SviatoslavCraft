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
    // speed зменшено (Sviatoslav попросив - "не така різка і не така
    // швидка") з 8.0 до 5.5.
    private float width = 0.6f * Chunk.BLOCK_SIZE, speed = 5.5f * Chunk.BLOCK_SIZE, jumpSpeed = 9.0f * Chunk.BLOCK_SIZE;
    private float verticalVelocity = 0;
    private boolean onGround = false;
    // РЕАЛЬНА фіча (Sviatoslav попросив - "не така різка ходьба... в
    // майні мене це бісило"): раніше рух був миттєвим - кожен кадр позиція
    // стрибала прямо на speed*dt у напрямку затиснутих клавіш, і зупинка
    // теж миттєва в момент відпускання. Тепер горизонтальна швидкість
    // (velX/velZ) - ОКРЕМИЙ стан, що плавно "доганяє" бажаний напрямок
    // (експоненційне згладжування, ACCEL_RATE - наскільки швидко) замість
    // стрибка прямо на неї - і так само плавно гасне до нуля, коли клавіші
    // відпущені, а не зупиняється миттєво. Швидкий тап-і-відпустити тепер
    // дає короткий плавний "розгін+гальмування", а не один різкий крок.
    private float velX = 0, velZ = 0;
    private static final float ACCEL_RATE = 9f;
    // Спринт (Sviatoslav попросив - Ctrl+W біжить, як у Minecraft):
    // множник швидкості, лише поки затиснуто вперед (Ctrl+A/S/D без W не
    // прискорює - той самий принцип, що й у ванільній грі, інакше
    // "спринт назад" виглядав би дивно).
    private static final float SPRINT_MULTIPLIER = 1.6f;
    // Присід (Sviatoslav попросив - лівий Shift, "майже повзе", і не дає
    // впасти з краю блока, як у Minecraft).
    private static final float CROUCH_MULTIPLIER = 0.3f;
    // Опускання камери на присіді (Sviatoslav - "інакше як гравець
    // зрозуміє, що він присів") - ЛИШЕ візуально (Camera.viewYOffset, не
    // сама фізична Y), плавно згладжується тим самим принципом, що й
    // velX/velZ вище, і повертається назад так само плавно, коли Shift
    // відпущено.
    private float crouchOffset = 0;
    private static final float CROUCH_EYE_DROP = 0.4f * Chunk.BLOCK_SIZE;
    private static final float CROUCH_TRANSITION_RATE = 10f;

    public Player(Camera camera, World world) {
        this.camera = camera; this.world = world;
        this.camera.setY(20.0f);
    }

    // Центр AABB (не рівень очей) для заданого Y камери.
    private float boxCenterY(float eyeY) { return eyeY + EYE_OFFSET; }

    // Один метод замість 4 окремих moveForward/Backward/Left/Right,
    // викликаних кожен окремо для затиснутих клавіш - той підхід мав
    // ПОБІЧНИЙ REAL BUG: якщо затиснуті 2 клавіші одразу (наприклад W+D),
    // обидва виклики застосовували СВІЙ ПОВНИЙ крок незалежно, тому рух по
    // діагоналі був у sqrt(2) раз швидший за рух по одній осі (класична,
    // майже завжди непомітна помилка нормалізації). Тут напрямок від УСІХ
    // затиснутих клавіш складається в один вектор і нормалізується ОДИН
    // раз - діагональ тепер із тією самою швидкістю, що й пряма лінія.
    public void updateMovement(float dt, boolean forward, boolean backward, boolean left, boolean right, boolean sprint, boolean crouch) {
        float yawRad = (float) Math.toRadians(camera.getYaw());
        float fx = (float) Math.sin(yawRad), fz = -(float) Math.cos(yawRad);
        float rx = (float) Math.cos(yawRad), rz = (float) Math.sin(yawRad);

        float dirX = 0, dirZ = 0;
        if (forward) { dirX += fx; dirZ += fz; }
        if (backward) { dirX -= fx; dirZ -= fz; }
        if (right) { dirX += rx; dirZ += rz; }
        if (left) { dirX -= rx; dirZ -= rz; }

        boolean hasInput = dirX != 0 || dirZ != 0;
        float targetVelX = 0, targetVelZ = 0;
        if (hasInput) {
            float len = (float) Math.sqrt(dirX*dirX + dirZ*dirZ);
            // Присід переважає спринт (як у Minecraft - на Ctrl+Shift+W
            // біжати не можна, лише повзти).
            float s = crouch ? speed * CROUCH_MULTIPLIER : (sprint && forward) ? speed * SPRINT_MULTIPLIER : speed;
            targetVelX = dirX / len * s;
            targetVelZ = dirZ / len * s;
        }

        // Експоненційне згладжування до цільової швидкості (0, якщо
        // клавіші відпущені) - той самий код "доганяє" і розгін, і
        // гальмування, лише ціль різна.
        float t = Math.min(1f, ACCEL_RATE * dt);
        velX += (targetVelX - velX) * t;
        velZ += (targetVelZ - velZ) * t;

        // Дуже маленькі залишки швидкості (майже нуль, але не точно)
        // інакше ніколи повністю не зупинили б гравця - обнуляємо поріг.
        if (!hasInput && Math.abs(velX) < 0.01f && Math.abs(velZ) < 0.01f) { velX = 0; velZ = 0; }

        float targetCrouchOffset = crouch ? -CROUCH_EYE_DROP : 0f;
        float ct = Math.min(1f, CROUCH_TRANSITION_RATE * dt);
        crouchOffset += (targetCrouchOffset - crouchOffset) * ct;
        camera.setViewYOffset(crouchOffset);

        moveTo(camera.getX() + velX * dt, camera.getZ() + velZ * dt, crouch);
    }

    // preventFallOff - лише поки на землі (onGround): у повітрі (стрибок/
    // падіння) заборона рухатись "у порожнечу" не має сенсу, гравець і так
    // уже не на блоці.
    private void moveTo(float nx, float nz, boolean preventFallOff) {
        AABB testX = new AABB(nx, boxCenterY(camera.getY()), camera.getZ(), width, HEIGHT);
        boolean edgeX = preventFallOff && onGround && !isGroundedAt(nx, camera.getZ());
        if (!collidesWithWorld(testX) && !edgeX) camera.setX(nx);
        AABB testZ = new AABB(camera.getX(), boxCenterY(camera.getY()), nz, width, HEIGHT);
        boolean edgeZ = preventFallOff && onGround && !isGroundedAt(camera.getX(), nz);
        if (!collidesWithWorld(testZ) && !edgeZ) camera.setZ(nz);
    }

    // Тонкий "щуп" одразу під ногами гравця в позиції (x,z) - чи є там
    // суцільний блок. Використовується ЛИШЕ для захисту від падіння на
    // присіді (Sviatoslav попросив - "як в майні не може впасти з краю
    // блока на шифті"): якщо під новою позицією ніг нічого нема, той крок
    // просто не застосовується (той самий принцип, що й звичайна колізія
    // testX/testZ вище, лише перевіряє ВІДСУТНІСТЬ опори, а не наявність
    // перешкоди).
    private boolean isGroundedAt(float x, float z) {
        float feetY = boxCenterY(camera.getY()) - HEIGHT / 2f;
        float probeHeight = 0.1f * Chunk.BLOCK_SIZE;
        AABB probe = new AABB(x, feetY - probeHeight / 2f, z, width, probeHeight);
        return collidesWithWorld(probe);
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

    public float getX() { return camera.getX(); } public float getY() { return camera.getY(); } public float getZ() { return camera.getZ(); }
    public float getYaw() { return camera.getYaw(); } public float getPitch() { return camera.getPitch(); }

    // Для рендеру "тіла" гравця в 3-й особі (F5) - справжньої моделі в
    // грі нема, лише коробка-заглушка розміром із хітбокс.
    public float getBodyCenterY() { return boxCenterY(camera.getY()); }
    public float getWidth() { return width; }
    public float getBodyHeight() { return HEIGHT; }
}
