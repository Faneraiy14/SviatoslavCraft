package com.sviatoslav.craft.engine.graphics;

import static org.lwjgl.opengl.GL11.*;

public class Camera {
    private float x = 0, y = 1.7f, z = 0;
    private float yaw = 0, pitch = 0;
    private float fov = 45.0f;
    private float aspectRatio = 16.0f / 9.0f;
    private float nearPlane = 0.1f, farPlane = 100.0f;

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
        glRotatef(pitch, 1.0f, 0.0f, 0.0f);
        glRotatef(yaw, 0.0f, 1.0f, 0.0f);
        glTranslatef(-x, -y, -z);
    }

    public void moveForward(float d) { float rad = (float) Math.toRadians(yaw); x += Math.sin(rad)*d; z -= Math.cos(rad)*d; }
    public void moveBackward(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.sin(rad)*d; z += Math.cos(rad)*d; }
    public void moveLeft(float d) { float rad = (float) Math.toRadians(yaw); x -= Math.cos(rad)*d; z -= Math.sin(rad)*d; }
    public void moveRight(float d) { float rad = (float) Math.toRadians(yaw); x += Math.cos(rad)*d; z += Math.sin(rad)*d; }
    public void rotate(float dy, float dp) { yaw += dy; pitch += dp; pitch = Math.max(-89.0f, Math.min(89.0f, pitch)); }

    public float getX() { return x; } public float getY() { return y; } public float getZ() { return z; }
    public void setX(float x) { this.x = x; } public void setY(float y) { this.y = y; } public void setZ(float z) { this.z = z; }
    public float getYaw() { return yaw; } public float getPitch() { return pitch; }
}
