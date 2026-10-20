import voxelin.loader.*;
import voxelin.core.*;

import java.io.File;
import java.util.*;

/** Рушій світла проти повільного еталона («оракула»): після кожної зміни інкрементальний результат має збігатись з повним перерахунком. */
public class VoxelinLightTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static final int W = 32; // область 2x2 чанки: x,z у 0..31
    static BlockRegistry reg;
    static int[] pal; // блоки для випадкових змін
    static int air, stone, leaves, water, glass, lamp, ice, limin;

    /** Еталон: чиста релаксація «найбільше значення»; правила ті самі, що в описі LightEngine, але без жодної оптимізації. */
    static byte[][] oracle(Chunk[] cs, int ch) {
        byte[][] val = new byte[4][65536];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int k = 0; k < 4; k++) for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int id = cs[k].get(x, y, z), a = reg.get(id).absorb, v = 0;
            if (ch == 0) { if (y == 255 && a < 15) v = 15 - a; } else v = reg.get(id).light;
            if (v > 0) { val[k][x | z << 4 | y << 8] = (byte) v; q.add(new int[]{k, x, y, z}); }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int l = val[c[0]][c[1] | c[3] << 4 | c[2] << 8];
            for (int[] d : dirs) {
                int gx = (c[0] & 1) * 16 + c[1] + d[0], gz = (c[0] >> 1) * 16 + c[3] + d[2], y = c[2] + d[1];
                if (gx < 0 || gx >= W || gz < 0 || gz >= W || y < 0 || y > 255) continue;
                int k = (gz >> 4) * 2 + (gx >> 4), x = gx & 15, z = gz & 15;
                int a = reg.get(cs[k].get(x, y, z)).absorb;
                if (a >= 15) continue;
                int cost = (ch == 0 && d[1] == -1) ? a : Math.max(1, a);
                int nl = l - cost, i = x | z << 4 | y << 8;
                if (nl > val[k][i]) { val[k][i] = (byte) nl; q.add(new int[]{k, x, y, z}); }
            }
        }
        return val;
    }

    static int mismatches(Chunk[] cs, String what) {
        int bad = 0;
        for (int ch = 0; ch < 2; ch++) {
            byte[][] o = oracle(cs, ch);
            for (int k = 0; k < 4; k++) for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int got = ch == 0 ? cs[k].sky(x, y, z) : cs[k].blockLight(x, y, z), exp = o[k][x | z << 4 | y << 8];
                if (got != exp && bad++ < 3) System.out.println("  " + what + ": канал " + ch + " чанк " + k + " (" + x + "," + y + "," + z + ") має " + got + ", треба " + exp + " [блок " + reg.get(cs[k].get(x, y, z)).name + "]");
            }
        }
        return bad;
    }

    static Chunk[] make() { Chunk[] cs = new Chunk[4]; for (int k = 0; k < 4; k++) cs[k] = new Chunk(k & 1, k >> 1); return cs; }

    static void link(Chunk[] cs) {
        LightEngine.connect(cs[0], 0, cs[1]); LightEngine.connect(cs[2], 0, cs[3]);
        LightEngine.connect(cs[0], 2, cs[2]); LightEngine.connect(cs[1], 2, cs[3]);
    }

    static void terrain(Chunk[] cs, Random r) {
        int base = 8 + r.nextInt(20);
        for (int gx = 0; gx < W; gx++) for (int gz = 0; gz < W; gz++) {
            int h = base + r.nextInt(14) - 7, k = (gz >> 4) * 2 + (gx >> 4);
            for (int y = 0; y <= h; y++) cs[k].set(gx & 15, y, gz & 15, r.nextInt(30) == 0 ? air : stone); // печерки
            if (r.nextInt(25) == 0) for (int y = h + 1; y < h + 6 + r.nextInt(10); y++) cs[k].set(gx & 15, y, gz & 15, pal[1 + r.nextInt(pal.length - 1)]); // стовпи всякого
            if (r.nextInt(40) == 0) for (int y = h + 1; y < 255 - r.nextInt(5); y++) cs[k].set(gx & 15, y, gz & 15, stone); // «до неба»
        }
        // дах-плита (тінь)
        int rx = r.nextInt(16), rz = r.nextInt(16), ry = 45 + r.nextInt(10);
        for (int x = rx; x < rx + 14; x++) for (int z = rz; z < rz + 14; z++) cs[(z >> 4) * 2 + (x >> 4)].set(x & 15, ry, z & 15, r.nextBoolean() ? stone : leaves);
    }

    static void fresh(LightEngine e, Chunk[] cs, int[] order) {
        for (Chunk c : cs) e.initLocal(c);
        for (int k : order) e.reconcile(cs[k]);
    }

    public static void main(String[] args) throws Exception {
        Game g = new Game(new File("nope"), null);
        reg = g.blocks;
        air = 0; stone = reg.byName("voxelin:stone").id; leaves = reg.byName("voxelin:oak_leaves").id; water = reg.byName("voxelin:water").id;
        glass = reg.byName("voxelin:glass").id; lamp = reg.byName("voxelin:lamp").id; ice = reg.byName("voxelin:ice").id; limin = reg.byName("voxelin:liminite_ore").id;
        pal = new int[]{air, stone, leaves, water, glass, lamp, ice, limin, air, air, stone};
        check(reg.get(leaves).absorb == 1 && reg.get(water).absorb == 2 && reg.get(glass).absorb == 0 && reg.get(stone).absorb == 15 && reg.get(ice).absorb == 1, "absorb за типовими правилами");
        check(reg.get(lamp).light == 15 && reg.get(limin).light == 4, "emit з даних");

        simple();
        Random r = new Random(12345);
        int[][] orders = {{0, 1, 2, 3}, {3, 2, 1, 0}, {1, 3, 0, 2}};
        // 1) початкове світло не залежить від порядку зшивання
        for (int round = 0; round < 6; round++) {
            Chunk[] ref = null;
            for (int[] ord : orders) {
                Chunk[] cs = make(); link(cs);
                terrain(cs, new Random(1000 + round));
                LightEngine e = new LightEngine(reg, (c, sy) -> {});
                fresh(e, cs, ord);
                check(mismatches(cs, "початкове, раунд " + round) == 0, "початкове світло = еталон (раунд " + round + ", порядок " + Arrays.toString(ord) + ")");
            }
        }
        // 2) чанки з'являються по одному (як у грі): зв'язати й зшити кожен новий
        for (int round = 0; round < 4; round++) {
            Chunk[] cs = make(); terrain(cs, new Random(2000 + round));
            LightEngine e = new LightEngine(reg, (c, sy) -> {});
            for (Chunk c : cs) e.initLocal(c);
            int[] ord = {round % 4, (round + 2) % 4, (round + 1) % 4, (round + 3) % 4};
            Arrays.fill(present, false);
            for (int k : ord) {
                linkOne(cs, k);
                e.reconcile(cs[k]);
            }
            check(mismatches(cs, "по одному") == 0, "чанки з'являються по одному = еталон (раунд " + round + ")");
        }
        // 3) випадкові правки по одній: інкрементальне = еталон + усі змінені секції повідомлені
        long edits = 0;
        for (int round = 0; round < 5; round++) {
            Chunk[] cs = make(); link(cs);
            terrain(cs, new Random(3000 + round));
            Set<Long> touched = new HashSet<>();
            LightEngine e = new LightEngine(reg, (c, sy) -> touched.add(((c.cx * 2 + c.cz) << 8) | sy));
            fresh(e, cs, orders[round % 3]);
            for (int step = 0; step < 120; step++) {
                int gx = r.nextInt(W), gz = r.nextInt(W);
                if (step % 4 == 0) { gx = r.nextBoolean() ? 15 + r.nextInt(2) : (r.nextBoolean() ? 0 : 31); } // межі чанків
                int y = step % 5 == 0 ? 255 - r.nextInt(3) : r.nextInt(70);
                int k = (gz >> 4) * 2 + (gx >> 4), lx = gx & 15, lz = gz & 15;
                int nw = pal[r.nextInt(pal.length)], old = cs[k].get(lx, y, lz);
                byte[][] before = snap(cs);
                cs[k].set(lx, y, lz, nw);
                touched.clear();
                e.blockChanged(cs[k], lx, y, lz, old, nw);
                edits++;
                if (mismatches(cs, "правка " + step + " раунд " + round + " (" + gx + "," + y + "," + gz + ") " + reg.get(old).name + "->" + reg.get(nw).name) != 0) check(false, "правка = еталон");
                // кожна клітинка зі зміненим світлом має лежати в повідомленій секції
                for (int c2 = 0; c2 < 4; c2++) for (int i = 0; i < 65536; i++) {
                    byte now = (byte) ((cs[c2].sky(i & 15, i >> 8, (i >> 4) & 15) << 4) | cs[c2].blockLight(i & 15, i >> 8, (i >> 4) & 15));
                    if (now != before[c2][i]) {
                        long key = ((cs[c2].cx * 2 + cs[c2].cz) << 8) | (i >> 12);
                        check(touched.contains(key), "змінену секцію повідомлено рендеру");
                    }
                }
            }
        }
        // 4) пакет: багато змін без окремого світла, потім recompute = еталон (і = послідовним змінам)
        for (int round = 0; round < 5; round++) {
            Chunk[] cs = make(); link(cs);
            terrain(cs, new Random(4000 + round));
            LightEngine e = new LightEngine(reg, (c, sy) -> {});
            fresh(e, cs, orders[0]);
            Set<Chunk> changed = new HashSet<>();
            for (int i = 0; i < 400; i++) {
                int gx = r.nextInt(W), gz = r.nextInt(W), y = r.nextInt(80), k = (gz >> 4) * 2 + (gx >> 4);
                cs[k].set(gx & 15, y, gz & 15, pal[r.nextInt(pal.length)]); changed.add(cs[k]);
            }
            e.recompute(changed);
            check(mismatches(cs, "пакет") == 0, "пакетний перерахунок = еталон (раунд " + round + ")");
        }
        System.out.println("VoxelinLightTest: " + n + " перевірок OK, правок " + edits);
    }

    static void linkOne(Chunk[] cs, int k) {
        int gx = k & 1, gz = k >> 1;
        for (int j = 0; j < 4; j++) {
            if (j == k) continue;
            int jx = j & 1, jz = j >> 1;
            if (jz == gz && jx == gx + 1 && present[j]) LightEngine.connect(cs[k], 0, cs[j]);
            if (jz == gz && jx == gx - 1 && present[j]) LightEngine.connect(cs[k], 1, cs[j]);
            if (jx == gx && jz == gz + 1 && present[j]) LightEngine.connect(cs[k], 2, cs[j]);
            if (jx == gx && jz == gz - 1 && present[j]) LightEngine.connect(cs[k], 3, cs[j]);
        }
        present[k] = true;
    }
    static boolean[] present = new boolean[4];

    static byte[][] snap(Chunk[] cs) {
        byte[][] s = new byte[4][65536];
        for (int k = 0; k < 4; k++) for (int i = 0; i < 65536; i++) s[k][i] = (byte) ((cs[k].sky(i & 15, i >> 8, (i >> 4) & 15) << 4) | cs[k].blockLight(i & 15, i >> 8, (i >> 4) & 15));
        return s;
    }

    /** Точкові випадки з відомим результатом. */
    static void simple() {
        Chunk[] cs = make(); link(cs);
        LightEngine e = new LightEngine(reg, (c, sy) -> {});
        for (int gx = 0; gx < W; gx++) for (int gz = 0; gz < W; gz++) cs[(gz >> 4) * 2 + (gx >> 4)].set(gx & 15, 0, gz & 15, stone);
        for (Chunk c : cs) e.initLocal(c);
        for (Chunk c : cs) e.reconcile(c);
        check(cs[0].sky(5, 100, 5) == 15 && cs[0].sky(5, 1, 5) == 15 && cs[0].sky(5, 0, 5) == 0, "відкрите небо 15 до самої землі");
        // лампа на підлозі в закритій кімнаті: 15, спад по 1
        for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) for (int y = 1; y <= 5; y++) if (x == 4 || x == 12 || z == 4 || z == 12 || y == 5) cs[0].set(x, y, z, stone);
        for (Chunk c : cs) e.initLocal(c);
        for (Chunk c : cs) e.reconcile(c);
        check(cs[0].sky(8, 2, 8) == 0 && cs[0].blockLight(8, 2, 8) == 0, "запечатана кімната темна");
        int old = cs[0].get(8, 1, 8); cs[0].set(8, 1, 8, lamp); e.blockChanged(cs[0], 8, 1, 8, old, lamp);
        check(cs[0].blockLight(8, 1, 8) == 15 && cs[0].blockLight(8, 2, 8) == 14 && cs[0].blockLight(9, 1, 8) == 14 && cs[0].blockLight(10, 1, 10) == 11, "лампа 15, спад на 1 на блок");
        check(cs[0].blockLight(3, 2, 8) == 0, "стіна ріже світло");
        cs[0].set(8, 1, 8, 0); e.blockChanged(cs[0], 8, 1, 8, lamp, 0);
        check(cs[0].blockLight(8, 2, 8) == 0 && cs[0].blockLight(9, 1, 8) == 0, "лампу прибрали - світло зникло");
        // дірка в даху: світло з градієнтом
        cs[0].set(8, 5, 8, 0); e.blockChanged(cs[0], 8, 5, 8, stone, 0);
        check(cs[0].sky(8, 5, 8) == 15 && cs[0].sky(8, 1, 8) == 15 && cs[0].sky(9, 1, 8) == 14 && cs[0].sky(10, 1, 8) == 13, "дірка пропускає небо з градієнтом");
        // межа чанків: лампа біля x=15 світить у сусіда однаково з обох боків
        cs[0].set(15, 3, 3, lamp); e.blockChanged(cs[0], 15, 3, 3, 0, lamp);
        check(cs[1].blockLight(0, 3, 3) == 14 && cs[1].blockLight(2, 3, 3) == 12, "світло через межу чанка");
        // листя -1, вода -2 від неба
        cs[2].set(3, 40, 3, leaves); e.blockChanged(cs[2], 3, 40, 3, 0, leaves);
        check(cs[2].sky(3, 40, 3) == 14, "листя гасить небо на 1");
        cs[2].set(20 & 15, 40, 3, water); e.blockChanged(cs[2], 4, 40, 3, 0, water);
        check(cs[2].sky(4, 40, 3) == 13, "вода гасить небо на 2");
    }
}
