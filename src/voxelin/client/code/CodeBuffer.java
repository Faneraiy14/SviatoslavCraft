package voxelin.client.code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Текст скрипта у вікні коду гри: рядки, курсор, виділення, відступи по 4 пробіли, скасування. Без вікна й GL - лише модель (тестується).
 * Клавіші приходять як коди GLFW (константи вбудовуються при компіляції - LWJGL під час тестів не потрібен).
 * <ul>
 * <li>Enter зберігає відступ рядка (+4 після «{»), «}» на порожньому рядку відступає назад;</li>
 * <li>Tab / Shift+Tab - відступ виділених рядків або до наступної позиції кратної 4;</li>
 * <li>Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) - скасувати / повернути; суцільне друкування - один крок;</li>
 * <li>Ctrl+A/C/X/V, Ctrl+/ - закоментувати рядки, Ctrl+D - дублювати рядок, Ctrl+стрілки - по словах, Home - до першого символу.</li>
 * </ul>
 */
public final class CodeBuffer {
    public static final int MAX_CHARS = 200_000, INDENT = 4, UNDO = 300;

    private final List<StringBuilder> lines = new ArrayList<>();
    private int row, col, wantCol = -1;
    /** Початок виділення (anchorRow < 0 - нема). */
    private int anchorRow = -1, anchorCol;
    private final ArrayDeque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String lastKind = "";
    private long lastEditAt;
    private boolean modified;

    private record State(String text, int row, int col) {}

    public CodeBuffer(String text) { set(text); }

