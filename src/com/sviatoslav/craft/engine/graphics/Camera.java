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
    private static final float THIRD_PERSON_DISTANCE = 4.0f;

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
        float renderYaw = yaw;
        float renderX = x, renderY = y + viewYOffset, renderZ = z;
        if (viewMode != ViewMode.FIRST_PERSON) {
            // Горизонтальний напрямок "вперед" (той самий принцип, що й
            // Player.updateMovement - без урахування pitch, щоб не
            // ускладнювати) - камера зсувається УЗДОВЖ нього, а не за
            // яw/pitch окремо.
            float rad = (float) Math.toRadians(yaw);
            float fx = (float) Math.sin(rad), fz = -(float) Math.cos(rad);
            // THIRD_BACK - камера ПОЗАДУ гравця (мінус вперед), дивиться
            // тим самим yaw, що й гравець - видно спину. THIRD_FRONT -
            // камера ПОПЕРЕДУ (плюс вперед), yaw+180 - розвернута назад до
            // гравця, видно обличчя (той самий принцип, що й F5 у
            // Minecraft).
            float dist = viewMode == ViewMode.THIRD_BACK ? -THIRD_PERSON_DISTANCE : THIRD_PERSON_DISTANCE;
            renderX += fx * dist;
            renderZ += fz * dist;
            if (viewMode == ViewMode.THIRD_FRONT) renderYaw += 180f;
        }
        glRotatef(pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(renderYaw, 0.0f, 1.0f, 0.0f);
        glTranslatef(-renderX, -renderY, -renderZ);
    }

    public void setViewYOffset(float offset) { viewYOffset = offset; }

    public void moveForward(float d) { float rad = (float) Math.toRadians(yaw); x += Math.sin(rad)*d; z -= Math.cos(rad)*d; }
    public void moveBackward(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.sin(rad)*d; z += Math.cos(rad)*d; }
    public void moveLeft(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.cos(rad)*d; z -= Math.sin(rad)*d; }
    public void moveRight(float d) { float rad = (float) Math.toRadians(yaw); x += Math.cos(rad)*d; z += Math.sin(rad)*d; }
    public void rotate(float dy, float dp) { yaw += dy; pitch += dp; pitch = Math.max(-89.0f, Math.min(89.0f, pitch)); }

    public float getX() { return x; } public float getY() { return y; } public float getZ() { return z; }
    public void setX(float x) { this.x = x; } public void setY(float y) { this.y = y; } public void setZ(float z) { this.z = z; }
    public float getYaw() { return yaw; } public float getPitch() { return pitch; }
}
