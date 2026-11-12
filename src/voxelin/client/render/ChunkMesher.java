package voxelin.client.render;

/**
 * Будує геометрію секції 16^3 «жадібним» склеюванням: сусідні однакові грані зливаються в один прямокутник
 * (рівна підлога 16x16 = 1 чотирикутник замість 256). Чиста Java без OpenGL - працює у фонових потоках і в тестах.
 *
 * Вхід - «обгорнутий» масив 18^3 (секція + шар сусідів): індекс (x+1) + (z+1)*18 + (y+1)*324, x,y,z у -1..16.
 * Незавантажений сусід передається як непрозорий блок - на межі не з'являються дірки; після завантаження сусіда секція перебудовується.
 * Вершина: x,y,z (у блоках відносно секції), колір RGBA (int у float-бітах), номер грані 0..5, шар текстури - 6 float, 24 байти.
 * «Номер грані» несе ще світло й м'які кути: грань + 8*небо + 128*блок + 2048*AO (AO 0..3 - 3 світло, 0 - глибокий кут).
 * Грані 6 і 7 - лицьовий і зворотний бік площини рослини (UV за X і Y, без контуру блока).
 *
 * М'які кути (ambient occlusion): кожна вершина грані темніша, якщо біля неї суцільні блоки (два боки й кут - стандартна схема 0..3).
 * Склеювання не ламається: AO чотирьох кутів клітинки входить у ключ злиття, тож зливаються лише клітинки з однаковим візерунком -
 * відкрита рівнина лишається одним прямокутником, дробиться лише біля стін і в кутах. Квад із «косим» AO повертається на одну вершину,
 * щоб діагональ трикутників ішла вздовж градієнта (інакше видно смугу).
 */
public final class ChunkMesher {
    public static final int P = 18, FLOATS_PER_VERTEX = 6;
    private static final float[] SHADE = {0.70f, 0.70f, 1.0f, 0.5f, 0.85f, 0.85f, 0.92f, 0.92f}; // +X -X +Y -Y +Z -Z, рослина з двох боків

    /** Властивості блоків за id (знімок реєстру; незмінні). */
    public static final class Props {
        final boolean[] opaque, translucent, air;
        public boolean[] opaque() { return opaque; }
        public boolean[] translucent() { return translucent; }
        public boolean[] air() { return air; }
        /** Власне світло блока (0..15) - лампа сяє й сама; null - ні в кого. */
        public int[] emit;
        /** М'які кути (налаштування «М'ЯКЕ СВІТЛО»); false - як раніше, кожна грань рівна. */
        public volatile boolean ao = true;
        /** Блоки не-куби: коробки по 6 чисел {x0,y0,z0,x1,y1,z1} у частках блока (null - повний куб); null - таких нема. */
        public float[][] box;
        /** Блоки, чиї коробки залежать від клітинки (дошки): їх питаємо в CellBoxes. */
        public boolean[] perCell;
        /** Рослини-хрест (трава, квіти): дві діагональні площини з обох боків; box[id] для них теж не null (щоб не малювались кубом). */
        public boolean[] cross;
        final int[] color, layerTop, layerSide, layerBottom;
        /** color - множник кольору (білий для блоків із текстурою); layer* - шар масиву текстур для верху/боків/низу (0 - біла плитка). */
        public Props(boolean[] opaque, boolean[] translucent, boolean[] air, int[] color, int[] layerTop, int[] layerSide, int[] layerBottom) {
            this.opaque = opaque; this.translucent = translucent; this.air = air; this.color = color; this.layerTop = layerTop; this.layerSide = layerSide; this.layerBottom = layerBottom;
        }
    }

    /** Результат: вершини для непрозорого, рослинного (лише поблизу) і напівпрозорого проходів. */
    public static final class Mesh {
        public float[] solid, translucent, plants = new float[0];
        public int solidQuads, translucentQuads, plantQuads;
        public boolean isEmpty() { return solidQuads == 0 && translucentQuads == 0 && plantQuads == 0; }
    }

    public static int index(int x, int y, int z) { return (x + 1) + (z + 1) * P + (y + 1) * P * P; }

    /** Світло невідомого/«все небо»: небо 15, блоки 0. */
    public static final byte FULL_SKY = (byte) 0xF0;
    private final int[] mask = new int[16 * 16];
    private FloatList solid = new FloatList(), trans = new FloatList(), plantList = new FloatList();

    /** Коробки клітинки секції (локальні 0..15) у частках блока - для блоків perCell. */
    public interface CellBoxes { double[] at(int x, int y, int z); }

    /** Без світла (повне небо): для тестів і простих випадків. */
    public Mesh build(short[] padded, Props props) { return build(padded, null, props); }