    // ------------------------------------------------------------------ читання

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) { if (i > 0) sb.append('\n'); sb.append(lines.get(i)); }
        return sb.toString();
    }

    public int lineCount() { return lines.size(); }
    public String line(int i) { return lines.get(i).toString(); }
    public int row() { return row; }
    public int col() { return col; }
    public boolean modified() { return modified; }
    public void markSaved() { modified = false; }
    public boolean hasSelection() { return anchorRow >= 0 && (anchorRow != row || anchorCol != col); }

    /** Виділення {рядок1, стовпець1, рядок2, стовпець2} (від меншого) або null. */
    public int[] selection() {
        if (!hasSelection()) return null;
        boolean fwd = anchorRow < row || (anchorRow == row && anchorCol < col);
        return fwd ? new int[]{anchorRow, anchorCol, row, col} : new int[]{row, col, anchorRow, anchorCol};
    }

    public String selectedText() {
        int[] s = selection();
        if (s == null) return "";
        if (s[0] == s[2]) return lines.get(s[0]).substring(s[1], s[3]);
        StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int r = s[0] + 1; r < s[2]; r++) sb.append('\n').append(lines.get(r));
        return sb.append('\n').append(lines.get(s[2]), 0, s[3]).toString();
    }

    // ------------------------------------------------------------------ зміни

    /** Замінити весь текст (без кроку скасування). Табуляції стають 4 пробілами, \r прибирається. */
    public void set(String text) {
        lines.clear();
        String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", " ".repeat(INDENT));
        if (t.length() > MAX_CHARS) t = t.substring(0, MAX_CHARS);
        for (String l : t.split("\n", -1)) lines.add(new StringBuilder(l));
        row = col = 0; anchorRow = -1; wantCol = -1;
        undo.clear(); redo.clear(); modified = false; lastKind = "";
    }

    /** Поставити курсор (клік мишею); shift - розширити виділення. */
    public void moveTo(int r, int c, boolean shift) {
        startMove(shift);
        row = clamp(r, 0, lines.size() - 1); col = clamp(c, 0, lines.get(row).length()); wantCol = -1;
        endMove(shift);
    }

    /** Надрукований текст (з колбека символів). */
    public void type(String s) {
        if (s.isEmpty()) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\n') continue;
            if (c == '}' && !hasSelection() && lines.get(row).toString().isBlank() && col == lines.get(row).length()) { // «}» вирівнюється під відкривну
                snapshot("type");
                int cut = Math.min(INDENT, lines.get(row).length());
                lines.get(row).delete(0, cut); col -= cut;
                insertRaw("}");
                continue;
            }
            if (c == '\n') { newline(); continue; }
            snapshot("type");
            deleteSelection();
            insertRaw(String.valueOf(c));
        }
    }

    /** Клавіша (натиск або автоповтор). Повертає true, якщо оброблено. clipboard - що в буфері обміну (для Ctrl+V), copy - куди класти. */
    public boolean key(int key, boolean ctrl, boolean shift, String clipboard, Consumer<String> copy) {
        switch (key) {
            case GLFW_KEY_LEFT -> { if (!shift && hasSelection()) collapse(true); else { startMove(shift); if (ctrl) wordLeft(); else left(); endMove(shift); } }
            case GLFW_KEY_RIGHT -> { if (!shift && hasSelection()) collapse(false); else { startMove(shift); if (ctrl) wordRight(); else right(); endMove(shift); } }
            case GLFW_KEY_UP -> { startMove(shift); vertical(-1); endMove(shift); }
            case GLFW_KEY_DOWN -> { startMove(shift); vertical(1); endMove(shift); }
            case GLFW_KEY_PAGE_UP -> { startMove(shift); vertical(-20); endMove(shift); }
            case GLFW_KEY_PAGE_DOWN -> { startMove(shift); vertical(20); endMove(shift); }
            case GLFW_KEY_HOME -> { startMove(shift); if (ctrl) { row = 0; col = 0; } else { int f = firstNonSpace(row); col = col == f ? 0 : f; } wantCol = -1; endMove(shift); }
            case GLFW_KEY_END -> { startMove(shift); if (ctrl) row = lines.size() - 1; col = lines.get(row).length(); wantCol = -1; endMove(shift); }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> newline();
            case GLFW_KEY_BACKSPACE -> backspace(ctrl);
            case GLFW_KEY_DELETE -> delete();
            case GLFW_KEY_TAB -> { if (shift) dedentLines(); else tab(); }
            default -> {
                if (!ctrl) return false;
                switch (key) {
                    case GLFW_KEY_A -> { anchorRow = 0; anchorCol = 0; row = lines.size() - 1; col = lines.get(row).length(); }
                    case GLFW_KEY_C -> { if (hasSelection()) copy.accept(selectedText()); else copy.accept(lines.get(row) + "\n"); }
                    case GLFW_KEY_X -> {
                        snapshot("cut");
                        if (hasSelection()) { copy.accept(selectedText()); deleteSelection(); }
                        else { copy.accept(lines.get(row) + "\n"); if (lines.size() > 1) { lines.remove(row); row = Math.min(row, lines.size() - 1); } else lines.get(0).setLength(0); col = Math.min(col, lines.get(row).length()); }
                    }
                    case GLFW_KEY_V -> { if (clipboard != null && !clipboard.isEmpty()) { snapshot("paste"); deleteSelection(); insertRaw(clipboard.replace("\r\n", "\n").replace('\r', '\n').replace("\t", " ".repeat(INDENT))); lastKind = ""; } }
                    case GLFW_KEY_Z -> { if (shift) redo(); else undo(); }
                    case GLFW_KEY_Y -> redo();
                    case GLFW_KEY_D -> { snapshot("dup"); lines.add(row + 1, new StringBuilder(lines.get(row))); row++; lastKind = ""; }
                    case GLFW_KEY_SLASH -> toggleComment();
                    default -> { return false; }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ рух

    private void startMove(boolean shift) { if (shift && anchorRow < 0) { anchorRow = row; anchorCol = col; } }
    private void endMove(boolean shift) { if (!shift) anchorRow = -1; lastKind = ""; }
    private void collapse(boolean toStart) { int[] s = selection(); row = toStart ? s[0] : s[2]; col = toStart ? s[1] : s[3]; anchorRow = -1; wantCol = -1; }

    private void left() { if (col > 0) col--; else if (row > 0) { row--; col = lines.get(row).length(); } wantCol = -1; }
    private void right() { if (col < lines.get(row).length()) col++; else if (row < lines.size() - 1) { row++; col = 0; } wantCol = -1; }

    private void vertical(int d) {
        if (wantCol < 0) wantCol = col;
        int r = clamp(row + d, 0, lines.size() - 1);
        if (r == row) { col = d < 0 ? 0 : lines.get(row).length(); return; }
        row = r; col = Math.min(wantCol, lines.get(row).length());
    }

    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private void wordLeft() {
        if (col == 0) { left(); return; }
        StringBuilder l = lines.get(row);
        while (col > 0 && !word(l.charAt(col - 1))) col--;
        while (col > 0 && word(l.charAt(col - 1))) col--;
        wantCol = -1;
    }

    private void wordRight() {
        StringBuilder l = lines.get(row);
        if (col == l.length()) { right(); return; }
        while (col < l.length() && !word(l.charAt(col))) col++;
        while (col < l.length() && word(l.charAt(col))) col++;
        wantCol = -1;
    }

    private int firstNonSpace(int r) { StringBuilder l = lines.get(r); int i = 0; while (i < l.length() && l.charAt(i) == ' ') i++; return i; }

    // ------------------------------------------------------------------ редагування

    private int size() { int n = lines.size() - 1; for (StringBuilder l : lines) n += l.length(); return n; }

    /** Вставити як є (з \n), курсор - у кінець вставленого. Обрізає, якщо текст став би більшим за MAX_CHARS. */
    private void insertRaw(String s) {
        int room = MAX_CHARS - size();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        StringBuilder l = lines.get(row);
        String tail = l.substring(col);
        l.setLength(col);
        String[] parts = s.split("\n", -1);
        l.append(parts[0]);
        for (int i = 1; i < parts.length; i++) { lines.add(row + 1, new StringBuilder(parts[i])); row++; }
        col = lines.get(row).length();
        lines.get(row).append(tail);
        wantCol = -1; anchorRow = -1; modified = true;
    }

    private void deleteSelection() {
        int[] s = selection();
        anchorRow = -1;
        if (s == null) return;
        StringBuilder a = lines.get(s[0]);
        String tail = lines.get(s[2]).substring(s[3]);
        a.setLength(s[1]); a.append(tail);
        for (int r = s[2]; r > s[0]; r--) lines.remove(r);
        row = s[0]; col = s[1]; wantCol = -1; modified = true;
    }

    private void newline() {
        snapshot("newline");
        deleteSelection();
        String before = lines.get(row).substring(0, col);
        int ind = 0;
        while (ind < before.length() && before.charAt(ind) == ' ') ind++;
        if (before.stripTrailing().endsWith("{")) ind += INDENT;
        String after = lines.get(row).substring(col);
        if (after.isBlank()) after = ""; // хвостові пробіли не переносимо
        insertRaw("\n" + " ".repeat(ind));
        if (after.startsWith("}") && before.stripTrailing().endsWith("{")) { // «{|}» -> тіло й закривна дужка на окремих рядках
            int c = col;
            insertRaw("\n" + " ".repeat(Math.max(0, ind - INDENT)));
            row--; col = c;
        }
        lastKind = "newline";
    }

    private void backspace(boolean ctrl) {
        snapshot("backspace");
        if (hasSelection()) { deleteSelection(); return; }
        anchorRow = -1;
        if (col == 0) {
            if (row == 0) return;
            StringBuilder prev = lines.get(row - 1);
            int c = prev.length();
            prev.append(lines.get(row));
            lines.remove(row);
            row--; col = c; modified = true;
            return;
        }
        StringBuilder l = lines.get(row);
        int from = col - 1;
        if (ctrl) { int c = col; wordLeft(); from = col; col = c; }
        else if (firstNonSpace(row) >= col) from = Math.max(0, ((col - 1) / INDENT) * INDENT); // у відступі - стираємо до попередньої позиції кратної 4
        l.delete(from, col); col = from; wantCol = -1; modified = true;
    }

    private void delete() {
        snapshot("delete");
        if (hasSelection()) { deleteSelection(); return; }
        StringBuilder l = lines.get(row);
        if (col < l.length()) l.deleteCharAt(col);
        else if (row < lines.size() - 1) { l.append(lines.get(row + 1)); lines.remove(row + 1); }
        else return;
        modified = true;
    }

    private void tab() {
        if (hasSelection() && selection()[0] != selection()[2]) { indentLines(); return; }
        snapshot("type");
        deleteSelection();
        insertRaw(" ".repeat(INDENT - col % INDENT));
    }

    private int[] lineRange() {
        int[] s = selection();
        if (s == null) return new int[]{row, row};
        int last = s[3] == 0 && s[2] > s[0] ? s[2] - 1 : s[2]; // виділення до початку рядка його не захоплює
        return new int[]{s[0], last};
    }

    private void indentLines() {
        snapshot("indent");
        int[] r = lineRange();
        for (int i = r[0]; i <= r[1]; i++) lines.get(i).insert(0, " ".repeat(INDENT));
        if (anchorRow >= 0) anchorCol = anchorCol == 0 ? 0 : anchorCol + INDENT;
        col = col == 0 && row > r[1] ? 0 : col + INDENT;
        modified = true; lastKind = "";
    }

    private void dedentLines() {
        snapshot("indent");
        int[] r = lineRange();
        for (int i = r[0]; i <= r[1]; i++) {
            int cut = Math.min(INDENT, firstNonSpace(i));
            lines.get(i).delete(0, cut);
            if (i == row) col = Math.max(0, col - cut);
            if (i == anchorRow) anchorCol = Math.max(0, anchorCol - cut);
        }
        modified = true; lastKind = "";
    }

    private void toggleComment() {
        snapshot("comment");
        int[] r = lineRange();
        boolean all = true;
        for (int i = r[0]; i <= r[1]; i++) if (!lines.get(i).toString().isBlank() && !lines.get(i).toString().stripLeading().startsWith("//")) all = false;
        int ind = Integer.MAX_VALUE;
        for (int i = r[0]; i <= r[1]; i++) if (!lines.get(i).toString().isBlank()) ind = Math.min(ind, firstNonSpace(i));
        if (ind == Integer.MAX_VALUE) return;
        for (int i = r[0]; i <= r[1]; i++) {
            StringBuilder l = lines.get(i);
            if (l.toString().isBlank()) continue;
            int d;
            if (all) { int f = firstNonSpace(i); d = l.length() > f + 2 && l.charAt(f + 2) == ' ' ? 3 : 2; l.delete(f, f + d); d = -d; }
            else { l.insert(ind, "// "); d = 3; }
            if (i == row) col = Math.max(0, Math.min(l.length(), col + d));
            if (i == anchorRow) anchorCol = Math.max(0, Math.min(l.length(), anchorCol + d));
        }
        modified = true; lastKind = "";
    }

    // ------------------------------------------------------------------ скасування

    /** Запам'ятати стан перед зміною; кроки того самого виду поспіль (друк, стирання) зливаються в один, якщо між ними < 1.5 с. */
    private void snapshot(String kind) {
        long now = System.nanoTime();
        boolean merge = kind.equals(lastKind) && (kind.equals("type") || kind.equals("backspace") || kind.equals("delete")) && now - lastEditAt < 1_500_000_000L;
        lastEditAt = now; lastKind = kind;
        if (merge) return;
        undo.push(new State(text(), row, col));
        while (undo.size() > UNDO) undo.removeLast();
        redo.clear();
    }

    private void undo() { if (undo.isEmpty()) return; redo.push(new State(text(), row, col)); restore(undo.pop()); }
    private void redo() { if (redo.isEmpty()) return; undo.push(new State(text(), row, col)); restore(redo.pop()); }

    private void restore(State s) {
        lines.clear();
        for (String l : s.text.split("\n", -1)) lines.add(new StringBuilder(l));
        row = clamp(s.row, 0, lines.size() - 1); col = clamp(s.col, 0, lines.get(row).length());
        anchorRow = -1; wantCol = -1; lastKind = ""; modified = true;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
