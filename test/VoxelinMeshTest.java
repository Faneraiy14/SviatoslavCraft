import voxelin.client.render.ChunkMesher;
import voxelin.client.render.SectionPos;

/** Жадібний мешинг без OpenGL: кількість граней на відомих фігурах. */
public class VoxelinMeshTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    // id: 0 повітря, 1 камінь, 2 земля, 3 скло (напівпрозоре)
    static final ChunkMesher.Props PROPS = new ChunkMesher.Props(
            new boolean[]{false, true, true, false}, new boolean[]{false, false, false, true},
            new boolean[]{true, false, false, false}, new int[]{0, 0x808080, 0x804D1A, 0xCFEFF5},
            new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0});

    static short[] empty() { return new short[18 * 18 * 18]; }
    static void set(short[] p, int x, int y, int z, int id) { p[ChunkMesher.index(x, y, z)] = (short) id; }

    public static void main(String[] a) {
        ChunkMesher m = new ChunkMesher();
        short[] p = empty();
        check(m.build(p, PROPS).isEmpty(), "порожня секція - без геометрії");

        set(p, 5, 5, 5, 1);
        ChunkMesher.Mesh r = m.build(p, PROPS);
        check(r.solidQuads == 6 && r.translucentQuads == 0, "один блок = 6 граней: " + r.solidQuads);
        check(r.solid.length == 6 * 4 * ChunkMesher.FLOATS_PER_VERTEX, "5 float на вершину, 4 вершини на грань");

        p = empty();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) set(p, x, 0, z, 1);
        r = m.build(p, PROPS);
        check(r.solidQuads == 6, "плита 16x1x16 склеюється в 6 граней, а не " + (16 * 16 + 16 * 16 + 4 * 16) + ": " + r.solidQuads);

        p = empty();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) set(p, x, y, z, 1);
        for (int i = -1; i <= 16; i++) for (int j = -1; j <= 16; j++) { // сусіди теж камінь - зсередини нічого не видно
            for (int k : new int[]{-1, 16}) { set(p, k, i, j, 1); set(p, i, k, j, 1); set(p, i, j, k, 1); }
        }
        check(m.build(p, PROPS).isEmpty(), "суцільний камінь між кам'яними сусідами - порожній меш");

        p = empty();
        set(p, 3, 3, 3, 1); set(p, 4, 3, 3, 2);
        r = m.build(p, PROPS);
        check(r.solidQuads == 10, "два різні блоки поруч: 6+6-2 приховані = 10 граней: " + r.solidQuads);

        p = empty();
        set(p, 3, 3, 3, 1); set(p, 4, 3, 3, 1);
        check(m.build(p, PROPS).solidQuads == 6, "два однакові блоки поруч склеюються в стрижень з 6 граней");

        p = empty();
        set(p, 3, 3, 3, 3); set(p, 4, 3, 3, 3);
        r = m.build(p, PROPS);
        check(r.solidQuads == 0 && r.translucentQuads == 6, "скло йде в окремий напівпрозорий прохід, між склом грані прибрано: " + r.translucentQuads);

        p = empty();
        set(p, 3, 3, 3, 1); set(p, 4, 3, 3, 3);
        r = m.build(p, PROPS);
        check(r.solidQuads == 6 && r.translucentQuads == 5, "камінь поруч зі склом: грань каменю видно, грань скла до каменю прихована: " + r.solidQuads + "/" + r.translucentQuads);

        p = empty();
        set(p, 0, 0, 0, 1); set(p, 15, 15, 15, 1);
        check(m.build(p, PROPS).solidQuads == 12, "блоки в кутах секції");

        // шахова дошка - найгірший випадок, склеювання неможливе
        p = empty();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) if ((x + z) % 2 == 0) set(p, x, 0, z, 1);
        r = m.build(p, PROPS);
        check(r.solidQuads == 128 * 6, "шахова дошка: 128 блоків по 6 граней: " + r.solidQuads);

        // вершини в межах секції й грань кодується числом 0..5
        for (int i = 0; i < r.solid.length; i += ChunkMesher.FLOATS_PER_VERTEX) {
            check(r.solid[i] >= 0 && r.solid[i] <= 16 && r.solid[i + 1] >= 0 && r.solid[i + 1] <= 16 && r.solid[i + 2] >= 0 && r.solid[i + 2] <= 16, "вершина в межах секції");
            int pf = (int) r.solid[i + 4];
            check((pf & 7) <= 5 && (pf >> 3 & 15) == 15 && (pf >> 7) == 0, "номер грані + повне небо без блокового світла: " + pf);
        }
        // світло: грані з різним світлом не зливаються, з однаковим - зливаються; пакування aFace = грань + 8*небо + 128*блок
        p = empty();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) set(p, x, 0, z, 1); // плита 4x4, верх дивиться в y=1
        byte[] lp = new byte[18 * 18 * 18];
        java.util.Arrays.fill(lp, (byte) 0xF0);
        r = m.build(p, lp, PROPS);
        check(r.solidQuads == 6, "плита з однаковим світлом - шість склеєних граней: " + r.solidQuads);
        lp[ChunkMesher.index(2, 1, 1)] = (byte) 0x5A; // над одним блоком інше світло: небо 5, блок 10
        r = m.build(p, lp, PROPS);
        check(r.solidQuads > 6, "різне світло ламає склеювання: " + r.solidQuads);
        boolean found = false;
        for (int i = 0; i < r.solid.length; i += ChunkMesher.FLOATS_PER_VERTEX) if ((int) r.solid[i + 4] == 2 + 8 * 5 + 128 * 10) found = true;
        check(found, "світло запаковане в aFace (верхня грань, небо 5, блок 10)");
        lp[ChunkMesher.index(2, 1, 1)] = (byte) 0xF0;
        ChunkMesher.Props pe = new ChunkMesher.Props(PROPS.opaque(), PROPS.translucent(), PROPS.air(), new int[]{0, 0x808080, 0x804D1A, 0xCFEFF5}, new int[4], new int[4], new int[4]);
        pe.emit = new int[]{0, 12, 0, 0}; // «камінь» тут світиться сам на 12
        r = m.build(p, lp, pe);
        found = false;
        for (int i = 0; i < r.solid.length; i += ChunkMesher.FLOATS_PER_VERTEX) if ((int) r.solid[i + 4] >> 7 == 12) found = true;
        check(found, "блок, що світиться, сяє й сам (emit піднімає блокове світло граней)");
        // ключі секцій: від'ємні й гігантські координати чанків (регресія - з них зникав світ ліворуч від початку)
        long[] cs = {0, 1, -1, 5, -5, 1000, -1000, 8_388_607, -8_388_608, 1L << 40, -(1L << 40), Long.MAX_VALUE >> 5, -(Long.MAX_VALUE >> 5)};
        java.util.Set<SectionPos> set = new java.util.HashSet<>();
        for (long cx : cs) for (long cz : cs) for (int sy = 0; sy < 16; sy += 5) {
            SectionPos k = new SectionPos(cx, sy, cz);
            check(k.cx() == cx && k.cz() == cz && k.sy() == sy && set.add(k) && set.contains(new SectionPos(cx, sy, cz)), "ключ секції " + cx + "," + sy + "," + cz);
        }
        System.out.println("VoxelinMeshTest: " + n + " перевірок OK");
    }
}
