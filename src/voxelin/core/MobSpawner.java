package voxelin.core;

import voxelin.core.entity.Mob;

import java.util.*;

/**
 * Природний спавн істот біля гравця. Вороги - у темряві (ефективне світло <= 7: небесне × яскравість неба, або блокове), далеко від гравця;
 * мирні - вдень на своїх блоках. Ліміти кількості в радіусі 64 блоків. Усе на long-координатах (працює й на 1e15).
 */
public final class MobSpawner {
    public static final int HOSTILE_CAP = 15, PASSIVE_CAP = 12, RADIUS = 64;
    private boolean primed;

    public void tick(World w, PlayerTarget p, Random rng, long tick) {
        if (p == null || !p.alive() || w.mobTypes.all().isEmpty()) return;
        if (!primed) { primed = true; for (int i = 0; i < 40; i++) attempt(w, p, rng, false); }
        if (tick % 20 == 0) { attempt(w, p, rng, true); attempt(w, p, rng, true); attempt(w, p, rng, false); }
    }

    /** Ліміт ворогів: у міжсвітті 6, удвічі більше, поки у світі зараження, і вдвічі менше після Ритуалу. */
    public static int hostileCap(World w) {
        if (w.info.type != WorldInfo.Type.BETWEEN) return HOSTILE_CAP;
        if (w.info.overInf == WorldInfo.InfState.ACTIVE) return 12;
        return w.info.ritualStage >= 2 ? 3 : 6;
    }

    private static int count(World w, Pos c, boolean hostile) {
        int n = 0;
        for (Mob m : w.mobs()) {
            if (m.dying || (m.type.hostile() || m.type.behavior == voxelin.api.MobSettings.Behavior.NEUTRAL_DAY) != hostile) continue;
            n++; // усі, хто є у світі: далекі однаково зникають, тож ліміт глобальний (інакше ті, що відійшли за радіус, відкривають місце новим)
        }
        return n;
    }

    /** Одна спроба; повертає, чи з'явилась істота. */
    public boolean attempt(World w, PlayerTarget p, Random rng, boolean hostile) {
        Pos pc = p.body();
        if (hostile) { if (count(w, pc, true) >= hostileCap(w)) return false; }
        else if (count(w, pc, false) >= PASSIVE_CAP || w.skyLevel() < 0.6) return false;
        List<MobType> pool = new ArrayList<>();
        for (MobType t : w.mobTypes.all()) if (!t.persistent && !t.boss && t.dimension.equals(w.dimension()) && (hostile ? (t.hostile() || t.behavior == voxelin.api.MobSettings.Behavior.NEUTRAL_DAY) : t.passive())) pool.add(t); // боси й постійні (дракон, Ткач, жителі) природно не з'являються
        if (pool.isEmpty()) return false;
        MobType type = pool.get(rng.nextInt(pool.size()));
        double ang = rng.nextDouble() * Math.PI * 2, dist = hostile ? 24 + rng.nextDouble() * 36 : 16 + rng.nextDouble() * 32;
        long bx = pc.blockX() + Math.round(Math.cos(ang) * dist), bz = pc.blockZ() + Math.round(Math.sin(ang) * dist);
        int n = hostile ? 1 : 1 + rng.nextInt(3), made = 0;
        for (int i = 0; i < n; i++) {
            long x = bx + (i == 0 ? 0 : rng.nextInt(5) - 2), z = bz + (i == 0 ? 0 : rng.nextInt(5) - 2);
            int y = findY(w, x, z, pc, hostile, rng);
            if (y < 0 || !suitable(w, type, x, y, z, hostile)) continue;
            if (w.spawnMob(type, Pos.ofBlock(x, y, z)) != null) made++;
        }
        return made > 0;
    }

    /** Висота, де можна стояти: знизу суцільна непрозора опора, над нею два вільні блоки; -1 - не знайдено / чанк не завантажено. */
    private int findY(World w, long x, long z, Pos pc, boolean hostile, Random rng) {
        if (w.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L)) == null) return -1;
        int top = hostile ? Math.max(2, Math.min(254, pc.blockY() + rng.nextInt(33) - 16)) : Math.min(254, w.surfaceY(x, z) + 4);
        int bottom = Math.max(1, top - (hostile ? 30 : 10));
        for (int y = top; y >= bottom; y--) {
            int below = w.blockId(x, y - 1, z), here = w.blockId(x, y, z), above = w.blockId(x, y + 1, z);
            if (below < 0 || here < 0 || above < 0) return -1;
            BlockType b = w.blocks.get(below);
            if (b.solid && b.opaque && here == 0 && above == 0) return y;
        }
        return -1;
    }

    /** Світло й опора підходять виду. */
    boolean suitable(World w, MobType t, long x, int y, long z, boolean hostile) {
        Chunk c = w.chunk(Math.floorDiv(x, 16L), Math.floorDiv(z, 16L));
        if (c == null) return false;
        int l = c.lightAt((int) Math.floorMod(x, 16L), y, (int) Math.floorMod(z, 16L)), sky = l >> 4, blk = l & 15;
        if (hostile) {
            int eff = Math.max(blk, (int) (sky * w.skyLevel()));
            if (eff > (w.info.type == WorldInfo.Type.BETWEEN ? 10 : w.info.type == WorldInfo.Type.NETHER ? 11 : 7)) return false; // у міжсвітті вічні сутінки - Відлуння з'являються й на поверхні островів
        } else {
            if (sky < 15) return false;
            if (!t.spawnOn.isEmpty() && !t.spawnOn.contains(w.blocks.get(w.blockId(x, y - 1, z)).name)) return false;
        }
        return true;
    }
}
