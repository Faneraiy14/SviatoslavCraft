package voxelin.client;

import voxelin.client.ui.Gui;
import voxelin.core.*;

import java.util.ArrayList;
import java.util.List;

/** Журнал (J): ліворуч «Шлях» (цілі гри) і назви відкритих записів, праворуч вибране. */
final class JournalScreen {
    private final World world;
    private final List<RecordRegistry.Record> list = new ArrayList<>();
    private int selected = -1; // -1 - «Шлях»
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

    /** Цілі шляху: виконані - приглушено з галочкою, поточна - з підказкою, далі - лише назва найближчої. */
    private void path(float x, float y) {
        Gui.text("ШЛЯХ", x, y, 2.8f, 0.25f, 0.17f, 0.08f);
        List<Progress.Goal> goals = Progress.path(world.info);
        Progress.Goal cur = Progress.current(world.info);
        int ci = cur == null ? goals.size() : goals.indexOf(cur);
        int from = Math.max(0, ci - 6); // старі виконані прокручуються, щоб поточна й підказка влазили
        float ly = y + 44;
        if (from > 0) { Gui.text("... ще виконано: " + from, x, ly, 1.8f, 0.4f, 0.32f, 0.2f); ly += 24; }
        for (int k = from; k < goals.size() && k <= ci + 1; k++) {
            Progress.Goal gl = goals.get(k);
            if (k < ci) { Gui.text("[x] " + gl.title(), x, ly, 2f, 0.38f, 0.42f, 0.25f); ly += 26; continue; }
            if (k == ci) {
                Gui.text("> " + gl.title(), x, ly, 2.3f, 0.55f, 0.2f, 0.05f); ly += 32;
                List<String> hint = new ArrayList<>();
                wrap(gl.hint(), 42, hint);
                for (String l : hint) { Gui.text(l, x + 20, ly, 1.9f, 0.2f, 0.14f, 0.08f); ly += 25; }
                ly += 8;
                continue;
            }
            Gui.text("[ ] далі: " + gl.title(), x, ly, 2f, 0.4f, 0.32f, 0.2f); ly += 26;
        }
        if (cur == null) Gui.text("Гру пройдено. Світ твій.", x, ly + 10, 2.4f, 0.2f, 0.45f, 0.15f);
        else if (world.info.eggChoice == WorldInfo.EggChoice.NONE && ci >= goals.size() - 2) Gui.text("Після Дракона шлях розгалузиться.", x, ly + 10, 1.8f, 0.4f, 0.32f, 0.2f);
    }

    void draw(Gui g) {
        float w = 900, h = 560, x = (Gui.W - w) / 2f, y = (Gui.H - h) / 2f;
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.55f);
        Gui.rect(x, y, w, h, 0.78f, 0.72f, 0.56f, 0.98f);
        Gui.frame(x, y, w, h, 3, 0.3f, 0.22f, 0.12f);
        Gui.text("ЖУРНАЛ", x + 20, y + 16, 3.2f, 0.25f, 0.17f, 0.08f);
        if (g.button("ШЛЯХ", x + 16, y + 70, 290, 40, selected < 0, true)) selected = -1;
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            float ry = y + 116 + i * 46;
            if (g.button(list.get(i).title, x + 16, ry, 290, 40, i == selected, true)) selected = i;
        }
        if (selected < 0) path(x + 330, y + 72);
        else if (!list.isEmpty()) {
            RecordRegistry.Record r = list.get(Math.min(selected, list.size() - 1));
            Gui.text(r.title.toUpperCase(java.util.Locale.ROOT), x + 330, y + 72, 2.8f, 0.25f, 0.17f, 0.08f);
            List<String> lines = new ArrayList<>();
            wrap(r.text, 40, lines);
            float ly = y + 120;
            for (String l : lines) { Gui.text(l, x + 330, ly, 2.1f, 0.2f, 0.14f, 0.08f); ly += 28; }
        }
        if (list.isEmpty()) Gui.text("Записи відкриваються подіями.", x + 20, y + h - 52, 1.8f, 0.35f, 0.25f, 0.12f);
        if (g.button("ЗАКРИТИ", x + w - 220, y + h - 64, 200, 48)) close = true;
    }
}
