package voxelin.core.gen;

import voxelin.core.BlockRegistry;
import voxelin.core.Chunk;
import voxelin.core.WorldInfo;

/**
 * Генератор світу етапу 2: суша/океан/гори з неперервного шуму, біом визначає поверхню й рослини. Біоми великі (поперек ~1-3 тис. блоків):
 * клімат (температура, вологість, «дивність», материк, гори) повільний, тому його рахуємо на рідкій сітці через 4 блоки й інтерполюємо -
 * це у кілька разів швидше й дає плавні переходи. Межі біомів не пряма лінія, а розсип: біом обирається з крихітним випадковим зсувом порогів
 * по колонках (дізеринг), тож на стику змішуються блоки обох біомів і переходить поступово.
 * Біоми: океан, пляж, рівнини, ліс, березовий гай, тайга, тундра, пустеля, гори, болото + «Скляні дюни» і «Тихі пустища».
 * Далі: печери («спагеті» й зали), руди жилами, дерева/кактуси/скляні шпилі/моноліти, вода до рівня моря; знизу - неоднорідна корінна порода.
 * Детерміновано за зерном і long-координатами (Noise); викликається з фонових потоків без спільного змінного стану. Дерева не перетинають
 * межу чанка (стовбури лише в x,z 3..12), тож чанки незалежні.
 */
public final class TerrainGenerator implements Generator {
    public static final int SEA = 62;
    /** Періоди (блоків) повільних шумів: чим більше, тим більші біоми. */
    static final int P_CONT = 2400, P_MOUNT = 1500, P_TEMP = 2800, P_HUM = 2300, P_WEIRD = 1700;
    /** Ширина розсипу на межах біомів у одиницях шуму (≈ десятки блоків). */
    static final double DITHER = 0.03;

    public enum Biome {
        OCEAN("Океан"), BEACH("Пляж"), PLAINS("Рівнини"), FOREST("Ліс"), BIRCH("Березовий гай"), TAIGA("Тайга"), TUNDRA("Тундра"), DESERT("Пустеля"),
        MOUNTAINS("Гори"), SWAMP("Болото"), GLASS_DUNES("Скляні дюни"), QUIET_WASTES("Тихі пустища");
        public final String title;
        Biome(String t) { title = t; }
    }

    private final long seed;
    private final WorldInfo.Type type;
    private final Noise nCont, nTemp, nHum, nWeird, nMount, nHill, nDetail, nPool, nFloor, nCave1, nCave2, nCave3;
    private final int air = 0, stone, deepStone, bedrock, grass, dirt, sand, sandstone, gravel, clay, snow, water, mud, paleGrass, sintered, duneGlass,
            oakLog, oakLeaves, birchLog, birchLeaves, spruceLog, spruceLeaves, paleLog, cactus, monolith,
            coal, iron, copper, gold, diamond, liminite;

    private final BlockRegistry reg;
    private final StructurePlacer placer;

    public TerrainGenerator(long seed, WorldInfo.Type type, BlockRegistry reg) { this(seed, type, reg, java.util.List.of()); }

