package voxelin.core;

import voxelin.api.Source;
import voxelin.core.entity.PlayerEntity;
import voxelin.core.gen.NetherGenerator;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Портал у Незер (як у Minecraft): вертикальна рамка з обсидіану (усередині від 2x3 до 21x21, кути не обов'язкові), розпалюється кресалом.
 * Зламаєш будь-який блок рамки чи порталу - гасне весь. Вхід - ноги в порталі; у Незері гравець з'являється на 1/8 координат,
 * а поруч будується рамка з порталом назад (якщо поблизу ще нема). Назад - у ту точку, звідки заходив.
 */
public final class NetherPortal {
    private NetherPortal() {}

    public static final String BLOCK = "voxelin:nether_portal", FRAME = "voxelin:obsidian", LIGHTER = "voxelin:flint_and_steel";
    public static final int MIN_W = 2, MIN_H = 3, MAX = 21, SCALE = 8;
    private static final Source SRC = Sources.SYSTEM;
    private static boolean collapsing;

    /** Чи можна в цьому вимірі (звичайний світ і Незер). */
    public static boolean allowed(World w) { return w.info.type != WorldInfo.Type.END && w.info.type != WorldInfo.Type.BETWEEN; }

    /**
     * Розпалити рамку, у порожнину якої потрапляє клітинка (x,y,z). Повертає кількість блоків порталу (0 - рамки нема або вона неправильна).
     */
    public static int ignite(World w, long x, int y, long z) {
        if (!allowed(w)) return 0;
        BlockType portal = w.blocks.byName(BLOCK);
        if (portal == null || !empty(w, x, y, z)) return 0;
        for (int axis = 0; axis < 2; axis++) { // 0 - рамка вздовж X, 1 - вздовж Z
            long dx = axis == 0 ? 1 : 0, dz = axis == 0 ? 0 : 1;
            int by = y;
            while (by > 0 && y - by < MAX && empty(w, x, by - 1, z)) by--;
            if (!frame(w, x, by - 1, z)) continue;
            long left = 0;
            while (left < MAX && empty(w, x - dx * (left + 1), by, z - dz * (left + 1))) left++;
            long lx = x - dx * left, lz = z - dz * left;
            if (!frame(w, lx - dx, by, lz - dz)) continue;
            int wdt = 0;
            while (wdt <= MAX && empty(w, lx + dx * wdt, by, lz + dz * wdt)) wdt++;
            if (wdt < MIN_W || wdt > MAX || !frame(w, lx + dx * wdt, by, lz + dz * wdt)) continue;
            int hgt = 0;
            while (hgt <= MAX && empty(w, lx, by + hgt, lz)) hgt++;
            if (hgt < MIN_H || hgt > MAX || y >= by + hgt) continue;
            if (!rect(w, lx, by, lz, dx, dz, wdt, hgt)) continue;
            for (int i = 0; i < wdt; i++) for (int j = 0; j < hgt; j++) w.setBlock(SRC, lx + dx * i, by + j, lz + dz * i, portal);
            return wdt * hgt;
        }
        return 0;
    }

    /** Усередині все порожнє, по краях (без кутів) - обсидіан. */
    private static boolean rect(World w, long lx, int by, long lz, long dx, long dz, int wdt, int hgt) {
        for (int i = 0; i < wdt; i++) {
            for (int j = 0; j < hgt; j++) if (!empty(w, lx + dx * i, by + j, lz + dz * i)) return false;
            if (!frame(w, lx + dx * i, by - 1, lz + dz * i) || !frame(w, lx + dx * i, by + hgt, lz + dz * i)) return false;
        }
        for (int j = 0; j < hgt; j++) if (!frame(w, lx - dx, by + j, lz - dz) || !frame(w, lx + dx * wdt, by + j, lz + dz * wdt)) return false;
        return true;
    }

    private static boolean empty(World w, long x, int y, long z) { return w.blockId(x, y, z) == 0; }
    private static boolean frame(World w, long x, int y, long z) { int id = w.blockId(x, y, z); return id > 0 && w.blocks.get(id).name.equals(FRAME); }
    private static boolean isPortal(World w, long x, int y, long z) { int id = w.blockId(x, y, z); return id > 0 && w.blocks.get(id).name.equals(BLOCK); }

    /** Хук World.setBlock: на місці рамки чи порталу стало щось інше - гасимо весь портал, що з ним межує. */
    static void changed(World w, long x, int y, long z, BlockType old, BlockType now) {
        if (collapsing || old == now || !(old.name.equals(FRAME) || old.name.equals(BLOCK))) return;
        collapsing = true;
        try {
            Set<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] k : d) if (isPortal(w, x + k[0], y + k[1], z + k[2])) q.add(new BlockPos(x + k[0], y + k[1], z + k[2]));
            while (!q.isEmpty() && seen.size() < MAX * MAX * 4) {
                BlockPos p = q.poll();
                if (!seen.add(p) || !isPortal(w, p.x(), p.y(), p.z())) continue;
                w.setBlock(SRC, p.x(), p.y(), p.z(), w.blocks.air());
                for (int[] k : d) q.add(new BlockPos(p.x() + k[0], p.y() + k[1], p.z() + k[2]));
            }
        } finally { collapsing = false; }
    }

    /** Точка прибуття в Незер для входу в (x, z) звичайного світу. */
    public static long[] target(long x, long z) { return new long[]{Math.floorDiv(x, SCALE), Math.floorDiv(z, SCALE)}; }

    /**
     * Облаштувати прибуття в Незері біля (ax, az): якщо в радіусі 12 уже є портал - стати біля нього; інакше знайти порожнину
     * (або вирубати її на висоті 64), постелити майданчик 5x5 і збудувати рамку 4x5 з порталом за спиною. Повертає позицію очей.
     */
    public static Pos arrive(World w, long ax, long az) {
        for (int r = 0; r <= 12; r++) for (long x = ax - r; x <= ax + r; x++) for (long z = az - r; z <= az + r; z++) {
            if (Math.max(Math.abs(x - ax), Math.abs(z - az)) != r) continue;
            for (int y = NetherGenerator.LAVA + 1; y < NetherGenerator.CEIL - 4; y++) {
                if (!isPortal(w, x, y, z) || isPortal(w, x, y - 1, z)) continue;
                for (int[] k : new int[][]{{0, 2}, {0, -2}, {2, 0}, {-2, 0}, {1, 2}, {1, -2}, {2, 1}, {-2, 1}}) // поруч із наявним порталом
                    if (standable(w, x + k[0], y, z + k[1])) return eyes(x + k[0], y, z + k[1]);
            }
        }
        // готова порожнина поблизу: тверда підлога, 4 блоки вгору порожньо й місце для рамки за спиною
        int y = -1;
        search:
        for (int r = 0; r <= 16; r += 2) for (long x = ax - r; x <= ax + r; x += 2) for (long z = az - r; z <= az + r; z += 2) {
            if (Math.max(Math.abs(x - ax), Math.abs(z - az)) != r) continue;
            for (int dy = 0; dy < 40; dy++) for (int sgn : new int[]{1, -1}) {
                int yy = 64 + dy * sgn;
                if (yy <= NetherGenerator.LAVA + 1 || yy >= NetherGenerator.CEIL - 6) continue;
                if (standable(w, x, yy, z) && w.blockId(x, yy + 2, z) == 0 && w.blockId(x, yy + 3, z) == 0 && w.blockId(x, yy, z + 3) == 0 && w.blockId(x, yy + 2, z + 3) == 0) { ax = x; az = z; y = yy; break search; }
            }
        }
        boolean dug = y < 0;
        if (dug) y = 64;
        BlockType cinder = w.blocks.byName("voxelin:cinder"), obs = w.blocks.byName(FRAME), portal = w.blocks.byName(BLOCK), air = w.blocks.air();
        int half = dug ? 3 : 2;
        for (long x = ax - half; x <= ax + half; x++) for (long z = az - 3; z <= az + (dug ? 4 : 2); z++) {
            w.setBlock(SRC, x, y - 1, z, cinder);
            for (int yy = y; yy <= y + 3; yy++) w.setBlock(SRC, x, yy, z, air);
        }
        if (dug) { // вирубана кімната: жарові жили по кутах - щоб було видно
            BlockType vein = w.blocks.byName("voxelin:ember_vein");
            for (long x : new long[]{ax - half - 1, ax + half + 1}) for (long z : new long[]{az - 2, az + 3}) w.setBlock(SRC, x, y + 2, z, vein);
        }
        long fz = az - 3; // рамка вздовж X за спиною
        for (int i = -1; i <= 2; i++) { w.setBlock(SRC, ax + i, y - 1, fz, obs); w.setBlock(SRC, ax + i, y + 3, fz, obs); }
        for (int j = 0; j < 3; j++) { w.setBlock(SRC, ax - 1, y + j, fz, obs); w.setBlock(SRC, ax + 2, y + j, fz, obs); }
        for (int i = 0; i < 2; i++) for (int j = 0; j < 3; j++) w.setBlock(SRC, ax + i, y + j, fz, portal);
        return eyes(ax, y, az);
    }

    private static boolean standable(World w, long x, int y, long z) {
        int below = w.blockId(x, y - 1, z);
        if (below <= 0 || !w.blocks.get(below).solid) return false;
        return w.blockId(x, y, z) == 0 && w.blockId(x, y + 1, z) == 0;
    }

    private static Pos eyes(long x, int y, long z) { return Pos.ofBlock(x, y + PlayerEntity.EYE + 0.02, z); }
}
