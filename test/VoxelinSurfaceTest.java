import voxelin.api.*;
import voxelin.client.LocalActions;
import voxelin.client.render.ChunkMesher;
import voxelin.core.*;
import voxelin.core.gen.TerrainGenerator;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * «Жива поверхня» (V1): погода з сіда й часу, рух сонця, трава й квіти (генерація, опора, обсипання, заміна блоком),
 * геометрія рослини-хреста в мешері, /weather.
 */
public class VoxelinSurfaceTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        weather(); sun(); plants(); generation(); mesh();
        System.out.println("VoxelinSurfaceTest: " + n + " перевірок OK");
    }

    static void weather() {
        // детермінованість: той самий сід і час - той самий дощ (у хоста й гостей)
        for (long t = 0; t < 200_000; t += 997) check(Weather.rain(42, t) == Weather.rain(42, t), "дощ однаковий для тих самих сіда й часу");
        // частка дощу близька до RAIN_CHANCE на довгому відрізку; значення в 0..1
        int rainy = 0, slots = 20_000;
        for (long s = 0; s < slots; s++) if (Weather.rainySlot(7, s)) rainy++;
        double share = rainy / (double) slots;
        check(Math.abs(share - Weather.RAIN_CHANCE) < 0.02, "частка дощових відрізків ~" + Weather.RAIN_CHANCE + ": " + share);
        boolean smooth = true; double prev = Weather.rain(7, 0);
        for (long t = 1; t < 400_000; t++) { double r = Weather.rain(7, t); if (r < 0 || r > 1 || Math.abs(r - prev) > 0.01) smooth = false; prev = r; }
        check(smooth, "дощ наростає й стихає плавно (без стрибків) і лишається в 0..1");
        check(!Weather.wetBiome("Пустеля") && Weather.wetBiome("Рівнини") && Weather.wetBiome(null), "у пустелі сухо, на рівнинах - дощ");
        long left = Weather.ticksToChange(7, 12345);
        check(left > 0 && (Weather.rainySlot(7, Math.floorDiv(12345 + left, Weather.SLOT)) != Weather.rainySlot(7, Math.floorDiv(12345, Weather.SLOT))), "ticksToChange вказує на справжню зміну: " + left);
        // різні сіди - різна погода
        int diff = 0;
        for (long s = 0; s < 200; s++) if (Weather.rainySlot(1, s) != Weather.rainySlot(2, s)) diff++;
        check(diff > 20, "різні світи - різна погода: " + diff);
    }

    static void sun() {
        check(Math.abs(DayCycle.sunAngle(0.975)) < 1e-9, "схід сонця посеред світанку");
        check(Math.abs(DayCycle.sunAngle(0.65) - Math.PI) < 1e-9, "захід посеред вечора");
        double noon = DayCycle.sunAngle((0.975 + 0.65 + 1) / 2 % 1);
        check(Math.abs(noon - Math.PI / 2) < 1e-9, "зеніт посередині дня: " + noon);
        check(DayCycle.sunAngle(0.8) > Math.PI && DayCycle.sunAngle(0.8) < 2 * Math.PI, "уночі сонце під горизонтом (місяць над ним)");
        double prev = DayCycle.sunAngle(0.975); boolean mono = true;
        for (int i = 1; i <= 1000; i++) { double p = (0.975 + i / 1000.0) % 1, ang = DayCycle.sunAngle(p); if (i < 1000 && ang <= prev) mono = false; prev = ang; }
        check(mono, "кут сонця весь час росте протягом доби");
        // сонце видно (вище горизонту) рівно тоді, коли небо світле або світає/смеркає
        for (double p = 0; p < 1; p += 0.01) {
            double ang = DayCycle.sunAngle(p);
            if (DayCycle.sky(p) >= 1) check(Math.sin(ang) > 0, "повний день - сонце над горизонтом (p=" + p + ")");
            if (DayCycle.sky(p) <= DayCycle.NIGHT_SKY) check(Math.sin(ang) < 0, "ніч - сонце під горизонтом (p=" + p + ")");
        }
    }

    static World open(Game g, String name, WorldInfo.Type type) throws Exception {
        g.createWorld(name, 3, type);
        World w = g.openWorld(name);
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }

    static int surface(World w, long x, long z) { for (int y = 200; y > 0; y--) if (w.blockId(x, y, z) > 0) return y; return 0; }

    static void plants() throws Exception {
        Game g = new Game(new File(Files.createTempDirectory("vx-surface").toFile(), "saves"), null);
        World w = open(g, "P", WorldInfo.Type.FLAT);
        Source p = Sources.player("T");
        BlockType grassPl = w.blocks.byName("voxelin:tall_grass"), poppy = w.blocks.byName("voxelin:poppy"), stone = w.blocks.byName("voxelin:stone");
        check(grassPl != null && poppy != null && w.blocks.byName("voxelin:dandelion") != null && w.blocks.byName("voxelin:cornflower") != null, "трава й три квітки зареєстровані");
        check(World.isPlant(grassPl) && !grassPl.solid && !grassPl.opaque && grassPl.hardness == 0, "рослина прохідна, прозора, ламається одразу");
        check(CreativeMenu.category(w.itemTypes.get("voxelin:poppy"), poppy) == CreativeMenu.Tab.NATURE, "квіти - у вкладці природи");
        int s = surface(w, 4, 4);
        LocalActions act = new LocalActions(w, g.commands, p);
        check(act.placeBlock(4, s + 1, 4, "voxelin:poppy"), "мак садиться на землю");
        check(w.blockId(4, s + 1, 4) == poppy.id, "мак стоїть");
        check(!w.isSolid(4, s + 1, 4), "крізь мак можна пройти");
        check(!act.placeBlock(4, s + 3, 4, "voxelin:poppy"), "у повітрі (без опори) квітка не садиться");
        check(!act.placeBlock(4, s + 2, 4, "voxelin:poppy"), "на квітку квітка не садиться (опора - лише повний блок)");
        // блок, поставлений у клітинку рослини, замінює її
        check(act.placeBlock(4, s + 1, 4, "voxelin:stone") && w.blockId(4, s + 1, 4) == stone.id, "блок у клітинці квітки замінює її");
        // обсипання без опори: квітка падає предметом
        check(act.placeBlock(6, s + 1, 6, "voxelin:poppy"), "ще мак");
        int items0 = w.itemEntities().size();
        w.harvest(p, 6, s, 6, null, false); // забрали землю з-під маку (без дропу самої землі)
        check(w.blockId(6, s + 1, 6) == 0, "без опори мак обсипався");
        check(w.itemEntities().size() == items0 + 1 && w.itemEntities().get(w.itemEntities().size() - 1).item.equals("voxelin:poppy"), "мак випав предметом");
        // висока трава без опори - без дропу
        check(act.placeBlock(8, s + 1, 8, "voxelin:tall_grass"), "трава садиться");
        int items1 = w.itemEntities().size();
        w.harvest(p, 8, s, 8, null, false);
        check(w.blockId(8, s + 1, 8) == 0 && w.itemEntities().size() == items1, "висока трава обсипається без дропу");
        check(w.replaceable(0) && w.replaceable(grassPl.id) && !w.replaceable(stone.id), "replaceable: повітря й рослини - так, камінь - ні");
        List<String> out = g.commands.execute(p, w, null, "/weather");
        check(!out.isEmpty() && (out.get(0).startsWith("Ясно") || out.get(0).startsWith("Іде дощ")), "/weather: " + out);
        w.close();
    }

    static void generation() throws Exception {
        Game g = new Game(new File(Files.createTempDirectory("vx-surface-gen").toFile(), "saves"), null);
        TerrainGenerator tg = new TerrainGenerator(42, WorldInfo.Type.NORMAL, g.blocks);
        int grassId = g.blocks.byName("voxelin:grass").id;
        int plants = 0, flowers = 0, floating = 0;
        for (long cx = -6; cx <= 6; cx++) for (long cz = -6; cz <= 6; cz++) {
            Chunk c = new Chunk(cx, cz); tg.generate(c);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < Chunk.HEIGHT; y++) {
                int id = c.get(x, y, z);
                if (id <= 0 || !World.isPlant(g.blocks.get(id))) continue;
                plants++;
                if (!g.blocks.get(id).name.equals("voxelin:tall_grass")) flowers++;
                if (c.get(x, y - 1, z) != grassId) floating++;
            }
        }
        check(plants > 2000, "на рівнинах і в лісах росте трава: " + plants);
        check(flowers > 20 && flowers < plants / 4, "квіти є, але їх менше, ніж трави: " + flowers + " з " + plants);
        check(floating == 0, "кожна рослина стоїть на дерні: висить " + floating);
        // пласкому світу рослини не додаються (там будують)
        TerrainGenerator flat = new TerrainGenerator(42, WorldInfo.Type.FLAT, g.blocks);
        Chunk fc = new Chunk(0, 0); flat.generate(fc);
        boolean none = true;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 80; y++) { int id = fc.get(x, y, z); if (id > 0 && World.isPlant(g.blocks.get(id))) none = false; }
        check(none, "у пласкому світі трави нема");
    }

    static void mesh() {
        int air = 0, stone = 1, plant = 2;
        boolean[] opaque = {false, true, false}, trans = {false, false, false}, isAir = {true, false, false};
        int[] col = {0, 0x808080, 0xFFFFFF}, layer = {0, 0, 5};
        ChunkMesher.Props pr = new ChunkMesher.Props(opaque, trans, isAir, col, layer, layer, layer);
        pr.box = new float[3][]; pr.box[plant] = new float[0];
        pr.perCell = new boolean[3];
        pr.cross = new boolean[3]; pr.cross[plant] = true;
        short[] pad = new short[ChunkMesher.P * ChunkMesher.P * ChunkMesher.P];
        pad[ChunkMesher.index(3, 0, 3)] = (short) stone;
        pad[ChunkMesher.index(3, 1, 3)] = (short) plant;
        ChunkMesher.Mesh m = new ChunkMesher().build(pad, pr);
        check(m.solidQuads == 6 && m.plantQuads == 4, "камінь - 6 граней, рослина - 4 квади в окремому проході (дві площини з двох боків): " + m.solidQuads + "/" + m.plantQuads);
        // квади рослини: грані 6 і 7, діагональні, на всю висоту клітинки
        int f6 = 0, f7 = 0;
        for (int q = 0; q < m.plantQuads; q++) {
            int face = ((int) m.plants[q * 4 * ChunkMesher.FLOATS_PER_VERTEX + 4]) & 7;
            if (face == 6) f6++; else if (face == 7) f7++;
        }
        check(f6 == 2 && f7 == 2, "по дві лицьові й зворотні площини: " + f6 + "/" + f7);
        // рослина не закриває грань каменю під собою (вона не повний блок) - камінь має верхню грань
        boolean top = false;
        for (int q = 0; q < m.solidQuads; q++) { int base = q * 4 * ChunkMesher.FLOATS_PER_VERTEX; if ((((int) m.solid[base + 4]) & 7) == 2 && m.solid[base + 1] == 1f) top = true; }
        check(top, "верх каменю під травою видно");
    }
}
