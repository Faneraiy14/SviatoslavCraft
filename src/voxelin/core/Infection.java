package voxelin.core;

import voxelin.api.Source;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Зараження (гілка «Зараження» розвилки яйця). Яйце дракона, поставлене у звичайному світі після цього вибору, стає Ядром Шву (незламне),
 * і починається відлік 30 діб: Тиха пора (0-9 день), Активна (10-19), Криза (20+), після 30-го - прорив (удвічі швидше, зона 128).
 * Розростання - клітинний автомат у зоні навколо ядра, завжди, поки йде відлік (поки ділянка не завантажена, спроби накопичуються):
 * блок наросту поглинає сусідній суцільний блок; у темряві швидше, на світлі й біля води повільніше. Зупинити: вилікувати (Зілля Шву - наріст
 * стає землею, ядро знову яйцем), спалити (Вогонь Шву - кратер назавжди, яйце втрачено), приручити (8 уламків лімініту - розростання стоїть,
 * наріст лишається). Наріст пам'ятається у infection.dat, тож очищення доходить і до чанків, що завантажаться пізніше.
 */
public final class Infection {
    public enum Phase { QUIET, ACTIVE, CRISIS }

    public static final int DAYS = 30, TAME_COST = 8, RADIUS = 64, BREACH_RADIUS = 128, CAP = 100_000, CRATER = 5, BUDGET = 200;
    /** Скільки спроб може накопичитись, поки ділянка ядра не завантажена (≈ 10 діб кризи). */
    public static final double OWED_CAP = 400_000;
    public enum Step { GREW, FAILED, DEFERRED }
    public static final String EGG = "voxelin:dragon_egg", CORE = "voxelin:seam_core", GROWTH = "voxelin:seam_growth",
            CURE = "voxelin:seam_cure", FIRE = "voxelin:seam_fire", SHARD = "voxelin:liminite_shard", SCAR = "voxelin:seam_scar";
    private static final Source SRC = Sources.SYSTEM;
    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final World w;
    final List<long[]> growth = new ArrayList<>();
    private final Random rng;

    Infection(World w) { this.w = w; this.rng = new Random(w.info.seed ^ 0x5EA3L); load(); }

    public int growthCount() { return growth.size(); }

    /** Номер дня зараження від 0. */
    public long day() { return Math.max(0, w.info.time - w.info.infStart) / w.dayTicks(); }

    public Phase phase() { long d = day(); return d < 10 ? Phase.QUIET : d < 20 || mild() ? Phase.ACTIVE : Phase.CRISIS; }

    /** «Катаклізми: помірні» - зараження зупиняється на активній порі: без кризи й прориву, відлік не закінчується. */
    public boolean mild() { return w.info.cataclysms == WorldInfo.Cataclysms.MILD; }

    /** Прорив (30-й день минув); у помірних катаклізмах не настає. */
    public boolean breached() { return !mild() && day() >= DAYS; }

    /** Спроб росту за тік: тиха 1/100, активна 2/40, криза 4/12; після 30-го дня (прорив) - удвічі більше. */
    public double rate() {
        Phase p = phase();
        double r = p == Phase.QUIET ? 1 / 100.0 : p == Phase.ACTIVE ? 2 / 40.0 : 4 / 12.0;
        return breached() ? r * 2 : r;
    }

    /** Радіус зони від ядра: 64, після прориву (30-й день) - 128. */
    public int radius() { return breached() ? BREACH_RADIUS : RADIUS; }

    /** Поставлено яйце: чи це початок зараження (лише звичайний світ, вибір «Зараження», ще не почалось). */
    boolean shouldStart(BlockType placed) {
        WorldInfo i = w.info;
        return placed.name.equals(EGG) && i.type != WorldInfo.Type.END && i.eggChoice == WorldInfo.EggChoice.INFECTION && i.infState == WorldInfo.InfState.NONE
                && i.cataclysms != WorldInfo.Cataclysms.OFF;
    }

    void start(long x, int y, long z) {
        WorldInfo i = w.info;
        i.infState = WorldInfo.InfState.ACTIVE; i.infX = x; i.infY = y; i.infZ = z; i.infStart = i.time;
        growth.clear();
        w.unlockRecord("voxelin:infection_start");
    }

    public boolean isCore(long x, int y, long z) {
        WorldInfo i = w.info;
        return i.infState != WorldInfo.InfState.NONE && i.infX == x && i.infY == y && i.infZ == z;
    }

