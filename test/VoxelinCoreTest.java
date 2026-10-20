import voxelin.api.*;
import voxelin.api.event.*;
import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;
import voxelin.core.gen.Noise;
import voxelin.core.gen.TerrainGenerator;
import voxelin.loader.*;
import voxelin.client.LocalActions;
import voxelin.client.PlayerActions;

import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Ядро Voxelin без вікна. Запуск: bash build.sh test (працює в тимчасовій теці). */
public class VoxelinCoreTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-test").toFile();
        sections();
        registry();
        commandsAndBudgets(root);
        storage(root);
        corruptRegion(root);
        generation(root);
        raycast();
        physics();
        pos();
        noise();
        farWorld(root);
        terrain();
        swimming();
        mods(root);
        inventory();
        actions(root);
        noLwjglInCore(new File(a.length > 0 ? a[0] : "out"));
        System.out.println("VoxelinCoreTest: " + n + " перевірок OK");
    }

    static void sections() {
        Section s = new Section();
        check(s.isEmpty() && s.get(3, 4, 5) == 0, "нова секція порожня");
        int v0 = s.version;
        s.set(3, 4, 5, 7); s.set(15, 15, 15, 2);
        check(s.get(3, 4, 5) == 7 && s.get(15, 15, 15) == 2 && s.nonAirCount() == 2 && s.version > v0, "set/get/лічильник/версія");
        s.set(3, 4, 5, 0);
        check(s.nonAirCount() == 1, "повітря зменшує лічильник");
        Chunk c = new Chunk(0, 0);
        check(c.section(5) == null, "порожня секція не створюється");
        c.set(1, 200, 1, 3);
        check(c.get(1, 200, 1) == 3 && c.section(200 >> 4) != null, "висота 256: блок на y=200");
        c.set(1, 300, 1, 3); c.set(1, -1, 1, 3);
        check(c.get(1, 300, 1) == 0 && c.get(1, -1, 1) == 0, "поза висотою ігнорується");
        check(!new ChunkPos(-1, 5).equals(new ChunkPos(5, -1)) && !new ChunkPos(-1, -1).equals(new ChunkPos(0, 0)), "ключі чанків не злипаються");
        java.util.Set<Integer> hashes = new java.util.HashSet<>();
        for (int x = -50; x <= 50; x++) for (int z = -50; z <= 50; z++) hashes.add(new ChunkPos(x, z).hashCode());
        check(hashes.size() > 10000, "хеш ChunkPos добре розподілений: " + hashes.size() + " із 10201");
    }

    static void registry() {
        Game g = new Game(new File("nope"), null);
        check(g.blocks.air().id == 0 && g.blocks.byName("voxelin:stone") != null, "ванільні блоки через API");
        check(g.blocks.isFrozen(), "реєстр закритий після запуску");
        boolean threw = false;
        try { g.blocks.register("x:y", new BlockSettings()); } catch (IllegalStateException e) { threw = true; }
        check(threw, "реєстрація після закриття заборонена");
        BlockRegistry r = new BlockRegistry();
        threw = false;
        try { r.register("Bad Name", new BlockSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "погана назва відхиляється");
        threw = false;
        r.register("m:a", new BlockSettings());
        try { r.register("m:a", new BlockSettings()); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "повтор назви відхиляється");
    }

    static World newWorld(Game g, String name, long seed) throws IOException {
        g.createWorld(name, seed, WorldInfo.Type.NORMAL);
        return g.openWorld(name);
    }

    static void loadAround(World w, int cx, int cz, int r) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            w.update(cx, cz, r);
            boolean all = true;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (dx * dx + dz * dz <= r * r + r && w.chunk(cx + dx, cz + dz) == null) all = false;
            if (all) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("чанки не завантажились");
    }

    static void commandsAndBudgets(File root) throws Exception {
        Game g = new Game(new File(root, "saves1"), null);
        World w = newWorld(g, "W", 1);
        loadAround(w, 0, 0, 1);
        Source p = Sources.player("Test");
        List<String> out = g.commands.execute(p, w, new long[]{0, 100, 0}, "/fill 0 100 0 9 104 9 stone");
        check(out.get(0).startsWith("Заповнено 500 з 500"), "fill 10x5x10: " + out);
        check(w.getBlock(9, 104, 9).name().equals("voxelin:stone"), "fill поставив блоки");
        out = g.commands.execute(p, w, new long[]{5, 100, 5}, "setblock ~ ~10 ~-2 glass");
        check(w.getBlock(5, 110, 3).name().equals("voxelin:glass"), "setblock з ~: " + out);
        out = g.commands.execute(p, w, null, "/fill 0 0 0 1000 255 1000 stone");
        check(out.get(0).contains("Забагато"), "fill має ліміт обсягу");
        out = g.commands.execute(p, w, null, "/nope");
        check(out.get(0).startsWith("Невідома"), "невідома команда");
        out = g.commands.execute(p, w, null, "/setblock 1 2 x stone");
        check(out.get(0).contains("Не число"), "погане число - повідомлення, а не виняток");
        Source weak = Sources.of("guest", Source.Kind.PLAYER, 0, 100);
        out = g.commands.execute(weak, w, null, "/setblock 0 120 0 stone");
        check(out.get(0).contains("прав"), "без прав /setblock заборонено");
        Source script = Sources.of("script", Source.Kind.SCRIPT, 4, 10);
        int ok = 0;
        for (int i = 0; i < 50; i++) if (w.setBlock(script, i % 16, 150, i / 16, g.blocks.byName("voxelin:dirt"))) ok++;
        check(ok == 10, "ліміт змін за тік для скрипта: " + ok);
        w.tick();
        check(w.setBlock(script, 0, 151, 0, g.blocks.byName("voxelin:dirt")), "новий тік - ліміт скинуто");
        // подія може скасувати зміну
        g.events.on("test", BlockChangeEvent.class, e -> { if (e.y == 160) e.cancel(); });
        check(!w.setBlock(p, 0, 160, 0, g.blocks.byName("voxelin:dirt")) && w.blockId(0, 160, 0) == 0, "BlockChangeEvent.cancel()");
        int[] fails = {0};
        g.events.on("bad", TickEvent.class, e -> { fails[0]++; throw new RuntimeException("бум"); });
        for (int i = 0; i < 6; i++) w.tick();
        check(fails[0] == 3, "зламаний слухач вимикається після 3 помилок: " + fails[0]);
        check(!w.setBlock(p, 100000, 50, 0, g.blocks.byName("voxelin:dirt")), "незавантажений чанк - false");
        w.close();
        w.close(); // повторно - без помилок
    }

    static void storage(File root) throws Exception {
        Game g = new Game(new File(root, "saves2"), null);
        World w = newWorld(g, "Save", 42);
        loadAround(w, 0, 0, 1);
        Source p = Sources.player("T");
        w.setBlock(p, 3, 200, 3, g.blocks.byName("voxelin:glass"));
        w.setBlock(p, -5, 70, -7, g.blocks.byName("voxelin:oak_planks"));
        int ground = w.blockId(8, 10, 8);
        w.close();
        File region = new File(root, "saves2/Save/region");
        check(region.isDirectory() && region.list().length >= 1, "регіони записано");
        check(Arrays.stream(region.list()).noneMatch(s -> s.endsWith(".tmp")), "тимчасових файлів не лишилось");
        World w2 = g.openWorld("Save");
        loadAround(w2, 0, 0, 1);
        check(w2.getBlock(3, 200, 3).name().equals("voxelin:glass") && w2.getBlock(-5, 70, -7).name().equals("voxelin:oak_planks"), "зміни збереглись");
        check(w2.blockId(8, 10, 8) == ground, "решта світу та сама");
        // вивантажити змінений чанк і одразу завантажити знову (гонка збереження)
        w2.setBlock(p, 1, 201, 1, g.blocks.byName("voxelin:stone"));
        w2.update(100, 100, 0);
        loadAround(w2, 0, 0, 1);
        check(w2.getBlock(1, 201, 1).name().equals("voxelin:stone"), "повторне завантаження під час збереження не губить змін");
        w2.close();
        // інший порядок блоків у новому запуску (мод додав блоки перед ванільними) - палітра за назвами
        WorldInfo info = WorldInfo.load(new File(root, "saves2/Save"));
        check(info.palette.contains("voxelin:glass") && info.palette.get(0).equals(BlockRegistry.AIR), "палітра назв у voxelin.world");
        BlockRegistry other = new BlockRegistry();
        other.register("zzz:first", new BlockSettings());
        new voxelin.content.VanillaContent(); // (лише щоб клас був у тесті)
        int[] p2rt = new int[info.palette.size()];
        for (int i = 0; i < p2rt.length; i++) { BlockType t = other.byName(info.palette.get(i)); p2rt[i] = t == null ? other.unknown().id : t.id; }
        Chunk c = new RegionStorage(new File(root, "saves2/Save")).load(0, 0, p2rt);
        check(c != null && other.get(c.get(3, 200, 3)).name.equals(BlockRegistry.UNKNOWN), "блок без мода -> voxelin:unknown, а не краш");
        // мод зник із палітри, але світ відкривається
        check(WorldInfo.isValidName("Мій світ 1") && !WorldInfo.isValidName("../x") && !WorldInfo.isValidName("a/b") && !WorldInfo.isValidName("") && !WorldInfo.isValidName("con:"), "назви світів");
        // старий світ v0.0.3 (без voxelin.world) не показується, але й не видаляється
        File old = new File(root, "saves2/world1"); old.mkdirs(); new File(old, "0_0.dat").createNewFile();
        check(!g.listWorlds().contains("world1") && g.listWorlds().contains("Save") && new File(old, "0_0.dat").exists(), "старі світи приховано, не видалено");
    }

    static void corruptRegion(File root) throws Exception {
        Game g = new Game(new File(root, "saves3"), null);
        World w = newWorld(g, "C", 5);
        loadAround(w, 0, 0, 0);
        w.setBlock(Sources.player("T"), 0, 200, 0, g.blocks.byName("voxelin:stone"));
        w.close();
        File rf = new File(root, "saves3/C/region/r.0.0.vxr");
        byte[] b = Files.readAllBytes(rf.toPath());
        Files.write(rf.toPath(), Arrays.copyOf(b, b.length / 2)); // обрізаний файл
        World w2 = g.openWorld("C");
        loadAround(w2, 0, 0, 0);
        check(w2.blockId(0, 200, 0) == 0 && w2.blockId(0, 0, 0) == g.blocks.byName("voxelin:bedrock").id, "битий регіон -> чанк згенеровано заново");
        String[] files = new File(root, "saves3/C/region").list();
        check(Arrays.stream(files).anyMatch(s -> s.contains(".corrupt-")), "битий файл відкладено, а не видалено");
        w2.close();
    }

    static void generation(File root) throws Exception {
        Game g = new Game(new File(root, "saves4"), null);
        World a = newWorld(g, "A", 777), b = newWorld(g, "B", 777), c = newWorld(g, "Cc", 778);
        loadAround(a, 3, -2, 1); loadAround(b, 3, -2, 1); loadAround(c, 3, -2, 1);
        boolean same = true, diff = false;
        for (int x = 40; x < 56; x++) for (int z = -40; z < -24; z++) for (int y = 0; y < 120; y++) {
            if (a.blockId(x, y, z) != b.blockId(x, y, z)) same = false;
            if (a.blockId(x, y, z) != c.blockId(x, y, z)) diff = true;
        }
        check(same, "одне зерно - той самий світ");
        check(diff, "інше зерно - інший світ");
        check(a.blockId(40, 0, -40) == g.blocks.byName("voxelin:bedrock").id, "корінна порода на y=0");
        int top = a.surfaceY(40, -40);
        check(top > 20 && top < 200 && a.blockId(40, top, -40) == g.blocks.byName("voxelin:grass").id, "трава на поверхні: " + top);
        a.close(); b.close(); c.close();
    }

    static final Raycast.Solid NOTHING = (x, y, z) -> false;

    static void raycast() {
        for (long base : new long[]{0, 1_000_000_000_000L, -4_000_000_000_000_000L}) { // однаково біля нуля й далеко
            final long bx = base;
            Raycast.Solid wall = (x, y, z) -> x == bx + 3 && y == 5 && z == bx;
            Raycast.Hit h = Raycast.cast(Pos.ofBlock(bx, 5.5, bx), 90, 0, 10, wall);
            check(h != null && h.x() == bx + 3 && h.px() == bx + 2, "промінь на схід влучає в стіну на " + base + ": " + h);
            Raycast.Solid corner = (x, y, z) -> (x == bx + 1 && y == 5 && z == bx) || (x == bx && y == 5 && z == bx - 1);
            Raycast.Hit d = Raycast.cast(Pos.ofBlock(bx, 5.5, bx), 45, 0, 10, corner);
            check(d != null && d.distance() < 2, "діагональний кут не проскочити (DDA) на " + base);
        }
        check(Raycast.cast(Pos.ofBlock(0, 5, 0), 0, 0, 3, NOTHING) == null, "порожньо - null");
    }

    /** Одна й та сама поведінка гравця на будь-якій відстані: падіння, стрибок, стіна, присід біля краю. */
    static void physics() {
        for (long base : new long[]{0, 7, -123_456, 1_000_000_000_000L, 1_000_000_000_000_000L, -100_000_000_000_000_000L}) {
            final long bx = base;
            PlayerEntity.Solid floor = (x, y, z) -> y <= 0;
            PlayerEntity p = new PlayerEntity(floor, Pos.ofBlock(bx, 40, bx));
            for (int i = 0; i < 200; i++) p.tick(0.05, false, false, false, false, false, false, false);
            double feet = p.pos.y - PlayerEntity.EYE;
            check(p.onGround() && Math.abs(feet - 1.0) < 0.2, "падіння з висоти на " + base + ": ноги на " + feet);
            check(p.pos.blockX() == bx && p.pos.blockZ() == bx, "стоячи не зсувається на " + base + ": " + p.pos);
            double y0 = p.pos.y;
            p.tick(0.05, false, false, false, false, true, false, false);
            for (int i = 0; i < 3; i++) p.tick(0.05, false, false, false, false, false, false, false);
            check(p.pos.y > y0, "стрибок на " + base);
            // стіна на x = bx + 5
            PlayerEntity w = new PlayerEntity((x, y, z) -> y <= 0 || x == bx + 5, Pos.ofBlock(bx, 1 + PlayerEntity.EYE + 0.01, bx));
            for (int i = 0; i < 200; i++) w.tick(0.05, false, false, false, true, false, true, false);
            check(w.pos.blockX() < bx + 5 && w.pos.blockX() >= bx + 3, "стіна зупиняє рух на " + base + ": x=" + (w.pos.blockX() - bx));
            // присід не дає впасти з краю (підлога лише при x <= bx)
            PlayerEntity e = new PlayerEntity((x, y, z) -> y <= 0 && x <= bx, Pos.ofBlock(bx, 1 + PlayerEntity.EYE + 0.01, bx));
            for (int i = 0; i < 5; i++) e.tick(0.05, false, false, false, false, false, false, false);
            for (int i = 0; i < 100; i++) e.tick(0.05, false, false, false, true, false, false, true);
            check(e.onGround() && e.pos.blockX() <= bx + 1, "присід не дає впасти з краю на " + base + ": x=" + (e.pos.blockX() - bx));
            // біг через межу чанків: позиція нормалізується, чанк змінюється плавно
            PlayerEntity r = new PlayerEntity(floor, Pos.ofBlock(bx, 1 + PlayerEntity.EYE + 0.01, bx));
            long c0 = r.pos.cx;
            for (int i = 0; i < 200; i++) r.tick(0.05, true, false, false, false, false, true, false);
            check(r.pos.x >= 0 && r.pos.x < 16 && r.pos.z >= 0 && r.pos.z < 16, "локальні координати в межах чанка на " + base);
            double moved = Math.hypot(r.pos.relX(Math.floorDiv(bx, 16L)) - Pos.ofBlock(bx, 0, bx).relX(Math.floorDiv(bx, 16L)), r.pos.relZ(Math.floorDiv(bx, 16L)) - Pos.ofBlock(bx, 0, bx).relZ(Math.floorDiv(bx, 16L)));
            check(moved > 20 && moved < 120, "біг за 10 с пройшов розумну відстань на " + base + ": " + moved);
            check(new PlayerEntity(floor, Pos.ofBlock(bx, 1 + PlayerEntity.EYE, bx)).overlapsBlock(bx, 1, bx), "не замурувати себе блоком на " + base);
        }
    }

    static void pos() {
        Pos p = new Pos(0, 0, -0.5, 10, 16.25);
        check(p.cx == -1 && p.cz == 1 && Math.abs(p.x - 15.5) < 1e-12 && Math.abs(p.z - 0.25) < 1e-12, "normalize переносить у сусідні чанки: " + p);
        Pos q = Pos.ofBlock(-1, 5, -17);
        check(q.cx == -1 && q.cz == -2 && q.blockX() == -1 && q.blockZ() == -17, "ofBlock/blockX для від'ємних: " + q);
        for (long b : new long[]{Long.MAX_VALUE / 2, Long.MIN_VALUE / 2, 1_000_000_000_000_000_000L, -1_000_000_000_000_000_000L}) {
            Pos z = Pos.ofBlock(b, 70, b);
            double fx = Math.floorMod(b, 16L) + 0.5;
            check(z.blockX() == b && z.blockZ() == b && z.x == fx && z.z == fx, "Pos точний на " + b + ": " + z);
            z.add(0.25, 0, -0.25);
            check(z.blockX() == b && z.blockZ() == b && Math.abs(z.x - (fx + 0.25)) < 1e-12 && Math.abs(z.z - (fx - 0.25)) < 1e-12, "малий зсув на " + b + " не втрачається (у float/double від повної координати було б 0): " + z);
        }
        check(Pos.ofBlock(100, 0, 100).relX(0) == 100.5 && Pos.ofBlock(-1, 0, 0).relX(0) == -0.5, "relX");
    }

    static void noise() {
        Noise n = new Noise(1234);
        long[] bases = {0, 1_000, 1_000_000, 1_000_000_000L, 1_000_000_000_000L, 1_000_000_000_000_000L, 1_000_000_000_000_000_000L, -1_000_000_000_000_000_000L, Long.MAX_VALUE / 4};
        for (long b : bases) {
            double prev = n.fractal2(b, b, 96, 4), maxStep = 0, min = 9, max = -9;
            for (int i = 1; i <= 400; i++) {
                double v = n.fractal2(b + i, b - i, 96, 4);
                maxStep = Math.max(maxStep, Math.abs(v - prev)); prev = v; min = Math.min(min, v); max = Math.max(max, v);
                check(v > -1.5 && v < 1.5 && !Double.isNaN(v), "значення в діапазоні на " + b);
            }
            check(maxStep < 0.08, "шум плавний (немає стрибків від втрати точності) на " + b + ": крок " + maxStep);
            check(max - min > 0.05 || b == 0, "шум не плаский на " + b + ": " + (max - min));
        }
        // однаковий результат при повторі, інше зерно - інший
        check(n.fractal2(123456789012345L, -987654321098765L, 96, 4) == n.fractal2(123456789012345L, -987654321098765L, 96, 4), "детермінований");
        check(n.fractal2(5000, 7000, 96, 4) != new Noise(1235).fractal2(5000, 7000, 96, 4), "інше зерно - інше значення");
        // РЕГРЕСІЯ: старий шум повторювався кожні 256*період блоків. Тут не має бути жодного точного збігу зі зсувом на 24576/12288/98304
        for (long shift : new long[]{24_576, 12_288, 98_304, 65_536, 256 * 24}) {
            int same = 0;
            for (int i = 0; i < 300; i++) { long x = i * 37L + 11, z = i * 53L - 400; if (Math.abs(n.fractal2(x, z, 96, 4) - n.fractal2(x + shift, z, 96, 4)) < 1e-12) same++; }
            check(same == 0, "шум не повторюється зі зсувом " + shift + ": збігів " + same);
        }
        // 3D шум теж плавний і точний далеко
        for (long b : new long[]{0, 1_000_000_000_000_000_000L}) {
            double prev = n.sample3(0, b, 64, b, 16), maxStep = 0;
            for (int i = 1; i <= 200; i++) { double v = n.sample3(0, b + i, 64 + i / 4, b, 16); maxStep = Math.max(maxStep, Math.abs(v - prev)); prev = v; }
            check(maxStep < 0.2, "3D шум плавний на " + b + ": " + maxStep);
        }
        // від'ємні й додатні координати поруч нуля не мають шва
        double a = n.sample2(0, -1, -1, 96), c = n.sample2(0, 0, 0, 96);
        check(Math.abs(a - c) < 0.1, "нема шва на нулі: " + a + " / " + c);
    }

    /** Світ на гігантських координатах: генерація, зміни, збереження й повторне завантаження, регіони. */
    static void farWorld(File root) throws Exception {
        for (long bx : new long[]{1_000_000_000_000L, 1_000_000_000_000_000_000L, -1_000_000_000_000_000_000L}) {
            Game g = new Game(new File(root, "saves-far"), null);
            String name = "Far" + (bx > 0 ? "P" : "N") + Math.abs(bx);
            g.createWorld(name, 99, WorldInfo.Type.NORMAL);
            World w = g.openWorld(name);
            long cx = Math.floorDiv(bx, 16L);
            for (int i = 0; i < 600; i++) { w.update(cx, cx, 1); boolean all = true; for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.chunk(cx + dx, cx + dz) == null) all = false; if (all) break; Thread.sleep(10); }
            check(w.chunk(cx, cx) != null, "чанк на " + bx + " згенеровано");
            int top = w.surfaceY(bx, bx);
            String surfBlock = w.getBlock(bx, top, bx).name();
            check(!surfBlock.equals("voxelin:air") && !surfBlock.equals("voxelin:water") && w.getBlock(bx, 0, bx).name().equals("voxelin:bedrock"), "рельєф на " + bx + ": висота " + top + ", поверхня " + surfBlock);
            check(top >= 6 && top <= 225, "розумна висота на " + bx + ": " + top);
            Source p = Sources.player("T");
            check(w.setBlock(p, bx + 3, 200, bx + 5, g.blocks.byName("voxelin:glass")), "setBlock на " + bx);
            List<String> out = g.commands.execute(p, w, new long[]{bx, 150, bx}, "/fill ~ ~ ~ ~4 ~2 ~4 stone");
            check(out.get(0).startsWith("Заповнено 75 з 75"), "/fill на " + bx + ": " + out);
            check(w.getBlock(bx + 4, 152, bx + 4).name().equals("voxelin:stone"), "fill поставив блок на " + bx);
            out = g.commands.execute(p, w, new long[]{bx, 150, bx}, "/fill " + bx + " 0 " + bx + " " + (bx + 600) + " 255 " + (bx + 600) + " stone");
            check(out.get(0).contains("Забагато"), "ліміт /fill діє й на " + bx + ": " + out.get(0));
            out = g.commands.execute(p, w, new long[]{bx, 150, bx}, "/fill " + Long.MIN_VALUE + " 0 " + Long.MIN_VALUE + " " + Long.MAX_VALUE + " 255 " + Long.MAX_VALUE + " stone");
            check(out.get(0).contains("Забагато"), "проміжок більший за long не переповнюється: " + out.get(0));
            out = g.commands.execute(p, w, new long[]{bx, 150, bx}, "/setblock " + (bx + 1) + " 160 " + (bx + 1) + " dirt");
            check(out.get(0).startsWith("Поставлено"), "setblock з великими числами: " + out);
            w.info.setPlayer(new Pos(cx, cx, 3.5, 120, 7.25), 33, -12);
            w.close();
            // повторне відкриття: зміни й позиція збереглись
            World w2 = g.openWorld(name);
            check(w2.info.hasPlayer && w2.info.playerCx == cx && w2.info.playerCz == cx && w2.info.playerX == 3.5 && w2.info.playerY == 120 && w2.info.playerZ == 7.25, "позиція гравця на " + bx + " збережена точно");
            for (int i = 0; i < 600 && w2.chunk(cx, cx) == null; i++) { w2.update(cx, cx, 0); Thread.sleep(10); }
            check(w2.getBlock(bx + 3, 200, bx + 5).name().equals("voxelin:glass") && w2.getBlock(bx + 4, 152, bx + 4).name().equals("voxelin:stone"), "зміни на " + bx + " збереглись у регіоні");
            w2.close();
            String[] files = new File(root, "saves-far/" + name + "/region").list();
            check(files != null && files.length >= 1 && Arrays.stream(files).anyMatch(f -> f.startsWith("r.") && f.endsWith(".vxr")), "регіонні файли з long-іменами: " + Arrays.toString(files));
        }
        // математичний край long: чанки за ним не створюються й не переповнюються
        check(World.inRange(0, 0) && World.inRange(World.MAX_CHUNK, -World.MAX_CHUNK) && !World.inRange(World.MAX_CHUNK + 1, 0) && !World.inRange(0, -World.MAX_CHUNK - 1), "край діапазону long");
        check(World.MAX_CHUNK * 16 > 4_000_000_000_000_000_000L, "край світу > 4 квінтильйонів блоків: " + World.MAX_CHUNK * 16);
        // старий формат світу відхиляється з поясненням, файли не чіпаються
        File oldDir = new File(root, "saves-far/OldFmt"); oldDir.mkdirs();
        try (Writer wr = new FileWriter(new File(oldDir, "voxelin.world"))) { wr.write("format=1\nname=OldFmt\nseed=1\ntype=NORMAL\ncreated=0\npalette=voxelin:air\n"); }
        boolean threw = false; String msg = "";
        try { WorldInfo.load(oldDir); } catch (IOException e) { threw = true; msg = e.getMessage(); }
        check(threw && msg.contains("формат") && new File(oldDir, "voxelin.world").exists(), "світ формату 1 відхилено з поясненням, файл на місці: " + msg);
    }

    static void mods(File root) throws Exception {
        File modsDir = new File(root, "mods");
        modsDir.mkdirs();
        String api = System.getProperty("java.class.path");
        jar(modsDir, "good.jar", "good", "GoodMod", "2",
                "public class GoodMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) {"
                + " c.registries().block(\"ruby\", new voxelin.api.BlockSettings().color(0xFF0000).light(7));"
                + " c.commands().register(\"hello\", \"\", 0, k -> k.reply(\"hi \" + k.source().name())); } }", api);
        jar(modsDir, "broken.jar", "broken", "BrokenMod", "2",
                "public class BrokenMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) { throw new RuntimeException(\"зламано\"); } }", api);
        jar(modsDir, "future.jar", "future", "FutureMod", "999",
                "public class FutureMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) {} }", api);
        jar(modsDir, "thief.jar", "thief", "ThiefMod", "2",
                "public class ThiefMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) { c.registries().block(\"voxelin:diamond\", new voxelin.api.BlockSettings()); } }", api);
        jar(modsDir, "old.jar", "oldapi", "OldMod", "1",
                "public class OldMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) {} }", api);
        Files.write(new File(modsDir, "notamod.jar").toPath(), new byte[]{1, 2, 3});
        Game g = new Game(new File(root, "saves5"), modsDir);
        Map<String, ModLoader.Status> st = new HashMap<>();
        for (ModLoader.ModInfo m : g.mods.mods()) st.put(m.id, m.status);
        check(st.get("good") == ModLoader.Status.OK && g.blocks.byName("good:ruby") != null && g.blocks.byName("good:ruby").light == 7, "добрий мод додав блок");
        check(st.get("broken") == ModLoader.Status.FAILED, "мод із винятком позначено, гра стартувала");
        check(st.get("future") == ModLoader.Status.INCOMPATIBLE, "мод з новішим API не вантажиться");
        check(st.get("oldapi") == ModLoader.Status.INCOMPATIBLE && g.blocks.byName("oldapi:x") == null, "мод зі старим API v1 (координати int) відхилено");
        check(st.get("thief") == ModLoader.Status.FAILED && g.blocks.byName("voxelin:diamond") == null, "мод не може реєструвати чужі назви");
        check(st.get("notamod.jar") == ModLoader.Status.FAILED, "битий jar не валить гру");
        World w = newWorld(g, "M", 1);
        loadAround(w, 0, 0, 0);
        check(g.commands.execute(Sources.player("Ann"), w, null, "/hello").get(0).equals("hi Ann"), "команда мода працює");
        check(w.setBlock(Sources.player("Ann"), 0, 150, 0, g.blocks.byName("good:ruby")) && w.getBlock(0, 150, 0).name().equals("good:ruby"), "блок мода ставиться");
        w.close();
    }

    static void jar(File dir, String file, String id, String cls, String apiV, String src, String cp) throws Exception {
        File work = Files.createTempDirectory("vxmod").toFile();
        File java = new File(work, cls + ".java");
        Files.write(java.toPath(), src.getBytes("UTF-8"));
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-encoding", "UTF-8", "-cp", cp, "-d", work.getPath(), java.getPath());
        if (rc != 0) throw new IllegalStateException("тестовий мод не скомпілювався");
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Voxelin-Mod", cls);
        m.getMainAttributes().putValue("Voxelin-Mod-Id", id);
        m.getMainAttributes().putValue("Voxelin-Api", apiV);
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(new File(dir, file)), m)) {
            out.putNextEntry(new JarEntry(cls + ".class"));
            out.write(Files.readAllBytes(new File(work, cls + ".class").toPath()));
        }
    }

    static void actions(File root) throws Exception {
        Game g = new Game(new File(root, "saves6"), null);
        World w = newWorld(g, "Act", 3);
        loadAround(w, 0, 0, 0);
        PlayerActions pa = new LocalActions(w, g.commands, Sources.player("T"));
        int bed = g.blocks.byName("voxelin:bedrock").id;
        check(!pa.breakBlock(0, 0, 0, null, true) && w.blockId(0, 0, 0) == bed, "корінну породу зламати не можна");
        int top = w.surfaceY(1, 1);
        check(pa.breakBlock(1, top, 1, null, true) && w.blockId(1, top, 1) == 0, "ламання трави");
        check(!pa.breakBlock(1, top + 5, 1, null, true), "ламати повітря - false");
        check(pa.placeBlock(1, top, 1, "voxelin:stone") && w.getBlock(1, top, 1).name().equals("voxelin:stone"), "постановка в порожнє місце");
        check(!pa.placeBlock(1, top, 1, "voxelin:dirt"), "поставити в зайняте - false");
        check(!pa.placeBlock(1, top + 3, 1, "voxelin:no_such_block"), "невідомий блок - false");
        check(!pa.placeBlock(5000, 10, 5000, "voxelin:stone"), "поза завантаженими чанками - false");
        check(pa.command("/help", null).size() == 1, "команда через PlayerActions");
        w.close();
    }

    static Game vanilla() { return new Game(new File("nope"), null); }

    /** Біоми, води, печери, руди, дерева: детермінованість, охоплення, розумні пропорції. */
    static void terrain() {
        Game g = vanilla();
        int[] block = new int[1];
        // 1) усі 12 біомів трапляються (об'єднання кількох зерен), на гігантських координатах теж
        java.util.Set<TerrainGenerator.Biome> seen = java.util.EnumSet.noneOf(TerrainGenerator.Biome.class);
        for (long seed : new long[]{1, 2, 3, 4, 12345}) {
            TerrainGenerator tg = new TerrainGenerator(seed, WorldInfo.Type.NORMAL, g.blocks);
            for (int i = -60; i < 60; i++) for (int j = -60; j < 60; j++) {
                long x = i * 400L, z = j * 400L; // біоми великі (тисячі блоків) - беремо ±24 000
                seen.add(tg.biome(x, z));
                int h = tg.surfaceY(x, z);
                check(h >= 6 && h <= 225, "висота в межах 6..225: " + h);
            }
        }
        check(seen.size() == TerrainGenerator.Biome.values().length, "усі біоми трапляються: " + seen);
        for (long base : new long[]{1_000_000_000_000_000L, -9_000_000_000_000_000L}) {
            TerrainGenerator tg = new TerrainGenerator(7, WorldInfo.Type.NORMAL, g.blocks);
            java.util.Set<TerrainGenerator.Biome> far = java.util.EnumSet.noneOf(TerrainGenerator.Biome.class);
            for (int i = 0; i < 80; i++) for (int j = 0; j < 80; j++) { far.add(tg.biome(base + i * 300L, base + j * 300L)); int h = tg.surfaceY(base + i * 300L, base + j * 300L); check(h >= 6 && h <= 225, "висота далеко"); }
            check(far.size() >= 6, "на " + base + " теж різні біоми: " + far.size());
        }
        // 2) детермінованість чанка й незалежність від сусідів
        TerrainGenerator tg = new TerrainGenerator(555, WorldInfo.Type.NORMAL, g.blocks);
        for (long[] pos : new long[][]{{0, 0}, {-7, 12}, {300, -300}, {1_000_000_000L, 5}}) {
            Chunk a = new Chunk(pos[0], pos[1]), b = new Chunk(pos[0], pos[1]);
            tg.generate(a); new TerrainGenerator(555, WorldInfo.Type.NORMAL, g.blocks).generate(b);
            boolean same = true;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < Chunk.HEIGHT; y++) if (a.get(x, y, z) != b.get(x, y, z)) same = false;
            check(same, "чанк " + pos[0] + "," + pos[1] + " генерується однаково");
        }
        // 2б) рідка сітка клімату: surfaceY (4 вузли) і генерація чанка (спільна сітка 5x5) дають ОДНАКОВУ поверхню; пагорби не мають «швів» на межах чанків
        for (long[] pos : new long[][]{{0, 0}, {-3, 5}, {40, -40}, {-1_000_000L, 2_000_000L}}) {
            Chunk ch = new Chunk(pos[0], pos[1]); tg.generate(ch);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                long wx = pos[0] * 16 + x, wz = pos[1] * 16 + z;
                int sy = tg.surfaceY(wx, wz);
                int top = 0; for (int y = Chunk.HEIGHT - 1; y > 0; y--) { int id = ch.get(x, y, z); if (id != 0 && id != g.blocks.byName("voxelin:water").id && id != g.blocks.byName("voxelin:oak_leaves").id && id != g.blocks.byName("voxelin:birch_leaves").id && id != g.blocks.byName("voxelin:spruce_leaves").id && id != g.blocks.byName("voxelin:oak_log").id && id != g.blocks.byName("voxelin:birch_log").id && id != g.blocks.byName("voxelin:spruce_log").id && id != g.blocks.byName("voxelin:pale_log").id && id != g.blocks.byName("voxelin:cactus").id && id != g.blocks.byName("voxelin:dune_glass").id && id != g.blocks.byName("voxelin:monolith").id) { top = y; break; } }
                check(top == sy, "поверхня чанка = surfaceY у " + wx + "," + wz + ": " + top + " проти " + sy);
            }
        }
        // 2в) біоми великі: сусідні точки на відстані 100 блоків здебільшого того ж біому (не «каша»)
        {
            long same = 0, total = 0;
            for (int i = -50; i < 50; i++) for (int j = -50; j < 50; j++) { long x = i * 211L, z = j * 211L; if (tg.biome(x, z) == tg.biome(x + 100, z)) same++; total++; }
            check(same / (double) total > 0.7, "біоми великі й не «каша» (білий шум дав би ~0.2; решта - узбережжя й зона змішування на межах): " + (same / (double) total) + " сусідніх точок (100 блоків) того ж біому");
        }
        // 3) статистика на 24x24 чанках: руди, печери, вода, дерева
        int stone = g.blocks.byName("voxelin:stone").id, water = g.blocks.byName("voxelin:water").id, bedrock = g.blocks.byName("voxelin:bedrock").id;
        String[] ores = {"coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore", "liminite_ore"};
        int[] oreIds = new int[ores.length], oreCount = new int[ores.length];
        for (int i = 0; i < ores.length; i++) oreIds[i] = g.blocks.byName("voxelin:" + ores[i]).id;
        long caveAir = 0, solidUnder = 0, waterBlocks = 0, logs = 0, leaves = 0, badBedrock = 0, columns = 0;
        long[] forest = locate(tg, TerrainGenerator.Biome.FOREST), oceanSpot = locate(tg, TerrainGenerator.Biome.OCEAN);
        check(forest != null && oceanSpot != null, "знайдено ліс і океан для статистики");
        final long fcx = Math.floorDiv(forest[0], 16L), fcz = Math.floorDiv(forest[1], 16L);
        int oakLog = g.blocks.byName("voxelin:oak_log").id, birchLog = g.blocks.byName("voxelin:birch_log").id, spruceLog = g.blocks.byName("voxelin:spruce_log").id;
        for (int dx = -12; dx < 12; dx++) for (int dz = -12; dz < 12; dz++) {
            long cx = fcx + dx, cz = fcz + dz;
            Chunk c = new Chunk(cx, cz);
            tg.generate(c);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                columns++;
                if (c.get(x, 0, z) != bedrock) badBedrock++;
                int surf = tg.surfaceY(cx * 16L + x, cz * 16L + z);
                for (int y = 1; y < Chunk.HEIGHT; y++) {
                    int id = c.get(x, y, z);
                    for (int o = 0; o < oreIds.length; o++) if (id == oreIds[o]) oreCount[o]++;
                    if (id == water) { waterBlocks++; check(y <= TerrainGenerator.SEA, "вода лише до рівня моря: y=" + y); }
                    if (id == oakLog || id == birchLog || id == spruceLog) logs++;
                    if (y >= 3 && y < surf - 4) { if (id == 0) caveAir++; else solidUnder++; }
                }
            }
        }
        check(badBedrock == 0, "корінна порода на y=0 всюди");
        for (int cx = 0; cx < 3; cx++) for (int cz = 0; cz < 3; cz++) {
            Chunk bc = new Chunk(cx, cz); tg.generate(bc);
            int thick1 = 0, thick2 = 0;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                check(bc.get(x, 0, z) == bedrock && bc.get(x, 3, z) != bedrock, "корінна порода лише в y 0..2");
                if (bc.get(x, 1, z) == bedrock) thick1++;
                if (bc.get(x, 2, z) == bedrock) thick2++;
            }
            check(thick1 > 60 && thick1 < 200 && thick2 > 10 && thick2 < 120, "дно світу «рване»: y1=" + thick1 + " y2=" + thick2 + " з 256");
        }
        for (int o = 0; o < ores.length; o++) check(oreCount[o] > 0, "руда " + ores[o] + " трапляється: " + oreCount[o]);
        check(oreCount[0] > oreCount[1] && oreCount[1] > oreCount[3] && oreCount[3] >= oreCount[4], "руди за рідкістю: вугілля > залізо > золото >= діаманти: " + java.util.Arrays.toString(oreCount));
        double caveFrac = caveAir / (double) (caveAir + solidUnder);
        check(caveFrac > 0.005 && caveFrac < 0.2, "печери займають розумну частку підземелля: " + caveFrac);
        // вода: в районі океану її багато й лише до рівня моря (виміряно вище в циклі: y <= SEA)
        for (int dx = -4; dx < 4; dx++) for (int dz = -4; dz < 4; dz++) {
            Chunk wc = new Chunk(Math.floorDiv(oceanSpot[0], 16L) + dx, Math.floorDiv(oceanSpot[1], 16L) + dz); tg.generate(wc);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y <= TerrainGenerator.SEA; y++) if (wc.get(x, y, z) == water) waterBlocks++;
        }
        check(waterBlocks > 5000, "в океані багато води: " + waterBlocks);
        check(logs > 100, "дерева ростуть: " + logs);
        // 4) океан: під водою дно, вода до рівня моря; плоский світ без печер і руд
        long[] ocean = null;
        for (int i = -60; i < 60 && ocean == null; i++) for (int j = -60; j < 60; j++) if (tg.biome(i * 50L, j * 50L) == TerrainGenerator.Biome.OCEAN) { ocean = new long[]{i * 50L, j * 50L}; break; }
        check(ocean != null, "знайдено океан");
        Chunk oc = new Chunk(Math.floorDiv(ocean[0], 16L), Math.floorDiv(ocean[1], 16L)); tg.generate(oc);
        int lx = (int) Math.floorMod(ocean[0], 16L), lz = (int) Math.floorMod(ocean[1], 16L), os = tg.surfaceY(ocean[0], ocean[1]);
        check(os < TerrainGenerator.SEA && oc.get(lx, os + 1, lz) == water && oc.get(lx, TerrainGenerator.SEA, lz) == water && oc.get(lx, TerrainGenerator.SEA + 1, lz) == 0, "океан: вода над дном до рівня моря");
        TerrainGenerator flat = new TerrainGenerator(1, WorldInfo.Type.FLAT, g.blocks);
        Chunk fc = new Chunk(3, 3); flat.generate(fc);
        check(fc.get(5, 64, 5) == g.blocks.byName("voxelin:grass").id && fc.get(5, 65, 5) == 0 && fc.get(5, 30, 5) == g.blocks.byName("voxelin:stone").id, "плаский світ: рівна трава на 64");
        // 5) місце появи на суші
        for (long seed : new long[]{1, 2, 3, 4, 5, 6, 7, 8}) {
            try {
                Game gg = new Game(new File(java.nio.file.Files.createTempDirectory("vx-land").toFile(), "s"), null);
                gg.createWorld("L", seed, WorldInfo.Type.NORMAL);
                World w = gg.openWorld("L");
                long[] land = w.findLand(0, 0);
                check(w.surfaceY(land[0], land[1]) > w.seaLevel() + 1 || (land[0] == 0 && land[1] == 0), "спавн на суші для зерна " + seed + ": " + w.surfaceY(land[0], land[1]));
                w.close();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    /** Точка, де біом «глибокий» (сусіди на 60 блоків у 4 напрямках того ж біому), найближча до початку координат (кільцями по 120 блоків, до 30 000). */
    static long[] locate(TerrainGenerator tg, TerrainGenerator.Biome b) {
        for (int r = 0; r <= 30000; r += 120) for (int i = -r; i <= r; i += 120) for (long[] p : new long[][]{{i, -r}, {i, r}, {-r, i}, {r, i}}) {
            if (tg.biome(p[0], p[1]) == b && tg.biome(p[0] + 60, p[1]) == b && tg.biome(p[0] - 60, p[1]) == b && tg.biome(p[0], p[1] + 60) == b && tg.biome(p[0], p[1] - 60) == b) return p;
        }
        return null;
    }

    static void swimming() {
        PlayerEntity.Solid floor = (x, y, z) -> y <= 0;
        PlayerEntity.Liquid sea = (x, y, z) -> y >= 1 && y <= 30;
        PlayerEntity dry = new PlayerEntity(floor, (x, y, z) -> false, Pos.ofBlock(0, 25, 0)), wet = new PlayerEntity(floor, sea, Pos.ofBlock(0, 25, 0));
        for (int i = 0; i < 20; i++) { dry.tick(0.05, false, false, false, false, false, false, false); wet.tick(0.05, false, false, false, false, false, false, false); }
        check(25 - wet.pos.y < 4 && 25 - dry.pos.y > 8, "у воді падає повільно: вода " + (25 - wet.pos.y) + " проти " + (25 - dry.pos.y));
        check(wet.inWater && wet.headInWater, "inWater/headInWater у воді");
        for (int i = 0; i < 60; i++) wet.tick(0.05, false, false, false, false, true, false, false);
        check(wet.pos.y > 25 - 2, "пробіл у воді піднімає: y=" + wet.pos.y);
        // повільніше рухається у воді
        PlayerEntity a = new PlayerEntity(floor, sea, Pos.ofBlock(0, 10, 0)), b = new PlayerEntity(floor, (x, y, z) -> false, Pos.ofBlock(0, 1 + PlayerEntity.EYE + 0.01, 0));
        for (int i = 0; i < 40; i++) { a.tick(0.05, true, false, false, false, false, false, false); b.tick(0.05, true, false, false, false, false, false, false); }
        double da = Math.hypot(a.pos.relX(0) - Pos.ofBlock(0, 0, 0).relX(0), a.pos.relZ(0) - Pos.ofBlock(0, 0, 0).relZ(0)), db = Math.hypot(b.pos.relX(0) - Pos.ofBlock(0, 0, 0).relX(0), b.pos.relZ(0) - Pos.ofBlock(0, 0, 0).relZ(0));
        check(da < db * 0.8 && da > 0, "у воді повільніше: " + da + " проти " + db);
    }

    static void inventory() {
        Inventory inv = new Inventory();
        check(inv.add("voxelin:stone", 100) == 0 && inv.get(0).count == 64 && inv.get(1).count == 36, "стопки по 64");
        inv.select(1); inv.takeSelected();
        check(inv.get(1).count == 35, "забрати один");
        inv.scroll(-2);
        check(inv.selected() == Inventory.HOTBAR - 1, "колесо по колу");
        check(inv.add("x", 64 * 40) > 0, "переповнення повертає залишок");
    }

    /** Ядро й API не мають знати про вікно/OpenGL: жоден їхній клас не посилається на org/lwjgl. */
    static void noLwjglInCore(File out) throws IOException {
        int scanned = 0;
        for (String pkg : new String[]{"voxelin/core", "voxelin/api", "voxelin/loader", "voxelin/content"}) {
            File d = new File(out, pkg);
            if (!d.isDirectory()) continue;
            try (var files = Files.walk(d.toPath())) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".class"))::iterator) {
                    scanned++;
                    check(!new String(Files.readAllBytes(f), "ISO-8859-1").contains("org/lwjgl"), "клас ядра посилається на LWJGL: " + f);
                }
            }
        }
        check(scanned > 20, "перевірено класи ядра: " + scanned);
    }
}