    public TerrainGenerator(long seed, WorldInfo.Type type, BlockRegistry reg, java.util.List<StructureTemplate> structures) {
        this.seed = seed; this.type = type; this.reg = reg;
        nCont = new Noise(seed ^ 0x1111A5A5A5A5L); nTemp = new Noise(seed ^ 0x2222B4B4B4B4L); nHum = new Noise(seed ^ 0x3333C3C3C3C3L);
        nWeird = new Noise(seed ^ 0x4444D2D2D2D2L); nMount = new Noise(seed ^ 0x5555E1E1E1E1L); nHill = new Noise(seed ^ 0x6666F0F0F0F0L);
        nDetail = new Noise(seed ^ 0x77770F0F0F0FL); nPool = new Noise(seed ^ 0x88881E1E1E1EL); nFloor = new Noise(seed ^ 0xCCCC5A5A5A5AL);
        nCave1 = new Noise(seed ^ 0x99992D2D2D2DL); nCave2 = new Noise(seed ^ 0xAAAA3C3C3C3CL); nCave3 = new Noise(seed ^ 0xBBBB4B4B4B4BL);
        stone = id(reg, "stone"); deepStone = id(reg, "deep_stone"); bedrock = id(reg, "bedrock"); grass = id(reg, "grass"); dirt = id(reg, "dirt");
        sand = id(reg, "sand"); sandstone = id(reg, "sandstone"); gravel = id(reg, "gravel"); clay = id(reg, "clay"); snow = id(reg, "snow"); water = id(reg, "water");
        mud = id(reg, "mud"); paleGrass = id(reg, "pale_grass"); sintered = id(reg, "sintered_sand"); duneGlass = id(reg, "dune_glass");
        oakLog = id(reg, "oak_log"); oakLeaves = id(reg, "oak_leaves"); birchLog = id(reg, "birch_log"); birchLeaves = id(reg, "birch_leaves");
        spruceLog = id(reg, "spruce_log"); spruceLeaves = id(reg, "spruce_leaves"); paleLog = id(reg, "pale_log"); cactus = id(reg, "cactus"); monolith = id(reg, "monolith");
        if (structures.isEmpty()) placer = null;
        else {
            boolean[] liquid = new boolean[reg.size()];
            for (voxelin.core.BlockType b : reg.all()) liquid[b.id] = b.liquid;
            placer = new StructurePlacer(seed, structures, this::surfaceY, 62).liquids(liquid).pathBlock(gravel);
        }
        coal = id(reg, "coal_ore"); iron = id(reg, "iron_ore"); copper = id(reg, "copper_ore"); gold = id(reg, "gold_ore"); diamond = id(reg, "diamond_ore"); liminite = id(reg, "liminite_ore");
    }

    private static int id(BlockRegistry r, String n) {
        if (r.byName("voxelin:" + n) == null) throw new IllegalStateException("генератору потрібен блок voxelin:" + n + " (ванільний вміст не завантажено?)");
        return r.byName("voxelin:" + n).id;
    }

