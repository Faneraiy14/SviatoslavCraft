package voxelin.loader;

import voxelin.core.GameVersion;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * «Є оновлення» - за публічним списком версій (flhub.luchezarikcentr.com/voxelin/versions.json). Гра й сервер НІКОЛИ не
 * оновлюються самі: лише кажуть, що вийшло, і чи це безпечно - зайдуть гравці з новою грою на старий сервер, чи підійдуть
 * моди, що змінилось несумісно. Без інтернету - тихо нічого (null).
 */
public final class UpdateCheck {
    private UpdateCheck() {}

    public static final String MANIFEST = "https://flhub.luchezarikcentr.com/voxelin/versions.json";

    /** Нова версія і що про неї сказати (по рядку). */
    public record Update(String version, List<String> lines) {}

    /** Завантажити список і порівняти (блокує до ~5 с; викликати не з головного потоку). null - оновлень нема або нема зв'язку. */
    public static Update check(String url, String current, int protocol, int api, boolean server) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", "Voxelin/" + current);
            if (c.getResponseCode() != 200) return null;
            byte[] b;
            try (InputStream in = c.getInputStream()) { b = in.readNBytes(1 << 20); }
            return compare(new String(b, StandardCharsets.UTF_8), current, protocol, api, server);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** Порівняти з уже прочитаним списком (тести). */
    @SuppressWarnings("unchecked")
    public static Update compare(String json, String current, int protocol, int api, boolean server) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> m)) return null;
        Map<String, Object> latest = (Map<String, Object>) m.get("latest");
        if (latest == null) return null;
        String newest = str(latest.get(server ? "server" : "release"));
        if (newest == null || GameVersion.compare(newest, current) <= 0) return null;
        Map<String, Object> v = null;
        List<String> breaking = new ArrayList<>();
        Object vs = m.get("versions");
        for (Object o : vs instanceof List<?> l0 ? l0 : List.of()) {
            if (!(o instanceof Map<?, ?> e)) continue;
            String id = str(e.get("id"));
            if (id == null) continue;
            if (id.equals(newest)) v = (Map<String, Object>) e;
            // несумісні зміни всіх версій між нашою і найновішою
            if (GameVersion.compare(id, current) > 0 && GameVersion.compare(id, newest) <= 0 && e.get("breaking") instanceof List<?> l)
                for (Object x : l) if (x != null) breaking.add(id + ": " + x);
        }
        List<String> out = new ArrayList<>();
        out.add("Є оновлення Voxelin " + newest + " (зараз " + current + ").");
        if (v != null) {
            int nProto = num(v.get("protocol"), -1), nMin = num(v.get("minProtocol"), nProto), nApi = num(v.get("api"), -1), nApiMin = num(v.get("apiMin"), nApi);
            if (nProto > 0) {
                if (nMin <= protocol && protocol <= nProto) out.add(server ? "Гравці з новою грою зайдуть і на цей сервер - оновлювати можна без поспіху." : "З новою грою зможеш заходити й на старі сервери.");
                else out.add(server ? "Гравці з новою грою НЕ зайдуть на цей сервер, поки його не оновиш." : "Нова гра не заходить на сервери цієї версії - їх теж треба оновити.");
            }
            if (nApiMin > api) out.add("Моди під цю версію гри після оновлення не завантажаться (потрібне API " + nApiMin + ") - онови їх разом із грою.");
            else if (nApi > api) out.add("Нове API модів (" + nApi + "): старі моди працюють, нові можуть вимагати оновлення.");
        }
        for (String x : breaking) out.add("Увага, " + x);
        out.add("Світ перед першим відкриттям новою версією копіюється в backups/ сам.");
        out.add(server ? "Завантажити: flhub.luchezarikcentr.com/voxelin/download/" + newest + " (Docker: --build-arg VERSION=" + newest + ")"
                       : "FL Launcher оновить сам; або flhub.luchezarikcentr.com/voxelin/download");
        return new Update(newest, out);
    }

    private static String str(Object o) { return o instanceof String s ? s : null; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }

    /** Найпростіший розбір JSON (об'єкти, масиви, рядки, числа, true/false/null) - для списку версій, без бібліотек. */
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }
        static Object parse(String s) { try { Json j = new Json(s); Object v = j.value(); return v; } catch (RuntimeException e) { return null; } }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws(); if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = string(); ws(); expect(':'); m.put(k, value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect('}'); return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws(); if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i) == ',') { i++; continue; } expect(']'); return l; } }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("json");
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r'); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private void expect(char c) { if (s.charAt(i) != c) throw new IllegalArgumentException("json"); i++; }
    }
}
