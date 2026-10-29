package voxelin.client.look;

import java.util.*;

/** Мінімальний розбір JSON (для моделей Blockbench): об'єкт -> Map, масив -> List, число -> Double, рядок, true/false, null. Глибина до 64. */
final class Json {
    private final String s;
    private int i, depth;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("зайвий текст після кінця");
        return v;
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON, символ " + i + ": " + m); }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; } // Blockbench інколи лишає коментарі
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("несподіваний кінець");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        if (++depth > 64) throw err("надто глибоко");
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; depth--; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("очікувався ключ");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("очікувалось :");
            i++; ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("незакритий об'єкт");
            char c = s.charAt(i++);
            if (c == '}') break;
            if (c != ',') throw err("очікувалось , або }");
        }
        depth--;
        return m;
    }

    private List<Object> array() {
        if (++depth > 64) throw err("надто глибоко");
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; depth--; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("незакритий масив");
            char c = s.charAt(i++);
            if (c == ']') break;
            if (c != ',') throw err("очікувалось , або ]");
        }
        depth--;
        return l;
    }

    private String string() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("незакритий рядок");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("незакритий рядок");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                case 'u' -> { if (i + 4 > s.length()) throw err("погане \\u"); try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (NumberFormatException x) { throw err("погане \\u"); } i += 4; }
                default -> b.append(e);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("несподіваний символ '" + s.charAt(i) + "'");
        try { return Double.parseDouble(s.substring(st, i)); } catch (NumberFormatException e) { throw err("погане число"); }
    }
}