    /**
     * Тік світу. Зараження росте ЗАВЖДИ, поки йде відлік (рішення Святослава 21.10): спроби накопичуються щотіку (infOwed), а виконуються,
     * коли ділянка ядра завантажена - до BUDGET за тік, тож після повернення наріст «доростає» пропущене. Гравець для росту не потрібен.
     */
    void tick(long tick, PlayerTarget target) {
        WorldInfo i = w.info;
        if (i.infState == WorldInfo.InfState.CURED || i.infState == WorldInfo.InfState.BURNED) { if (!growth.isEmpty() && tick % 20 == 0) purge(); return; }
        if (i.infState != WorldInfo.InfState.ACTIVE) return;
        i.infOwed = Math.min(OWED_CAP, i.infOwed + rate());
        for (int b = 0; b < BUDGET && i.infOwed >= 1; b++) {
            if (step() == Step.DEFERRED) break; // ділянка не завантажена - чекаємо, борг лишається
            i.infOwed -= 1;
        }
    }

    private boolean inZone(long x, long z) { WorldInfo i = w.info; int r = radius(); return Math.abs(x - i.infX) <= r && Math.abs(z - i.infZ) <= r; }

    /** Одна спроба росту (для тестів і звіту). true - виросло. */
    public boolean spread() { return step() == Step.GREW; }

    /** Одна спроба: вибрати клітинку наросту (або ядро) й поглинути її сусіда в зоні ядра. DEFERRED - потрібний чанк не завантажено. */
    public Step step() {
        WorldInfo i = w.info;
        if (i.infState != WorldInfo.InfState.ACTIVE || growth.size() >= CAP) return Step.FAILED;
        BlockType core = w.blocks.byName(CORE), gt = w.blocks.byName(GROWTH);
        if (core == null || gt == null) return Step.FAILED;
        int coreId = w.blockId(i.infX, i.infY, i.infZ);
        if (coreId < 0) return Step.DEFERRED;
        long[] from = null;
        for (int t = 0; t < 12 && !growth.isEmpty(); t++) {
            int k = rng.nextInt(growth.size()); long[] g = growth.get(k);
            int id = w.blockId(g[0], (int) g[1], g[2]);
            if (id < 0) continue; // незавантажено - інша клітинка
            if (id != gt.id) { growth.set(k, growth.get(growth.size() - 1)); growth.remove(growth.size() - 1); continue; } // наріст зламали - забути
            from = g; break;
        }
        if ((from == null || rng.nextInt(4) == 0) && coreId == core.id) from = new long[]{i.infX, i.infY, i.infZ}; // ядро прибрали командою - з нього не росте
        if (from == null) return growth.isEmpty() ? Step.FAILED : Step.DEFERRED;
        int[] d = DIRS[rng.nextInt(6)];
        long x = from[0] + d[0], z = from[2] + d[2]; int y = (int) from[1] + d[1];
        if (!inZone(x, z)) return Step.FAILED;
        if (w.blockId(x, y, z) < 0) return Step.DEFERRED;
        if (!consumable(x, y, z)) return Step.FAILED;
        if (rng.nextDouble() >= chance(x, y, z)) return Step.FAILED;
        if (!w.setBlock(SRC, x, y, z, gt)) return Step.FAILED;
        growth.add(new long[]{x, y, z});
        return Step.GREW;
    }

    /** Чи може наріст поглинути блок: суцільний, ламкий, не рідина, не обсидіан і не сам Шов. */
    public boolean consumable(long x, int y, long z) {
        int id = w.blockId(x, y, z);
        if (id <= 0) return false; // повітря або незавантажено
        BlockType b = w.blocks.get(id);
        if (!b.solid || b.liquid || b.hardness < 0) return false;
        return !(b.name.equals(GROWTH) || b.name.equals(CORE) || b.name.equals(SCAR) || b.name.equals("voxelin:obsidian") || b.interact != null);
    }

    /** Шанс росту: темрява 1.0, світло (рівень > 7 поруч) 0.35; вода поруч - ще x0.25. */
    public double chance(long x, int y, long z) {
        int light = 0; boolean water = false;
        for (int[] d : DIRS) {
            long ax = x + d[0], az = z + d[2]; int ay = y + d[1];
            Chunk c = w.chunk(Math.floorDiv(ax, 16), Math.floorDiv(az, 16));
            if (c == null) continue;
            int l = c.lightAt((int) (ax & 15), ay, (int) (az & 15));
            light = Math.max(light, Math.max((int) Math.round(((l >> 4) & 15) * w.skyLevel()), l & 15));
            int id = w.blockId(ax, ay, az);
            if (id > 0 && w.blocks.get(id).liquid) water = true;
        }
        double c = light > 7 ? 0.35 : 1.0;
        return water ? c * 0.25 : c;
    }

