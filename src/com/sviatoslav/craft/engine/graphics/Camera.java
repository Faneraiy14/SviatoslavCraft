package com.sviatoslav.craft.engine.graphics;

import static org.lwjgl.opengl.GL11.*;

public class Camera {
    private float x = 0, y = 1.7f, z = 0;
    private float yaw = 0, pitch = 0;
    private float fov = 45.0f;
    private float aspectRatio = 16.0f / 9.0f;
    private float nearPlane = 0.1f, farPlane = 100.0f;
    // Присід (Sviatoslav попросив - камера має видимо опускатись, інакше
    // незрозуміло, що гравець присів). ЛИШЕ для рендеру - фізична позиція
    // (y, через get/setY) лишається незмінною, щоб колізія/гравітація не
    // "плавали" разом із чисто візуальним ефектом.
    private float viewYOffset = 0;

    // F5 - перемикання виду (Sviatoslav попросив "1, 2, 3 лиця", як у
    // Minecraft). ЛИШЕ впливає на те, ЗВІДКИ й КУДИ дивиться РЕНДЕР
    // (applyView нижче) - x/y/z/yaw/pitch (фізичний стан, від якого
    // рахуються рух/raycast/колізія) лишаються незмінними, інакше в
    // третій особі гравець рухався б і цілився не туди, куди дивиться.
    public enum ViewMode { FIRST_PERSON, THIRD_BACK, THIRD_FRONT }
    private ViewMode viewMode = ViewMode.FIRST_PERSON;
    // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "камера дивиться на
    // голову", коробка-заглушка займала весь екран): 4.0 було замало
    // відносно зросту гравця (HEIGHT=1.8*BLOCK_SIZE=2.7) й вертикального
    // fov=45° - видима половина висоти на дистанції D дорівнює
    // D*tan(fov/2)=D*tan(22.5°)≈D*0.414; на D=4.0 це лише ≈1.66, а
    // половина зросту гравця - 1.35, тобто гравець МАЙЖЕ впритул
    // заповнював кадр, без жодного запасу навколо. 7.0 дає ≈2.9 - удвічі
    // більше за половину зросту, з нормальним запасом кадру навколо.
    private static final float THIRD_PERSON_DISTANCE = 7.0f;
    // Фактична дистанція, яку РЕАЛЬНО використовує applyView() - за
    // замовчуванням дорівнює константі вище, але SviatoslavCraft.render()
    // щокадру підрізає її через setThirdPersonDistance() до найближчої
    // суцільної перешкоди (Sviatoslav попросив - "камера не могла
    // дивитись крізь блоки"): Camera сама не знає про World/чанки (інший
    // пакет), тому саму перевірку робить виклик ЗЗОВНІ (де є доступ до
    // World), а Camera лише застосовує вже готовий, безпечний результат.
    private float thirdPersonDistance = THIRD_PERSON_DISTANCE;

    public float getThirdPersonMaxDistance() { return THIRD_PERSON_DISTANCE; }
    public void setThirdPersonDistance(float d) { thirdPersonDistance = d; }

    public void cycleViewMode() {
        viewMode = switch (viewMode) {
            case FIRST_PERSON -> ViewMode.THIRD_BACK;
            case THIRD_BACK -> ViewMode.THIRD_FRONT;
            case THIRD_FRONT -> ViewMode.FIRST_PERSON;
        };
    }
    public ViewMode getViewMode() { return viewMode; }

    public void updateProjection() {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        float yMax = (float) (nearPlane * Math.tan(Math.toRadians(fov / 2)));
        float xMax = yMax * aspectRatio;
        glFrustum(-xMax, xMax, -yMax, yMax, nearPlane, farPlane);
        glMatrixMode(GL_MODELVIEW);
    }

    public void applyView() {
        glLoadIdentity();
        float renderYaw = yaw, renderPitch = pitch;
        float renderX = x, renderY = y + viewYOffset, renderZ = z;
        if (viewMode != ViewMode.FIRST_PERSON) {
            // РЕАЛЬНИЙ БАГ (Sviatoslav знайшов живцем - "камера
            // закріплена не до голови"): раніше зсув рахувався ЛИШЕ по
            // yaw (горизонтально), pitch ігнорувався - тому щойно гравець
            // дивився вгору/вниз, камера лишалась на тій самій висоті й
            // горизонтальному зсуві, а "голова" (коробка) з'їжджала
            // кудись відносно кадру, замість того, щоб камера
            // оберталась РАЗОМ із поглядом, як селфі-палка, жорстко
            // прикріплена до голови. Тепер - повний 3D напрямок "вперед"
            // (yaw І pitch), той самий принцип, що й BlockRayCast.cast.
            float yawRad = (float) Math.toRadians(yaw);
            float pitchRad = (float) Math.toRadians(pitch);
            float fx = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
            float fy = (float) -Math.sin(pitchRad);
            float fz = (float) (-Math.cos(yawRad) * Math.cos(pitchRad));
            // THIRD_BACK - камера ПОЗАДУ гравця (мінус вперед), дивиться
            // тим самим напрямком, що й гравець - видно спину. THIRD_FRONT
            // - камера ПОПЕРЕДУ (плюс вперед), розвернута НАЗАД до
            // гравця (yaw+180, pitch зі зворотним знаком - інакше при
            // нахилі погляду вниз камера "спереду" не дивилась би точно
            // назад на гравця, а кудись убік; перевірено векторно - для
            // напрямку F=(fx,fy,fz), протилежний -F відповідає САМЕ
            // yaw+180/-pitch).
            float dist = viewMode == ViewMode.THIRD_BACK ? -thirdPersonDistance : thirdPersonDistance;
            renderX += fx * dist;
            renderY += fy * dist;
            renderZ += fz * dist;
            if (viewMode == ViewMode.THIRD_FRONT) { renderYaw += 180f; renderPitch = -pitch; }
        }
        glRotatef(renderPitch, 1.0f, 0.0f, 0.0f);
        glRotatef(renderYaw, 0.0f, 1.0f, 0.0f);
        glTranslatef(-renderX, -renderY, -renderZ);
    }

    public void setViewYOffset(float offset) { viewYOffset = offset; }
    // Потрібен для raycast'а (BlockRayCast) - інакше приціл цілиться від
    // ФІЗИЧНОГО ока (не опущеного на присіді), а бачиш ти з ОПУЩЕНОЇ
    // камери - розбіжність (Sviatoslav знайшов живцем - "приціл дивиться
    // туда же коли я стою").
    public float getViewYOffset() { return viewYOffset; }

    public void moveForward(float d) { float rad = (float) Math.toRadians(yaw); x += Math.sin(rad)*d; z -= Math.cos(rad)*d; }
    public void moveBackward(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.sin(rad)*d; z += Math.cos(rad)*d; }
    public void moveLeft(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.cos(rad)*d; z -= Math.sin(rad)*d; }
    public void moveRight(float d) { float rad = (float) Math.toRadians(yaw); x += Math.cos(rad)*d; z += Math.sin(rad)*d; }
    public void rotate(float dy, float dp) { yaw += dy; pitch += dp; pitch = Math.max(-89.0f, Math.min(89.0f, pitch)); }

    public float getX() { return x; } public float getY() { return y; } public float getZ() { return z; }
    public void setX(float x) { this.x = x; } public void setY(float y) { this.y = y; } public void setZ(float z) { this.z = z; }
    public float getYaw() { return yaw; } public float getPitch() { return pitch; }
}
