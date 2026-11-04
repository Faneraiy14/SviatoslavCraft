package voxelin.client.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Підсвітка рядка NyxilumLang для вікна коду: ключові слова, рядки, числа, коментарі, виклики функцій. По одному рядку (без багаторядкових рядків). */
public final class Highlight {
    private Highlight() {}

    public enum Kind { TEXT, KEYWORD, STRING, NUMBER, COMMENT, CALL }

    public record Span(int from, int to, Kind kind) {}

    static final Set<String> KEYWORDS = Set.of("func", "var", "let", "const", "if", "else", "while", "for", "in", "return", "break", "continue",
            "import", "true", "false", "null", "and", "or", "not", "class", "new", "this", "try", "catch", "throw");

    public static List<Span> spans(String line) {
        List<Span> out = new ArrayList<>();
        int i = 0, n = line.length(), textFrom = 0;
        while (i < n) {
            char c = line.charAt(i);
            int start = i;
            Kind k = null;
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') { i = n; k = Kind.COMMENT; }
            else if (c == '"' || c == '\'') {
                i++;
                while (i < n && line.charAt(i) != c) { if (line.charAt(i) == '\\') i++; i++; }
                i = Math.min(n, i + 1); k = Kind.STRING;
            } else if (Character.isDigit(c) && (i == 0 || !ident(line.charAt(i - 1)))) {
                while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '.' || line.charAt(i) == '_')) i++;
                k = Kind.NUMBER;
            } else if (ident(c)) {
                while (i < n && ident(line.charAt(i))) i++;
                String w = line.substring(start, i);
                int j = i; while (j < n && line.charAt(j) == ' ') j++;
                if (KEYWORDS.contains(w)) k = Kind.KEYWORD;
                else if (j < n && line.charAt(j) == '(') k = Kind.CALL;
                else continue; // звичайне ім'я - частина тексту
            } else { i++; continue; }
            if (start > textFrom) out.add(new Span(textFrom, start, Kind.TEXT));
            out.add(new Span(start, i, k));
            textFrom = i;
        }
        if (textFrom < n) out.add(new Span(textFrom, n, Kind.TEXT));
        return out;
    }

    private static boolean ident(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** Колір виду (r, g, b) - тема вікна коду. */
    public static float[] color(Kind k) {
        return switch (k) {
            case KEYWORD -> new float[]{0.98f, 0.62f, 0.38f};
            case STRING -> new float[]{0.62f, 0.86f, 0.5f};
            case NUMBER -> new float[]{0.75f, 0.6f, 1f};
            case COMMENT -> new float[]{0.5f, 0.55f, 0.65f};
            case CALL -> new float[]{0.45f, 0.78f, 1f};
            default -> new float[]{0.9f, 0.92f, 0.96f};
        };
    }
}
