package voxelin.client.code;

import voxelin.core.Json;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Програма з блоків «як у Scratch» (рівень 2 у DESIGN.md): стопки під «шапками»-подіями, блоки-дії, блоки-обгортки (повторити, якщо).
 * Кожна програма перетворюється на справжній код NyxilumLang ({@link #toCode}) - гравець бачить його поруч і може перейти на код.
 * Зберігається в scripts/&lt;назва&gt;/blocks.json; main.nx поруч генерується з неї.
 * <p>
 * Поля перевіряються при введенні ({@link #check}), а читання JSON замінює погане значення типовим, тож модель завжди дає код, що
 * парситься: числа - вирази з цілих, лічильників повторів (i, j, k), числа з команди (arg) і змінних через + - *; назви - латиниця.
 */
public final class BlockProgram {
    public enum Field { TEXT, NUM, BLOCK, NAME, CMD, OP, PCT }

    /** Вид блока: id, категорія, підпис (поля - «%»), види полів, типові значення, чи обгортка (має тіло), чи шапка. */
    public record Kind(String id, String category, String label, Field[] fields, String[] defaults, boolean wraps, boolean hat) {}

    public static final List<Kind> KINDS = List.of(
            new Kind("start", "події", "коли скрипт запускається", new Field[0], new String[0], true, true),
            new Kind("command", "події", "коли гравець пише /%", new Field[]{Field.NAME}, new String[]{"hello"}, true, true),
            new Kind("second", "події", "кожну секунду", new Field[0], new String[0], true, true),
            new Kind("placed", "події", "коли гравець ставить %", new Field[]{Field.BLOCK}, new String[]{"torch"}, true, true),
            new Kind("broken", "події", "коли гравець ламає %", new Field[]{Field.BLOCK}, new String[]{"*"}, true, true),
            new Kind("say", "дії", "сказати %", new Field[]{Field.TEXT}, new String[]{"Привіт!"}, false, false),
            new Kind("setblock", "дії", "поставити % на % % %", new Field[]{Field.BLOCK, Field.NUM, Field.NUM, Field.NUM}, new String[]{"stone", "2", "0", "0"}, false, false),
            new Kind("fill", "дії", "заповнити % від % % % до % % %", new Field[]{Field.BLOCK, Field.NUM, Field.NUM, Field.NUM, Field.NUM, Field.NUM, Field.NUM}, new String[]{"glass", "-2", "0", "-2", "2", "3", "2"}, false, false),
            new Kind("run", "дії", "команда %", new Field[]{Field.CMD}, new String[]{"/time set 0"}, false, false),
            new Kind("repeat", "керування", "повторити % разів", new Field[]{Field.NUM}, new String[]{"5"}, true, false),
            new Kind("ifblock", "керування", "якщо на % % % є %", new Field[]{Field.NUM, Field.NUM, Field.NUM, Field.BLOCK}, new String[]{"0", "-1", "0", "grass"}, true, false),
            new Kind("ifchance", "керування", "з шансом % %", new Field[]{Field.PCT}, new String[]{"50"}, true, false),
            new Kind("setvar", "змінні", "змінна % = %", new Field[]{Field.NAME, Field.NUM}, new String[]{"count", "0"}, false, false),
            new Kind("changevar", "змінні", "змінна % += %", new Field[]{Field.NAME, Field.NUM}, new String[]{"count", "1"}, false, false),
            new Kind("ifvar", "змінні", "якщо % % %", new Field[]{Field.NAME, Field.OP, Field.NUM}, new String[]{"count", ">", "10"}, true, false));

    public static Kind kind(String id) { for (Kind k : KINDS) if (k.id.equals(id)) return k; return null; }

    /** Найглибше вкладення повторів (лічильники i, j, k). */
    public static final int MAX_LOOP_DEPTH = 3, MAX_BLOCKS = 500, MAX_REPEAT = 10000;
    public static final List<String> OPS = List.of("=", "≠", "<", ">", "≤", "≥");

    /** Блок: вид, значення полів, тіло (для обгорток і шапок). */
    public static final class Node {
        public final Kind kind;
        public final String[] values;
        public final List<Node> body = new ArrayList<>();
        public Node(Kind kind) { this.kind = kind; this.values = kind.defaults.clone(); }
        public Node copy() { Node n = new Node(kind); System.arraycopy(values, 0, n.values, 0, values.length); for (Node c : body) n.body.add(c.copy()); return n; }
    }

    /** Шапки (події) з їхніми стопками. */
    public final List<Node> hats = new ArrayList<>();

    // ------------------------------------------------------------------ перевірка полів

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,15}"), BLOCK = Pattern.compile("[a-z0-9_]{1,32}(:[a-z0-9_]{1,32})?"),
            TERM = Pattern.compile("\\d{1,6}|[a-z][a-z0-9_]{0,15}"), OPER = Pattern.compile("[-+*]"), PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]{0,15})}");

    /** null - значення годиться, інакше пояснення українською. hat - поле шапки (там блок може бути «*» - будь-який). */
    public static String check(Field f, String v, boolean hat) {
        if (v == null) return "порожньо";
        switch (f) {
            case TEXT -> { if (v.length() > 120) return "до 120 символів"; if (v.indexOf('\n') >= 0) return "один рядок"; }
            case CMD -> { if (!v.startsWith("/")) return "команда починається з /"; if (v.length() > 200) return "до 200 символів"; if (v.indexOf('\n') >= 0) return "один рядок"; }
            case NAME -> { if (!NAME.matcher(v).matches()) return "латиниця, цифри, _ (до 16), з літери"; }
            case BLOCK -> { if (!(hat && v.equals("*")) && !BLOCK.matcher(v).matches()) return hat ? "назва блока латиницею (stone, voxelin:torch) або *" : "назва блока латиницею (stone, voxelin:torch)"; }
            case OP -> { if (!OPS.contains(v)) return "одне з " + OPS; }
            case PCT -> { try { int p = Integer.parseInt(v.trim()); if (p < 1 || p > 100) return "від 1 до 100"; } catch (NumberFormatException e) { return "ціле число 1..100"; } }
            case NUM -> { if (expr(v) == null) return "число або вираз: 3, i*2, arg+1, count-5"; }
        }
        return null;
    }

    /** Вираз (до 4 доданків через + - *) -> токени, або null. Пробіли можна. */
    static List<String> expr(String v) {
        String s = v.replace(" ", "");
        if (s.isEmpty() || s.length() > 64) return null;
        List<String> out = new ArrayList<>();
        int i = 0;
        boolean wantTerm = true;
        while (i < s.length()) {
            if (wantTerm) {
                if (s.charAt(i) == '-' && (out.isEmpty() || OPER.matcher(out.get(out.size() - 1)).matches())) { out.add("-u"); i++; continue; }
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                String t = s.substring(i, j);
                if (!TERM.matcher(t).matches()) return null;
                out.add(t); i = j; wantTerm = false;
            } else {
                String op = s.substring(i, i + 1);
                if (!OPER.matcher(op).matches()) return null;
                out.add(op); i++; wantTerm = true;
            }
        }
        long terms = out.stream().filter(t -> TERM.matcher(t).matches()).count();
        return wantTerm || terms > 4 ? null : out;
    }

    // ------------------------------------------------------------------ код

    /** Код NyxilumLang для scripts/&lt;id&gt;/main.nx. */
    public String toCode(String id) {
        StringBuilder sb = new StringBuilder();
        sb.append("import \"../voxelin.nx\"\n\n");
        sb.append("// Створено редактором блоків Voxelin (").append(id).append("/blocks.json).\n");
        sb.append("// Зміни тут перезапише редактор блоків; щоб писати кодом - видали blocks.json.\n\n");
        Set<String> vars = new TreeSet<>();
        for (Node h : hats) collectVars(h, vars);
        for (String v : vars) sb.append("var v_").append(v).append(" = 0\n");
        sb.append("var vx_started = false\n\n");
        sb.append("func vxPos(v) {\n    return floor(v)\n}\n\n");
        List<String> starts = new ArrayList<>(), seconds = new ArrayList<>(), blockCalls = new ArrayList<>(), commands = new ArrayList<>();
        Set<String> cmdNames = new HashSet<>();
        for (int n = 0; n < hats.size(); n++) {
            Node h = hats.get(n);
            String fn = "vx_h" + (n + 1);
            sb.append("// ").append(describe(h)).append('\n');
            sb.append("func ").append(fn).append("(ev) {\n");
            prologue(sb, h);
            body(sb, h.body, 1, 0);
            sb.append("    return null\n}\n\n");
            switch (h.kind.id) {
                case "start" -> starts.add(fn);
                case "second" -> seconds.add(fn);
                case "command" -> { if (cmdNames.add(h.values[0])) commands.add("    command(\"" + h.values[0] + "\", \"[число]\", " + fn + ")\n"); }
                case "placed" -> blockCalls.add(h.values[0].equals("*") ? "    if (nw != \"voxelin:air\") {\n        " + fn + "(ev)\n    }\n"
                        : "    if (nw == \"" + full(h.values[0]) + "\") {\n        " + fn + "(ev)\n    }\n");
                case "broken" -> blockCalls.add(h.values[0].equals("*") ? "    if (nw == \"voxelin:air\" && old != \"voxelin:air\") {\n        " + fn + "(ev)\n    }\n"
                        : "    if (nw == \"voxelin:air\" && old == \"" + full(h.values[0]) + "\") {\n        " + fn + "(ev)\n    }\n");
                default -> { }
            }
        }
        if (!starts.isEmpty() || !seconds.isEmpty()) {
            sb.append("func vx_second(ev) {\n");
            if (!starts.isEmpty()) { // «коли запускається» - на першій секунді: до vxRun() гра ще не відповідає на запити (player, run)
                sb.append("    if (!vx_started) {\n        vx_started = true\n");
                for (String f : starts) sb.append("        ").append(f).append("(ev)\n");
                sb.append("    }\n");
            }
            for (String f : seconds) sb.append("    ").append(f).append("(ev)\n");
            sb.append("    return null\n}\n\n");
        }
        if (!blockCalls.isEmpty()) {
            sb.append("func vx_block(ev) {\n    var nw = mapGet(ev, \"new\")\n    var old = mapGet(ev, \"old\")\n");
            for (String c : blockCalls) sb.append(c);
            sb.append("    return null\n}\n\n");
        }
        sb.append("func main() {\n");
        if (!starts.isEmpty() || !seconds.isEmpty()) sb.append("    on(\"second\", vx_second)\n");
        if (!blockCalls.isEmpty()) sb.append("    on(\"block\", vx_block)\n");
        for (String c : commands) sb.append(c);
        sb.append("    vxRun()\n}\n");
        return sb.toString();
    }

    /** Звідки рахуються позиції: у подіях блоку - від блоку, інакше - від ніг гравця. */
    private static void prologue(StringBuilder sb, Node h) {
        sb.append("    var vx_i = 0\n    var vx_j = 0\n    var vx_k = 0\n    var vx_ni = 0\n    var vx_nj = 0\n    var vx_nk = 0\n    var vx_arg = 0\n");
        switch (h.kind.id) {
            case "command" -> sb.append("    var vx_args = mapGet(ev, \"args\")\n    if (len(vx_args) > 0) {\n        vx_arg = toInt(vx_args[0])\n    }\n")
                    .append("    var px = vxPos(mapGet(ev, \"x\"))\n    var py = vxPos(mapGet(ev, \"y\"))\n    var pz = vxPos(mapGet(ev, \"z\"))\n");
            case "placed", "broken" -> sb.append("    var px = vxPos(mapGet(ev, \"x\"))\n    var py = vxPos(mapGet(ev, \"y\"))\n    var pz = vxPos(mapGet(ev, \"z\"))\n");
            default -> sb.append("    var p = player()\n    if (isNull(p)) {\n        return null\n    }\n")
                    .append("    var px = vxPos(mapGet(p, \"x\"))\n    var py = vxPos(mapGet(p, \"y\"))\n    var pz = vxPos(mapGet(p, \"z\"))\n");
        }
    }

    private static final String[] LOOP = {"vx_i", "vx_j", "vx_k"}, LIMIT = {"vx_ni", "vx_nj", "vx_nk"};

    private static void body(StringBuilder sb, List<Node> list, int depth, int loops) {
        String ind = "    ".repeat(depth);
        for (Node n : list) {
            String[] v = n.values;
            switch (n.kind.id) {
                case "say" -> sb.append(ind).append("say(").append(text(v[0])).append(")\n");
                case "setblock" -> sb.append(ind).append("setBlock(px + ").append(num(v[1])).append(", py + ").append(num(v[2])).append(", pz + ").append(num(v[3])).append(", \"").append(full(v[0])).append("\")\n");
                case "fill" -> sb.append(ind).append("run(\"/fill \" + (px + ").append(num(v[1])).append(") + \" \" + (py + ").append(num(v[2])).append(") + \" \" + (pz + ").append(num(v[3]))
                        .append(") + \" \" + (px + ").append(num(v[4])).append(") + \" \" + (py + ").append(num(v[5])).append(") + \" \" + (pz + ").append(num(v[6])).append(") + \" ").append(full(v[0])).append("\")\n");
                case "run" -> sb.append(ind).append("run(").append(text(v[0])).append(")\n");
                case "setvar" -> sb.append(ind).append("v_").append(v[0]).append(" = ").append(num(v[1])).append('\n');
                case "changevar" -> sb.append(ind).append("v_").append(v[0]).append(" = v_").append(v[0]).append(" + ").append(num(v[1])).append('\n');
                case "repeat" -> {
                    if (loops >= MAX_LOOP_DEPTH) { sb.append(ind).append("// повтор глибше ").append(MAX_LOOP_DEPTH).append(" рівнів пропущено\n"); continue; }
                    String c = LOOP[loops], lim = LIMIT[loops];
                    sb.append(ind).append(lim).append(" = clamp(").append(num(v[0])).append(", 0, ").append(MAX_REPEAT).append(")\n");
                    sb.append(ind).append(c).append(" = 0\n");
                    sb.append(ind).append("while (").append(c).append(" < ").append(lim).append(") {\n");
                    body(sb, n.body, depth + 1, loops + 1);
                    sb.append(ind).append("    ").append(c).append(" = ").append(c).append(" + 1\n");
                    sb.append(ind).append("}\n");
                }
                case "ifblock" -> wrap(sb, ind, "getBlock(px + " + num(v[0]) + ", py + " + num(v[1]) + ", pz + " + num(v[2]) + ") == \"" + full(v[3]) + "\"", n, depth, loops);
                case "ifchance" -> wrap(sb, ind, "randomInt(1, 100) <= " + Integer.parseInt(v[0].trim()), n, depth, loops);
                case "ifvar" -> wrap(sb, ind, "v_" + v[0] + " " + op(v[1]) + " " + num(v[2]), n, depth, loops);
                default -> { }
            }
        }
    }

    private static void wrap(StringBuilder sb, String ind, String cond, Node n, int depth, int loops) {
        sb.append(ind).append("if (").append(cond).append(") {\n");
        body(sb, n.body, depth + 1, loops);
        sb.append(ind).append("}\n");
    }

    private static String op(String o) { return switch (o) { case "=" -> "=="; case "≠" -> "!="; case "≤" -> "<="; case "≥" -> ">="; default -> o; }; }

    /** Вираз поля -> код у дужках. */
    static String num(String v) {
        List<String> t = expr(v);
        if (t == null) return "(0)";
        StringBuilder sb = new StringBuilder("(");
        for (String x : t) {
            if (x.equals("-u")) sb.append('-');
            else if (OPER.matcher(x).matches()) sb.append(' ').append(x).append(' ');
            else sb.append(term(x));
        }
        return sb.append(')').toString();
    }

    private static String term(String x) {
        if (Character.isDigit(x.charAt(0))) return x;
        return switch (x) { case "i" -> "vx_i"; case "j" -> "vx_j"; case "k" -> "vx_k"; case "arg" -> "vx_arg"; default -> "v_" + x; };
    }

    /** Текст із {назва} -> рядковий вираз NyxilumLang (лапки й \ екрануються). */
    static String text(String v) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = PLACEHOLDER.matcher(v);
        int last = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() > last) { sb.append(any ? " + " : "").append(literal(v.substring(last, m.start()))); any = true; }
            sb.append(any ? " + " : "").append("toString(").append(term(m.group(1))).append(')');
            any = true; last = m.end();
        }
        if (last < v.length() || !any) sb.append(any ? " + " : "").append(literal(v.substring(last)));
        return sb.toString();
    }

    private static String literal(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\');
            if (c >= 32) sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String full(String b) { return b.contains(":") ? b : "voxelin:" + b; }

    private static void collectVars(Node n, Set<String> out) {
        for (int i = 0; i < n.values.length; i++) {
            Field f = n.kind.fields[i];
            String v = n.values[i];
            if (f == Field.NAME && !n.kind.id.equals("command")) out.add(v);
            if (f == Field.NUM && expr(v) != null) for (String t : expr(v)) if (TERM.matcher(t).matches() && !Character.isDigit(t.charAt(0)) && !List.of("i", "j", "k", "arg").contains(t)) out.add(t);
            if (f == Field.TEXT || f == Field.CMD) { java.util.regex.Matcher m = PLACEHOLDER.matcher(v); while (m.find()) if (!List.of("i", "j", "k", "arg").contains(m.group(1))) out.add(m.group(1)); }
        }
        for (Node c : n.body) collectVars(c, out);
    }

    /** Підпис блока з підставленими значеннями (для коментарів і списків). */
    public static String describe(Node n) {
        StringBuilder sb = new StringBuilder();
        int f = 0;
        for (char c : n.kind.label.toCharArray()) { if (c == '%' && f < n.values.length) sb.append(n.values[f++]); else sb.append(c); }
        return sb.toString();
    }

    // ------------------------------------------------------------------ дерево

    /** Усього блоків (з шапками). */
    public int count() { int[] c = {0}; walk(hats, n -> c[0]++); return c[0]; }

    private static void walk(List<Node> list, java.util.function.Consumer<Node> f) { for (Node n : list) { f.accept(n); walk(n.body, f); } }

    /** Список, у якому лежить блок (hats - для шапок), або null. */
    public List<Node> parentList(Node target) {
        if (hats.contains(target)) return hats;
        return find(hats, target);
    }

    private static List<Node> find(List<Node> list, Node target) {
        for (Node n : list) {
            if (n.body.contains(target)) return n.body;
            List<Node> r = find(n.body, target);
            if (r != null) return r;
        }
        return null;
    }

    /** Блок, у тілі якого лежить список (null - верхній рівень). */
    private Node owner(List<Node> list) {
        Node[] r = {null};
        walk(hats, n -> { if (n.body == list) r[0] = n; });
        return r[0];
    }

    /** Скільки повторів обгортають блок (разом із ним самим, якщо він повтор). */
    public int loopDepth(Node target) {
        int d = target.kind.id.equals("repeat") ? 1 : 0;
        for (List<Node> l = parentList(target); l != null && l != hats; ) {
            Node o = owner(l);
            if (o == null) break;
            if (o.kind.id.equals("repeat")) d++;
            l = parentList(o);
        }
        return d;
    }

    private static int innerLoops(Node n) { int m = 0; for (Node c : n.body) m = Math.max(m, innerLoops(c)); return m + (n.kind.id.equals("repeat") ? 1 : 0); }

    /**
     * Вставити новий блок: шапка - у кінець; інакше під вибраний (у тіло, якщо вибрано обгортку чи шапку). Без вибору - у кінець останньої
     * шапки (якщо шапок нема - створюється «коли скрипт запускається»). Повертає вставлений блок або null (забагато блоків / вкладень).
     */
    public Node insert(Kind k, Node selected) {
        if (count() >= MAX_BLOCKS) return null;
        Node n = new Node(k);
        if (k.hat) { hats.add(n); return n; }
        if (selected == null) {
            if (hats.isEmpty()) hats.add(new Node(kind("start")));
            selected = hats.get(hats.size() - 1);
        }
        List<Node> into; int at;
        if (selected.kind.wraps) { into = selected.body; at = into.size(); }
        else { into = parentList(selected); if (into == null) return null; at = into.indexOf(selected) + 1; }
        into.add(at, n);
        if (loopDepth(n) + innerLoops(n) - (n.kind.id.equals("repeat") ? 1 : 0) > MAX_LOOP_DEPTH) { into.remove(at); return null; }
        return n;
    }

    public void delete(Node n) { List<Node> l = parentList(n); if (l != null) l.remove(n); }

    public Node duplicate(Node n) {
        List<Node> l = parentList(n);
        if (l == null || count() + countOf(n) > MAX_BLOCKS) return null;
        Node c = n.copy();
        l.add(l.indexOf(n) + 1, c);
        if (!n.kind.hat && loopDepth(c) + innerLoops(c) - (c.kind.id.equals("repeat") ? 1 : 0) > MAX_LOOP_DEPTH) { l.remove(c); return null; }
        return c;
    }

    private static int countOf(Node n) { int c = 1; for (Node b : n.body) c += countOf(b); return c; }

    /**
     * Посунути блок вгору (dir -1) чи вниз (+1): через сусіда-обгортку - у її тіло, з краю тіла - назовні (перед/після обгортки).
     * Шапки міняються місцями між собою. false - рухатись нікуди.
     */
    public boolean move(Node n, int dir) {
        List<Node> l = parentList(n);
        if (l == null) return false;
        int i = l.indexOf(n);
        if (l == hats) {
            int j = i + dir;
            if (j < 0 || j >= hats.size()) return false;
            Collections.swap(hats, i, j); return true;
        }
        List<Node> to; int at;
        int j = i + dir;
        if (j >= 0 && j < l.size()) {
            Node nb = l.get(j);
            if (nb.kind.wraps) { to = nb.body; at = dir < 0 ? nb.body.size() : 0; } // у тіло сусідньої обгортки
            else { Collections.swap(l, i, j); return true; }
        } else {
            Node o = owner(l);
            if (o == null) return false;
            List<Node> ol = parentList(o);
            if (ol == hats) { // з краю стопки шапки - у сусідню шапку
                int h = hats.indexOf(o) + dir;
                if (h < 0 || h >= hats.size()) return false;
                to = hats.get(h).body; at = dir < 0 ? to.size() : 0;
            } else { to = ol; at = ol.indexOf(o) + (dir < 0 ? 0 : 1); }
        }
        l.remove(i);
        to.add(Math.min(at, to.size()), n);
        if (loopDepth(n) + innerLoops(n) - (n.kind.id.equals("repeat") ? 1 : 0) > MAX_LOOP_DEPTH) { to.remove(n); l.add(i, n); return false; }
        return true;
    }

    // ------------------------------------------------------------------ JSON

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1);
        List<Object> hs = new ArrayList<>();
        for (Node h : hats) hs.add(node(h));
        root.put("hats", hs);
        return Json.write(root);
    }

    private static Map<String, Object> node(Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", n.kind.id);
        m.put("values", new ArrayList<>(Arrays.asList(n.values)));
        if (!n.body.isEmpty()) { List<Object> b = new ArrayList<>(); for (Node c : n.body) b.add(node(c)); m.put("body", b); }
        return m;
    }

    /** Розібрати blocks.json: невідомі блоки пропускаються, погані поля - типові, ліміти зберігаються. Зламаний JSON - IllegalArgumentException. */
    public static BlockProgram fromJson(String text) {
        Object root = Json.parse(text);
        BlockProgram p = new BlockProgram();
        if (!(root instanceof Map<?, ?> m) || !(m.get("hats") instanceof List<?> hs)) throw new IllegalArgumentException("не програма з блоків");
        int[] budget = {MAX_BLOCKS};
        for (Object o : hs) {
            Node h = read(o, true, 0, budget);
            if (h != null) p.hats.add(h);
        }
        return p;
    }

    /** budget - скільки ще блоків можна прочитати (спільний на всю програму). */
    private static Node read(Object o, boolean hat, int loops, int[] budget) {
        if (!(o instanceof Map<?, ?> m) || budget[0] <= 0) return null;
        Kind k = kind(String.valueOf(m.get("kind")));
        if (k == null || k.hat != hat) return null;
        if (k.id.equals("repeat") && loops >= MAX_LOOP_DEPTH) return null;
        Node n = new Node(k);
        budget[0]--;
        if (m.get("values") instanceof List<?> vs) for (int i = 0; i < n.values.length && i < vs.size(); i++) {
            Object o2 = vs.get(i);
            String v = o2 == null ? null : o2 instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : String.valueOf(o2); // JSON-числа - без «.0»
            if (check(k.fields[i], v, hat) == null) n.values[i] = v;
        }
        if (k.wraps && m.get("body") instanceof List<?> b) {
            int inner = loops + (k.id.equals("repeat") ? 1 : 0);
            for (Object c : b) { Node x = read(c, false, inner, budget); if (x != null) n.body.add(x); }
        }
        return n;
    }
}
