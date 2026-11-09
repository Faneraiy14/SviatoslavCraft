package voxelin.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Пошук шляху для наземних істот: A* по клітинках блоків. Клітинка (x,y,z) - місце для ніг: під нею твердий блок (або вода - пливе),
 * у ній і вище на висоту істоти - не твердо; лави ні в ногах, ні під ногами. Ходи на 8 сусідів: рівно, на 1 блок угору (є місце для
 * стрибка над головою), униз до MAX_DROP блоків; по діагоналі - лише коли вільні обидва бокові проходи (не зрізати кути). Вода втричі
 * «дорожча». Межі: maxNodes вузлів і RANGE блоків від старту. Незавантажений чанк - суцільний (isSolid), тож туди шлях не веде.
 * Якщо до цілі не дійти - шлях до найближчої досяжної точки (зомбі підходить до стіни, а не стоїть). Лише читає блоки - можна з
 * фонового потоку (World.findPath при asyncPaths).
 */
public final class Pathfinder {
    public static final int MAX_DROP = 3, RANGE = 32, DEFAULT_NODES = 600;
    /** Скільки пошуків дозволено за один тік світу (усім істотам разом). */
    public static final int BUDGET_PER_TICK = 6;

    /** Скільки пошуків зроблено від старту гри (для тестів і F3; з фонового потоку лічба приблизна). */
    public static volatile long searches;

    private Pathfinder() {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final class Node implements Comparable<Node> {
        final int x, y, z; // відносно старту (x,z) і абсолютна y
        double g, f; Node parent; boolean closed;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private static int key(int x, int y, int z) { return ((x + 128) & 255) | (((z + 128) & 255) << 8) | ((y & 511) << 16); }

    /** Чи можна стояти ногами в (x,y,z) істоті заввишки h блоків. */
    public static boolean standable(World w, long x, int y, long z, int h) {
        if (y < 1 || y + h >= Chunk.HEIGHT) return false;
        for (int i = 0; i < h; i++) if (w.isSolid(x, y + i, z) || w.isLava(x, y + i, z)) return false;
        if (w.isLiquid(x, y, z)) return true; // пливе
        return w.isSolid(x, y - 1, z) && !w.isLava(x, y - 1, z);
    }

    /**
     * Шлях від ніг (sx,sy,sz) до (gx,gy,gz). Повертає точки [x,y,z, x,y,z, ...] без стартової клітинки (останні - ціль або найближча досяжна)
     * або null, якщо нікуди не просунутись.
     */
    public static long[] find(World w, long sx, int sy, long sz, long gx, int gy, long gz, int height, int maxNodes) {
        searches++;
        int h = Math.max(1, Math.min(4, height));
        if (Math.abs(gx - sx) > RANGE || Math.abs(gz - sz) > RANGE) { // ціль далеко: ідемо в її бік до межі
            double d = Math.max(Math.abs(gx - sx), Math.abs(gz - sz));
            gx = sx + Math.round((gx - sx) * (RANGE / d)); gz = sz + Math.round((gz - sz) * (RANGE / d));
        }
        int tx = (int) (gx - sx), tz = (int) (gz - sz);
        HashMap<Integer, Node> all = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node start = new Node(0, sy, 0);
        start.f = heur(0, sy, 0, tx, gy, tz);
        all.put(key(0, sy, 0), start); open.add(start);
        Node best = start; double bestH = start.f;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node c = open.poll();
            if (c.closed) continue;
            c.closed = true; expanded++;
            if (c.x == tx && c.z == tz && Math.abs(c.y - gy) <= 1) { best = c; break; }
            double hh = heur(c.x, c.y, c.z, tx, gy, tz);
            if (hh < bestH) { bestH = hh; best = c; }
            for (int[] d : DIRS) {
                int nx = c.x + d[0], nz = c.z + d[1];
                if (Math.abs(nx) > RANGE || Math.abs(nz) > RANGE) continue;
                long wx = sx + nx, wz = sz + nz;
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && (!clear(w, sx + c.x + d[0], c.y, sz + c.z, h) || !clear(w, sx + c.x, c.y, sz + c.z + d[1], h))) continue;
                int ny = Integer.MIN_VALUE;
                if (standable(w, wx, c.y, wz, h)) ny = c.y;
                else if (standable(w, wx, c.y + 1, wz, h) && !w.isSolid(sx + c.x, c.y + h, sz + c.z)) ny = c.y + 1; // стрибок: над головою вільно
                else if (clear(w, wx, c.y, wz, h)) {
                    for (int dy = 1; dy <= MAX_DROP; dy++) if (standable(w, wx, c.y - dy, wz, h)) { ny = c.y - dy; break; }
                }
                if (ny == Integer.MIN_VALUE) continue;
                double step = (diag ? 1.414 : 1.0) + (ny > c.y ? 0.5 : 0) + (ny < c.y ? 0.2 * (c.y - ny) : 0);
                if (w.isLiquid(wx, ny, wz)) step *= 3;
                double g = c.g + step;
                int k = key(nx, ny, nz);
                Node n = all.get(k);
                if (n == null) { n = new Node(nx, ny, nz); all.put(k, n); }
                else if (n.closed || g >= n.g) continue;
                n.g = g; n.parent = c; n.f = g + heur(nx, ny, nz, tx, gy, tz);
                open.add(n);
            }
        }
        if (best == start) return null;
        List<Node> rev = new ArrayList<>();
        for (Node n = best; n != null && n != start; n = n.parent) rev.add(n);
        long[] out = new long[rev.size() * 3];
        for (int i = 0; i < rev.size(); i++) { Node n = rev.get(rev.size() - 1 - i); out[i * 3] = sx + n.x; out[i * 3 + 1] = n.y; out[i * 3 + 2] = sz + n.z; }
        return out;
    }

    /** Місце для тіла (без вимоги опори): прохід, у який можна ступити або впасти. */
    private static boolean clear(World w, long x, int y, long z, int h) {
        if (y < 1 || y + h >= Chunk.HEIGHT) return false;
        for (int i = 0; i < h; i++) if (w.isSolid(x, y + i, z) || w.isLava(x, y + i, z)) return false;
        return true;
    }

    private static double heur(int x, int y, int z, int tx, int ty, int tz) {
        int dx = Math.abs(x - tx), dz = Math.abs(z - tz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + Math.abs(y - ty) * 0.5;
    }
}
