import voxelin.core.*;
import voxelin.core.gen.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Записки Сторожа: серія записів, читання по порядку, стоянка в Тихих пустищах з гарантованою запискою, перекреслені абзаци. */
public class VoxelinNotesTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-notes").toFile(), "saves"), null);
        registry(); reading(); camp();
        System.out.println("VoxelinNotesTest: " + n + " перевірок OK");
    }

    static void registry() {
        check(g.items.get(Story.NOTE) != null && g.items.get(Story.NOTE).maxStack == 16, "предмет «Записка Сторожа»");
        check(Story.notes(g.records) == 7, "сім записок у серії: " + Story.notes(g.records));
        int struck = 0;
        for (int i = 1; i <= 7; i++) {
            RecordRegistry.Record r = g.records.get(Story.PREFIX + i);
            check(r != null && r.title.startsWith("Записка Сторожа"), "запис " + i);
            for (String p : r.text.split("\n")) if (Story.struck(p)) { struck++; check(p.length() > 3, "перекреслений абзац не порожній"); }
            check(!Story.struck(r.text.split("\n")[0]), "перший абзац - рука Сторожа: " + i);
        }
        check(struck >= 3, "кілька перекреслених абзаців (чужа рука): " + struck);
        for (RecordRegistry.Record r : g.records.all()) if (!r.id.startsWith(Story.PREFIX)) for (String p : r.text.split("\n")) check(!Story.struck(p), "інші записи без перекреслень: " + r.id);
        LootTable lt = g.loot.get("voxelin:keeper_note");
        check(lt != null, "таблиця keeper_note");
        Random rng = new Random(3);
        for (int i = 0; i < 200; i++) {
            List<Inventory.Stack> st = lt.roll(rng, g.items);
            check(st.size() == 1 && st.get(0).item.equals(Story.NOTE) && st.get(0).count == 1, "у скрині стоянки - рівно одна записка");
        }
    }

    static void reading() throws Exception {
        g.createWorld("r", 1, WorldInfo.Type.FLAT);
        World w = g.openWorld("r");
        List<String> got = new ArrayList<>();
        for (int i = 0; i < 7; i++) got.add(Story.read(w));
        for (int i = 0; i < 7; i++) check(got.get(i).equals(Story.PREFIX + (i + 1)) && w.info.records.contains(got.get(i)), "записки читаються по порядку: " + got);
        check(Story.read(w) == null, "восьма записка нічого не відкриває");
        g.createWorld("r2", 1, WorldInfo.Type.FLAT);
        World w2 = g.openWorld("r2");
        w2.unlockRecord(Story.PREFIX + "1"); w2.unlockRecord(Story.PREFIX + "3");
        check(Story.PREFIX.concat("2").equals(Story.read(w2)) && Story.PREFIX.concat("4").equals(Story.read(w2)), "пропущені відкриваються першими");
    }

    static void camp() {
        TerrainGenerator tg = new TerrainGenerator(12345, WorldInfo.Type.NORMAL, g.blocks, g.structures.templates());
        long[] loc = tg.locate("keeper_camp", 0, 0);
        check(loc != null, "стоянка Сторожа існує у світі");
        check(tg.biomeAt(loc[0], loc[2]).equals("Тихі пустища"), "стоянка - у Тихих пустищах: " + tg.biomeAt(loc[0], loc[2]));
        long[] loc2 = tg.locate("keeper_camp", loc[0] + 2000, loc[2]);
        check(loc2 != null && (loc2[0] != loc[0] || loc2[2] != loc[2]), "стоянок кілька (є друга далі)");
        // структура справді в чанках: скриня із запискою чекає на появу (Chunk.pending) або вже стоїть
        StructureTemplate t = null;
        for (StructureTemplate s : g.structures.templates()) if (s.name.equals("voxelin:keeper_camp")) t = s;
        check(t != null, "шаблон keeper_camp розібрано");
        int chest = g.blocks.byName("voxelin:chest").id, mono = g.blocks.byName("voxelin:monolith").id, chests = 0, monos = 0;
        for (long cx = (loc[0] >> 4) - 1; cx <= (loc[0] >> 4) + 1; cx++) for (long cz = (loc[2] >> 4) - 1; cz <= (loc[2] >> 4) + 1; cz++) {
            Chunk c = new Chunk(cx, cz); tg.generate(c);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 1; y < 200; y++) { int b = c.get(x, y, z); if (b == chest) chests++; if (b == mono) monos++; }
        }
        check(chests >= 2, "дві скрині стоянки на місці: " + chests);
        check(monos >= 4, "моноліт стоянки: " + monos);
    }
}
