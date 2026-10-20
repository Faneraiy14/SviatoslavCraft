package voxelin.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Рушій світла: небесне (SKY) і від блоків (BLK), рівні 0..15. Правила (за ними ж рахує еталон-«оракул» у тестах):
 *  - Світло від джерела спадає на вартість кожної клітинки, В ЯКУ входить: max(1, absorb) по горизонталі й угору, absorb (може бути 0) униз
 *    для неба - тому відкритий стовп повітря лишає небо 15 до самого низу. Непрозорий блок (absorb 15) світло не впускає (але може світитись сам).
 *  - Небо: віртуальне джерело 15 над кожним стовпом (над y=255). Блоки: кожен блок з emit>0 дає своє значення.
 *  - Світло лише в завантажених чанках (сусід, якого нема, - стіна, поки не завантажиться).
 * Три операції: initLocal(чанк) - у фоновому потоці генерації (лише всередині чанка); reconcile(чанк) - головний потік при появі чанка в світі
 * (обмін через межі); blockChanged - головний потік при зміні блока (вилучення + додавання, двофазний BFS). recompute - пакетні зміни.
 * Зберігається в Chunk.light (див. там), на диск не пишеться. Клас, окрім initLocal, не потокобезпечний (головний потік).
 */
public final class LightEngine {
    /** Кому повідомляти про секції (чанк, sy), де світло змінилось - щоб рендер їх перебудував. */
    public interface Touch { void changed(Chunk c, int sy); }

    private static final int SKY = 0, BLK = 1;

    private final int[] absorb, emit;
    private final Touch touch;

    public LightEngine(BlockRegistry reg, Touch touch) {
        absorb = new int[reg.size()]; emit = new int[reg.size()];
        for (BlockType t : reg.all()) { absorb[t.id] = t.absorb; emit[t.id] = t.light; }
        this.touch = touch;
    }

    private int abs(int id) { return id >= 0 && id < absorb.length ? absorb[id] : 15; }

    // ------------------------------------------------------------------ зв'язок сусідніх чанків (веде світ на головному потоці)

    /** Зв'язати b як сусіда a у напрямку d (0 = +x, 1 = -x, 2 = +z, 3 = -z) і навпаки. */
    public static void connect(Chunk a, int d, Chunk b) { a.nb[d] = b; b.nb[d ^ 1] = a; }

    /** Розірвати всі зв'язки чанка (при вивантаженні). */
    public static void disconnect(Chunk c) {
        for (int d = 0; d < 4; d++) { Chunk n = c.nb[d]; if (n != null) n.nb[d ^ 1] = null; c.nb[d] = null; }
    }

    // ------------------------------------------------------------------ черги й обхід сусідів (головний потік)

    private static final class Q {
        Chunk[] c = new Chunk[1024]; int[] i = new int[1024], v = new int[1024]; int head, tail;
        void add(Chunk ch, int idx, int val) {
            if (tail == c.length) { int n = tail * 2; c = Arrays.copyOf(c, n); i = Arrays.copyOf(i, n); v = Arrays.copyOf(v, n); }
            c[tail] = ch; i[tail] = idx; v[tail] = val; tail++;
        }
        void clear() { Arrays.fill(c, 0, tail, null); head = tail = 0; }
    }

    private final Q remQ = new Q(), seedQ = new Q();
    private Chunk nc;
    private int ni;
    private final Map<Chunk, Integer> dirty = new IdentityHashMap<>();

    /** Сусід клітинки за напрямком d (0 +x, 1 -x, 2 +z, 3 -z, 4 +y, 5 -y) у полях nc/ni; false - сусіда нема. */
    private boolean step(Chunk c, int idx, int d) {
        int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
        switch (d) {
            case 0: if (x == 15) { nc = c.nb[0]; if (nc == null) return false; ni = idx - 15; } else { nc = c; ni = idx + 1; } return true;
            case 1: if (x == 0) { nc = c.nb[1]; if (nc == null) return false; ni = idx + 15; } else { nc = c; ni = idx - 1; } return true;
            case 2: if (z == 15) { nc = c.nb[2]; if (nc == null) return false; ni = idx - 240; } else { nc = c; ni = idx + 16; } return true;
            case 3: if (z == 0) { nc = c.nb[3]; if (nc == null) return false; ni = idx + 240; } else { nc = c; ni = idx - 16; } return true;
            case 4: if (y == 255) return false; nc = c; ni = idx + 256; return true;
            default: if (y == 0) return false; nc = c; ni = idx - 256; return true;
        }
    }

