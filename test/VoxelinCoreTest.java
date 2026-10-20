import voxelin.api.*;
import voxelin.api.event.*;
import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;
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
        mods(root);
        inventory();
        actions(root);
        border(root);
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
        check(Chunk.key(-1, 5) != Chunk.key(5, -1) && Chunk.key(-1, -1) != Chunk.key(0, 0), "ключі чанків не злипаються");
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
        List<String> out = g.commands.execute(p, w, new int[]{0, 100, 0}, "/fill 0 100 0 9 104 9 stone");
        check(out.get(0).startsWith("Заповнено 500 з 500"), "fill 10x5x10: " + out);
        check(w.getBlock(9, 104, 9).name().equals("voxelin:stone"), "fill поставив блоки");
        out = g.commands.execute(p, w, new int[]{5, 100, 5}, "setblock ~ ~10 ~-2 glass");
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

    static void raycast() {
        Raycast.Solid wall = (x, y, z) -> x == 3 && y == 0 && z == 0;
        Raycast.Hit h = Raycast.cast(Units.center(0), Units.center(0), Units.center(0), 90, 0, 10 * Units.BLOCK_SIZE, wall);
        check(h != null && h.x() == 3 && h.px() == 2, "промінь на схід влучає в стіну, клітинка перед нею вільна");
        // два блоки, що торкаються лише ребром: промінь рівно по діагоналі не має проскочити між ними
        Raycast.Solid corner = (x, y, z) -> (x == 1 && y == 0 && z == 0) || (x == 0 && y == 0 && z == -1);
        Raycast.Hit d = Raycast.cast(Units.center(0), Units.center(0), Units.center(0), 45, 0, 10 * Units.BLOCK_SIZE, corner);
        check(d != null && d.distance() < 2 * Units.BLOCK_SIZE, "діагональний кут не проскочити (DDA)");
        check(Raycast.cast(0, 0, 0, 0, 0, Units.BLOCK_SIZE * 3, (x, y, z) -> false) == null, "порожньо - null");
    }

    static void physics() {
        PlayerEntity.Solid floor = (x, y, z) -> y <= 0;
        PlayerEntity p = new PlayerEntity(floor, 0, 40, 0);
        for (int i = 0; i < 200; i++) p.tick(0.05f, false, false, false, false, false, false, false);
        float feet = p.bodyCenterY() - PlayerEntity.HEIGHT / 2f;
        check(p.onGround() && Math.abs(feet - Units.BLOCK_SIZE) < 0.26f, "падіння з висоти: стоїть на підлозі без зависання, ноги на " + feet);
        float y0 = p.y;
        p.tick(0.05f, false, false, false, false, true, false, false);
        for (int i = 0; i < 3; i++) p.tick(0.05f, false, false, false, false, false, false, false);
        check(p.y > y0, "стрибок");
        PlayerEntity w = new PlayerEntity((x, y, z) -> y <= 0 || x == 5, Units.center(0), Units.BLOCK_SIZE + PlayerEntity.EYE + 0.01f, Units.center(0));
        for (int i = 0; i < 200; i++) w.tick(0.05f, false, false, false, true, false, true, false);
        check(w.x < Units.toWorld(5) && w.x > Units.toWorld(3), "стіна зупиняє рух: x=" + w.x);
        PlayerEntity e = new PlayerEntity((x, y, z) -> y <= 0 && x <= 0, Units.center(0), Units.BLOCK_SIZE + PlayerEntity.EYE + 0.01f, Units.center(0));
        for (int i = 0; i < 5; i++) e.tick(0.05f, false, false, false, false, false, false, false);
        for (int i = 0; i < 100; i++) e.tick(0.05f, false, false, false, true, false, false, true);
        check(e.onGround() && e.x < Units.toWorld(1) + PlayerEntity.WIDTH, "присід не дає впасти з краю: x=" + e.x);
        check(new PlayerEntity(floor, Units.center(0), 3, Units.center(0)).overlapsBlock(0, 1, 0), "не замурувати себе блоком");
    }

    static void mods(File root) throws Exception {
        File modsDir = new File(root, "mods");
        modsDir.mkdirs();
        String api = System.getProperty("java.class.path");
        jar(modsDir, "good.jar", "good", "GoodMod", "1",
                "public class GoodMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) {"
                + " c.registries().block(\"ruby\", new voxelin.api.BlockSettings().color(0xFF0000).light(7));"
                + " c.commands().register(\"hello\", \"\", 0, k -> k.reply(\"hi \" + k.source().name())); } }", api);
        jar(modsDir, "broken.jar", "broken", "BrokenMod", "1",
                "public class BrokenMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) { throw new RuntimeException(\"зламано\"); } }", api);
        jar(modsDir, "future.jar", "future", "FutureMod", "999",
                "public class FutureMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) {} }", api);
        jar(modsDir, "thief.jar", "thief", "ThiefMod", "1",
                "public class ThiefMod implements voxelin.api.VoxelinMod { public void init(voxelin.api.ModContext c) { c.registries().block(\"voxelin:diamond\", new voxelin.api.BlockSettings()); } }", api);
        Files.write(new File(modsDir, "notamod.jar").toPath(), new byte[]{1, 2, 3});
        Game g = new Game(new File(root, "saves5"), modsDir);
        Map<String, ModLoader.Status> st = new HashMap<>();
        for (ModLoader.ModInfo m : g.mods.mods()) st.put(m.id, m.status);
        check(st.get("good") == ModLoader.Status.OK && g.blocks.byName("good:ruby") != null && g.blocks.byName("good:ruby").light == 7, "добрий мод додав блок");
        check(st.get("broken") == ModLoader.Status.FAILED, "мод із винятком позначено, гра стартувала");
        check(st.get("future") == ModLoader.Status.INCOMPATIBLE, "мод з новішим API не вантажиться");
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
        check(!pa.breakBlock(0, 0, 0) && w.blockId(0, 0, 0) == bed, "корінну породу зламати не можна");
        int top = w.surfaceY(1, 1);
        check(pa.breakBlock(1, top, 1) && w.blockId(1, top, 1) == 0, "ламання трави");
        check(!pa.breakBlock(1, top + 5, 1), "ламати повітря - false");
        check(pa.placeBlock(1, top, 1, "voxelin:stone") && w.getBlock(1, top, 1).name().equals("voxelin:stone"), "постановка в порожнє місце");
        check(!pa.placeBlock(1, top, 1, "voxelin:dirt"), "поставити в зайняте - false");
        check(!pa.placeBlock(1, top + 3, 1, "voxelin:no_such_block"), "невідомий блок - false");
        check(!pa.placeBlock(5000, 10, 5000, "voxelin:stone"), "поза завантаженими чанками - false");
        check(pa.command("/help", null).size() == 1, "команда через PlayerActions");
        w.close();
    }

    static void border(File root) throws Exception {
        Game g = new Game(new File(root, "saves7"), null);
        World w = newWorld(g, "Border", 11);
        int edge = (World.BORDER >> 4) - 1; // останній чанк усередині межі
        check(World.inBorder(edge, 0) && !World.inBorder(edge + 1, 0) && World.inBorder(-(World.BORDER >> 4), 0) && !World.inBorder(-(World.BORDER >> 4) - 1, 0), "межа в чанках симетрична");
        for (int i = 0; i < 400 && w.chunk(edge, 0) == null; i++) { w.update(edge, 0, 2); Thread.sleep(10); }
        for (int i = 0; i < 60; i++) { w.update(edge, 0, 2); Thread.sleep(5); } // дати шанс (помилково) створитися чанкам за межею
        check(w.chunk(edge, 0) != null && w.chunk(edge + 1, 0) == null && w.chunk(edge + 2, 0) == null, "за межею чанки не створюються");
        check(w.isSolid(World.BORDER, 70, 0) && w.blockId(World.BORDER, 70, 0) == -1, "за межею - суцільна стіна (незавантажене)");
        check(!w.setBlock(Sources.player("T"), World.BORDER + 3, 70, 0, g.blocks.byName("voxelin:stone")), "за межею блок поставити не можна");
        PlayerEntity p = new PlayerEntity(w::isSolid, Units.center(World.BORDER - 4), Units.toWorld(w.surfaceY(World.BORDER - 4, 0) + 1) + PlayerEntity.EYE + 0.02f, Units.center(0));
        for (int i = 0; i < 200; i++) p.tick(0.05f, false, false, false, true, false, true, false); // біжить на схід
        check(Units.toBlock(p.x) < World.BORDER, "гравець не може вийти за межу: x=" + Units.toBlock(p.x));
        w.close();
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
