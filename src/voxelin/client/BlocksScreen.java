package voxelin.client;

import voxelin.client.code.BlockProgram;
import voxelin.client.code.BlockProgram.Field;
import voxelin.client.code.BlockProgram.Kind;
import voxelin.client.code.BlockProgram.Node;
import voxelin.client.code.Highlight;
import voxelin.client.ui.Gui;
import voxelin.core.BlockType;
import voxelin.loader.ScriptHost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Редактор блоків «як у Scratch» (рівень 2): ліворуч палітра (клік - вставити під вибраний блок), посередині стопки блоків під подіями,
 * праворуч - код NyxilumLang, у який перетворюється програма (оновлюється одразу). Поля - клік і друк (Enter - прийняти, Esc - скасувати),
 * перевірка одразу; ↑/↓ переносять блок (через обгортки - всередину й назовні). Ctrl+S - зберегти blocks.json і main.nx і перезапустити.
 */
final class BlocksScreen {
    static final float PX = 16, PW = 250, CX = 282, CW = 600, CY = 64, CH = 540, RX = 896, RW = 368, ROW = 30, IND = 22, TS = 1.6f;

    private final Voxelin v;
    private final ScriptHost host;
    final String id;
    private BlockProgram prog = new BlockProgram();
    private Node selected;
    private boolean modified;
    private int scroll, codeScroll;
    private String status = "";
    private long statusAt;
    private String pendingLeave;
    /** Поле, що редагується: блок, номер поля, текст. */
    private Node editNode;
    private int editField;
    private StringBuilder editText;
    private String editError;
    private List<String> blockNames;
    boolean close;
    /** Перейти у вікно коду цього скрипта. */
    boolean toCode;

    BlocksScreen(Voxelin v, ScriptHost host, String id) {
        this.v = v; this.host = host; this.id = id;
        v.input.beginEdit();
        blockNames = new ArrayList<>();
        for (BlockType t : v.game.blocks.all()) if (!t.isAir()) blockNames.add(t.name.startsWith("voxelin:") ? t.name.substring(8) : t.name);
        try {
            if (host.blocksFile(id).isFile()) prog = BlockProgram.fromJson(Files.readString(host.blocksFile(id).toPath(), StandardCharsets.UTF_8));
            else if (host.mainFile(id).isFile()) say("Скрипт написаний кодом - збереження блоків замінить main.nx");
        } catch (IOException | IllegalArgumentException e) { say("blocks.json не прочитано (" + e.getMessage() + ") - починаємо з нуля"); }
    }

    private void say(String s) { status = s; statusAt = System.currentTimeMillis(); }

    void end() { v.input.endEdit(); }

    private void save() {
        commitEdit();
        try { host.saveBlocks(id, prog.toJson(), prog.toCode(id)); modified = false; say("Збережено - скрипт «" + id + "» перезапускається"); }
        catch (IOException e) { say("Не збережено: " + e.getMessage()); }
    }

    private boolean leave(String what) {
        if (!modified || what.equals(pendingLeave)) return true;
        pendingLeave = what;
        say("Є незбережені зміни: Ctrl+S - зберегти, або ще раз - вийти без збереження");
        return false;
    }

    private void changed() { modified = true; pendingLeave = null; }

    // ------------------------------------------------------------------ кадр