    // ------------------------------------------------------------------ клімат на рідкій сітці

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static double smooth(double e0, double e1, double v) { double t = clamp((v - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); }

    /** Повільні величини в точці сітки: материк, гори, температура, вологість, «дивність». */
    private static final class Clim { double c, m, t, hu, w; }

    /** Вузол сітки (gx,gz) = світова точка (4gx, 4gz). Однаковий результат для будь-якого чанка, що його використовує. */
    private Clim sample(long gx, long gz) {
        long x = gx * 4, z = gz * 4;
        Clim k = new Clim();
        k.c = nCont.fractal2(x, z, P_CONT, 3);
        k.m = nMount.fractal2(x, z, P_MOUNT, 2);
        k.t = nTemp.fractal2(x, z, P_TEMP, 2);
        k.hu = nHum.fractal2(x, z, P_HUM, 2);
        k.w = nWeird.fractal2(x, z, P_WEIRD, 2);
        return k;
    }

    /** Білінійна інтерполяція чотирьох вузлів; один порядок операцій і для чанка, і для окремої колонки - результати збігаються до біта. */
    private static void blend(Clim a, Clim b, Clim c, Clim d, double fx, double fz, Clim o) {
        double w00 = (1 - fx) * (1 - fz), w10 = fx * (1 - fz), w01 = (1 - fx) * fz, w11 = fx * fz;
        o.c = a.c * w00 + b.c * w10 + c.c * w01 + d.c * w11;
        o.m = a.m * w00 + b.m * w10 + c.m * w01 + d.m * w11;
        o.t = a.t * w00 + b.t * w10 + c.t * w01 + d.t * w11;
        o.hu = a.hu * w00 + b.hu * w10 + c.hu * w01 + d.hu * w11;
        o.w = a.w * w00 + b.w * w10 + c.w * w01 + d.w * w11;
    }

    /** Результат для колонки: фінальна висота поверхні й біом. */
    private static final class Col { int surf; Biome biome; }

    /** Дрібний випадковий зсув -1..1 по колонці (дізеринг на межах біомів; крок 2 блоки - зерниста, а не ґратчаста межа). */
    private double jitter(long x, long z) {
        long h = Noise.mix(Noise.mix(seed ^ 0xD17E5L) + (x >> 1) * 0x9E3779B97F4A7C15L ^ (z >> 1) * 0xC2B2AE3D27D4EB4FL);
        return ((h >>> 11) / (double) (1L << 53)) * 2 - 1;
    }

    private Col column(long x, long z, Clim k, Col out) {
        if (type == WorldInfo.Type.FLAT) { out.surf = 64; out.biome = Biome.PLAINS; return out; }
        double c = k.c + 0.10; // зсув: трохи більше суші, ніж моря
        double land = smooth(-0.04, 0.22, c);
        double m = clamp((k.m - 0.02) / 0.60, 0, 1);
        m = m * m * (3 - 2 * m);
        double hills = nHill.fractal2(x, z, 120, 3) * 7 * (0.4 + 0.6 * land) + nDetail.fractal2(x, z, 16, 2) * 1.6;
        double base = 66 + (c < 0 ? c * 62 : c * 45); // океани глибші за пагорби
        int h = (int) Math.round(clamp(base + m * land * 125 + hills, 6, 225));
        out.surf = h;
        if (h < SEA) { out.biome = Biome.OCEAN; return out; }
        if (h > 104) { out.biome = Biome.MOUNTAINS; return out; }
        double t = k.t + jitter(x, z) * DITHER, hu = k.hu + jitter(x + 7, z + 13) * DITHER, w = k.w + jitter(x + 29, z - 5) * DITHER;
        if (hu > 0.2 && h <= SEA + 3 && t > -0.2) { // болото: плоске, з калюжами
            out.biome = Biome.SWAMP;
            out.surf = nPool.fractal2(x, z, 24, 2) > 0.1 ? SEA - 2 : SEA;
            return out;
        }
        if (h <= SEA + 1 && c < 0.15) { out.biome = Biome.BEACH; return out; } // пляж лише біля узбережжя
        if (t < -0.25) out.biome = hu > 0.02 ? Biome.TAIGA : Biome.TUNDRA;
        else if (t > 0.2 && hu < 0.05) out.biome = w > 0.03 ? Biome.GLASS_DUNES : Biome.DESERT;
        else if (w < -0.24 && t > -0.12 && t < 0.3) out.biome = Biome.QUIET_WASTES;
        else if (hu > 0.09) out.biome = t > 0.08 ? Biome.BIRCH : Biome.FOREST;
        else out.biome = Biome.PLAINS;
        return out;
    }

    /** Колонка за довільними координатами (4 вузли сітки + локальні пагорби); те саме, що generate() рахує для чанка. */
    private Col columnAt(long x, long z) {
        long gx = Math.floorDiv(x, 4L), gz = Math.floorDiv(z, 4L);
        Clim o = new Clim();
        blend(sample(gx, gz), sample(gx + 1, gz), sample(gx, gz + 1), sample(gx + 1, gz + 1), Math.floorMod(x, 4L) / 4.0, Math.floorMod(z, 4L) / 4.0, o);
        return column(x, z, o, new Col());
    }

    @Override public int surfaceY(long x, long z) { return columnAt(x, z).surf; }
    @Override public String biomeAt(long x, long z) { return columnAt(x, z).biome.title; }
    @Override public int seaLevel() { return SEA; }

    @Override public java.util.List<String> structureNames() {
        java.util.List<String> r = new java.util.ArrayList<>();
        if (placer != null) { for (StructureTemplate t : placer.templates()) if (t.role == null) r.add(t.name); if (placer.hasVillages()) r.add("village"); }
        return r;
    }

    @Override public long[] locate(String name, long x, long z) {
        if (placer == null || name == null) return null;
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.equals("village") || n.equals("voxelin:village")) {
            long[] v = placer.locateVillage(x, z, 12);
            return v == null ? null : new long[]{v[0], surfaceY(v[0], v[1]) + 1, v[1], v[2]};
        }
        for (StructureTemplate t : placer.templates()) {
            if (t.role != null) continue;
            if (!t.name.equals(n) && !t.name.equals("voxelin:" + n)) continue;
            StructurePlacer.Placement p = placer.locate(t, x, z, 24);
            if (p == null) return null;
            double dx = p.centerX() - x, dz = p.centerZ() - z;
            return new long[]{p.centerX(), p.oy, p.centerZ(), Math.round(Math.sqrt(dx * dx + dz * dz))};
        }
        return null;
    }

    public Biome biome(long x, long z) { return columnAt(x, z).biome; }

    // ------------------------------------------------------------------ генерація чанка

    private static long hash(long a, long b, long c, long d) {
        return Noise.mix(Noise.mix(Noise.mix(Noise.mix(a) ^ b) ^ c) ^ d);
    }

