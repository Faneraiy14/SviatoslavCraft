import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Калькулятор балансу (не тест - звіт): проганяє справжній код гри (бій, броня, політ босів, генерація руд, видобуток, зараження)
 * і друкує таблиці. Запуск: ./build.sh balance
 */
public class VoxelinBalanceReport {
    static Game g;
    static final StringBuilder out = new StringBuilder();
    static void p(String s) { out.append(s).append('\n'); }

    static final String[] WEAPONS = {null, "voxelin:wooden_sword", "voxelin:stone_sword", "voxelin:iron_sword", "voxelin:diamond_sword", "voxelin:diamond_axe"};
    static final String[][] SETS = {{}, {"leather"}, {"iron"}, {"diamond"}};
    static final String[] SET_NAMES = {"без броні", "шкіра", "залізо", "алмаз"};

    static double weaponDamage(String w) { return w == null ? 1 : g.items.get(w).attack; }
    static String wname(String w) { return w == null ? "рука" : g.items.get(w).title; }
    static int setPoints(int k) {
        if (k == 0) return 0;
        int p = 0;
        for (String part : new String[]{"helmet", "chestplate", "leggings", "boots"}) p += g.items.get("voxelin:" + SETS[k][0] + "_" + part).armorPoints;
        return p;
    }

