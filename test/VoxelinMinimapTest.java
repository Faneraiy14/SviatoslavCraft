import voxelin.api.*;
import voxelin.client.Minimap;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;

/** Мінікарта без вікна: колір найвищого блока, вода, оновлення після зміни блока, наближення, печерний зріз, незавантажені чанки, бюджет. */
public class VoxelinMinimapTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static final Source P = Sources.player("P");

    public static void main(String[] a) throws Exception {
        Game g = new Game(new File(Files.createTempDirectory("vx-map").toFile(), "saves"), null);
        g.createWorld("m", 3, WorldInfo.Type.FLAT);
        World w = g.openWorld("m");
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int s0 = 0;
        for (int y = 200; y > 0; y--) if (w.blockId(0, y, 0) > 0) { s0 = y; break; }
        final int S = s0;
        BlockType stone = g.blocks.byName("voxelin:stone"), water = g.blocks.byName("voxelin:water"), air = g.blocks.byName("voxelin:air");
        int top = w.blockId(0, S, 0);
        int[] pal = new int[w.blocks.all().length];
        for (BlockType t : w.blocks.all()) pal[t.id] = 0xFF000000 | (t.id * 2654435 & 0xFFFFFF); // різні кольори для кожного блока
        pal[air.id] = 0;
        pal[water.id] = 0xFF0000FF;
        Minimap m = new Minimap(w, pal);

        // повний прохід за кілька кадрів (бюджет)
        int frames = 0;
        do { m.update(0, S + 2, 0, 0); frames++; } while (m.lastWork > Minimap.REFRESH && frames < 50);
        check(frames > 1 && frames < 20, "карта будується порціями: " + frames + " кадрів");
        check(m.colorAt(0, 0) == pal[top], "колір верхнього блока");
        check(!m.cave(), "на поверхні - не печера");
        // стовп з каменю: верх - камінь, схил світліший за рівну поверхню (вище за північного сусіда)
        w.batch(() -> { for (int y = S + 1; y <= S + 3; y++) w.setBlock(P, 5, y, 5, stone); });
        for (int i = 0; i < 40; i++) m.update(0, S + 2, 0, 0);
        int col = m.colorAt(5, 5);
        check(col != pal[top] && ((col >> 16) & 255) >= ((pal[stone.id] >> 16) & 255), "новий блок з'явився (коло оновлення), освітлений схил");
        // вода над дном - відтінок води, але не чиста вода
        w.batch(() -> { w.setBlock(P, -6, S, -6, water); });
        for (int i = 0; i < 40; i++) m.update(0, S + 2, 0, 0);
        int wc = m.colorAt(-6, -6);
        check(wc != pal[water.id] && (wc & 255) > ((wc >> 16) & 255), "вода: синя, крізь неї видно дно");
        // наближення 4: одна клітинка - 4 блоки, видно до незавантаженого світу
        for (int i = 0; i < 40; i++) m.update(0, S + 2, 0, 2);
        check(m.colorAt(-100, 100) == 0xFF14141C, "незавантажений чанк - темна клітинка");
        check(m.colorAt(1, 1) == pal[top] || m.colorAt(1, 1) != 0, "наближення 4 рахує середину клітинки");
        // печера: дах над головою -> зріз нижче очей, стіни темні
        w.batch(() -> {
            for (long x = -4; x <= 4; x++) for (long z = -4; z <= 4; z++) for (int y = S + 1; y <= S + 4; y++) w.setBlock(P, x, y, z, (Math.abs(x) == 4 || Math.abs(z) == 4 || y == S + 4) ? stone : air);
        });
        for (int i = 0; i < 200 && !m.cave(); i++) { w.update(0, 0, 3); w.tick(); m.update(0, S + 2, 0, 0); Thread.sleep(5); }
        check(m.cave(), "під дахом - печерний зріз");
        for (int i = 0; i < 40; i++) m.update(0, S + 2, 0, 0);
        check(m.colorAt(0, 0) == pal[top], "у кімнаті видно підлогу, а не дах");
        check(m.colorAt(4, 0) == 0xFF2A2A30, "стіна на рівні очей - темна");
        // величезні координати
        Minimap far = new Minimap(w, pal);
        far.update(1_000_000_000_000_000L, 70, -1_000_000_000_000_000L, 1);
        check(far.colorAt(1_000_000_000_000_000L, -1_000_000_000_000_000L) == 0xFF14141C, "1e15: без переповнень, світ не завантажений");
        // палітра з текстур: середнє непрозорих, прозоре - 0
        int[] tp = Minimap.palette(w, name -> { int[] px = new int[256]; java.util.Arrays.fill(px, name.contains("glass") ? 0 : 0xFF204060); if (name.contains("leaves")) for (int i = 0; i < 256; i += 4) px[i] = 0; return px; });
        check(tp[stone.id] == 0xFF204060, "палітра: середній колір текстури");
        check(tp[air.id] == 0, "повітря прозоре");
        check(tp[g.blocks.byName("voxelin:oak_leaves").id] == 0xFF204060, "листя з дірками видно на карті");
        BlockType glass = g.blocks.byName("voxelin:glass");
        check(glass == null || tp[glass.id] == 0, "скло прозоре");
        w.close();
        System.out.println("VoxelinMinimapTest: " + n + " перевірок OK");
        System.exit(0);
    }
}