    private int get(Chunk c, int idx, int ch) { return ch == SKY ? c.skyIdx(idx) : c.blkIdx(idx); }

    private void set(Chunk c, int idx, int ch, int v) {
        if (ch == SKY) c.setSkyIdx(idx, v); else c.setBlkIdx(idx, v);
        mark(c, idx);
    }

    private void addMark(Chunk c, int sy) { dirty.merge(c, 1 << sy, (a, b) -> a | b); }

    /** Секція, де змінилась клітинка, і сусідні секції, чиї грані читають її світло (якщо клітинка на межі секції). */
    private void mark(Chunk c, int idx) {
        int sy = idx >> 12, x = idx & 15, z = (idx >> 4) & 15, ly = (idx >> 8) & 15;
        addMark(c, sy);
        if (ly == 0 && sy > 0) addMark(c, sy - 1);
        if (ly == 15 && sy < 15) addMark(c, sy + 1);
        if (x == 0 && c.nb[1] != null) addMark(c.nb[1], sy);
        if (x == 15 && c.nb[0] != null) addMark(c.nb[0], sy);
        if (z == 0 && c.nb[3] != null) addMark(c.nb[3], sy);
        if (z == 15 && c.nb[2] != null) addMark(c.nb[2], sy);
    }

    private void flush() {
        if (dirty.isEmpty()) return;
        for (Map.Entry<Chunk, Integer> e : dirty.entrySet()) for (int sy = 0; sy < 16; sy++) if ((e.getValue() & (1 << sy)) != 0) touch.changed(e.getKey(), sy);
        dirty.clear();
    }

    // ------------------------------------------------------------------ додавання світла (BFS від джерел)

    private void floodAdd(int ch, Q q) {
        while (q.head < q.tail) {
            Chunk c = q.c[q.head]; int idx = q.i[q.head]; q.head++;
            int l = get(c, idx, ch);
            if (l == 0 || (ch == BLK && l == 1)) continue; // нема чого передавати (небо з рівнем 1 ще йде вниз без втрат)
            for (int d = 0; d < 6; d++) {
                if (!step(c, idx, d)) continue;
                int a = abs(nc.idAt(ni));
                if (a >= 15) continue;
                int cost = (ch == SKY && d == 5) ? a : Math.max(1, a);
                int nl = l - cost;
                if (nl > get(nc, ni, ch)) { set(nc, ni, ch, nl); q.add(nc, ni, 0); }
            }
        }
        q.clear();
    }

    // ------------------------------------------------------------------ вилучення світла (залежні клітинки)

    private void floodRemove(int ch, Q rem, Q seeds) {
        while (rem.head < rem.tail) {
            Chunk c = rem.c[rem.head]; int idx = rem.i[rem.head], v = rem.v[rem.head]; rem.head++;
            for (int d = 0; d < 6; d++) {
                if (!step(c, idx, d)) continue;
                Chunk n = nc; int nidx = ni;
                int id = n.idAt(nidx), a = abs(id), ln = get(n, nidx, ch);
                if (ln == 0) continue;
                if (a >= 15) { if (ch == BLK) seeds.add(n, nidx, 0); continue; } // непрозорий лише сам світить (напр. лампа)
                int cost = (ch == SKY && d == 5) ? a : Math.max(1, a);
                if (ln <= v - cost) { // це світло залежало від прибраного
                    int nv = ch == BLK ? emit[id] : 0; // власне джерело лишається джерелом
                    set(n, nidx, ch, nv);
                    rem.add(n, nidx, ln);
                    if (nv > 0) seeds.add(n, nidx, 0);
                } else seeds.add(n, nidx, 0); // незалежне - буде перезаливати спорожніле
            }
        }
        rem.clear();
    }

    // ------------------------------------------------------------------ зміна блока

