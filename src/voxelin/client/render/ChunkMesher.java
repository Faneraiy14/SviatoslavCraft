package voxelin.client.render;

/**
 * Будує геометрію секції 16^3 «жадібним» склеюванням: сусідні однакові грані зливаються в один прямокутник
 * (рівна підлога 16x16 = 1 чотирикутник замість 256). Чиста Java без OpenGL - працює у фонових потоках і в тестах.
 *
 * Вхід - «обгорнутий» масив 18^3 (секція + шар сусідів): індекс (x+1) + (z+1)*18 + (y+1)*324, x,y,z у -1..16.
 * Незавантажений сусід передається як непрозорий блок - на межі не з'являються дірки; після завантаження сусіда секція перебудовується.
 * Вершина: x,y,z (у блоках відносно секції), колір RGBA (int у float-бітах), номер грані 0..5, шар текстури - 6 float, 24 байти.
 */
public final class ChunkMesher {
    public static final int P = 18, FLOATS_PER_VERTEX = 6;
    private static final float[] SHADE = {0.70f, 0.70f, 1.0f, 0.5f, 0.85f, 0.85f}; // +X -X +Y -Y +Z -Z

    /** Властивості блоків за id (знімок реєстру; незмінні). */
    public static final class Props {
        final boolean[] opaque, translucent, air;
        final int[] color, layerTop, layerSide, layerBottom;
        /** color - множник кольору (білий для блоків із текстурою); layer* - шар масиву текстур для верху/боків/низу (0 - біла плитка). */
        public Props(boolean[] opaque, boolean[] translucent, boolean[] air, int[] color, int[] layerTop, int[] layerSide, int[] layerBottom) {
            this.opaque = opaque; this.translucent = translucent; this.air = air; this.color = color; this.layerTop = layerTop; this.layerSide = layerSide; this.layerBottom = layerBottom;
        }
    }

    /** Результат: вершини для непрозорого й напівпрозорого проходів. */
    public static final class Mesh {
        public float[] solid, translucent;
        public int solidQuads, translucentQuads;
        public boolean isEmpty() { return solidQuads == 0 && translucentQuads == 0; }
    }

    public static int index(int x, int y, int z) { return (x + 1) + (z + 1) * P + (y + 1) * P * P; }

    private final int[] mask = new int[16 * 16];
    private FloatList solid = new FloatList(), trans = new FloatList();

    public Mesh build(short[] padded, Props props) {
        solid.clear(); trans.clear();
        int[] pos = new int[3], step = new int[3];
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1, u = (axis + 1) % 3, v = (axis + 2) % 3;
            boolean positive = (face & 1) == 0;
            for (int s = 0; s < 16; s++) {
                // маска: id блока, чия грань у цьому напрямку видима, або 0
                for (int b = 0; b < 16; b++) for (int a = 0; a < 16; a++) {
                    pos[axis] = s; pos[u] = a; pos[v] = b;
                    int id = padded[index(pos[0], pos[1], pos[2])];
                    int m = 0;
                    if (id > 0 && !props.air[id]) {
                        pos[axis] += positive ? 1 : -1;
                        int nb = padded[index(pos[0], pos[1], pos[2])];
                        boolean visible = nb <= 0 || props.air[nb] || (!props.opaque[nb] && nb != id);
                        if (visible) m = id;
                    }
                    mask[a + b * 16] = m;
                }
                // жадібне злиття прямокутників
                for (int b = 0; b < 16; b++) for (int a = 0; a < 16; ) {
                    int id = mask[a + b * 16];
                    if (id == 0) { a++; continue; }
                    int w = 1;
                    while (a + w < 16 && mask[a + w + b * 16] == id) w++;
                    int h = 1;
                    outer:
                    while (b + h < 16) {
                        for (int k = 0; k < w; k++) if (mask[a + k + (b + h) * 16] != id) break outer;
                        h++;
                    }
                    for (int hh = 0; hh < h; hh++) for (int k = 0; k < w; k++) mask[a + k + (b + hh) * 16] = 0;
                    quad(props.translucent[id] ? trans : solid, face, axis, u, v, positive ? s + 1 : s, a, b, a + w, b + h, color(props, id, face), layer(props, id, face));
                    a += w;
                }
            }
        }
        Mesh r = new Mesh();
        r.solid = solid.toArray(); r.solidQuads = r.solid.length / (4 * FLOATS_PER_VERTEX);
        r.translucent = trans.toArray(); r.translucentQuads = r.translucent.length / (4 * FLOATS_PER_VERTEX);
        return r;
    }

    private static int color(Props p, int id, int face) {
        int c = p.color[id];
        float sh = SHADE[face];
        int r = (int) (((c >> 16) & 255) * sh), g = (int) (((c >> 8) & 255) * sh), b = (int) ((c & 255) * sh);
        int alpha = p.translucent[id] && layer(p, id, face) == 0 ? 110 : 255; // з текстурою прозорість бере сама текстура
        return r | (g << 8) | (b << 16) | (alpha << 24); // у пам'яті R,G,B,A (little-endian) - для нормалізованого GL_UNSIGNED_BYTE
    }

    /** Шар текстури для грані: +Y - верх, -Y - низ, решта - бік. */
    private static int layer(Props p, int id, int face) { return face == 2 ? p.layerTop[id] : (face == 3 ? p.layerBottom[id] : p.layerSide[id]); }

    /** Чотирикутник проти годинникової стрілки, якщо дивитись ззовні (для відсікання задніх граней). */
    private static void quad(FloatList out, int face, int axis, int u, int v, int plane, int u0, int v0, int u1, int v1, int rgba, int layer) {
        int[][] corners = (face & 1) == 0
                ? new int[][]{{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}}
                : new int[][]{{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        float colorBits = Float.intBitsToFloat(rgba);
        float[] p = new float[3];
        for (int[] c : corners) {
            p[axis] = plane; p[u] = c[0]; p[v] = c[1];
            out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(colorBits); out.add(face); out.add(layer);
        }
    }

    static final class FloatList {
        float[] a = new float[4096]; int n;
        void add(float f) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = f; }
        void clear() { n = 0; }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
