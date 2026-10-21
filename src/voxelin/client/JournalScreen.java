package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.ArrayList;
import java.util.List;

/** Журнал записів (J): ліворуч назви відкритих записів, праворуч текст вибраного. */
final class JournalScreen {
    private final World world;
    private final List<RecordRegistry.Record> list = new ArrayList<>();
    private int selected;
    boolean close;

    JournalScreen(World world) {
        this.world = world;
        for (RecordRegistry.Record r : world.records.all()) if (world.info.records.contains(r.id)) list.add(r);
    }

    static void wrap(String text, int width, List<String> out) {
        for (String para : text.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String w : para.split(" ")) {
                if (cur.length() + w.length() + 1 > width && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                if (cur.length() > 0) cur.append(' ');
                cur.append(w);
            }
            out.add(cur.toString());
        }
    }

    void draw(Gui g) {
        float w = 900, h = 560, x = (Gui.W - w) / 2f, y = (Gui.H - h) / 2f;
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.55f);
        Gui.rect(x, y, w, h, 0.78f, 0.72f, 0.56f, 0.98f);
        Gui.frame(x, y, w, h, 3, 0.3f, 0.22f, 0.12f);
        Gui.text("ЖУРНАЛ", x + 20, y + 16, 3.2f, 0.25f, 0.17f, 0.08f);
        if (list.isEmpty()) { Gui.text("Записів ще нема. Вони відкриваються подіями: Брама Шву, Край, Дракон...", x + 20, y + 90, 2.1f, 0.35f, 0.25f, 0.12f); }
        for (int i = 0; i < Math.min(9, list.size()); i++) {
            float ry = y + 70 + i * 46;
            if (g.button(list.get(i).title, x + 16, ry, 290, 40, i == selected, true)) selected = i;
        }
        if (!list.isEmpty()) {
            RecordRegistry.Record r = list.get(Math.min(selected, list.size() - 1));
            Gui.text(r.title.toUpperCase(java.util.Locale.ROOT), x + 330, y + 72, 2.8f, 0.25f, 0.17f, 0.08f);
            List<String> lines = new ArrayList<>();
            wrap(r.text, 40, lines);
            float ly = y + 120;
            for (String l : lines) { Gui.text(l, x + 330, ly, 2.1f, 0.2f, 0.14f, 0.08f); ly += 28; }
        }
        if (g.button("ЗАКРИТИ", x + w - 220, y + h - 64, 200, 48)) close = true;
    }
}
