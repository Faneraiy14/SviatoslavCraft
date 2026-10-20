package voxelin.core;

/**
 * Позиція у світі без втрати точності на будь-якій відстані («плаваюче начало»): чанк (long) + зсув усередині чанка в блоках (double).
 * Фізика, камера й малювання працюють з малими числами відносно ЧАНКА, тож точність однакова і біля нуля, і за 10^15 блоків.
 * x,z у [0,16) після normalize(); y - висота в блоках (звичайне число, світ 256 заввишки).
 */
public final class Pos {
    public long cx, cz;
    public double x, y, z;

    public Pos() {}
    public Pos(long cx, long cz, double x, double y, double z) { this.cx = cx; this.cz = cz; this.x = x; this.y = y; this.z = z; normalize(); }

    /** Центр блока (bx, by, bz) - місце, де гравцю стати. */
    public static Pos ofBlock(long bx, double y, long bz) { return new Pos(Math.floorDiv(bx, 16L), Math.floorDiv(bz, 16L), Math.floorMod(bx, 16L) + 0.5, y, Math.floorMod(bz, 16L) + 0.5); }

    public Pos copy() { Pos p = new Pos(); p.set(this); return p; }
    public Pos set(Pos o) { cx = o.cx; cz = o.cz; x = o.x; y = o.y; z = o.z; return this; }

    /** Повернути x,z у [0,16), переносячи цілі чанки в cx,cz. */
    public Pos normalize() {
        if (x < 0 || x >= 16) { long d = (long) Math.floor(x / 16.0); cx += d; x -= d * 16.0; }
        if (z < 0 || z >= 16) { long d = (long) Math.floor(z / 16.0); cz += d; z -= d * 16.0; }
        return this;
    }

    /** Зсунути на (dx,dy,dz) блоків (малі зсуви) і нормалізувати. */
    public Pos add(double dx, double dy, double dz) { x += dx; y += dy; z += dz; return normalize(); }

    /** Координата блока X/Z, у якому лежить позиція. */
    public long blockX() { return cx * 16 + (long) Math.floor(x); }
    public long blockZ() { return cz * 16 + (long) Math.floor(z); }
    public int blockY() { return (int) Math.floor(y); }

    /** Відстань по X/Z у блоках від початку чанка (ocx, ocz) - мале число, якщо чанки поруч. */
    public double relX(long ocx) { return (double) (cx - ocx) * 16.0 + x; }
    public double relZ(long ocz) { return (double) (cz - ocz) * 16.0 + z; }

    /** Для відладки: «123456789012.5». */
    public String fmtX() { return fmt(blockX(), x); }
    public String fmtZ() { return fmt(blockZ(), z); }
    private static String fmt(long block, double local) { return block + "." + (int) ((local - Math.floor(local)) * 10); }

    @Override public String toString() { return "Pos[" + fmtX() + ", " + String.format(java.util.Locale.ROOT, "%.2f", y) + ", " + fmtZ() + "]"; }
}
