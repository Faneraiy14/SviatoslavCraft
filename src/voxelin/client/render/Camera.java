package voxelin.client.render;

/** Камера: позиція й кути (світові одиниці), режим 1/2/3-ї особи (F5), матриці. Фізичний гравець не залежить від режиму камери. */
public final class Camera {
    public enum Mode { FIRST, THIRD_BACK, THIRD_FRONT }
    public static final float THIRD_MAX = 7f;

    public float x, y, z, yaw, pitch, fov = 70f;
    public Mode mode = Mode.FIRST;
    public float thirdDistance = THIRD_MAX;
    public final Mat4 proj = new Mat4(), view = new Mat4(), projView = new Mat4();
    /** Позиція, з якої реально дивимось (з урахуванням 3-ї особи). */
    public float rx, ry, rz;

    public void cycle() { mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length]; }

    public void update(int fbW, int fbH, float far) {
        proj.perspective(fov, fbW / (float) fbH, 0.1f, far);
        float ryaw = yaw, rpitch = pitch;
        rx = x; ry = y; rz = z;
        if (mode != Mode.FIRST) {
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            float fx = (float) (Math.sin(yr) * Math.cos(pr)), fy = (float) -Math.sin(pr), fz = (float) (-Math.cos(yr) * Math.cos(pr));
            float d = mode == Mode.THIRD_BACK ? -thirdDistance : thirdDistance;
            rx += fx * d; ry += fy * d; rz += fz * d;
            if (mode == Mode.THIRD_FRONT) { ryaw += 180f; rpitch = -pitch; }
        }
        view.identity().rotateX(rpitch).rotateY(ryaw).translate(-rx, -ry, -rz);
        projView.set(proj).mul(view);
    }
}