    /** Інтервал ударів гравця: кулдаун 0.4 с, але невразливість моба 10 тіків (0.5 с) - береться більше. */
    static final double HIT_INTERVAL = Math.max(0.4, Mob.INVULN / 20.0);
    static final double REGEN_PER_S = 20.0 / Vitals.REGEN_TICKS;

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-balance").toFile();
        g = new Game(new File(root, "saves"), null);
        p("# Звіт балансу Voxelin (" + java.time.LocalDate.now() + ")");
        p("Удар гравця: раз на " + HIT_INTERVAL + " с (кулдаун 0.4 с / невразливість моба 0.5 с). Відновлення здоров'я при голоді >= 18: " + REGEN_PER_S + " од/с.");
        melee();
        armor();
        bosses();
        mining();
        ores();
        endCost();
        infection();
        String text = out.toString();
        System.out.println(text);
        if (a.length > 0) Files.writeString(new File(a[0]).toPath(), text);
    }

    static void melee() {
        p("\n## 1. Скільки ударів і секунд, щоб убити");
        String[] mobs = {"chicken", "cow", "spider", "zombie", "ender_dragon", "seam_weaver"};
        StringBuilder h = new StringBuilder("| зброя (шкода) |");
        for (String m : mobs) h.append(' ').append(g.mobTypes.find(m).title).append(" (").append((int) g.mobTypes.find(m).health).append(") |");
        p(h.toString());
        p("|---" + "|---".repeat(mobs.length) + "|");
        for (String w : WEAPONS) {
            double d = weaponDamage(w);
            StringBuilder r = new StringBuilder("| " + wname(w) + " (" + fmt(d) + ") |");
            for (String m : mobs) { int hits = (int) Math.ceil(g.mobTypes.find(m).health / d); r.append(' ').append(hits).append(" / ").append(fmt((hits - 1) * HIT_INTERVAL)).append(" с |"); }
            p(r.toString());
        }
    }

    static void armor() {
        p("\n## 2. Броня проти наземних ворогів (шкода за удар, шкода/с, скільки секунд проживеш проти 1 / 3 ворогів з повним здоров'ям)");
        p("| броня (очки, захист) | зомбі: удар | зомбі: шкода/с | 1 зомбі | 3 зомбі | павук: удар | 3 павуки |");
        p("|---|---|---|---|---|---|---|");
        MobType z = g.mobTypes.find("zombie"), s = g.mobTypes.find("spider");
        for (int k = 0; k < 4; k++) {
            int pts = setPoints(k);
            double zd = Inventory.reduce(z.attackDamage, pts), zdps = zd / (z.cooldownTicks / 20.0);
            double sd = Inventory.reduce(s.attackDamage, pts), sdps = sd / (s.cooldownTicks / 20.0);
            p("| " + SET_NAMES[k] + " (" + pts + ", -" + (int) Math.round(100 * (1 - Inventory.reduce(1, pts))) + "%) | " + fmt(zd) + " | " + fmt(zdps) + " | " + survive(zdps) + " | " + survive(3 * zdps) + " | " + fmt(sd) + " | " + survive(3 * sdps) + " |");
        }
        p("(«безкінечно» - відновлення від їжі перекриває шкоду)");
    }

    static String survive(double dps) {
        double net = dps - REGEN_PER_S;
        return net <= 0 ? "безкінечно" : fmt(Vitals.MAX / net) + " с";
    }

    static final class Dummy implements PlayerTarget {
        Pos pos; int hits; double total; int pts;
        Dummy(Pos p, int pts) { pos = p; this.pts = pts; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return false; }
        public void hurt(double d, double kx, double kz) { hits++; total += Inventory.reduce(d, pts); }
    }

    static World end(String name) throws Exception {
        g.createWorld(name, 9, WorldInfo.Type.NORMAL);
        World w = g.openDimension(name, "end");
        for (int i = 0; i < 600; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        w.naturalSpawning = false;
        return w;
    }

    /** Живий політ боса 120 с над гравцем, що стоїть у центрі острова: скільки ударів і шкоди (з алмазною бронею). */
    static double[] flight(String id, boolean enraged) throws Exception {
        World w = end("b-" + id.replace(':', '_') + enraged);
        w.killAll(null);
        Dummy d = new Dummy(Pos.ofBlock(0, voxelin.core.gen.EndGenerator.TOP + 2, 0), setPoints(3));
        w.setPlayerTarget(d);
        Mob m = w.spawnMob(g.mobTypes.get(id), Pos.ofBlock(30, voxelin.core.gen.EndGenerator.TOP + 22, 0));
        m.home = Pos.ofBlock(0, voxelin.core.gen.EndGenerator.TOP, 0); m.keep = true; m.enraged = enraged;
        int secs = 120;
        for (int t = 0; t < secs * 20; t++) w.tick();
        w.close();
        return new double[]{d.hits, d.total, d.hits * 60.0 / secs};
    }

    static void bosses() throws Exception {
        p("\n## 3. Боси Краю (у повній алмазній броні - обов'язкова для входу; гравець стоїть у центрі 120 с)");
        p("| бос | здоров'я | шкода за удар (без броні -> в алмазі) | ударів за хв | шкода за хв | відновлення за хв | час убити алмазним мечем |");
        p("|---|---|---|---|---|---|---|");
        String[][] list = {{"voxelin:ender_dragon", "0"}, {"voxelin:seam_weaver", "0"}, {"voxelin:seam_weaver", "1"}};
        for (String[] b : list) {
            MobType t = g.mobTypes.get(b[0]);
            boolean en = b[1].equals("1");
            double[] r = flight(b[0], en);
            double raw = t.attackDamage * (en ? 1.5 : 1);
            int hits = (int) Math.ceil(t.health / weaponDamage("voxelin:diamond_sword"));
            p("| " + t.title + (en ? " (лютий)" : "") + " | " + (int) t.health + " | " + fmt(raw) + " -> " + fmt(Inventory.reduce(raw, setPoints(3))) + " | " + fmt(r[2]) + " | " + fmt(r[1] / 2) + " | " + fmt(REGEN_PER_S * 60) + " | " + hits + " ударів, якщо влучати щоразу, коли пролітає |");
        }
    }

    static void mining() {
        p("\n## 4. Час видобутку (с)");
        String[] blocks = {"stone", "iron_ore", "diamond_ore", "liminite_ore", "obsidian", "oak_log", "dirt"};
        String[] tools = {null, "voxelin:wooden_pickaxe", "voxelin:stone_pickaxe", "voxelin:iron_pickaxe", "voxelin:diamond_pickaxe"};
        StringBuilder h = new StringBuilder("| інструмент |");
        for (String b : blocks) h.append(' ').append(g.items.get("voxelin:" + b).title).append(" |");
        p(h.toString()); p("|---" + "|---".repeat(blocks.length) + "|");
        for (String t : tools) {
            ItemType it = t == null ? null : g.items.get(t);
            StringBuilder r = new StringBuilder("| " + (t == null ? "рука" : it.title) + " |");
            for (String b : blocks) {
                BlockType bt = g.blocks.byName("voxelin:" + b);
                r.append(' ').append(fmt(Mining.seconds(bt, it, false))).append(Mining.canHarvest(bt, it) ? "" : " ✗").append(" |");
            }
            p(r.toString());
        }
        p("(✗ - блок ламається, але нічого не випадає)");
    }

    static double diamondsPerChunk, liminitePerChunk;

    static void ores() throws Exception {
        p("\n## 5. Руди (середнє на чанк 16x16, звичайний світ, 3 зерна x 121 чанк)");
        String[] ores = {"coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore", "liminite_ore"};
        double[] sum = new double[ores.length];
        int chunks = 0;
        for (long seed : new long[]{1, 42, 2026}) {
            String name = "ores" + seed;
            g.createWorld(name, seed, WorldInfo.Type.NORMAL);
            World w = g.openWorld(name);
            w.naturalSpawning = false;
            for (int i = 0; i < 3000; i++) { w.update(0, 0, 6); boolean all = true; for (int dx = -5; dx <= 5 && all; dx++) for (int dz = -5; dz <= 5 && all; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
            int[] ids = new int[ores.length];
            for (int k = 0; k < ores.length; k++) ids[k] = g.blocks.byName("voxelin:" + ores[k]) == null ? -9 : g.blocks.byName("voxelin:" + ores[k]).id;
            for (int cx = -5; cx <= 5; cx++) for (int cz = -5; cz <= 5; cz++) {
                Chunk c = w.chunk(cx, cz);
                if (c == null) continue;
                chunks++;
                for (int y = 0; y < Chunk.HEIGHT; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    int id = c.get(x, y, z);
                    for (int k = 0; k < ores.length; k++) if (id == ids[k]) sum[k]++;
                }
            }
            w.close();
        }
        p("| руда | блоків на чанк |"); p("|---|---|");
        for (int k = 0; k < ores.length; k++) p("| " + g.items.get("voxelin:" + ores[k]).title + " | " + fmt(sum[k] / chunks) + " |");
        diamondsPerChunk = sum[4] / chunks; liminitePerChunk = sum[5] / chunks * 1.5; // лімініт дає 1-2 уламки
    }

    static void endCost() {
        p("\n## 6. Ціна шляху до Енду й розвилки");
        int armorD = 5 + 8 + 7 + 4, eyes = 12;
        int d = armorD + eyes + 3 + 2; // броня, очі, кирка, меч
        p("- Повна алмазна броня: " + armorD + " алмазів; 12 Очей Енду: 12 алмазів + 12 уламків лімініту + 12 золота; алмазна кирка 2 + меч 2 (за рецептами інструментів).");
        p("- Разом до Енду: ~" + d + " алмазів, 12 лімініту. За генерацією це ~" + fmt(d / Math.max(0.01, diamondsPerChunk)) + " чанків із вибраними алмазами і ~" + fmt(12 / Math.max(0.01, liminitePerChunk)) + " чанків для лімініту.");
        p("- Ритуал: 3 якорі = 3 алмази + 6 лімініту + 3 обсидіану. Зараження: вилікувати = 2 лімініту + золото + яблуко + скло; спалити = 1 лімініт + 3 вугілля + скло; приручити = 8 лімініту.");
    }

    static void infection() throws Exception {
        p("\n## 7. Зараження: скільки блоків наросту за день (гравець стоїть біля ядра весь день)");
        g.createWorld("inf", 7, WorldInfo.Type.NORMAL);
        World w = g.openWorld("inf");
        w.naturalSpawning = false;
        for (int i = 0; i < 3000; i++) { w.update(0, 0, 5); boolean all = true; for (int dx = -4; dx <= 4 && all; dx++) for (int dz = -4; dz <= 4 && all; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        w.info.eggChoice = WorldInfo.EggChoice.INFECTION;
        int y = 200; while (y > 0 && w.blockId(0, y, 0) <= 0) y--;
        w.setBlock(Sources.SYSTEM, 0, y + 1, 0, g.blocks.byName(Infection.EGG));
        long day = w.dayTicks();
        p("| день | фаза | спроб за день | нових блоків | усього |"); p("|---|---|---|---|---|");
        for (int dn = 0; dn < 32; dn++) {
            w.info.time = w.info.infStart + dn * day + 1;
            Infection.Phase ph = w.infection.phase();
            int interval = ph == Infection.Phase.QUIET ? 100 : ph == Infection.Phase.ACTIVE ? 40 : 12, batch = (ph == Infection.Phase.QUIET ? 1 : ph == Infection.Phase.ACTIVE ? 2 : 4) * (dn >= Infection.DAYS ? 2 : 1);
            long tries = day / interval * batch;
            int before = w.infection.growthCount();
            for (long k = 0; k < tries; k++) w.infection.spread(0, 0);
            if (dn % 3 == 0 || dn >= 28 || dn == 10 || dn == 20) p("| " + (dn + 1) + " | " + ph + " | " + tries + " | " + (w.infection.growthCount() - before) + " | " + w.infection.growthCount() + " |");
        }
        w.close();
        p("(межа наросту - " + Infection.CAP + " блоків; росте лише в " + Infection.RADIUS + " блоках від гравця)");
    }

    static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }
}
