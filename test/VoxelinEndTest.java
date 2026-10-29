import voxelin.api.*;
import voxelin.core.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Виміри, Енд, портал із правилом алмазної броні. */
public class VoxelinEndTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;
    static File root;
    static final String[] KINDS = {"helmet", "chestplate", "leggings", "boots"};

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("vx-end").toFile();
        g = new Game(new File(root, "saves"), null);
        generator(); portal(); dimension(); gate();
        System.out.println("VoxelinEndTest: " + n + " перевірок OK");
    }

    static int id(String name) { return g.blocks.byName("voxelin:" + name).id; }

    static void generator() {
        EndGenerator a = new EndGenerator(7, g.blocks), b = new EndGenerator(7, g.blocks), c = new EndGenerator(8, g.blocks);
        Chunk c0 = new Chunk(0, 0), c1 = new Chunk(0, 0), c2 = new Chunk(0, 0);
        a.generate(c0); b.generate(c1); c.generate(c2);
        boolean same = true, diff = false;
        for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) { if (c0.get(x, y, z) != c1.get(x, y, z)) same = false; if (c0.get(x, y, z) != c2.get(x, y, z)) diff = true; }
        check(same, "детерміновано для того самого зерна");
        check(c0.get(8, 62, 8) == id("end_stone") && c0.get(8, 10, 8) == 0, "острів із каменю Краю, під ним порожнеча");
        Chunk far = new Chunk(8, 0); a.generate(far); // x 128..143, поза островом (радіус 46) і до малих острівців (150)
        check(far.nonAirCount() == 0, "далеко від острова порожнеча");
        // платформа прибуття 7x7: обсидіан, 3x3 порталу в центрі, повітря над ним
        long px = EndGenerator.PLATFORM_X, pz = EndGenerator.PLATFORM_Z;
        Map<Long, Chunk> pcs = new HashMap<>();
        for (long cxx = Math.floorDiv(px - 3, 16L); cxx <= Math.floorDiv(px + 3, 16L); cxx++) { Chunk t = new Chunk(cxx, Math.floorDiv(pz, 16L)); a.generate(t); pcs.put(cxx, t); }
        java.util.function.BiFunction<Long, Long, Integer> at = (x, z) -> pcs.get(Math.floorDiv(x, 16L)).get((int) Math.floorMod(x, 16L), EndGenerator.TOP, (int) Math.floorMod(z, 16L));
        boolean portals = true, ring = true;
        for (long x = px - 3; x <= px + 3; x++) for (long z = pz - 3; z <= pz + 3; z++) { boolean inner = Math.abs(x - px) <= 1 && Math.abs(z - pz) <= 1; int v = at.apply(x, z); if (inner && v != id("end_portal")) portals = false; if (!inner && v != id("obsidian")) ring = false; }
        check(portals && ring, "платформа: обсидіан 7x7 з порталом 3x3 у центрі");
        Chunk pcc = pcs.get(Math.floorDiv(px, 16L));
        check(pcc.get((int) Math.floorMod(px, 16L), EndGenerator.TOP + 1, (int) Math.floorMod(pz + 3, 16L)) == 0 && pcc.get((int) Math.floorMod(px, 16L), EndGenerator.TOP + 3, (int) Math.floorMod(pz + 3, 16L)) == 0, "над платформою вільно");
        // вежі
        int obs = 0;
        for (long cx = -3; cx <= 3; cx++) for (long cz = -3; cz <= 3; cz++) { Chunk t = new Chunk(cx, cz); a.generate(t); for (int y = 0; y < 256; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (t.get(x, y, z) == id("obsidian") && y > EndGenerator.TOP + 6) obs++; }
        check(obs > 8 * 30, "обсидіанові вежі піднімаються над островом: " + obs);
        // малі острівці далеко
        int islands = 0;
        for (long cx = 10; cx < 50; cx++) for (long cz = 0; cz < 20; cz++) { Chunk t = new Chunk(cx, cz); a.generate(t); if (t.nonAirCount() > 0) islands++; }
        check(islands > 0, "малі острівці є: " + islands);
        check(a.biomeAt(0, 0).equals("Енд") && a.surfaceY(0, 0) >= EndGenerator.TOP - 3 && a.surfaceY(500, 500) >= 0, "біом і висота");
    }

    static World flat(String name) throws Exception {
        g.createWorld(name, 3, WorldInfo.Type.FLAT);
        World w = g.openWorld(name);
        for (int i = 0; i < 600; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        return w;
    }

    /** Кільце з 12 порожніх рамок навколо (cx,cz) на висоті y. Повертає позиції. */
    static List<long[]> ring(World w, Source p, long cx, int y, long cz) {
        List<long[]> r = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 2 || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) continue;
            w.setBlock(p, cx + dx, y, cz + dz, g.blocks.byName("voxelin:end_frame"));
            r.add(new long[]{cx + dx, cz + dz});
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) w.setBlock(p, cx + dx, y, cz + dz, g.blocks.air());
        return r;
    }

    static void portal() throws Exception {
        World w = flat("portal");
        Source p = Sources.player("P");
        int y = 80;
        List<long[]> cells = ring(w, p, 8, y, 8);
        check(cells.size() == 12, "12 клітинок кільця");
        Inventory inv = new Inventory(g.items);
        inv.add("voxelin:end_eye", 12);
        // перші 11 очей: «Око вставлено»
        for (int i = 0; i < 11; i++) {
            long[] c = cells.get(i);
            String m = EndPortal.insertEye(w, p, c[0], y, c[1], inv, false);
            check(m != null && m.startsWith("Око вставлено") && w.blockId(c[0], y, c[1]) == id("end_frame_eye"), "око " + (i + 1));
        }
        check(EndPortal.filledRing(w, 6, y, 8) == null, "11 очей - кільце ще не повне");
        check(w.blockId(8, y, 8) == 0, "портал ще не відкрився");
        // 12-те без броні
        long[] last = cells.get(11);
        String m = EndPortal.insertEye(w, p, last[0], y, last[1], inv, false);
        check(m != null && m.contains("потрібна повна алмазна") && w.blockId(last[0], y, last[1]) == id("end_frame") && w.blockId(8, y, 8) == 0, "без броні: відмова, рамка порожня, портала нема");
        check(Trading.count(inv, g.items, "voxelin:end_eye") == 1, "око повернулось в інвентар");
        // залізна броня не годиться
        for (int k = 0; k < 4; k++) inv.setArmor(k, new Inventory.Stack("voxelin:iron_" + KINDS[k], 1));
        m = EndPortal.insertEye(w, p, last[0], y, last[1], inv, false);
        check(m.contains("потрібна") && w.blockId(8, y, 8) == 0 && Trading.count(inv, g.items, "voxelin:end_eye") == 1, "залізна броня не відчиняє");
        // три алмазних шматки з чотирьох
        for (int k = 0; k < 3; k++) inv.setArmor(k, new Inventory.Stack("voxelin:diamond_" + KINDS[k], 1));
        m = EndPortal.insertEye(w, p, last[0], y, last[1], inv, false);
        check(m.contains("потрібна") && w.blockId(8, y, 8) == 0, "неповний алмазний комплект не відчиняє");
        // повний алмазний
        inv.setArmor(3, new Inventory.Stack("voxelin:diamond_boots", 1));
        m = EndPortal.insertEye(w, p, last[0], y, last[1], inv, false);
        check(m.contains("відчинився") && Trading.count(inv, g.items, "voxelin:end_eye") == 0, "повний алмазний комплект: портал відкрито, око витрачено: " + m);
        int portals = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (w.blockId(8 + dx, y, 8 + dz) == id("end_portal")) portals++;
        check(portals == 9, "отвір 3x3 заповнено порталом: " + portals);
        check(w.blocks.get(id("end_portal")).hardness < 0 && !w.blocks.get(id("end_portal")).solid && w.blocks.get(id("end_frame")).hardness < 0, "портал і рамки незламні, портал не суцільний");
        check(!w.harvest(p, 8, y, 8, g.items.get("voxelin:diamond_pickaxe"), true), "портал не зламати");
        // не в руці / не та рамка / креатив
        Inventory e = new Inventory(g.items);
        check(EndPortal.insertEye(w, p, cells.get(0)[0], y, cells.get(0)[1], e, false) == null, "без ока в руці - нічого");
        e.add("voxelin:end_eye", 1);
        check(EndPortal.insertEye(w, p, 30, y, 30, e, false) == null, "не рамка - нічого");
        check(EndPortal.insertEye(w, p, cells.get(0)[0], y, cells.get(0)[1], e, false) == null, "рамка вже з оком - нічого");
        // креатив: око не витрачається
        List<long[]> c2 = ring(w, p, 20, y, 8);
        Inventory cr = new Inventory(g.items);
        cr.add("voxelin:end_eye", 1);
        String mm = EndPortal.insertEye(w, p, c2.get(0)[0], y, c2.get(0)[1], cr, true);
        check(mm != null && Trading.count(cr, g.items, "voxelin:end_eye") == 1, "у креативі око не витрачається");
        // два кільця поруч незалежні: друге кільце неповне, перше не псується
        check(EndPortal.filledRing(w, 20 - 2, y, 8) == null && EndPortal.filledRing(w, cells.get(0)[0], y, cells.get(0)[1]) != null, "кільця незалежні");
        // порожнє кільце без портала не відкриється від чужого ока
        w.close();
    }

    static void dimension() throws Exception {
        g.createWorld("Dim", 55, WorldInfo.Type.NORMAL);
        World over = g.openWorld("Dim");
        over.naturalSpawning = false;
        Source p = Sources.player("P");
        Inventory inv = over.inventory(p);
        inv.add("voxelin:stone", 33); inv.setArmor(0, new Inventory.Stack("voxelin:diamond_helmet", 1, 5));
        over.vitals(p).damage(7);
        over.setGameMode("creative");
        over.close();
        WorldInfo oi = over.info;
        World end = g.openDimension("Dim", "end");
        check(new File(root, "saves/Dim/dimensions/end").isDirectory() && end.dimension().equals("end") && end.info.type == WorldInfo.Type.END && end.info.seed == 55, "теки й тип виміру");
        check(!g.listWorlds().contains("end") && g.listWorlds().contains("Dim"), "вимір не з'являється в списку світів");
        check(end.skyLevel() == World.END_SKY, "небо в Енді стале");
        end.setTime(0); check(end.skyLevel() == World.END_SKY, "час не міняє небо Енду");
        check(!end.naturalSpawning, "природного спавну в Енді нема");
        end.info.copyPlayerFrom(oi);
        end.info.hasReturn = true; end.info.returnX = 123; end.info.returnY = 70; end.info.returnZ = -456; end.info.dragonDead = true;
        Inventory ei = end.inventory(p);
        check(ei.get(0) != null && ei.get(0).count == 33 && ei.armor(0) != null && ei.armor(0).damage == 5, "інвентар і броня перенесені");
        check(end.creative() && end.vitals(p).health() > 0, "режим гри перенесено");
        end.close();
        World again = g.openDimension("Dim", "end");
        check(again.info.hasReturn && again.info.returnX == 123 && again.info.returnY == 70 && again.info.returnZ == -456 && again.info.dragonDead, "точка повернення й прапорець дракона збережені");
        check(again.inventory(p).get(0).count == 33, "інвентар Енду збережено окремо");
        again.close();
        // звичайний світ лишився
        World back = g.openWorld("Dim");
        check(back.dimension().equals("overworld") && back.skyLevel() >= DayCycle.NIGHT_SKY, "звичайний світ - звичайний");
        back.close();
        boolean threw = false;
        try { g.openDimension("Dim", "sift"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "невідомий вимір відхиляється");
        // биті поля повернення
        Files.write(new File(root, "saves/Dim/dimensions/end/voxelin.world").toPath(), (new String(Files.readAllBytes(new File(root, "saves/Dim/dimensions/end/voxelin.world").toPath())) + "\nreturnY=999\n").getBytes());
        check(!WorldInfo.load(new File(root, "saves/Dim/dimensions/end")).hasReturn, "биті координати повернення відкидаються");
        // перенесення без Return: copyPlayerFrom не чіпає чужих полів
        WorldInfo x = new WorldInfo(), y = new WorldInfo();
        x.health = 3; x.hasVitals = true; x.mode = WorldInfo.Mode.CREATIVE; x.inv[2] = "voxelin:stone|4|0"; x.hasInv = true;
        y.copyPlayerFrom(x);
        check(y.health == 3 && y.mode == WorldInfo.Mode.CREATIVE && y.inv[2].equals("voxelin:stone|4|0") && y.hasInv && !y.hasReturn, "copyPlayerFrom");
    }

    static void gate() throws Exception {
        check(g.structures.find("seam_gate") != null && g.structures.find("seam_gate").place == StructureTemplate.Place.UNDERGROUND, "шаблон брами Шву");
        StructureTemplate t = g.structures.find("seam_gate");
        int frames = 0;
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) if (t.id(x, y, z) == id("end_frame")) frames++;
        check(frames == 12, "у брамі 12 рамок: " + frames);
        // у світі: locate і справжнє кільце з відчиненим отвором
        g.createWorld("Gate", 12345, WorldInfo.Type.NORMAL);
        World w = g.openWorld("Gate");
        w.naturalSpawning = false;
        long[] loc = w.locate("seam_gate", 0, 0);
        check(loc != null, "/locate seam_gate знаходить");
        long cx = Math.floorDiv(loc[0], 16L), cz = Math.floorDiv(loc[2], 16L);
        for (int i = 0; i < 900; i++) { w.update(cx, cz, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(cx + dx, cz + dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int found = 0; long fx = 0, fz = 0; int fy = 0;
        for (long x = loc[0] - 8; x <= loc[0] + 8; x++) for (long z = loc[2] - 8; z <= loc[2] + 8; z++) for (int y = 10; y < 60; y++) if (w.blockId(x, y, z) == id("end_frame")) { found++; fx = x; fz = z; fy = y; }
        check(found == 12, "у світі 12 рамок: " + found);
        // місце, де має бути отвір: ставимо 12 очей і перевіряємо правило на справжній брамі
        Source p = Sources.player("P");
        Inventory inv = new Inventory(g.items);
        inv.add("voxelin:end_eye", 12);
        for (int k = 0; k < 4; k++) inv.setArmor(k, new Inventory.Stack("voxelin:diamond_" + KINDS[k], 1));
        String last = null;
        List<long[]> list = new ArrayList<>();
        for (long x = loc[0] - 8; x <= loc[0] + 8; x++) for (long z = loc[2] - 8; z <= loc[2] + 8; z++) if (w.blockId(x, fy, z) == id("end_frame")) list.add(new long[]{x, z});
        for (long[] c : list) last = EndPortal.insertEye(w, p, c[0], fy, c[1], inv, false);
        check(last != null && last.contains("відчинився"), "брама з шаблона відчиняється: " + last);
        w.close();
        // рецепт ока
        Inventory.Stack[] grid = new Inventory.Stack[4];
        grid[0] = new Inventory.Stack("voxelin:liminite_shard", 1); grid[1] = new Inventory.Stack("voxelin:diamond", 1); grid[3] = new Inventory.Stack("voxelin:gold_ingot", 1);
        Inventory.Stack r = Crafting.match(g.recipes, g.items, grid, 2);
        check(r != null && r.item.equals("voxelin:end_eye"), "рецепт Ока Енду");
    }
}
