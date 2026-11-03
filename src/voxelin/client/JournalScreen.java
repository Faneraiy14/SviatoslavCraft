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
    /** Сторінка списку записів (по PER_PAGE на сторінку). */
    private int page;
    static final int PER_PAGE = 8;
    boolean close;

    JournalScreen(World world) {
        this.world = world;
        refresh();
    }

    private int known = -1;
    /** Перебудувати список, якщо відкрилися нові записи (і поки журнал відкритий). */
    private void refresh() {
        if (known == world.info.records.size()) return;
        known = world.info.records.size();
        list.clear();
        for (RecordRegistry.Record r : world.records.all()) if (world.info.records.contains(r.id)) list.add(r);
        if (pendingSelect >= 0 && pendingSelect < list.size()) { selected = pendingSelect; page = selected / PER_PAGE; pendingSelect = -1; }
    }
    private int pendingSelect = -1;

    /** Вибрати запис за номером у списку (для знімків); сторінка - та, де він. */
    void select(int i) { if (i >= 0 && i < list.size()) { selected = i; page = i / PER_PAGE; } else pendingSelect = i; }

    static int pages(int n) { return Math.max(1, (n + PER_PAGE - 1) / PER_PAGE); }

    /** Рядок тексту для малювання: перекреслений чи ні. */
    record Line(String text, boolean struck) {}

    /** Текст запису -> рядки під ширину; абзац з «~» (core/Story) - перекреслений, без самої тильди. */
    static List<Line> lines(String text, int width) {
        List<Line> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            boolean st = Story.struck(para);
            List<String> w = new ArrayList<>();
            wrap(st ? para.substring(1) : para, width, w);
            for (String l : w) out.add(new Line(l, st));
        }
        return out;
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
        refresh();
        float w = 900, h = 560, x = (Gui.W - w) / 2f, y = (Gui.H - h) / 2f;
        Gui.rect(0, 0, Gui.W, Gui.H, 0, 0, 0, 0.55f);
        Gui.rect(x, y, w, h, 0.78f, 0.72f, 0.56f, 0.98f);
        Gui.frame(x, y, w, h, 3, 0.3f, 0.22f, 0.12f);
        Gui.text("ЖУРНАЛ", x + 20, y + 16, 3.2f, 0.25f, 0.17f, 0.08f);
        if (g.button("ШЛЯХ", x + 16, y + 70, 290, 40, selected < 0, true)) selected = -1;
        int pc = pages(list.size());
        page = Math.max(0, Math.min(page, pc - 1));
        for (int k = 0; k < PER_PAGE; k++) {
            int i = page * PER_PAGE + k;
            if (i >= list.size()) break;
            float ry = y + 116 + k * 46;
            if (g.button(list.get(i).title, x + 16, ry, 290, 40, i == selected, true)) selected = i;
        }
        if (pc > 1) { // гортання списку: записів буває понад 20
            float py = y + 116 + PER_PAGE * 46;
            if (g.button("<", x + 16, py, 60, 34, false, page > 0)) page--;
            Gui.textCentered((page + 1) + " / " + pc, x + 161, py + 9, 2f, 0.25f, 0.17f, 0.08f);
            if (g.button(">", x + 246, py, 60, 34, false, page < pc - 1)) page++;
        }
        if (selected < 0) path(x + 330, y + 72);
        else if (!list.isEmpty()) {
            RecordRegistry.Record r = list.get(Math.min(selected, list.size() - 1));
            Gui.text(r.title.toUpperCase(java.util.Locale.ROOT), x + 330, y + 72, 2.8f, 0.25f, 0.17f, 0.08f);
            float ly = y + 120;
            for (Line l : lines(r.text, 40)) {
                if (l.struck()) { // чужа рука: блідіше й перекреслено
                    Gui.text(l.text(), x + 330, ly, 2.1f, 0.45f, 0.3f, 0.3f);
                    Gui.rect(x + 330, ly + 5, Gui.textWidth(l.text(), 2.1f), 2.5f, 0.35f, 0.12f, 0.12f, 0.9f);
                } else Gui.text(l.text(), x + 330, ly, 2.1f, 0.2f, 0.14f, 0.08f);
                ly += 28;
            }
        }
        if (list.isEmpty()) Gui.text("Записи відкриваються подіями.", x + 20, y + h - 52, 1.8f, 0.35f, 0.25f, 0.12f);
        if (g.button("ЗАКРИТИ", x + w - 220, y + h - 64, 200, 48)) close = true;
    }
}
