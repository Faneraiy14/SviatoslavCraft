package voxelin.core;

import voxelin.api.Source;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Справжні дошки (рішення Святослава 29.10.2026): дошка - брусок 16x4x4 пікселі, у блоці їх до 16. Блок ділиться на сітку 4x4x4,
 * дошка займає рядок із чотирьох клітинок уздовж X, уздовж Z або стоячи (Y); напрямки в одному блоці можна мішати, аби дошки не
 * перетинались. Клітинка з дошками - блок «*_boards» (одна порода на клітинку), а що саме зайнято - маска в цьому класі (boards.dat):
 * біти 0-15 - дошки вздовж X (номер y*4+z), 16-31 - вздовж Z (y*4+x), 32-47 - стоячі (x*4+z).
 * Мапа потокобезпечна: мешер читає її у фонових потоках.
 */
public final class Boards {
    public static final int X = 0, Z = 1, Y = 2;
    public static final String SHAPE = "boards";
    private static final Source SRC = Sources.SYSTEM;

    private final World w;
    private final Map<BlockPos, Long> masks = new ConcurrentHashMap<>();

    Boards(World w) { this.w = w; load(); }

    public long mask(long x, int y, long z) { Long m = masks.get(new BlockPos(x, y, z)); return m == null ? 0 : m; }

    public int count() { return masks.size(); }

    /** Номер біта дошки: вісь і дві координати в сітці 4x4 (для X: a=y, b=z; для Z: a=y, b=x; для Y: a=x, b=z). */
    public static int bit(int axis, int a, int b) { return axis * 16 + a * 4 + b; }

    /** Зайняті клітинки сітки 4x4x4 (біт x + y*4 + z*16). */
    public static long occupancy(long mask) {
        long o = 0;
        for (int i = 0; i < 48; i++) {
            if ((mask >>> i & 1) == 0) continue;
            int axis = i / 16, a = (i % 16) / 4, b = i % 4;
            for (int t = 0; t < 4; t++) {
                int x, y, z;
                if (axis == X) { x = t; y = a; z = b; } else if (axis == Z) { x = b; y = a; z = t; } else { x = a; y = t; z = b; }
                o |= 1L << (x + y * 4 + z * 16);
            }
        }
        return o;
    }

    /** Коробки дощок у частках блока: по 6 чисел {x0,y0,z0,x1,y1,z1}. */
    public static double[] boxes(long mask) {
        int n = Long.bitCount(mask & 0xFFFF_FFFF_FFFFL);
        double[] r = new double[n * 6];
        int k = 0;
        for (int i = 0; i < 48; i++) {
            if ((mask >>> i & 1) == 0) continue;
            int axis = i / 16, a = (i % 16) / 4, b = i % 4;
            double q = 0.25;
            if (axis == X) { r[k] = 0; r[k + 1] = a * q; r[k + 2] = b * q; r[k + 3] = 1; r[k + 4] = a * q + q; r[k + 5] = b * q + q; }
            else if (axis == Z) { r[k] = b * q; r[k + 1] = a * q; r[k + 2] = 0; r[k + 3] = b * q + q; r[k + 4] = a * q + q; r[k + 5] = 1; }
            else { r[k] = a * q; r[k + 1] = 0; r[k + 2] = b * q; r[k + 3] = a * q + q; r[k + 4] = 1; r[k + 5] = b * q + q; }
            k += 6;
        }
        return r;
    }

    /** Номер біта дошки, що проходить через клітинку сітки (sx, sy, sz) уздовж осі. */
    public static int bitThrough(int axis, int sx, int sy, int sz) { return axis == X ? bit(X, sy, sz) : axis == Z ? bit(Z, sy, sx) : bit(Y, sx, sz); }

    public boolean isBoards(BlockType t) { return t != null && SHAPE.equals(t.shape); }

    /**
     * Поставити дошку породи boardsBlock, що проходить через клітинку сітки (sx,sy,sz) блока (x,y,z), уздовж осі.
     * Блок має бути повітрям/рідиною або дошками тієї ж породи, і дошка не має перетинати наявні.
     */
    public boolean place(Source src, long x, int y, long z, BlockType boardsBlock, int axis, int sx, int sy, int sz) {
        if (!isBoards(boardsBlock) || sx < 0 || sx > 3 || sy < 0 || sy > 3 || sz < 0 || sz > 3) return false;
        int id = w.blockId(x, y, z);
        if (id < 0) return false;
        BlockType cur = w.blocks.get(id);
        if (id != 0 && !cur.liquid && cur != boardsBlock) return false;
        long m = cur == boardsBlock ? mask(x, y, z) : 0;
        int bit = bitThrough(axis, sx, sy, sz);
        long nm = m | 1L << bit;
        if ((m >>> bit & 1) != 0 || (occupancy(m) & occupancy(1L << bit)) != 0) return false;
        masks.put(new BlockPos(x, y, z), nm); // спершу маска - щоб мешер після setBlock уже бачив дошку
        if (cur != boardsBlock && !w.setBlock(src, x, y, z, boardsBlock)) { if (m == 0) masks.remove(new BlockPos(x, y, z)); else masks.put(new BlockPos(x, y, z), m); return false; }
        if (cur == boardsBlock) w.changed(x, y, z);
        return true;
    }

    /** Прибрати одну дошку (номер біта). Остання - клітинка стає повітрям. */
    public boolean remove(Source src, long x, int y, long z, int bit) {
        long m = mask(x, y, z);
        if (bit < 0 || bit >= 48 || (m >>> bit & 1) == 0) return false;
        long nm = m & ~(1L << bit);
        if (nm == 0) return w.setBlock(src, x, y, z, w.blocks.air()); // setBlock прибере й маску
        masks.put(new BlockPos(x, y, z), nm);
        w.changed(x, y, z);
        return true;
    }

    /** Блок перестав бути дошками (зламано цілком, замінено командою). */
    void cleared(long x, int y, long z) { masks.remove(new BlockPos(x, y, z)); }

    /** Біт дошки, у яку влучає промінь у точці (частки блока, трохи всередину від грані), або -1. */
    public static int partAt(long mask, double fx, double fy, double fz) {
        int sx = Math.max(0, Math.min(3, (int) (fx * 4))), sy = Math.max(0, Math.min(3, (int) (fy * 4))), sz = Math.max(0, Math.min(3, (int) (fz * 4)));
        for (int axis = 0; axis < 3; axis++) { int b = bitThrough(axis, sx, sy, sz); if ((mask >>> b & 1) != 0) return b; }
        return -1;
    }

    // ------------------------------------------------------------------ збереження

    private File file() { return new File(w.dir, "boards.dat"); }

    void save() {
        if (masks.isEmpty() && !file().isFile()) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<BlockPos, Long> e : masks.entrySet()) sb.append(e.getKey().x()).append(' ').append(e.getKey().y()).append(' ').append(e.getKey().z()).append(' ').append(Long.toHexString(e.getValue())).append('\n');
            File tmp = new File(w.dir, "boards.dat.tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files2.replace(tmp, file());
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати дошки: " + e); }
    }

    private void load() {
        File f = file();
        if (!f.isFile()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 4) continue;
                try {
                    long x = Long.parseLong(p[0]), z = Long.parseLong(p[2]), m = Long.parseUnsignedLong(p[3], 16) & 0xFFFF_FFFF_FFFFL; int y = Integer.parseInt(p[1]);
                    if (y >= 0 && y < Chunk.HEIGHT && m != 0) masks.put(new BlockPos(x, y, z), m);
                } catch (NumberFormatException e) { /* битий рядок */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати дошки: " + e); }
    }
}