    /** ПКМ по ядру. Повертає повідомлення (стан або результат дії) або null, якщо це не ядро. */
    public String use(Inventory inv, long x, int y, long z, boolean creative) {
        WorldInfo i = w.info;
        if (!isCore(x, y, z)) return null;
        if (i.infState == WorldInfo.InfState.TAMED) return "Ядро приручене: Шов спить.";
        if (i.infState != WorldInfo.InfState.ACTIVE) return null;
        Inventory.Stack st = inv.selectedStack();
        String held = st == null ? "" : st.item;
        if (held.equals(CURE)) { if (!creative) inv.takeSelected(); cure(); return "Зілля Шву подіяло: наріст в'яне, ядро знову стало яйцем."; }
        if (held.equals(FIRE)) { if (!creative) inv.takeSelected(); burn(); return "Вогонь Шву випалив ядро. Кратер лишиться назавжди."; }
        if (held.equals(SHARD)) {
            if (!creative && Trading.count(inv, w.itemTypes, SHARD) < TAME_COST) return "Щоб приручити ядро, потрібно " + TAME_COST + " уламків лімініту.";
            if (!creative) takeShards(inv, TAME_COST);
            tame(); return "Ядро прийняло лімініт і заснуло. Розростання зупинено.";
        }
        Phase p = phase();
        return "Ядро Шву: день " + (day() + 1) + (mild() ? "" : " з " + DAYS) + " (" + (p == Phase.QUIET ? "тиха пора" : p == Phase.ACTIVE ? "активна пора" : "криза") + "), наросту: " + growth.size()
                + ". Зупинити: Зілля Шву, Вогонь Шву або " + TAME_COST + " уламків лімініту.";
    }

    private static void takeShards(Inventory inv, int n) {
        for (int k = 0; k < Inventory.SIZE && n > 0; k++) {
            Inventory.Stack s = inv.get(k);
            if (s == null || !s.item.equals(SHARD)) continue;
            int m = Math.min(n, s.count); s.count -= m; n -= m;
            if (s.count <= 0) inv.set(k, null);
        }
    }

    void cure() {
        WorldInfo i = w.info;
        i.infState = WorldInfo.InfState.CURED;
        BlockType egg = w.blocks.byName(EGG);
        if (egg != null) w.setBlock(SRC, i.infX, i.infY, i.infZ, egg);
        purge();
        w.unlockRecord("voxelin:infection_end");
    }

    void burn() {
        WorldInfo i = w.info;
        i.infState = WorldInfo.InfState.BURNED;
        BlockType air = w.blocks.get(0), scar = w.blocks.byName(SCAR);
        for (int dx = -CRATER; dx <= CRATER; dx++) for (int dy = -CRATER; dy <= CRATER; dy++) for (int dz = -CRATER; dz <= CRATER; dz++) {
            int r2 = dx * dx + dy * dy + dz * dz;
            long x = i.infX + dx, z = i.infZ + dz; int y = i.infY + dy;
            int id = w.blockId(x, y, z);
            if (id < 0 || (id > 0 && w.blocks.get(id).hardness < 0 && !w.blocks.get(id).name.equals(CORE))) continue; // незламне (рамки, корінна порода) лишається
            if (r2 <= CRATER * CRATER) w.setBlock(SRC, x, y, z, air);
            else if (r2 <= (CRATER + 1) * (CRATER + 1) && id > 0 && scar != null) w.setBlock(SRC, x, y, z, scar); // обпалена оболонка кратера
        }
        purge();
        w.unlockRecord("voxelin:infection_end");
    }

    void tame() {
        w.info.infState = WorldInfo.InfState.TAMED;
        w.unlockRecord("voxelin:infection_end");
    }

    /** Прибрати наріст у завантажених чанках (вилікувано - стає землею, спалено - попелом-шрамом). Незавантажені - пізніше. */
    void purge() {
        BlockType to = w.blocks.byName(w.info.infState == WorldInfo.InfState.BURNED ? SCAR : "voxelin:dirt");
        if (to == null) to = w.blocks.get(0);
        int g = w.blocks.byName(GROWTH) == null ? -1 : w.blocks.byName(GROWTH).id;
        for (int k = growth.size() - 1; k >= 0; k--) {
            long[] p = growth.get(k);
            int id = w.blockId(p[0], (int) p[1], p[2]);
            if (id < 0) continue;
            if (id == g) w.setBlock(SRC, p[0], (int) p[1], p[2], to);
            growth.remove(k);
        }
    }

    // ------------------------------------------------------------------ збереження

    private File file() { return new File(w.dir, "infection.dat"); }

    void save() {
        if (growth.isEmpty() && !file().isFile()) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (long[] p : growth) sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append('\n');
            File tmp = new File(w.dir, "infection.dat.tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files2.replace(tmp, file());
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати зараження: " + e); }
    }

    private void load() {
        File f = file();
        if (!f.isFile()) return;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                if (p.length != 3 || growth.size() >= CAP) continue;
                try {
                    long x = Long.parseLong(p[0]), z = Long.parseLong(p[2]); int y = Integer.parseInt(p[1]);
                    if (y >= 0 && y < Chunk.HEIGHT) growth.add(new long[]{x, y, z});
                } catch (NumberFormatException e) { /* битий рядок */ }
            }
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось прочитати зараження: " + e); }
    }
}