    /** Викликати ПІСЛЯ того, як блок у чанку вже замінено (oldId -> newId) на клітинці (lx, y, lz). */
    public void blockChanged(Chunk c, int lx, int y, int lz, int oldId, int newId) {
        int idx = lx | (lz << 4) | (y << 8);
        for (int ch = 0; ch < 2; ch++) {
            remQ.clear(); seedQ.clear();
            int old = get(c, idx, ch);
            if (old > 0) { set(c, idx, ch, 0); remQ.add(c, idx, old); floodRemove(ch, remQ, seedQ); }
            int a = abs(newId);
            if (ch == BLK && emit[newId] > 0) { set(c, idx, BLK, emit[newId]); seedQ.add(c, idx, 0); }
            if (a < 15) { // прозорий: сусіди заливають у нього світло
                for (int d = 0; d < 6; d++) if (step(c, idx, d) && get(nc, ni, ch) > 0) seedQ.add(nc, ni, 0);
                if (ch == SKY && y == 255) { int v = 15 - a; if (v > get(c, idx, SKY)) { set(c, idx, SKY, v); seedQ.add(c, idx, 0); } }
            }
            floodAdd(ch, seedQ);
        }
        flush();
    }

    // ------------------------------------------------------------------ стик чанків

    /** Чанк щойно з'явився у світі: обмін світлом через межі з усіма завантаженими сусідами. */
    public void reconcile(Chunk c) {
        for (int ch = 0; ch < 2; ch++) {
            seedQ.clear();
            for (int d = 0; d < 4; d++) {
                Chunk n = c.nb[d];
                if (n == null) continue;
                for (int y = 0; y < 256; y++) for (int t = 0; t < 16; t++) {
                    int ia, ib;
                    switch (d) {
                        case 0: ia = 15 | (t << 4) | (y << 8); ib = (t << 4) | (y << 8); break;
                        case 1: ia = (t << 4) | (y << 8); ib = 15 | (t << 4) | (y << 8); break;
                        case 2: ia = t | (15 << 4) | (y << 8); ib = t | (y << 8); break;
                        default: ia = t | (y << 8); ib = t | (15 << 4) | (y << 8); break;
                    }
                    relax(c, ia, n, ib, ch);
                }
            }
            floodAdd(ch, seedQ);
        }
        flush();
    }

    private void relax(Chunk a, int ia, Chunk b, int ib, int ch) {
        int la = get(a, ia, ch), lb = get(b, ib, ch);
        if (la == 0 && lb == 0) return;
        int aa = abs(a.idAt(ia)), ab = abs(b.idAt(ib));
        if (ab < 15 && la - Math.max(1, ab) > lb) { set(b, ib, ch, la - Math.max(1, ab)); seedQ.add(b, ib, 0); }
        if (aa < 15 && lb - Math.max(1, aa) > la) { set(a, ia, ch, lb - Math.max(1, aa)); seedQ.add(a, ia, 0); }
    }

    /**
     * Пакетні зміни (багато блоків без окремих оновлень): світло зачеплених чанків і їхніх сусідів перераховується заново й зшивається
     * (світло йде не далі 15 блоків, тож далі першого кільця сусідів нічого не змінюється).
     */
    public void recompute(Collection<Chunk> changed) {
        Map<Chunk, Boolean> affected = new IdentityHashMap<>();
        for (Chunk c : changed) { affected.put(c, true); for (int d = 0; d < 4; d++) if (c.nb[d] != null) affected.put(c.nb[d], true); }
        for (Chunk c : affected.keySet()) initLocal(c);
        for (Chunk c : affected.keySet()) reconcile(c);
        for (Chunk c : affected.keySet()) dirty.put(c, 0xFFFF);
        flush();
    }

    // ------------------------------------------------------------------ початкове світло чанка (фоновий потік)