    void draw(Gui g) {
        Input in = v.input;
        Gui.rect(0, 0, Gui.W, Gui.H, 0.02f, 0.03f, 0.07f, 0.97f);
        Gui.text("БЛОКИ: " + id + (modified ? " *" : ""), 16, 20, 2.6f, 1f, 0.85f, 0.35f);
        keys(in);
        if (close) return;
        palette(g);
        canvas(g, in);
        code(g, in);
        float by = 664;
        if (g.button("ЗБЕРЕГТИ (Ctrl+S)", CX, by, 290, 44, true, true)) save();
        if (g.button("^", CX + 300, by, 50, 44, false, selected != null) && prog.move(selected, -1)) changed();
        if (g.button("v", CX + 356, by, 50, 44, false, selected != null) && prog.move(selected, 1)) changed();
        if (g.button("КОПІЯ", CX + 412, by, 90, 44, false, selected != null)) { Node c = prog.duplicate(selected); if (c != null) { selected = c; changed(); } else say("Забагато блоків"); }
        if (g.button("ВИДАЛИТИ", CX + 508, by, 92, 44, false, selected != null)) { prog.delete(selected); selected = null; changed(); }
        if (g.button("КОД", RX, by, 150, 44) && leave("code")) { toCode = true; close = true; end(); }
        if (g.button("ЗАКРИТИ", RX + 160, by, RW - 160, 44)) tryClose();
        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 6000) Gui.text(status, 400, 26, 1.5f, 0.95f, 0.9f, 0.6f);
    }

    private void tryClose() { if (leave("close")) { close = true; end(); } }

    private void keys(Input in) {
        String typed = in.pollTyped();
        boolean ctrl = in.down(GLFW_KEY_LEFT_CONTROL) || in.down(GLFW_KEY_RIGHT_CONTROL);
        if (editText != null && !ctrl) for (char c : typed.toCharArray()) if (editText.length() < 200) editText.append(c);
        for (int[] e; (e = in.pollKey()) != null; ) {
            int key = e[0];
            boolean c = (e[1] & GLFW_MOD_CONTROL) != 0;
            if (c && key == GLFW_KEY_S) { save(); continue; }
            if (editText != null) {
                if (key == GLFW_KEY_ESCAPE) { editText = null; editError = null; }
                else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) commitEdit();
                else if (key == GLFW_KEY_BACKSPACE && editText.length() > 0) editText.setLength(editText.length() - 1);
                else if (key == GLFW_KEY_TAB) { List<String> s = suggestions(); if (!s.isEmpty()) { editText.setLength(0); editText.append(s.get(0)); } }
                else if (c && key == GLFW_KEY_V) { String cb = in.clipboard().replace("\n", " "); editText.append(cb, 0, Math.min(cb.length(), 200 - editText.length())); }
                continue;
            }
            if (key == GLFW_KEY_ESCAPE) { tryClose(); if (close) return; }
            else if (selected != null && key == GLFW_KEY_DELETE) { prog.delete(selected); selected = null; changed(); }
            else if (selected != null && key == GLFW_KEY_UP && c) { if (prog.move(selected, -1)) changed(); }
            else if (selected != null && key == GLFW_KEY_DOWN && c) { if (prog.move(selected, 1)) changed(); }
        }
        in.clearPressed();
    }

    /** Прийняти введене в поле, якщо годиться (інакше лишаємось у полі з поясненням). */
    private void commitEdit() {
        if (editText == null) return;
        Field f = editNode.kind.fields()[editField];
        String val = f == Field.BLOCK || f == Field.NAME ? editText.toString().trim().toLowerCase(java.util.Locale.ROOT) : editText.toString();
        String err = BlockProgram.check(f, val, editNode.kind.hat());
        if (err != null) { editError = err; return; }
        if (!val.equals(editNode.values[editField])) { editNode.values[editField] = val; changed(); }
        editText = null; editError = null;
    }

    private List<String> suggestions() {
        List<String> out = new ArrayList<>();
        if (editText == null || editNode.kind.fields()[editField] != Field.BLOCK) return out;
        String q = editText.toString().trim().toLowerCase(java.util.Locale.ROOT);
        for (String n : blockNames) if (n.startsWith(q)) out.add(n);
        for (String n : blockNames) if (!n.startsWith(q) && n.contains(q) && out.size() < 40) out.add(n);
        return out.size() > 6 ? out.subList(0, 6) : out;
    }

    // ------------------------------------------------------------------ палітра

    static float[] color(String category) {
        return switch (category) {
            case "події" -> new float[]{0.85f, 0.66f, 0.12f};
            case "дії" -> new float[]{0.3f, 0.52f, 0.92f};
            case "керування" -> new float[]{0.92f, 0.56f, 0.16f};
            default -> new float[]{0.86f, 0.36f, 0.28f};
        };
    }

    private void palette(Gui g) {
        InventoryScreen.panel(PX, CY, PW, CH);
        float y = CY + 8;
        String cat = "";
        for (Kind k : BlockProgram.KINDS) {
            if (!k.category().equals(cat)) { cat = k.category(); InventoryScreen.label(cat.toUpperCase(java.util.Locale.ROOT), PX + 10, y + 4); y += 22; }
            float[] c = color(k.category());
            String label = k.label().replace("%", "…");
            boolean hov = g.over(PX + 8, y, PW - 16, 24);
            Gui.rect(PX + 8, y, PW - 16, 24, c[0] * (hov ? 1.15f : 1f), c[1] * (hov ? 1.15f : 1f), c[2] * (hov ? 1.15f : 1f), 1f);
            if (k.hat()) Gui.rect(PX + 8, y, 46, 3, 1f, 1f, 1f, 0.5f);
            float sc = Math.min(1.45f, (PW - 30) / Math.max(1, Gui.textWidth(label, 1)));
            Gui.text(label, PX + 14, y + 12 - 3.5f * sc, sc, 1f, 1f, 1f);
            if (hov && g.click) {
                g.click = false;
                commitEdit();
                Node n = prog.insert(k, selected);
                if (n == null) say(prog.count() >= BlockProgram.MAX_BLOCKS ? "Забагато блоків (до " + BlockProgram.MAX_BLOCKS + ")" : "Повтори можна вкладати лише на " + BlockProgram.MAX_LOOP_DEPTH + " рівні");
                else { selected = n; changed(); }
            }
            y += 27;
        }
    }

    // ------------------------------------------------------------------ полотно

    private record Row(Node node, int depth, boolean end) {}

    private void rows(List<Node> list, int depth, List<Row> out) {
        for (Node n : list) {
            out.add(new Row(n, depth, false));
            if (n.kind.wraps()) { rows(n.body, depth + 1, out); out.add(new Row(n, depth, true)); }
        }
    }

    private void canvas(Gui g, Input in) {
        InventoryScreen.panel(CX, CY, CW, CH);
        List<Row> rows = new ArrayList<>();
        for (Node h : prog.hats) { rows(List.of(h), 0, rows); rows.add(null); } // null - відступ між стопками
        int visible = (int) ((CH - 16) / ROW);
        if (g.over(CX, CY, CW, CH)) { double sc = in.consumeScroll(); if (sc != 0) scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible + 2), scroll - (int) Math.signum(sc) * 2)); }
        if (prog.hats.isEmpty()) {
            Gui.textCentered("Клікни блок у палітрі ліворуч -", CX + CW / 2f, CY + 200, 1.8f, 0.75f, 0.78f, 0.9f);
            Gui.textCentered("він стане під вибраним.", CX + CW / 2f, CY + 226, 1.8f, 0.75f, 0.78f, 0.9f);
            Gui.textCentered("Почни з події (жовті).", CX + CW / 2f, CY + 252, 1.8f, 0.75f, 0.78f, 0.9f);
        }
        boolean clickedBlock = false;
        for (int i = scroll; i < rows.size() && i - scroll < visible; i++) {
            Row r = rows.get(i);
            if (r == null) continue;
            float y = CY + 8 + (i - scroll) * ROW, x = CX + 10 + r.depth * IND;
            float[] c = color(r.node.kind.category());
            boolean sel = r.node == selected;
            if (r.end) { // нижня «щелепа» обгортки
                Gui.rect(x, y, 120, 12, c[0] * 0.85f, c[1] * 0.85f, c[2] * 0.85f, 1f);
                Gui.rect(x, y - ROW * 0 - 2, 8, 4, c[0] * 0.85f, c[1] * 0.85f, c[2] * 0.85f, 1f);
                continue;
            }
            if (r.node.kind.wraps()) { // ліва стінка «C» уздовж тіла
                int len = 1; for (int k = i + 1; k < rows.size() && rows.get(k) != null && !(rows.get(k).end && rows.get(k).node == r.node); k++) len++;
                Gui.rect(x, y, 10, Math.min(len, visible - (i - scroll)) * ROW, c[0] * 0.85f, c[1] * 0.85f, c[2] * 0.85f, 1f);
            }
            float w = drawBlock(g, r.node, x, y, c, sel);
            if (g.over(x, y, w, ROW - 4) && g.click) { g.click = false; clickedBlock = true; commitEdit(); selected = r.node; }
        }
        if (g.over(CX, CY, CW, CH) && g.click && !clickedBlock) { g.click = false; commitEdit(); selected = null; }
        if (editError != null) Gui.text(editError, CX + 10, CY + CH - 22, 1.5f, 1f, 0.45f, 0.4f);
        else if (editText != null) Gui.text("Enter - прийняти, Esc - скасувати" + (editNode.kind.fields()[editField] == Field.BLOCK ? ", Tab - підказка" : "")
                + (editNode.kind.fields()[editField] == Field.NUM ? "; можна i, j, k (повтори), arg (число з команди), змінні" : "")
                + (editNode.kind.fields()[editField] == Field.TEXT ? "; {назва} - значення змінної" : ""), CX + 10, CY + CH - 22, 1.35f, 0.7f, 0.75f, 0.85f);
        else Gui.text(prog.count() + " блоків. Зсув x y z - від гравця (у подіях блоку - від блоку)", CX + 10, CY + CH - 22, 1.3f, 0.55f, 0.58f, 0.7f);
    }

    /** Блок із полями; повертає ширину. Клік по полю - редагувати його. */
    private float drawBlock(Gui g, Node n, float x, float y, float[] c, boolean sel) {
        String label = n.kind.label();
        // ширина: текст + поля
        float w = 16;
        int f = 0;
        for (String part : label.split("%", -1)) {
            w += Gui.textWidth(part, TS);
            if (f < n.values.length) { w += fieldW(n, f) + 6; f++; }
        }
        float h = ROW - 4;
        Gui.rect(x, y, w, h, c[0], c[1], c[2], 1f);
        if (n.kind.hat()) Gui.rect(x, y - 3, Math.min(w, 70), 4, c[0], c[1], c[2], 1f); // «шапка»
        Gui.rect(x, y + h - 3, w, 3, c[0] * 0.7f, c[1] * 0.7f, c[2] * 0.7f, 1f);
        if (sel) Gui.frame(x - 1, y - 1, w + 2, h + 2, 2, 1f, 1f, 1f);
        float tx = x + 8;
        f = 0;
        for (String part : label.split("%", -1)) {
            Gui.text(part, tx, y + h / 2 - 3.5f * TS, TS, 1f, 1f, 1f);
            tx += Gui.textWidth(part, TS);
            if (f < n.values.length) {
                float fw = fieldW(n, f);
                boolean editing = editText != null && editNode == n && editField == f;
                Gui.rect(tx + 3, y + 3, fw, h - 6, 1f, 1f, 1f, editing ? 1f : 0.92f);
                if (editing && editError != null) Gui.frame(tx + 3, y + 3, fw, h - 6, 2, 1f, 0.3f, 0.3f);
                String val = editing ? editText + ((System.currentTimeMillis() / 500) % 2 == 0 ? "|" : " ") : n.values[f];
                Gui.text(val, tx + 7, y + h / 2 - 3.5f * TS, TS, 0.12f, 0.12f, 0.16f);
                if (g.over(tx + 3, y + 3, fw, h - 6) && g.click) {
                    g.click = false;
                    commitEdit();
                    selected = n;
                    if (n.kind.fields()[f] == Field.OP) { // оператор - клік перемикає
                        List<String> ops = BlockProgram.OPS;
                        n.values[f] = ops.get((ops.indexOf(n.values[f]) + 1) % ops.size()); changed();
                    } else if (editText == null) { editNode = n; editField = f; editText = new StringBuilder(n.values[f]); editError = null; }
                }
                if (editing) suggestBox(g, tx + 3, y + h);
                tx += fw + 6;
                f++;
            }
        }
        return w;
    }

    private float fieldW(Node n, int f) {
        boolean editing = editText != null && editNode == n && editField == f;
        String val = editing ? editText + "|" : n.values[f];
        return Math.max(26, Gui.textWidth(val, TS) + 10);
    }

    private void suggestBox(Gui g, float x, float y) {
        List<String> s = suggestions();
        if (s.isEmpty()) return;
        float w = 200;
        Gui.rect(x, y, w, s.size() * 20 + 4, 0.05f, 0.06f, 0.1f, 0.97f);
        for (int i = 0; i < s.size(); i++) {
            boolean hov = g.over(x, y + 2 + i * 20, w, 20);
            if (hov) Gui.rect(x, y + 2 + i * 20, w, 20, 0.25f, 0.3f, 0.5f, 1f);
            Gui.text(s.get(i), x + 6, y + 6 + i * 20, 1.4f, 0.9f, 0.92f, 1f);
            if (hov && g.click) { g.click = false; editText.setLength(0); editText.append(s.get(i)); commitEdit(); return; }
        }
    }

    // ------------------------------------------------------------------ код праворуч

    private void code(Gui g, Input in) {
        InventoryScreen.panel(RX, CY, RW, CH);
        InventoryScreen.label("КОД (NyxilumLang)", RX + 10, CY + 8);
        String[] lines = prog.toCode(id).split("\n");
        float sc = 1.15f, lh = 13.5f;
        int visible = (int) ((CH - 40) / lh), cols = (int) ((RW - 16) / (6 * sc));
        if (g.over(RX, CY, RW, CH)) { double s = in.consumeScroll(); if (s != 0) codeScroll = Math.max(0, Math.min(Math.max(0, lines.length - visible), codeScroll - (int) Math.signum(s) * 4)); }
        codeScroll = Math.min(codeScroll, Math.max(0, lines.length - visible));
        for (int i = codeScroll; i < lines.length && i - codeScroll < visible; i++) {
            String l = lines[i];
            float y = CY + 30 + (i - codeScroll) * lh;
            for (Highlight.Span sp : Highlight.spans(l)) {
                if (sp.from() >= cols) break;
                float[] c = Highlight.color(sp.kind());
                Gui.text(l.substring(sp.from(), Math.min(sp.to(), cols)), RX + 8 + sp.from() * 6 * sc, y, sc, c[0], c[1], c[2]);
            }
        }
    }

    /** Для знімків і тестів. */
    BlockProgram program() { return prog; }
    void select(Node n) { selected = n; }
}
