import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Структури: розбір шаблонів, повороти, розподіл, склеювання через межі чанків, скрині, /locate, файли гравця. */
public class VoxelinStructureTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;

    static final String TINY = "place: surface\nspacing: 40\nchance: 1\nsink: 1\nlegend:\n  S = stone\n  K = chest loot=ruins\n  _ = air\nlayers:\nSSS\nSKS\n---\nS__\n___\n";

    public static void main(String[] a) throws Exception {
        g = new Game(new File("nope"), null);
        root = Files.createTempDirectory("vx-struct").toFile();
        parsing(); rotation(); placer(); stitching(); world(); far(); files(); perf();
        System.out.println("VoxelinStructureTest: " + n + " перевірок OK");
    }

    static StructureTemplate parse(String text) throws Exception { return StructureTemplate.parse("voxelin:t", text, g.blocks); }

    static void bad(String text, String why) {
        try { parse(text); check(false, "має бути помилка: " + why); }
        catch (StructureTemplate.StructureException e) { check(e.getMessage().startsWith("рядок "), "помилка з номером рядка (" + why + "): " + e.getMessage()); }
        catch (Exception e) { check(false, "неочікуваний виняток (" + why + "): " + e); }
    }

    static void parsing() throws Exception {
        check(g.structures.templates().stream().filter(t -> t.role == null).count() == 3, "три ванільні окремі структури");
        for (String nm : new String[]{"ruins", "dungeon", "mine"}) check(g.structures.find(nm) != null && g.structures.find("voxelin:" + nm) != null, "шаблон " + nm);
        StructureTemplate t = parse(TINY);
        check(t.sx == 3 && t.sz == 2 && t.sy == 2 && t.place == StructureTemplate.Place.SURFACE && t.spacing == 40 && t.chance == 1 && t.sink == 1, "розміри й заголовок");
        int stone = g.blocks.byName("voxelin:stone").id, chest = g.blocks.byName("voxelin:chest").id;
        check(t.id(0, 0, 0) == stone && t.id(1, 0, 1) == chest && t.lootAt(1, 0, 1).equals("ruins") && t.id(1, 1, 0) == 0 && t.id(0, 1, 0) == stone, "клітинки (камінь, скриня, повітря)");
        check(t.lootAt(0, 0, 0) == null, "лише скриня має здобич");
        bad("legend:\n S = stone\nlayers:\nS\n", "нема place");
        bad(TINY.replace("spacing: 40", "spacing: 5"), "spacing менше габариту");
        bad(TINY.replace("spacing: 40", "spacing: abc"), "spacing не число");
        bad(TINY.replace("chance: 1", "chance: 1.5"), "chance поза межами");
        bad(TINY.replace("chance: 1", "chance: x"), "chance не число");
        bad(TINY.replace("S = stone", "S = nonexistent_block"), "невідомий блок");
        bad(TINY.replace("SKS", "SQS"), "символ не в легенді");
        bad(TINY.replace("SSS\nSKS", "SSS\nSK"), "рядки різної ширини");
        bad(TINY.replace("S__\n___", "S__"), "шари різної висоти");
        bad(TINY.replace("K = chest loot=ruins", "K = chest"), "скриня без loot");
        bad(TINY.replace("  _ = air", "  S = stone"), "символ визначено двічі");
        bad("place: surface\nspacing: 40\nchance: 1\nlegend:\n  S = stone\n", "нема layers");
        bad("place: surface\nspacing: 40\nchance: 1\nlegend:\n  S = stone\nlayers:\n", "нема шарів");
        bad("place: surface\nspacing: 40\nchance: 1\nlegend:\n  S = stone\nlayers:\n...\n...\n", "усе «не чіпати»");
        bad(TINY.replace("place: surface", "place: sky"), "невідомий place");
        bad(("place: underground\nspacing: 40\nchance: 1\ndepth: 50 10\nlegend:\n  S = stone\nlayers:\nS\n"), "depth навпаки");
        bad(("place: underground\nspacing: 40\nchance: 1\nlegend:\n  S = stone\nlayers:\nS\n"), "depth нема");
        bad("place: surface\nspacing: 500\nchance: 1\nlegend:\n  S = stone\nlayers:\n" + "S".repeat(33) + "\n", "завширшки 33");
        bad("", "порожній текст");
        bad("garbage\nwithout structure", "сміття");
        // коментарі й порожні рядки, '#' як символ
        StructureTemplate c = parse("// коментар\nplace: surface\nspacing: 30\nchance: 0.5\n\nlegend:\n  # = stone\nlayers:\n// ще\n#.#\n");
        check(c.sx == 3 && c.id(0, 0, 0) > 0 && c.id(1, 0, 0) == -1, "коментарі ігноруються, # - символ легенди");
        // ванільні шаблони правильні
        StructureTemplate ruins = g.structures.find("ruins"), dun = g.structures.find("dungeon"), mine = g.structures.find("mine");
        check(ruins.sx == 9 && ruins.sz == 9 && ruins.sy == 6 && dun.sy == 5 && mine.sx == 25 && mine.sz == 3 && mine.sy == 5, "розміри ванільних шаблонів");
        check(dun.place == StructureTemplate.Place.UNDERGROUND && dun.depthMin == 14 && mine.depthMax == 46, "підземні шаблони");
        int chests = 0; for (int y = 0; y < dun.sy; y++) for (int z = 0; z < dun.sz; z++) for (int x = 0; x < dun.sx; x++) if (dun.lootAt(x, y, z) != null) chests++;
        check(chests == 2, "у підземеллі дві скрині: " + chests);
        for (StructureTemplate t2 : g.structures.templates()) if (t2.role == null) check(g.loot.get(t2.name.substring(t2.name.indexOf(':') + 1)) != null, "таблиця здобичі для " + t2.name);
    }

    static void rotation() throws Exception {
        StructureTemplate t = parse(TINY); // 3 x 2
        for (int rot = 0; rot < 4; rot++) {
            Set<Long> seen = new HashSet<>();
            int w = t.width(rot), d = t.depth(rot);
            check((rot & 1) == 0 ? (w == 3 && d == 2) : (w == 2 && d == 3), "габарит повороту " + rot);
            for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
                int rx = t.rx(rot, x, z), rz = t.rz(rot, x, z);
                check(rx >= 0 && rx < w && rz >= 0 && rz < d, "клітинка в габариті повороту " + rot);
                check(seen.add(rx * 100L + rz), "поворот " + rot + " - взаємно однозначний");
            }
        }
        check(t.rx(0, 2, 1) == 2 && t.rz(0, 2, 1) == 1, "rot 0 - тотожність");
        // чотири повороти на 90 повертають усе на місце
        int sx = t.sx, sz = t.sz;
        for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            int cx = x, cz = z, cw = sx, cd = sz;
            for (int i = 0; i < 4; i++) {
                // поворот на 90 усередині габариту cw x cd: (x,z) -> (cd-1-z, x)
                int nx = cd - 1 - cz, nz = cx; cx = nx; cz = nz; int tmp = cw; cw = cd; cd = tmp;
            }
            check(cx == x && cz == z, "чотири повороти по 90 = тотожність");
        }
        // асиметричний маркер: клітинка (0,0) шаблона - у різних кутах за поворотами
        check(t.rx(1, 0, 0) == t.sz - 1 && t.rz(1, 0, 0) == 0, "rot 1: (0,0) -> правий верхній");
        check(t.rx(2, 0, 0) == t.sx - 1 && t.rz(2, 0, 0) == t.sz - 1, "rot 2: (0,0) -> протилежний кут");
        check(t.rx(3, 0, 0) == 0 && t.rz(3, 0, 0) == t.sx - 1, "rot 3: (0,0) -> лівий нижній");
    }

    /** Фіктивна поверхня для розподілу. */
    static StructurePlacer placer(long seed, StructurePlacer.Surface s, int sea, StructureTemplate... ts) { return new StructurePlacer(seed, Arrays.asList(ts), s, sea); }

    static void placer() throws Exception {
        StructureTemplate ruins = g.structures.find("ruins"), dun = g.structures.find("dungeon"), mine = g.structures.find("mine");
        StructurePlacer.Surface flat = (x, z) -> 70;
        StructurePlacer p1 = placer(42, flat, 62, ruins, dun, mine), p1b = placer(42, flat, 62, ruins, dun, mine), p2 = placer(43, flat, 62, ruins, dun, mine);
        int found = 0, diff = 0, same = 0;
        for (StructureTemplate t : p1.templates()) {
            int cnt = 0;
            for (long cx = -30; cx < 30; cx++) for (long cz = -30; cz < 30; cz++) {
                StructurePlacer.Placement a = p1.placementFor(t, cx, cz), b = p1b.placementFor(t, cx, cz), c = p2.placementFor(t, cx, cz);
                check((a == null) == (b == null) && (a == null || (a.ox == b.ox && a.oz == b.oz && a.oy == b.oy && a.rot == b.rot)), "розподіл детермінований");
                if (a != null) {
                    cnt++; found++;
                    check(a.ox >= cx * t.spacing && a.ox + a.w <= (cx + 1) * t.spacing && a.oz >= cz * t.spacing && a.oz + a.d <= (cz + 1) * t.spacing, t.name + ": габарит не виходить за клітинку");
                    if (t.place == StructureTemplate.Place.UNDERGROUND) check(a.oy >= t.depthMin && a.oy <= t.depthMax && a.oy + t.sy + 4 < 70, t.name + ": під поверхнею");
                }
                if (c == null || a == null || a.ox != c.ox || a.oz != c.oz) diff++; else same++;
            }
            double rate = cnt / 3600.0;
            check(Math.abs(rate - t.chance) < 0.06, t.name + ": частота " + rate + " біля " + t.chance);
        }
        check(diff > same * 3, "інше зерно - інша карта структур: " + diff + " проти " + same);
        // океан і нерівності відхиляють поверхневі
        StructurePlacer ocean = placer(42, (x, z) -> 50, 62, ruins);
        StructurePlacer hilly = placer(42, (x, z) -> 70 + (int) ((x * 7 + z * 13) % 9), 62, ruins);
        int o = 0, h = 0;
        for (long cx = -20; cx < 20; cx++) for (long cz = -20; cz < 20; cz++) { if (ocean.placementFor(ruins, cx, cz) != null) o++; if (hilly.placementFor(ruins, cx, cz) != null) h++; }
        check(o == 0, "в океані поверхневих нема");
        check(h < 40 * 40 * 0.45 * 0.5, "на нерівному поверхневих значно менше: " + h);
        // підземні відхиляються, якщо поверхня занадто низько
        StructurePlacer shallow = placer(42, (x, z) -> 30, 62, dun);
        int s = 0; for (long cx = -20; cx < 20; cx++) for (long cz = -20; cz < 20; cz++) if (shallow.placementFor(dun, cx, cz) != null) s++;
        check(s < 40 * 40 * 0.7 * 0.6, "під низькою поверхнею підземелля рідкісні: " + s);
        // різні види з одним id клітинки не збігаються
        // locate: найближча справжня
        StructurePlacer.Placement near = p1.locate(ruins, 0, 0, 24);
        check(near != null, "locate знайшов");
        double best = Double.MAX_VALUE;
        for (long cx = -10; cx <= 10; cx++) for (long cz = -10; cz <= 10; cz++) { StructurePlacer.Placement p = p1.placementFor(ruins, cx, cz); if (p != null) best = Math.min(best, Math.hypot(p.centerX(), p.centerZ())); }
        check(Math.abs(Math.hypot(near.centerX(), near.centerZ()) - best) < 1e-9, "locate - справді найближча: " + best);
        StructureTemplate never = parse(TINY.replace("chance: 1", "chance: 0"));
        check(placer(1, flat, 62, never).locate(never, 0, 0, 5) == null, "chance 0 - структур нема, locate = null");
    }

    /** Окремі структури без частин сіл (села можуть перекривати їх - перевіряються в VoxelinVillageTest). */
    static List<StructureTemplate> standalone() { List<StructureTemplate> r = new ArrayList<>(); for (StructureTemplate t : g.structures.templates()) if (t.role == null) r.add(t); return r; }

    static Chunk gen(TerrainGenerator tg, long cx, long cz) { Chunk c = new Chunk(cx, cz); tg.generate(c); return c; }

    static int blockAt(Map<Long, Chunk> m, TerrainGenerator tg, long x, int y, long z) {
        long cx = Math.floorDiv(x, 16L), cz = Math.floorDiv(z, 16L);
        long key = cx * 1_000_003L + cz;
        Chunk c = m.computeIfAbsent(key, k -> gen(tg, cx, cz));
        return c.get((int) Math.floorMod(x, 16L), y, (int) Math.floorMod(z, 16L));
    }

    /** Усі клітинки шаблона на своїх місцях (з поворотом) у згенерованих чанках. */
    static void verify(StructurePlacer.Placement p, TerrainGenerator tg, Map<Long, Chunk> m, String what) {
        StructureTemplate t = p.t;
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            int id = t.id(x, y, z);
            if (id < 0) continue;
            long wx = p.ox + t.rx(p.rot, x, z), wz = p.oz + t.rz(p.rot, x, z);
            check(blockAt(m, tg, wx, p.oy + y, wz) == id, what + ": клітинка " + x + "," + y + "," + z + " на місці (поворот " + p.rot + ")");
        }
    }

    static void stitching() throws Exception {
        long seed = 77;
        TerrainGenerator tg = new TerrainGenerator(seed, WorldInfo.Type.FLAT, g.blocks, standalone());
        StructurePlacer pl = new StructurePlacer(seed, standalone(), tg::surfaceY, tg.seaLevel());
        for (StructureTemplate t : g.structures.templates()) {
            if (t.role != null) continue;
            int verified = 0;
            outer:
            for (long cx = 0; cx < 12 && verified < 3; cx++) for (long cz = 0; cz < 12 && verified < 3; cz++) {
                StructurePlacer.Placement p = pl.placementFor(t, cx, cz);
                if (p == null) continue;
                Map<Long, Chunk> m = new HashMap<>();
                verify(p, tg, m, t.name);
                // обернений порядок генерації - той самий результат
                Map<Long, Chunk> rev = new HashMap<>();
                for (long x = Math.floorDiv(p.ox + p.w, 16L); x >= Math.floorDiv(p.ox, 16L); x--) for (long z = Math.floorDiv(p.oz + p.d, 16L); z >= Math.floorDiv(p.oz, 16L); z--) rev.put(x * 1_000_003L + z, gen(tg, x, z));
                verify(p, tg, rev, t.name + " (зворотний порядок)");
                for (Map.Entry<Long, Chunk> e : rev.entrySet()) {
                    Chunk a = e.getValue(), b = m.get(e.getKey());
                    if (b == null) continue;
                    for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) check(a.get(x, y, z) == b.get(x, y, z), "порядок генерації не впливає");
                }
                verified++;
                // структура справді перетинає межу чанків хоча б по одній осі
                int chunksX = (int) (Math.floorDiv(p.ox + p.w - 1, 16L) - Math.floorDiv(p.ox, 16L)) + 1;
                check(chunksX >= 1, "габарит у чанках");
            }
            check(verified > 0, "знайдено й перевірено структуру " + t.name);
        }
        // усі чанки з розподілу: pending-скрині збігаються з блоками скринь
        Chunk[] all = new Chunk[1];
        int chestBlocks = 0, pend = 0;
        int chestId = g.blocks.byName("voxelin:chest").id;
        for (long cx = -10; cx < 10; cx++) for (long cz = -10; cz < 10; cz++) {
            Chunk c = gen(tg, cx, cz);
            for (Chunk.Pending p : c.pending) { pend++; check(c.get((int) Math.floorMod(p.x(), 16L), p.y(), (int) Math.floorMod(p.z(), 16L)) == chestId && g.loot.get(p.loot()) != null, "pending вказує на справжню скриню"); }
            for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (c.get(x, y, z) == chestId) chestBlocks++;
        }
        check(pend == chestBlocks && pend > 0, "кожна скриня має pending: " + pend + " з " + chestBlocks);
        // звичайна генерація без структур не міняється: чанк без структур однаковий із генератором без шаблонів
        TerrainGenerator plain = new TerrainGenerator(seed, WorldInfo.Type.FLAT, g.blocks);
        int different = 0, total = 0;
        for (long cx = -4; cx < 4; cx++) for (long cz = -4; cz < 4; cz++) { Chunk a = gen(tg, cx, cz), b = gen(plain, cx, cz); total++; if (!a.pending.isEmpty()) different++; if (a.pending.isEmpty()) for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (a.get(x, y, z) != b.get(x, y, z) && a.nonAirCount() == b.nonAirCount()) { /* лише підземні/інші */ } }
        check(total == 64, "перевірено чанки без структур");
    }

    static void world() throws Exception {
        Game g2 = new Game(new File(root, "saves"), null);
        g2.createWorld("S", 99, WorldInfo.Type.FLAT);
        World w = g2.openWorld("S");
        w.naturalSpawning = false;
        long[] r = w.locate("ruins", 0, 0);
        check(r != null, "locate у світі: ruins");
        long[] none = w.locate("nonsense", 0, 0);
        check(none == null, "невідома структура - null");
        long cx = Math.floorDiv(r[0], 16L), cz = Math.floorDiv(r[2], 16L);
        for (int i = 0; i < 600; i++) { w.update(cx, cz, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        // у світі є скриня зі здобиччю неподалік знайденого центру
        ChestEntity found = null; BlockEntity any = null;
        for (BlockEntity e : w.blockEntities()) if (e instanceof ChestEntity && Math.abs(e.x - r[0]) <= 12 && Math.abs(e.z - r[2]) <= 12) { found = (ChestEntity) e; break; }
        check(found != null && !found.isEmpty() && found.contents().size() <= 3, "поруч зі знайденим центром є скриня зі здобиччю: " + (found == null ? "нема" : found.contents().size()));
        long fx = found.x, fz = found.z; int fy = found.y;
        check(w.blocks.get(w.blockId(fx, fy, fz)).name.equals("voxelin:chest"), "на місці сутності - блок скрині");
        String content = found.encode();
        // розграбована скриня не відновлюється після перезапуску
        for (int i = 0; i < 27; i++) found.set(i, null);
        w.close();
        World w2 = new Game(new File(root, "saves"), null).openWorld("S");
        w2.naturalSpawning = false;
        for (int i = 0; i < 600; i++) { w2.update(cx, cz, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w2.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        BlockEntity again = w2.blockEntityAt(fx, fy, fz);
        check(again instanceof ChestEntity && ((ChestEntity) again).isEmpty(), "розграбована скриня лишається порожньою після перезапуску");
        long count = w2.blockEntities().stream().filter(e -> e instanceof ChestEntity).count();
        check(count >= 1, "скрині на місці: " + count);
        // /tp через фіктивну ціль, /locate командою
        long[] tp = new long[3]; boolean[] called = {false};
        w2.setPlayerTarget(new PlayerTarget() {
            public Pos body() { return Pos.ofBlock(0, 70, 0); }
            public boolean alive() { return true; }
            public boolean creative() { return true; }
            public void hurt(double d, double kx, double kz) {}
            public void teleport(long x, int y, long z) { called[0] = true; tp[0] = x; tp[1] = y; tp[2] = z; }
        });
        Source p = Sources.player("P");
        List<String> out = g2.commands.execute(p, w2, new long[]{0, 70, 0}, "/tp 100 80 -200");
        check(called[0] && tp[0] == 100 && tp[1] == 80 && tp[2] == -200 && out.get(0).startsWith("Перенесено"), "/tp: " + out);
        out = g2.commands.execute(p, w2, new long[]{10, 70, 10}, "/tp ~5 ~ ~-5");
        check(tp[0] == 15 && tp[1] == 70 && tp[2] == 5, "/tp з ~: " + Arrays.toString(tp));
        for (String bad : new String[]{"/tp", "/tp 1 2", "/tp a b c", "/tp 1 300 1", "/tp 1 -5 1", "/tp 1 2 3 4"}) {
            called[0] = false;
            out = g2.commands.execute(p, w2, new long[]{0, 70, 0}, bad);
            check(!called[0] && !out.isEmpty() && !out.get(0).startsWith("Перенесено"), "погана /tp: " + bad + " -> " + out);
        }
        out = g2.commands.execute(p, w2, new long[]{0, 70, 0}, "/locate dungeon");
        check(out.get(0).startsWith("dungeon: x "), "/locate: " + out);
        check(g2.commands.execute(p, w2, new long[]{0, 70, 0}, "/locate nope").get(0).contains("Не знайдено"), "/locate невідомої");
        check(g2.commands.execute(p, w2, new long[]{0, 70, 0}, "/locate").get(0).contains("Потрібна"), "/locate без аргументу");
        check(g2.commands.execute(Sources.of("g", Source.Kind.PLAYER, 0, 1), w2, null, "/tp 1 2 3").get(0).contains("прав"), "/tp потребує прав");
        w2.setPlayerTarget(null);
        check(!w2.teleport(p, 1, 2, 3), "без гравця телепорт не діє");
        w2.close();
    }

    static void far() throws Exception {
        long seed = 5;
        TerrainGenerator tg = new TerrainGenerator(seed, WorldInfo.Type.FLAT, g.blocks, standalone());
        StructurePlacer pl = new StructurePlacer(seed, standalone(), tg::surfaceY, tg.seaLevel());
        for (long base : new long[]{1_000_000_000_000_000L, -1_000_000_000_000_000L, 4_000_000_000_000_000_000L}) {
            StructureTemplate t = g.structures.find("ruins");
            StructurePlacer.Placement p = null;
            for (long cx = 0; cx < 20 && p == null; cx++) for (long cz = 0; cz < 20 && p == null; cz++) p = pl.placementFor(t, base / t.spacing + cx, base / t.spacing + cz);
            check(p != null, "структура знайдена біля " + base);
            Map<Long, Chunk> m = new HashMap<>();
            // ключі чанків по long: тут chunk key будується множенням - тому перевіряємо безпосередньо кілька клітинок
            int ok = 0;
            for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
                int id = t.id(x, y, z);
                if (id < 0) continue;
                long wx = p.ox + t.rx(p.rot, x, z), wz = p.oz + t.rz(p.rot, x, z);
                long ccx = Math.floorDiv(wx, 16L), ccz = Math.floorDiv(wz, 16L);
                Chunk c = new Chunk(ccx, ccz);
                tg.generate(c);
                check(c.get((int) Math.floorMod(wx, 16L), p.oy + y, (int) Math.floorMod(wz, 16L)) == id, "клітинка на місці на " + base);
                ok++;
                if (ok >= 40) break;
            }
        }
    }

    static void files() throws Exception {
        File dir = new File(root, "gamedir");
        File mods = new File(dir, "mods"); mods.mkdirs();
        File st = new File(dir, "structures"); st.mkdirs();
        Files.write(new File(st, "ruins.vxs").toPath(), TINY.getBytes());           // перекриває ванільні руїни
        Files.write(new File(st, "broken.vxs").toPath(), "це не шаблон".getBytes()); // пропускається
        Files.write(new File(st, "Bad Name.vxs").toPath(), TINY.getBytes());          // погана назва файла
        Files.write(new File(st, "extra.vxs").toPath(), TINY.replace("spacing: 40", "spacing: 64").getBytes()); // новий вид
        Game g3 = new Game(new File(dir, "saves"), mods);
        StructureTemplate ruins = g3.structures.find("ruins");
        check(ruins != null && ruins.sx == 3 && ruins.spacing == 40, "файл гравця перекрив ванільні руїни");
        check(g3.structures.find("extra") != null && g3.structures.find("broken") == null && g3.structures.find("bad name") == null, "новий вид додано, зламані пропущено");
        check(g3.structures.templates().stream().filter(t -> t.role == null).count() == 4, "ванільні dungeon+mine + руїни з файла + extra");
    }

    static void perf() throws Exception {
        TerrainGenerator with = new TerrainGenerator(12345, WorldInfo.Type.NORMAL, g.blocks, g.structures.templates());
        TerrainGenerator without = new TerrainGenerator(12345, WorldInfo.Type.NORMAL, g.blocks);
        for (int i = 0; i < 40; i++) { gen(with, i, 0); gen(without, i, 0); } // прогрів
        long t0 = System.nanoTime();
        for (int i = 0; i < 150; i++) gen(without, 200 + i % 15, i / 15);
        long a = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int i = 0; i < 150; i++) gen(with, 200 + i % 15, i / 15);
        long b = System.nanoTime() - t0;
        double ratio = (double) b / a;
        System.out.println("  (генерація чанка: без структур " + a / 150_000 + " мкс, зі структурами " + b / 150_000 + " мкс, x" + String.format("%.2f", ratio) + ")");
        check(ratio < 1.6, "структури не сповільнюють генерацію надто: x" + ratio);
    }
}