    @Override public void generate(Chunk c) {
        final long bx = c.cx * 16, bz = c.cz * 16;
        final int[] surf = new int[256];
        final Biome[] biomes = new Biome[256];
        // клімат: 5x5 вузлів на чанк (спільні з сусідніми чанками)
        Clim[][] grid = new Clim[5][5];
        if (type != WorldInfo.Type.FLAT) for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) grid[i][j] = sample(c.cx * 4 + i, c.cz * 4 + j);
        Col col = new Col();
        Clim k = new Clim();
        int maxSurf = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            if (type != WorldInfo.Type.FLAT) { int i = x >> 2, j = z >> 2; blend(grid[i][j], grid[i + 1][j], grid[i][j + 1], grid[i + 1][j + 1], (x & 3) / 4.0, (z & 3) / 4.0, k); }
            column(bx + x, bz + z, k, col);
            surf[x + z * 16] = col.surf; biomes[x + z * 16] = col.biome;
            maxSurf = Math.max(maxSurf, col.surf);
        }
        // 1) шари поверхні, камінь, глибинний камінь
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int s = surf[x + z * 16];
            Biome b = biomes[x + z * 16];
            long hh = hash(seed, bx + x, bz + z, 1);
            int deepTop = 18 + (int) (hh & 7); // межа глибинного каменю нерівна
            int oceanTop = b == Biome.OCEAN ? floorMaterial(bx + x, bz + z) : 0;
            for (int y = 1; y <= s; y++) c.set(x, y, z, layer(b, s - y, y, hh, deepTop, oceanTop));
            for (int y = s + 1; y <= SEA; y++) c.set(x, y, z, water); // океани, озера, болотні калюжі
        }
        // 2) печери
        if (type != WorldInfo.Type.FLAT && maxSurf > 8) carve(c, bx, bz, surf, maxSurf);
        // 3) руди
        if (type != WorldInfo.Type.FLAT) ores(c, surf, biomes);
        // 4) дно світу: корінна порода неоднорідна - y=0 завжди, y=1 і y=2 «рваним» краєм (її не можна зламати)
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            long hb = hash(seed, bx + x, bz + z, 7);
            c.set(x, 0, z, bedrock);
            if (((hb >>> 8) & 1023) < 640) c.set(x, 1, z, bedrock);
            if (((hb >>> 20) & 1023) < 300) c.set(x, 2, z, bedrock);
        }
        // 5) рослини й споруди ландшафту
        for (int x = 3; x < 13; x++) for (int z = 3; z < 13; z++) feature(c, x, z, surf[x + z * 16], biomes[x + z * 16], hash(seed, bx + x, bz + z, 2));
        // 6) структури (руїни, підземелля, шахти): лише клітинки шаблонів, що лежать у цьому чанку
        if (placer != null) placer.apply(c, reg);
    }

    /** Дно океану: органічні плями піску, гравію й глини за шумом (а не квадрати). */
    private int floorMaterial(long x, long z) {
        double v = nFloor.fractal2(x, z, 56, 2);
        return v > 0.22 ? gravel : (v < -0.26 ? clay : sand);
    }

    private int layer(Biome b, int d, int y, long hh, int deepTop, int oceanTop) {
        int under = y < deepTop ? deepStone : stone;
        switch (b) {
            case DESERT: return d <= 4 ? sand : (d <= 8 ? sandstone : under);
            case GLASS_DUNES: return d <= 2 ? sintered : (d <= 7 ? sandstone : under);
            case BEACH: return d <= 3 ? sand : under;
            case OCEAN: return d <= 2 ? oceanTop : under;
            case SWAMP: return d <= 1 ? mud : (d <= 3 ? dirt : under);
            case MOUNTAINS: return d == 0 && y > 140 ? snow : (d <= 2 && y <= 140 && ((hh >>> 4) & 7) == 0 ? gravel : under);
            case TUNDRA: return d == 0 ? snow : (d <= 3 ? dirt : under);
            case QUIET_WASTES: return d == 0 ? paleGrass : (d <= 3 ? dirt : under);
            default: return d == 0 ? grass : (d <= 3 ? dirt : under); // рівнини, ліс, берези, тайга
        }
    }

    // ------------------------------------------------------------------ печери

    /**
     * «Спагеті» - перетин двох шумів поблизу нуля; великі зали - високе значення третього (лише глибоко). Шум рахується в вузлах сітки через 4 блоки
     * й інтерполюється. Поверхневий шар не чіпаємо, під морем - ще товстіше, щоб не затопити печеру.
     */
    private void carve(Chunk c, long bx, long bz, int[] surf, int maxSurf) {
        final int gy = Math.min(Chunk.HEIGHT, maxSurf + 1) / 4 + 2;
        final int hallGy = Math.min(gy, 52 / 4 + 3); // зали лише нижче y~52
        double[][][] a = new double[5][5][gy], b = new double[5][5][gy], k = new double[5][5][hallGy];
        for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) for (int y = 0; y < gy; y++) {
            long wx = bx + i * 4, wz = bz + j * 4, wy = y * 4;
            a[i][j][y] = nCave1.sample3(0, wx, wy, wz, 30);
            b[i][j][y] = nCave2.sample3(1, wx, wy, wz, 30);
            if (y < hallGy) k[i][j][y] = nCave3.sample3(2, wx, wy, wz, 44);
        }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int s = surf[x + z * 16];
            int top = s < SEA ? s - 4 : s - 1; // під океаном не прориватись до води
            for (int y = 3; y <= top; y++) {
                int i = x >> 2, j = z >> 2, yy = y >> 2;
                double fx = (x & 3) / 4.0, fz = (z & 3) / 4.0, fy = (y & 3) / 4.0;
                boolean cave = false;
                if (Math.abs(tri(a, i, j, yy, fx, fy, fz)) < 0.07) cave = Math.abs(tri(b, i, j, yy, fx, fy, fz)) < 0.07;
                if (!cave && y < 52 && yy + 1 < hallGy) cave = tri(k, i, j, yy, fx, fy, fz) > 0.5;
                if (cave && c.get(x, y, z) != water) c.set(x, y, z, air);
            }
        }
    }

    private static double tri(double[][][] g, int i, int j, int y, double fx, double fy, double fz) {
        double c00 = g[i][j][y] * (1 - fx) + g[i + 1][j][y] * fx, c10 = g[i][j + 1][y] * (1 - fx) + g[i + 1][j + 1][y] * fx;
        double c01 = g[i][j][y + 1] * (1 - fx) + g[i + 1][j][y + 1] * fx, c11 = g[i][j + 1][y + 1] * (1 - fx) + g[i + 1][j + 1][y + 1] * fx;
        double lo = c00 * (1 - fz) + c10 * fz, hi = c01 * (1 - fz) + c11 * fz;
        return lo * (1 - fy) + hi * fy;
    }

    // ------------------------------------------------------------------ руди

    private void ores(Chunk c, int[] surf, Biome[] biomes) {
        boolean wastes = biomes[8 + 8 * 16] == Biome.QUIET_WASTES;
        vein(c, 0, coal, 18, 4, 10, 5, 100, 1.0);
        vein(c, 1, iron, 12, 3, 8, 5, 70, 1.0);
        vein(c, 2, copper, 9, 4, 9, 20, 90, 1.0);
        vein(c, 3, gold, 4, 3, 6, 5, 34, 1.0);
        vein(c, 4, diamond, 3, 2, 5, 3, 16, 0.6);
        vein(c, 5, liminite, wastes ? 5 : 1, 2, 5, 8, wastes ? Math.min(90, surf[8 + 8 * 16]) : 48, wastes ? 1.0 : 0.4);
    }

    /** attempts спроб на чанк (з імовірністю chance кожна): центр у x,z 2..13, жила - блукання на size блоків, лише замість каменю. */
    private void vein(Chunk c, int oreIndex, int ore, int attempts, int sizeMin, int sizeMax, int minY, int maxY, double chance) {
        if (maxY <= minY) return;
        for (int at = 0; at < attempts; at++) {
            long h = hash(seed ^ 0x0E5EEDL, c.cx * 31 + oreIndex, c.cz, at);
            if (((h >>> 40) & 1023) / 1023.0 > chance) continue;
            int x = 2 + (int) ((h >>> 4) % 12), z = 2 + (int) ((h >>> 12) % 12), y = minY + (int) ((h >>> 20) % (maxY - minY + 1));
            int size = sizeMin + (int) ((h >>> 28) % (sizeMax - sizeMin + 1));
            long r = h;
            for (int i = 0; i < size; i++) {
                int cur = c.get(x, y, z);
                if (cur == stone || cur == deepStone) c.set(x, y, z, ore);
                r = Noise.mix(r + i);
                x = Math.max(0, Math.min(15, x + (int) (r & 3) - 1)); y = Math.max(1, Math.min(250, y + (int) ((r >>> 2) & 3) - 1)); z = Math.max(0, Math.min(15, z + (int) ((r >>> 4) & 3) - 1));
            }
        }
    }

    // ------------------------------------------------------------------ рослини й споруди ландшафту

    /** Імовірність на колонку -> поріг для roll (0..1023). Колонок для рослин 10x10 = 100 з 256, тож значення в таблиці вже враховують це. */
    private static int rate(double perColumn) { return (int) Math.round(perColumn * 1024); }

    private void feature(Chunk c, int x, int z, int s, Biome b, long h) {
        if (s <= SEA && b != Biome.SWAMP) return;
        if (s + 22 >= Chunk.HEIGHT) return;
        int roll = (int) ((h >>> 8) & 1023); // 0..1023
        int var = (int) ((h >>> 24) & 15);
        switch (b) {
            case PLAINS: if (roll < rate(0.0045)) tree(c, x, z, s, oakLog, oakLeaves, 5 + (var & 1), 2); break;
            case FOREST: if (roll < rate(0.040)) tree(c, x, z, s, oakLog, oakLeaves, 5 + (var % 4), 3); break;
            case BIRCH: if (roll < rate(0.030)) tree(c, x, z, s, birchLog, birchLeaves, 7 + (var % 4), 2); break;
            case SWAMP: if (roll < rate(0.012) && s >= SEA) tree(c, x, z, s, oakLog, oakLeaves, 5, 2); break;
            case TAIGA: if (roll < rate(0.036)) spruce(c, x, z, s, 8 + (var % 6)); break;
            case TUNDRA: if (roll < rate(0.004)) spruce(c, x, z, s, 6 + (var % 4)); break;
            case DESERT: if (roll < rate(0.008)) for (int i = 1; i <= 1 + (var % 4); i++) c.set(x, s + i, z, cactus); break;
            case GLASS_DUNES: if (roll < rate(0.006)) spire(c, x, z, s, 4 + (var % 8)); break;
            case QUIET_WASTES:
                if (roll < rate(0.008)) deadTree(c, x, z, s, 5 + (var % 5), var);
                else if (roll < rate(0.0125)) for (int i = 1; i <= 5 + (var % 5); i++) c.set(x, s + i, z, monolith);
                break;
            default: break;
        }
    }

    /** Листяне дерево: стовбур trunk, крона радіуса rad (2 або 3). */
    private void tree(Chunk c, int x, int z, int s, int log, int leaves, int trunk, int rad) {
        for (int y = 1; y <= trunk; y++) c.set(x, s + y, z, log);
        int crown = s + trunk;
        for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) for (int dy = -1; dy <= rad; dy++) {
            int r = Math.abs(dx) + Math.abs(dz) + Math.max(0, dy);
            if (r > rad + 1 || (dx == 0 && dz == 0 && dy <= 0)) continue;
            if (c.get(x + dx, crown + dy, z + dz) == air) c.set(x + dx, crown + dy, z + dz, leaves);
        }
    }

    private void spruce(Chunk c, int x, int z, int s, int trunk) {
        for (int y = 1; y <= trunk; y++) c.set(x, s + y, z, spruceLog);
        int top = s + trunk + 1;
        for (int dy = 0; dy < trunk - 1; dy++) {
            int y = top - dy, rad = Math.min(3, (dy + 2) / 2);
            if (dy % 2 == 1 && rad > 0) rad--; // «ялинкові» яруси: ширший, вужчий
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > rad + (rad > 1 ? 0 : 1) || (dx == 0 && dz == 0 && y <= s + trunk)) continue;
                if (c.get(x + dx, y, z + dz) == air) c.set(x + dx, y, z + dz, spruceLeaves);
            }
        }
    }

    private void deadTree(Chunk c, int x, int z, int s, int trunk, int var) {
        for (int y = 1; y <= trunk; y++) c.set(x, s + y, z, paleLog);
        int dx = (var & 1) == 0 ? 1 : -1, dz = (var & 2) == 0 ? 1 : -1; // дві кривих гілки без листя
        c.set(x + dx, s + trunk - 1, z, paleLog); c.set(x + dx, s + trunk, z, paleLog);
        c.set(x, s + trunk - 2, z + dz, paleLog); c.set(x, s + trunk - 1, z + dz, paleLog);
    }

    private void spire(Chunk c, int x, int z, int s, int height) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(x + dx, s + 1, z + dz, duneGlass);
        for (int y = 2; y <= height; y++) c.set(x, s + y, z, duneGlass);
    }
}
