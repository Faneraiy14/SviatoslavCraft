package voxelin.client.render;

/** Матриця 4x4 у порядку стовпців (як чекає OpenGL). Лише те, що потрібно рушію. */
public final class Mat4 {
    public final float[] m = new float[16];

    public Mat4 identity() { java.util.Arrays.fill(m, 0); m[0] = m[5] = m[10] = m[15] = 1; return this; }

    public Mat4 perspective(float fovYDeg, float aspect, float near, float far) {
        java.util.Arrays.fill(m, 0);
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovYDeg) / 2));
        m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1; m[14] = 2 * far * near / (near - far);
        return this;
    }

    public Mat4 ortho(float l, float r, float b, float t, float n, float f) {
        identity();
        m[0] = 2 / (r - l); m[5] = 2 / (t - b); m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n);
        return this;
    }

    /** this = this * o */
    public Mat4 mul(Mat4 o) {
        float[] a = m, b = o.m, r = new float[16];
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++)
            r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3];
        System.arraycopy(r, 0, m, 0, 16);
        return this;
    }

    public Mat4 rotateX(float deg) { Mat4 r = new Mat4().identity(); float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg)); r.m[5] = c; r.m[6] = s; r.m[9] = -s; r.m[10] = c; return mul(r); }
    public Mat4 rotateY(float deg) { Mat4 r = new Mat4().identity(); float c = (float) Math.cos(Math.toRadians(deg)), s = (float) Math.sin(Math.toRadians(deg)); r.m[0] = c; r.m[2] = -s; r.m[8] = s; r.m[10] = c; return mul(r); }
    public Mat4 translate(float x, float y, float z) { Mat4 r = new Mat4().identity(); r.m[12] = x; r.m[13] = y; r.m[14] = z; return mul(r); }
    public Mat4 scale(float s) { Mat4 r = new Mat4().identity(); r.m[0] = r.m[5] = r.m[10] = s; return mul(r); }
    public Mat4 set(Mat4 o) { System.arraycopy(o.m, 0, m, 0, 16); return this; }

    /** 6 площин відсікання (a,b,c,d) з матриці проєкція*вид: точка всередині, якщо a*x+b*y+c*z+d >= 0 для всіх. */
    public float[][] frustumPlanes() {
        float[][] p = new float[6][4];
        for (int i = 0; i < 4; i++) {
            float r3 = m[i * 4 + 3];
            p[0][i] = r3 + m[i * 4];     p[1][i] = r3 - m[i * 4];
            p[2][i] = r3 + m[i * 4 + 1]; p[3][i] = r3 - m[i * 4 + 1];
            p[4][i] = r3 + m[i * 4 + 2]; p[5][i] = r3 - m[i * 4 + 2];
        }
        return p;
    }

    /** Чи перетинає коробка піраміду видимості (консервативно). */
    public static boolean boxVisible(float[][] planes, float x0, float y0, float z0, float x1, float y1, float z1) {
        for (float[] p : planes) {
            float x = p[0] >= 0 ? x1 : x0, y = p[1] >= 0 ? y1 : y0, z = p[2] >= 0 ? z1 : z0;
            if (p[0] * x + p[1] * y + p[2] * z + p[3] < 0) return false;
        }
        return true;
    }
}