    private static final class Scratch { final byte[] sky = new byte[65536], blk = new byte[65536]; int[] q = new int[1 << 14]; final int[] top = new int[256]; }

    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    /** Світло чанка лише з його власних блоків (сусідів нема). Безпечно викликати з будь-якого потоку для чанка, якого ще ніхто не бачить. */
    public void initLocal(Chunk c) {
        Scratch s = scratch.get();
        byte[] sky = s.sky, blk = s.blk;
        Arrays.fill(blk, (byte) 0);
        int[] top = s.top;
        // 1) небо зверху вниз по стовпах
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int col = x | (z << 4), l = 15, t = -1;
            for (int y = 255; y >= 0; y--) {
                int idx = col | (y << 8), a = abs(c.idAt(idx));
                if (a > 0 && t < 0) t = y;
                l = a >= 15 ? 0 : Math.max(0, l - a);
                sky[idx] = (byte) l;
            }
            top[col] = t;
        }
        // 2) джерела блокового світла
        int n = 0;
        int[] q = s.q;
        for (int sy = 0; sy < 16; sy++) {
            Section sec = c.sections[sy];
            if (sec == null || sec.isEmpty()) continue;
            short[] b = sec.blocks;
            for (int i = 0; i < 4096; i++) {
                int e = emit[b[i]];
                if (e > 0) { int idx = (sy << 12) | i; blk[idx] = (byte) e; if (n == q.length) q = s.q = Arrays.copyOf(q, n * 2); q[n++] = idx; }
            }
        }
        n = flood(c, blk, q, n, false);
        q = s.q;
        // 3) небо: ті клітинки, що можуть підняти сусіда (над нижчим стовпом, отвори печер) - початок BFS
        n = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int col = x | (z << 4);
            int ymax = top[col];
            if (x > 0) ymax = Math.max(ymax, top[col - 1]);
            if (x < 15) ymax = Math.max(ymax, top[col + 1]);
            if (z > 0) ymax = Math.max(ymax, top[col - 16]);
            if (z < 15) ymax = Math.max(ymax, top[col + 16]);
            for (int y = 0; y <= Math.min(255, ymax + 1); y++) {
                int idx = col | (y << 8), l = sky[idx];
                if (l == 0) continue;
                if (canImprove(c, sky, idx, l)) { if (n == q.length) q = s.q = Arrays.copyOf(q, n * 2); q[n++] = idx; }
            }
        }
        flood(c, sky, q, n, true);
        // 4) у чанк: секції, де «все небо 15, нічого від блоків», лишаються null
        for (int sy = 0; sy < 16; sy++) {
            byte[] a = null;
            int base = sy << 12;
            for (int i = 0; i < 4096; i++) {
                byte v = (byte) ((sky[base + i] << 4) | blk[base + i]);
                if (a == null) { if (v == (byte) 0xF0) continue; a = new byte[4096]; Arrays.fill(a, 0, i, (byte) 0xF0); }
                a[i] = v;
            }
            c.light[sy] = a;
        }
    }

    private boolean canImprove(Chunk c, byte[] sky, int idx, int l) {
        int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
        for (int d = 0; d < 6; d++) {
            int n;
            switch (d) {
                case 0: if (x == 15) continue; n = idx + 1; break;
                case 1: if (x == 0) continue; n = idx - 1; break;
                case 2: if (z == 15) continue; n = idx + 16; break;
                case 3: if (z == 0) continue; n = idx - 16; break;
                case 4: if (y == 255) continue; n = idx + 256; break;
                default: if (y == 0) continue; n = idx - 256; break;
            }
            int a = abs(c.idAt(n));
            if (a >= 15) continue;
            int cost = d == 5 ? a : Math.max(1, a);
            if (l - cost > sky[n]) return true;
        }
        return false;
    }

    /** BFS усередині чанка по масиву arr (небо - з нульовою вартістю вниз). q[0..n) - початкові клітинки. */
    private int flood(Chunk c, byte[] arr, int[] q, int n, boolean sky) {
        Scratch s = scratch.get();
        int head = 0;
        while (head < n) {
            int idx = q[head++];
            int l = arr[idx];
            if (l == 0 || (!sky && l == 1)) continue;
            int x = idx & 15, z = (idx >> 4) & 15, y = idx >> 8;
            for (int d = 0; d < 6; d++) {
                int nb;
                switch (d) {
                    case 0: if (x == 15) continue; nb = idx + 1; break;
                    case 1: if (x == 0) continue; nb = idx - 1; break;
                    case 2: if (z == 15) continue; nb = idx + 16; break;
                    case 3: if (z == 0) continue; nb = idx - 16; break;
                    case 4: if (y == 255) continue; nb = idx + 256; break;
                    default: if (y == 0) continue; nb = idx - 256; break;
                }
                int a = abs(c.idAt(nb));
                if (a >= 15) continue;
                int cost = (sky && d == 5) ? a : Math.max(1, a);
                int nl = l - cost;
                if (nl > arr[nb]) {
                    arr[nb] = (byte) nl;
                    if (n == q.length) { q = Arrays.copyOf(q, n * 2); s.q = q; }
                    q[n++] = nb;
                }
            }
        }
        return n;
    }
}
