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
    /** Назва біому колонки (нижній регістр) - для шаблонів із biomes:. */
    public interface BiomeAt { String name(long x, long z); }

    public static final class Placement {
        public final StructureTemplate t; public final long ox, oz; public final int oy, rot, w, d; public final long seed;
        Placement(StructureTemplate t, long ox, long oz, int oy, int rot, long seed) { this.t = t; this.ox = ox; this.oz = oz; this.oy = oy; this.rot = rot; this.w = t.width(rot); this.d = t.depth(rot); this.seed = seed; }
        public long centerX() { return ox + w / 2; }
        public long centerZ() { return oz + d / 2; }
    }

    /** Село: розкладка будівель навколо центру (колодязя) і стежки. Живе цілком усередині своєї клітинки VILLAGE_CELL x VILLAGE_CELL. */
    public static final class Village {
        public final long cx, cz;
        public final List<Placement> buildings;
        public final List<long[]> path;
        Village(long cx, long cz, List<Placement> b, List<long[]> path) { this.cx = cx; this.cz = cz; this.buildings = b; this.path = path; }
    }

    public static final int VILLAGE_CELL = 256, VILLAGE_MARGIN = 56;
    public static final double VILLAGE_CHANCE = 0.30;
    private static final int[] DOOR_X = {0, -1, 0, 1}, DOOR_Z = {1, 0, -1, 0}; // куди дивляться двері шаблона (+z) після повороту 0..3

    private final long seed;
    private int pathBlock = 1;
    private BiomeAt biomeAt;
    private final Map<String, List<StructureTemplate>> roles = new HashMap<>();
    private final Map<String, Optional<Village>> villageCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<Village>> e) { return size() > 96; }
    });

    private final List<StructureTemplate> templates;
    private final Surface surface;
    private final int sea;

    public StructurePlacer(long seed, List<StructureTemplate> templates, Surface surface, int sea) {
        this.seed = seed; this.templates = List.copyOf(templates); this.surface = surface; this.sea = sea;
        for (StructureTemplate t : this.templates) if (t.role != null) roles.computeIfAbsent(t.role, k -> new ArrayList<>()).add(t);
    }

    public List<StructureTemplate> templates() { return templates; }
    /** Id блока стежок у селах (гравій). */
    public StructurePlacer pathBlock(int id) { pathBlock = id; return this; }
    /** Підключити визначення біому (інакше шаблони з biomes: ніколи не ставляться). */
    public StructurePlacer biomes(BiomeAt b) { this.biomeAt = b; return this; }
    public boolean hasVillages() { return roles.containsKey("house"); }

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
        if (!t.biomes.isEmpty() && (biomeAt == null || !t.biomes.contains(biomeAt.name(wx + w / 2, wz + d / 2)))) return null;
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
            if (t.role != null) continue; // частини сіл ставить розкладка села
            int maxDim = Math.max(t.sx, t.sz);
            long x0 = Math.floorDiv(bx - maxDim + 1, (long) t.spacing), x1 = Math.floorDiv(bx + 15, (long) t.spacing);
            long z0 = Math.floorDiv(bz - maxDim + 1, (long) t.spacing), z1 = Math.floorDiv(bz + 15, (long) t.spacing);
            for (long cx = x0; cx <= x1; cx++) for (long cz = z0; cz <= z1; cz++) {
                Placement p = placementFor(t, cx, cz);
                if (p == null || p.ox + p.w <= bx || p.ox >= bx + 16 || p.oz + p.d <= bz || p.oz >= bz + 16) continue;
                stamp(c, p, bx, bz);
            }
        }
        applyVillages(c);
    }

    private void applyVillages(Chunk c) {
        if (!hasVillages()) return;
        final long bx = c.cx * 16, bz = c.cz * 16;
        long cx0 = Math.floorDiv(bx, (long) VILLAGE_CELL), cx1 = Math.floorDiv(bx + 15, (long) VILLAGE_CELL), cz0 = Math.floorDiv(bz, (long) VILLAGE_CELL), cz1 = Math.floorDiv(bz + 15, (long) VILLAGE_CELL);
        for (long cx = cx0; cx <= cx1; cx++) for (long cz = cz0; cz <= cz1; cz++) {
            Village v = village(cx, cz);
            if (v == null) continue;
            for (Placement p : v.buildings) if (!(p.ox + p.w <= bx || p.ox >= bx + 16 || p.oz + p.d <= bz || p.oz >= bz + 16)) stamp(c, p, bx, bz);
            for (long[] pc : v.path) {
                if (pc[0] < bx || pc[0] >= bx + 16 || pc[1] < bz || pc[1] >= bz + 16) continue;
                boolean inside = false;
                for (Placement p : v.buildings) if (pc[0] >= p.ox - 1 && pc[0] < p.ox + p.w + 1 && pc[1] >= p.oz - 1 && pc[1] < p.oz + p.d + 1 && p.t.role != null && !p.t.role.equals("lamp")) { inside = true; break; }
                if (inside) continue;
                int lx = (int) (pc[0] - bx), lz = (int) (pc[1] - bz), y = surface.surfaceY(pc[0], pc[1]);
                if (y <= sea || y < 2 || y > 253) continue;
                int cur = c.get(lx, y, lz);
                if (cur > 0 && !isLiquid(cur)) c.set(lx, y, lz, pathBlock);
            }
        }
    }

    private static boolean overlap(Placement a, long ox, long oz, int w, int d, int margin) {
        return !(a.ox + a.w + margin <= ox || a.ox >= ox + w + margin || a.oz + a.d + margin <= oz || a.oz >= oz + d + margin);
    }

    /** Село в клітинці (кешується) або null. */
    public Village village(long cellX, long cellZ) {
        String key = cellX + "," + cellZ;
        Optional<Village> o = villageCache.get(key);
        if (o == null) { o = Optional.ofNullable(computeVillage(cellX, cellZ)); villageCache.put(key, o); }
        return o.orElse(null);
    }

    private Placement buildingAt(StructureTemplate t, long cx, long cz, int rot, long sd, int maxVar) {
        int w = t.width(rot), d = t.depth(rot);
        long ox = cx - w / 2, oz = cz - d / 2;
        int[] ys = {surface.surfaceY(ox, oz), surface.surfaceY(ox + w - 1, oz), surface.surfaceY(ox, oz + d - 1), surface.surfaceY(ox + w - 1, oz + d - 1), surface.surfaceY(ox + w / 2, oz + d / 2)};
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y : ys) { min = Math.min(min, y); max = Math.max(max, y); }
        if (min <= sea + 1 || max - min > maxVar) return null;
        int oy = min + 1 - t.sink;
        if (oy < 2 || oy + t.sy > 254) return null;
        return new Placement(t, ox, oz, oy, rot, sd);
    }

    private StructureTemplate pick(String role, SplittableRandom r) {
        List<StructureTemplate> l = roles.get(role);
        if (l == null || l.isEmpty()) return null;
        int total = 0; for (StructureTemplate t : l) total += t.weight;
        int w = r.nextInt(total);
        for (StructureTemplate t : l) { if (w < t.weight) return t; w -= t.weight; }
        return l.get(l.size() - 1);
    }

    private Village computeVillage(long cellX, long cellZ) {
        if (!hasVillages()) return null;
        long h = Noise.mix(Noise.mix(seed ^ 0x71110EL) + cellX * 0xC2B2AE3D27D4EB4FL ^ (cellZ * 0x165667B19E3779F9L));
        if (((h >>> 11) / (double) (1L << 53)) >= VILLAGE_CHANCE) return null;
        SplittableRandom r = new SplittableRandom(h);
        long cx = cellX * VILLAGE_CELL + VILLAGE_MARGIN + r.nextInt(VILLAGE_CELL - 2 * VILLAGE_MARGIN), cz = cellZ * VILLAGE_CELL + VILLAGE_MARGIN + r.nextInt(VILLAGE_CELL - 2 * VILLAGE_MARGIN);
        int[] cy = {surface.surfaceY(cx, cz), surface.surfaceY(cx + 8, cz), surface.surfaceY(cx - 8, cz), surface.surfaceY(cx, cz + 8), surface.surfaceY(cx, cz - 8)};
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y : cy) { min = Math.min(min, y); max = Math.max(max, y); }
        if (min <= sea + 1 || max - min > 7) return null;
        List<Placement> bs = new ArrayList<>();
        StructureTemplate wellT = pick("well", r);
        if (wellT != null) { Placement w = buildingAt(wellT, cx, cz, 0, Noise.mix(h + 99), 4); if (w != null) bs.add(w); }
        int count = 4 + r.nextInt(5);
        List<Placement> doors = new ArrayList<>();
        int houses = 0;
        for (int attempt = 0; attempt < count * 4 && doors.size() < count; attempt++) {
            double ang = (attempt + r.nextDouble() * 0.6) * Math.PI * 2 / count, rad = 15 + r.nextDouble() * 13;
            long px = cx + Math.round(Math.cos(ang) * rad), pz = cz + Math.round(Math.sin(ang) * rad);
            String role = (attempt == 0 && roles.containsKey("hall") && r.nextInt(2) == 0) ? "hall" : (houses >= 3 && roles.containsKey("farm") && r.nextInt(4) == 0 ? "farm" : "house");
            StructureTemplate t = pick(role, r);
            if (t == null) continue;
            double tx = cx - px, tz = cz - pz;
            int rot = 0; double best = -1e9;
            for (int k = 0; k < 4; k++) { double dot = DOOR_X[k] * tx + DOOR_Z[k] * tz; if (dot > best) { best = dot; rot = k; } }
            Placement b = buildingAt(t, px, pz, rot, Noise.mix(h + 1000 + attempt), 3);
            if (b == null) continue;
            boolean clash = false;
            for (Placement o : bs) if (overlap(o, b.ox, b.oz, b.w, b.d, 2)) { clash = true; break; }
            if (clash) continue;
            bs.add(b); doors.add(b);
            if (!role.equals("farm")) houses++;
        }
        // стежки від дверей до колодязя + ліхтарі на півдорозі
        List<long[]> path = new ArrayList<>();
        StructureTemplate lampT = pick("lamp", r);
        for (Placement b : doors) {
            int dx = DOOR_X[b.rot], dz = DOOR_Z[b.rot], half = (dx == 0 ? b.d : b.w) / 2 + 1;
            long sx = b.ox + b.w / 2 + dx * half, sz = b.oz + b.d / 2 + dz * half;
            long x = sx, z = sz;
            List<long[]> mine = new ArrayList<>();
            while (x != cx) { mine.add(new long[]{x, z}); x += Long.signum(cx - x); }
            while (z != cz) { mine.add(new long[]{x, z}); z += Long.signum(cz - z); }
            path.addAll(mine);
            if (lampT != null && mine.size() > 8) {
                long[] m = mine.get((int) (mine.size() * 0.55));
                long lx = m[0] + (dx == 0 ? 2 : 0), lz = m[1] + (dz == 0 ? 2 : 0);
                Placement lp = buildingAt(lampT, lx, lz, 0, Noise.mix(h + 5000 + lx), 2);
                if (lp != null) { boolean clash = false; for (Placement o : bs) if (overlap(o, lp.ox, lp.oz, lp.w, lp.d, 1)) { clash = true; break; } if (!clash) bs.add(lp); }
            }
        }
        return new Village(cx, cz, List.copyOf(bs), path);
    }

    /** Найближче село до (x,z) (спіраль клітинок): {центр x, центр z, відстань} або null. */
    public long[] locateVillage(long x, long z, int maxCells) {
        long cx = Math.floorDiv(x, (long) VILLAGE_CELL), cz = Math.floorDiv(z, (long) VILLAGE_CELL);
        long[] best = null; double bestD = Double.MAX_VALUE;
        for (int r = 0; r <= maxCells; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                Village v = village(cx + dx, cz + dz);
                if (v == null) continue;
                double d = Math.hypot(v.cx - x, v.cz - z);
                if (d < bestD) { bestD = d; best = new long[]{v.cx, v.cz, Math.round(d)}; }
            }
            if (best != null && bestD <= (double) r * VILLAGE_CELL) return best;
        }
        return best;
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
            String mob = t.mobAt(x, y, z);
            if (mob != null) c.pending.add(new Chunk.Pending("mob", wx, wy, wz, mob, Noise.mix(p.seed ^ (wx * 31 + wy * 131 + wz * 17) ^ 0x30B5L)));
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
