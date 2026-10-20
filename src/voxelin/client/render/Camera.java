package voxelin.client.render;

import voxelin.core.Pos;
import voxelin.core.Units;

/**
 * Камера: позиція ока (Pos) і кути, режим 1/2/3-ї особи (F5), матриці. Малювання - «відносно камери»: матриця виду не містить великих
 * чисел (камера в межах свого чанка), а все інше зміщується відносно чанка камери - тому картинка чітка на будь-якій відстані.
 * Одиниця малювання = блок * Units.BLOCK_SIZE.
 */
public final class Camera {
    public enum Mode { FIRST, THIRD_BACK, THIRD_FRONT }
    public static final double THIRD_MAX = 4.7; // блоків

    public final Pos pos = new Pos();
    public float yaw, pitch, fov = 70f;
    public Mode mode = Mode.FIRST;
    public double thirdDistance = THIRD_MAX;
    public final Mat4 proj = new Mat4(), view = new Mat4(), projView = new Mat4();
    /** Звідки реально дивимось (з урахуванням 3-ї особи); чанк цієї позиції - начало координат малювання. */
    public final Pos eye = new Pos();

    public void cycle() { mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length]; }

    public void update(int fbW, int fbH, float far) {
        proj.perspective(fov, fbW / (float) fbH, 0.1f, far);
        float ryaw = yaw, rpitch = pitch;
        eye.set(pos);
        if (mode != Mode.FIRST) {
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            double fx = Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = -Math.cos(yr) * Math.cos(pr);
            double d = mode == Mode.THIRD_BACK ? -thirdDistance : thirdDistance;
            eye.add(fx * d, fy * d, fz * d);
            if (mode == Mode.THIRD_FRONT) { ryaw += 180f; rpitch = -pitch; }
        }
        float s = Units.BLOCK_SIZE;
        view.identity().rotateX(rpitch).rotateY(ryaw).translate((float) (-eye.x * s), (float) (-eye.y * s), (float) (-eye.z * s));
        projView.set(proj).mul(view);
    }
}
