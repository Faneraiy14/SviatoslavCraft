package voxelin.core;

import java.util.*;

/** Мінімальний JSON (моделі Blockbench, обмін зі скриптами nx): об'єкт -> Map, масив -> List, число -> Double, рядок, true/false, null. Глибина до 64. */
public final class Json {
    private final String s;
    private int i, depth;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
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

    /** Значення -> JSON одним рядком (Map, Collection, масив long/int, Number, Boolean, null, решта - рядок). Цілі числа без ".0". */
    public static String write(Object v) { StringBuilder b = new StringBuilder(); write(b, v); return b.toString(); }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Double d && (d.isNaN() || d.isInfinite())) b.append("null");
        else if (v instanceof Float f && (f.isNaN() || f.isInfinite())) b.append("null");
        else if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) b.append(d.longValue());
        else if (v instanceof Number) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) { if (!first) b.append(','); first = false; str(b, String.valueOf(e.getKey())); b.append(':'); write(b, e.getValue()); }
            b.append('}');
        } else if (v instanceof Collection<?> c) {
            b.append('[');
            boolean first = true;
            for (Object o : c) { if (!first) b.append(','); first = false; write(b, o); }
            b.append(']');
        } else if (v instanceof long[] a) { b.append('['); for (int k = 0; k < a.length; k++) { if (k > 0) b.append(','); b.append(a[k]); } b.append(']'); }
        else if (v instanceof int[] a) { b.append('['); for (int k = 0; k < a.length; k++) { if (k > 0) b.append(','); b.append(a[k]); } b.append(']'); }
        else str(b, v.toString());
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        b.append('"');
    }
}