    /** light - такий самий обгорнутий масив 18^3 (небо<<4 | блок) або null. Грані з різним світлом не зливаються. */
    public Mesh build(short[] padded, byte[] light, Props props) { return build(padded, light, props, null); }

    public Mesh build(short[] padded, byte[] light, Props props, CellBoxes cells) {
        solid.clear(); trans.clear(); plantList.clear();
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
                    if (id > 0 && !props.air[id] && (props.box == null || props.box[id] == null)) {
                        pos[axis] += positive ? 1 : -1;
                        int nb = padded[index(pos[0], pos[1], pos[2])];
                        boolean visible = nb <= 0 || props.air[nb] || (!props.opaque[nb] && nb != id);
                        if (visible) {
                            int lv = light == null ? 0xF0 : light[index(pos[0], pos[1], pos[2])] & 0xFF; // світло клітинки, у яку дивиться грань
                            if (props.emit != null && props.emit[id] > (lv & 15)) lv = (lv & 0xF0) | props.emit[id];
                            int ao = props.ao ? aoPattern(padded, props, pos, u, v) : 0xFF; // pos - клітинка перед гранню
                            m = id | (lv << 16) | (ao << 24);
                        }
                    }
                    mask[a + b * 16] = m;
                }
                // жадібне злиття прямокутників
                for (int b = 0; b < 16; b++) for (int a = 0; a < 16; ) {
                    int key = mask[a + b * 16];
                    if (key == 0) { a++; continue; }
                    int id = key & 0xFFFF, lv = (key >>> 16) & 0xFF, ao = key >>> 24;
                    int w = 1;
                    while (a + w < 16 && mask[a + w + b * 16] == key) w++;
                    int h = 1;
                    outer:
                    while (b + h < 16) {
                        for (int k = 0; k < w; k++) if (mask[a + k + (b + h) * 16] != key) break outer;
                        h++;
                    }
                    for (int hh = 0; hh < h; hh++) for (int k = 0; k < w; k++) mask[a + k + (b + hh) * 16] = 0;
                    quad(props.translucent[id] ? trans : solid, face, axis, u, v, positive ? s + 1 : s, a, b, a + w, b + h, color(props, id, face), layer(props, id, face), face + 8 * (lv >> 4) + 128 * (lv & 15), ao);
                    a += w;
                }
            }
        }
        if (props.box != null) boxes(padded, light, props, cells);
        Mesh r = new Mesh();
        r.solid = solid.toArray(); r.solidQuads = r.solid.length / (4 * FLOATS_PER_VERTEX);
        r.translucent = trans.toArray(); r.translucentQuads = r.translucent.length / (4 * FLOATS_PER_VERTEX);
        r.plants = plantList.toArray(); r.plantQuads = r.plants.length / (4 * FLOATS_PER_VERTEX);
        return r;
    }

    /** Блоки-коробки (смолоскип, стіл, дошки): по шість граней на коробку, без злиття; світло - своєї клітинки. */
    private void boxes(short[] padded, byte[] light, Props props, CellBoxes cells) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int idx = index(x, y, z), id = padded[idx];
            if (id <= 0 || props.box[id] == null) continue;
            float[] b = props.box[id];
            if (props.perCell != null && props.perCell[id]) {
                double[] d = cells == null ? null : cells.at(x, y, z);
                if (d == null) continue;
                b = new float[d.length];
                for (int k = 0; k < d.length; k++) b[k] = (float) d[k];
            }
            int lv = light == null ? 0xF0 : light[idx] & 0xFF;
            if (props.emit != null && props.emit[id] > (lv & 15)) lv = (lv & 0xF0) | props.emit[id];
            FloatList out = props.translucent[id] ? trans : solid;
            if (props.cross != null && props.cross[id]) { cross(plantList, x, y, z, props, id, lv); continue; } // окремо: малюються лише поблизу
            for (int k = 0; k + 5 < b.length; k += 6) {
                float[] lo = {x + b[k], y + b[k + 1], z + b[k + 2]}, hi = {x + b[k + 3], y + b[k + 4], z + b[k + 5]};
                for (int face = 0; face < 6; face++) {
                    int axis = face >> 1, u = (axis + 1) % 3, v = (axis + 2) % 3;
                    boolean positive = (face & 1) == 0;
                    quadF(out, face, axis, u, v, positive ? hi[axis] : lo[axis], lo[u], lo[v], hi[u], hi[v], color(props, id, face), layer(props, id, face), face + 8 * (lv >> 4) + 128 * (lv & 15) + 2048 * 3); // коробки - без AO
                }
            }
        }
    }

    /**
     * Рослина: площини (0,0)-(1,1) і (1,0)-(0,1) по X/Z, кожна з двох боків (грань 6 - лице, 7 - зворот із протилежним обходом).
     * Невеликий зсув за хешем клітинки - щоб поле трави не стояло ідеальною сіткою.
     */
    private static void cross(FloatList out, int x, int y, int z, Props props, int id, int lv) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        float ox = ((h & 7) - 3.5f) / 40f, oz = (((h >> 3) & 7) - 3.5f) / 40f;
        float x0 = x + 0.07f + ox, x1 = x + 0.93f + ox, z0 = z + 0.07f + oz, z1 = z + 0.93f + oz, y0 = y, y1 = y + 1f;
        float[][] planes = {{x0, z0, x1, z1}, {x0, z1, x1, z0}};
        for (float[] pl : planes) {
            for (int side = 0; side < 2; side++) {
                int face = 6 + side;
                float colorBits = Float.intBitsToFloat(color(props, id, face));
                float fl = face + 8 * (lv >> 4) + 128 * (lv & 15) + 2048 * 3, layer = props.layerSide[id];
                float[][] v = {{pl[0], y0, pl[1]}, {pl[2], y0, pl[3]}, {pl[2], y1, pl[3]}, {pl[0], y1, pl[1]}};
                for (int i = 0; i < 4; i++) {
                    float[] c = v[side == 0 ? i : (4 - i) & 3];
                    out.add(c[0]); out.add(c[1]); out.add(c[2]); out.add(colorBits); out.add(fl); out.add(layer);
                }
            }
        }
    }

    private static void quadF(FloatList out, int face, int axis, int u, int v, float plane, float u0, float v0, float u1, float v1, int rgba, int layer, int faceLight) {
        float[][] corners = (face & 1) == 0
                ? new float[][]{{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}}
                : new float[][]{{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        float colorBits = Float.intBitsToFloat(rgba);
        float[] p = new float[3];
        for (float[] c : corners) {
            p[axis] = plane; p[u] = c[0]; p[v] = c[1];
            out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(colorBits); out.add(faceLight); out.add(layer);
        }
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

    /**
     * AO чотирьох кутів грані, що дивиться в клітинку c (сусід блока): по 2 біти, кут (-u,-v) - біти 0-1, (+u,-v) - 2-3, (+u,+v) - 4-5,
     * (-u,+v) - 6-7. Значення 3 - світло, 0 - обидва боки суцільні. Усе в межах обгорнутого масиву (c по u, v - 0..15, плюс/мінус 1).
     */
    private static int aoPattern(short[] padded, Props props, int[] c, int u, int v) {
        int r = 0;
        int[] q = new int[3];
        for (int k = 0; k < 4; k++) {
            int du = k == 1 || k == 2 ? 1 : -1, dv = k >= 2 ? 1 : -1;
            q[0] = c[0]; q[1] = c[1]; q[2] = c[2]; q[u] += du; boolean s1 = solidCube(padded, props, q);
            q[u] -= du; q[v] += dv; boolean s2 = solidCube(padded, props, q);
            q[u] += du; boolean cr = solidCube(padded, props, q);
            int ao = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (cr ? 1 : 0));
            r |= ao << (2 * k);
        }
        return r;
    }

    private static boolean solidCube(short[] padded, Props props, int[] q) {
        int id = padded[index(q[0], q[1], q[2])];
        return id > 0 && props.opaque[id] && (props.box == null || props.box[id] == null);
    }

    /** Чотирикутник проти годинникової стрілки, якщо дивитись ззовні (для відсікання задніх граней). ao - візерунок aoPattern. */
    private static void quad(FloatList out, int face, int axis, int u, int v, int plane, int u0, int v0, int u1, int v1, int rgba, int layer, int faceLight, int ao) {
        // кути у термінах (u,v): 0 (-,-), 1 (+,-), 2 (+,+), 3 (-,+); додатна грань обходить 0,1,2,3, від'ємна - 0,3,2,1
        int[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        int[] order = (face & 1) == 0 ? new int[]{0, 1, 2, 3} : new int[]{0, 3, 2, 1};
        int a0 = ao & 3, a1 = (ao >> 2) & 3, a2 = (ao >> 4) & 3, a3 = (ao >> 6) & 3;
        int start = a0 + a2 < a1 + a3 ? 1 : 0; // трикутники 0-1-2 і 0-2-3: діагональ між найсвітлішою парою кутів
        float colorBits = Float.intBitsToFloat(rgba);
        float[] p = new float[3];
        for (int i = 0; i < 4; i++) {
            int k = order[(i + start) & 3];
            p[axis] = plane; p[u] = uv[k][0]; p[v] = uv[k][1];
            out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(colorBits); out.add(faceLight + 2048 * ((ao >> (2 * k)) & 3)); out.add(layer);
        }
    }

    static final class FloatList {
        float[] a = new float[4096]; int n;
        void add(float f) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = f; }
        void clear() { n = 0; }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
