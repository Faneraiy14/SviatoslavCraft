import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;

/** Оборона сіл: Вартовий (будується Т із залізних блоків + лампа, б'є ворогів), вороги полюють на жителів, жителі тікають і вночі йдуть додому, криза зараження йде на село. */
public class VoxelinDefenseTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-defense").toFile(), "saves"), null);
        build(); fights(); night(); crisis();
        System.out.println("VoxelinDefenseTest: " + n + " перевірок OK");
    }

    static World open(String name, long seed) throws Exception {
        g.createWorld(name, seed, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        load(w, 0, 0);
        return w;
    }

    static void load(World w, long cx, long cz) throws Exception {
        for (int i = 0; i < 900; i++) { w.update(cx, cz, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }
    static BlockType b(World w, String n) { return w.blocks.byName(n); }
    static int count(World w, String type) { int c = 0; for (Mob m : w.mobs()) if (m.type.name.equals(type) && m.alive()) c++; return c; }
    static Mob spawn(World w, String type, long x, long z) { return w.spawnMob(w.mobTypes.find(type), Pos.ofBlock(x, surface(w, x, z) + 1, z)); }

    static void build() throws Exception {
        World w = open("b", 7);
        check(w.mobTypes.find(Guard.MOB) != null && w.mobTypes.find(Guard.MOB).guard() && b(w, Guard.IRON) != null, "вміст: Вартовий і залізний блок");
        int y = surface(w, 0, 0) + 1;
        BlockType iron = b(w, Guard.IRON), lamp = b(w, Guard.HEAD);
        // Т уздовж X, лампа - останньою
        w.setBlock(P, 0, y, 0, iron); w.setBlock(P, 0, y + 1, 0, iron); w.setBlock(P, -1, y + 1, 0, iron); w.setBlock(P, 1, y + 1, 0, iron);
        check(count(w, Guard.MOB) == 0, "без лампи - нічого");
        w.setBlock(P, 0, y + 2, 0, lamp);
        check(count(w, Guard.MOB) == 1, "Т + лампа = Вартовий");
        check(w.blockId(0, y, 0) == 0 && w.blockId(0, y + 2, 0) == 0 && w.blockId(1, y + 1, 0) == 0, "блоки забрано");
        // Т уздовж Z, лампа першою, останнім - рука
        w.setBlock(P, 6, y, 6, iron); w.setBlock(P, 6, y + 1, 6, iron); w.setBlock(P, 6, y + 2, 6, lamp); w.setBlock(P, 6, y + 1, 5, iron);
        check(count(w, Guard.MOB) == 1, "без другої руки - нічого");
        w.setBlock(P, 6, y + 1, 7, iron);
        check(count(w, Guard.MOB) == 2, "останній блок - рука: теж складається");
        // неправильна форма: руки по діагоналі
        w.setBlock(P, 12, y, 0, iron); w.setBlock(P, 12, y + 1, 0, iron); w.setBlock(P, 11, y + 1, 0, iron); w.setBlock(P, 12, y + 1, 1, iron); w.setBlock(P, 12, y + 2, 0, lamp);
        check(count(w, Guard.MOB) == 2 && w.blockId(12, y + 2, 0) == lamp.id, "кутова форма - не Вартовий");
        // рецепт
        Inventory.Stack[] grid = new Inventory.Stack[9];
        for (int k = 0; k < 9; k++) grid[k] = new Inventory.Stack("voxelin:iron_ingot", 1);
        Inventory.Stack r = Crafting.match(w.recipes, w.itemTypes, grid, 3);
        check(r != null && r.item.equals(Guard.IRON), "9 злитків = залізний блок");
        w.close();
    }

    static void fights() throws Exception {
        World w = open("f", 7);
        w.info.time = w.dayTicks() * 8 / 10; // ніч: зомбі не горять
        // Вартовий убиває зомбі
        Mob guard = spawn(w, Guard.MOB, 0, 0), z = spawn(w, "zombie", 6, 0);
        int t = 0;
        for (; t < 600 && z.alive(); t++) w.tick();
        check(!z.alive() && guard.alive(), "Вартовий убив зомбі за " + t + " тіків");
        check(guard.prey == null || !guard.prey.alive(), "ціль Вартового - лише вороги");
        // гравця не чіпає
        double before = guard.health;
        w.tick();
        check(guard.alive() && guard.health == before, "Вартовий спокійний без ворогів");
        // зомбі полює на жителя, житель тікає
        Mob v = spawn(w, "villager_farmer", 30, 30), z2 = spawn(w, "zombie", 36, 30);
        for (int k = 0; k < 20; k++) w.tick();
        check(z2.prey == v, "зомбі обрав жителя ціллю");
        double hv = v.health, d0 = Math.abs(v.pos.relX(0) - z2.pos.relX(0));
        boolean fled = false;
        for (int k = 0; k < 200; k++) { w.tick(); if (v.fleeTicks > 0) fled = true; }
        check(fled, "житель тікав від зомбі");
        for (int k = 0; k < 2000 && v.alive() && v.health == hv; k++) w.tick();
        check(v.health < hv || !v.alive(), "зомбі дістав жителя (без захисту)");
        w.close();
    }

    static void night() throws Exception {
        World w = open("n", 7);
        Mob v = spawn(w, "villager_farmer", 0, 0);
        v.home = Pos.ofBlock(0, v.pos.y, 0);
        v.pos.set(Pos.ofBlock(12, v.pos.y, 3));
        w.info.time = w.dayTicks() * 8 / 10; // ніч (фаза 0.7-0.95)
        double d0 = Math.hypot(v.pos.relX(0) - 0.5, v.pos.relZ(0) - 0.5);
        for (int k = 0; k < 400; k++) w.tick();
        double d1 = Math.hypot(v.pos.relX(0) - 0.5, v.pos.relZ(0) - 0.5);
        check(w.skyLevel() < 0.5, "справді ніч: " + w.skyLevel());
        check(d1 < 1.5 && d0 > 10, "вночі житель прийшов додому: " + d0 + " -> " + d1);
        for (int k = 0; k < 200; k++) w.tick();
        check(Math.hypot(v.pos.relX(0) - 0.5, v.pos.relZ(0) - 0.5) < 1.5, "і стоїть там");
        w.close();
    }

    static void crisis() throws Exception {
        g.createWorld("c", 31, WorldInfo.Type.FLAT);
        World w = g.openWorld("c");
        w.naturalSpawning = false;
        long[] v = w.locate("village", 0, 0);
        check(v != null, "є село");
        // ядро за ~150 блоків від села
        long ex = v[0] + 150, ez = v[2];
        load(w, Math.floorDiv(ex, 16L), Math.floorDiv(ez, 16L));
        w.info.eggChoice = WorldInfo.EggChoice.INFECTION;
        int y = surface(w, ex, ez) + 1;
        check(w.setBlock(P, ex, y, ez, b(w, Infection.EGG)) && w.info.infState == WorldInfo.InfState.ACTIVE, "ядро поставлено");
        w.infection.front();
        check(!w.info.infVillage, "до кризи на село не йде");
        w.info.time = w.info.infStart + w.dayTicks() * 21;
        w.infection.front();
        check(w.info.infVillage && Math.abs(w.info.infVx - v[0]) < 2 && Math.abs(w.info.infVz - v[2]) < 2, "криза: обрано найближче село");
        check(w.info.records.contains("voxelin:infection_village"), "запис «Шов іде на село»");
        check(!w.info.infFrontSeeded, "ділянка села не завантажена - наріст ще не посіяно");
        load(w, Math.floorDiv(v[0], 16L), Math.floorDiv(v[2], 16L));
        int before = w.infection.growthCount();
        w.infection.front();
        check(w.info.infFrontSeeded && w.infection.growthCount() == before + 1, "посіяно наріст на краю села");
        check(w.infection.affects(v[0], v[2]) && !w.infection.affects(v[0] - 80, v[2]), "село в зоні дії");
        int made = w.infection.raid();
        check(made > 0 && made <= Infection.RAID_MAX, "нічний напад: " + made);
        int more = 0;
        for (int k = 0; k < 5; k++) more += w.infection.raid();
        check(made + more <= Infection.RAID_MAX, "не більше " + Infection.RAID_MAX + " ворогів біля села: " + (made + more));
        w.close();
    }
}
