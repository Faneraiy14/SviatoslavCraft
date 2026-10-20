package voxelin.core.gen;

import voxelin.core.Chunk;

import java.util.*;

/**
 * Детермінований розподіл структур по ґратці клітинок (без стану й без залежності від порядку генерації чанків). Для кожного шаблона:
 * клітинка spacing x spacing, hash(зерно, вид, клітинка) вирішує, чи є структура, де в клітинці (з полем, щоб габарит не виходив за неї) і який поворот.
 * Поверхневі - лише на суші й рівному місці; підземні - на заданій висоті під поверхнею. Чанк записує лише ті клітинки шаблона, що лежать у ньому.
 */
public final class StructurePlacer {
    public interface Surface { int surfaceY(long x, long z); }

    public static final class Placement {
        public final StructureTemplate t; public final long ox, oz; public final int oy, rot, w, d; public final long seed;
        Placement(StructureTemplate t, long ox, long oz, int oy, int rot, long seed) { this.t = t; this.ox = ox; this.oz = oz; this.oy = oy; this.rot = rot; this.w = t.width(rot); this.d = t.depth(rot); this.seed = seed; }
        public long centerX() { return ox + w / 2; }
        public long centerZ() { return oz + d / 2; }
    }

    private final long seed;
    private final List<StructureTemplate> templates;
    private final Surface surface;
    private final int sea;

    public StructurePlacer(long seed, List<StructureTemplate> templates, Surface surface, int sea) {
        this.seed = seed; this.templates = List.copyOf(templates); this.surface = surface; this.sea = sea;
    }

    public List<StructureTemplate> templates() { return templates; }

    private long cellHash(StructureTemplate t, long cx, long cz) {
        return Noise.mix(Noise.mix(Noise.mix(seed ^ (t.name.hashCode() * 0x9E3779B97F4A7C15L) ^ 0x57A0C7L) + cx * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L));
    }

    /** Структура в клітинці або null. */
    public Placement placementFor(StructureTemplate t, long cx, long cz) {
        long h = cellHash(t, cx, cz);
        if (((h >>> 11) / (double) (1L << 53)) >= t.chance) return null;
        int maxDim = Math.max(t.sx, t.sz), span = t.spacing - maxDim;
        long h2 = Noise.mix(h + 1), h3 = Noise.mix(h + 2), h4 = Noise.mix(h + 3);
        int offX = (int) ((h2 >>> 1) % (span + 1)), offZ = (int) ((h3 >>> 1) % (span + 1)), rot = (int) (h4 & 3);
        long wx = cx * t.spacing + offX, wz = cz * t.spacing + offZ;
        int w = t.width(rot), d = t.depth(rot);
        int[] ys = {surface.surfaceY(wx, wz), surface.surfaceY(wx + w - 1, wz), surface.surfaceY(wx, wz + d - 1), surface.surfaceY(wx + w - 1, wz + d - 1), surface.surfaceY(wx + w / 2, wz + d / 2)};
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y : ys) { min = Math.min(min, y); max = Math.max(max, y); }
        int oy;
        if (t.place == StructureTemplate.Place.SURFACE) {
            if (min <= sea + 1 || max - min > 4) return null;
            oy = min + 1 - t.sink;
            if (oy < 2 || oy + t.sy > 254) return null;
        } else {
            oy = t.depthMin + (int) ((h4 >>> 8) % (t.depthMax - t.depthMin + 1));
            if (oy + t.sy + 4 >= min || oy < 3) return null; // має лишатись під поверхнею з запасом
        }
        return new Placement(t, wx, wz, oy, rot, Noise.mix(h4 + 5));
    }

    /** Записати в чанк усі клітинки структур, що його перетинають. */
    public void apply(Chunk c, voxelin.core.BlockRegistry blocks) {
        final long bx = c.cx * 16, bz = c.cz * 16;
        for (StructureTemplate t : templates) {
            int maxDim = Math.max(t.sx, t.sz);
            long x0 = Math.floorDiv(bx - maxDim + 1, (long) t.spacing), x1 = Math.floorDiv(bx + 15, (long) t.spacing);
            long z0 = Math.floorDiv(bz - maxDim + 1, (long) t.spacing), z1 = Math.floorDiv(bz + 15, (long) t.spacing);
            for (long cx = x0; cx <= x1; cx++) for (long cz = z0; cz <= z1; cz++) {
                Placement p = placementFor(t, cx, cz);
                if (p == null || p.ox + p.w <= bx || p.ox >= bx + 16 || p.oz + p.d <= bz || p.oz >= bz + 16) continue;
                stamp(c, p, bx, bz);
            }
        }
    }

    private void stamp(Chunk c, Placement p, long bx, long bz) {
        StructureTemplate t = p.t;
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            int id = t.id(x, y, z);
            if (id < 0) continue;
            long wx = p.ox + t.rx(p.rot, x, z), wz = p.oz + t.rz(p.rot, x, z);
            int wy = p.oy + y;
            if (wx < bx || wx >= bx + 16 || wz < bz || wz >= bz + 16 || wy < 1 || wy > 254) continue;
            int lx = (int) (wx - bx), lz = (int) (wz - bz);
            c.set(lx, wy, lz, id);
            String loot = t.lootAt(x, y, z);
            if (loot != null) c.pending.add(new Chunk.Pending("chest", wx, wy, wz, loot, Noise.mix(p.seed ^ (wx * 31 + wy * 131 + wz * 17))));
            if (y == 0 && t.place == StructureTemplate.Place.SURFACE && id > 0) { // фундамент під нерівним місцем
                for (int fy = wy - 1; fy >= Math.max(1, wy - 8); fy--) {
                    int cur = c.get(lx, fy, lz);
                    if (cur > 0 && !isLiquid(cur)) break;
                    c.set(lx, fy, lz, t.foundation);
                }
            }
        }
    }

    private boolean isLiquid(int id) { return liquidIds != null && id < liquidIds.length && liquidIds[id]; }

    private boolean[] liquidIds;
    /** Які id - рідина (фундамент не вважає воду опорою). Виставляє генератор. */
    public StructurePlacer liquids(boolean[] ids) { this.liquidIds = ids; return this; }

    /** Найближча структура цього шаблона до (x,z): спіраль клітинок, до maxCells від клітинки точки. null - не знайдено. */
    public Placement locate(StructureTemplate t, long x, long z, int maxCells) {
        long cx = Math.floorDiv(x, (long) t.spacing), cz = Math.floorDiv(z, (long) t.spacing);
        Placement best = null; double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= maxCells; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                Placement p = placementFor(t, cx + dx, cz + dz);
                if (p == null) continue;
                double ddx = p.centerX() - x, ddz = p.centerZ() - z, d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < bestD) { bestD = d; best = p; }
            }
            if (best != null && bestD <= (double) r * t.spacing) return best; // далі кільця вже дальші за знайдене
        }
        return best;
    }
}
