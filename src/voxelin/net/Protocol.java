package voxelin.net;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Протокол гри по мережі (TCP). Кадр: int довжина (1..MAX_FRAME) + байт виду + дані (DataOutputStream). Хост вирішує все:
 * гість лише просить (рух, чанк, зламати, поставити, чат), хост перевіряє й розсилає наслідки. Усе, що приходить ззовні, недовірливе:
 * розміри, рядки, координати й частота перевіряються до будь-якої дії.
 */
public final class Protocol {
    private Protocol() {}

    public static final int VERSION = 4, DEFAULT_PORT = 24555, MAX_FRAME = 1 << 20, MAX_GUESTS = 8, TIMEOUT_MS = 15_000, HANDSHAKE_MS = 5_000;
    /** Найбільша дальність, яку хост дає гостю (чанків): кожен гість завантажує хосту світ довкола себе. */
    public static final int MAX_VIEW = 6;
    public static final int MAX_CHAT = 200, MAX_NAME = 16, CODE_LEN = 6;
    /** Літери коду: без схожих (0/O, 1/I). */
    public static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    // гість -> хост
    public static final int C_HELLO = 1, C_POS = 2, C_CHUNK = 3, C_BREAK = 4, C_PLACE = 5, C_CHAT = 6, C_BYE = 7, C_STATE = 8, C_INV = 9, C_OPEN = 10, C_MENU = 11, C_CLOSE = 12;
    // хост -> гість
    public static final int S_WELCOME = 64, S_REJECT = 65, S_CHUNK = 66, S_NOCHUNK = 67, S_BLOCK = 68, S_TIME = 69, S_PLAYERS = 70, S_CHAT = 71, S_KICK = 72, S_TELEPORT = 73, S_MOBS = 74, S_GIVE = 75, S_HURT = 76, S_INV = 77, S_MENU = 78, S_MENU_END = 79;

    public interface Body { void write(DataOutputStream out) throws IOException; }

    /** Кадр без префікса довжини: [вид][дані]. */
    public static byte[] frame(int type, Body body) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(type);
            if (body != null) body.write(out);
            out.flush();
            byte[] b = bos.toByteArray();
            if (b.length > MAX_FRAME) throw new IllegalArgumentException("кадр завеликий: " + b.length);
            return b;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static void write(DataOutputStream out, byte[] frame) throws IOException { out.writeInt(frame.length); out.write(frame); }

    /** Прочитати кадр; довжина поза 1..MAX_FRAME - IOException (з'єднання рвемо). */
    public static byte[] read(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > MAX_FRAME) throw new IOException("поганий розмір кадру: " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    /** Рядок UTF з обмеженням довжини (у символах); довший - IOException. */
    public static String str(DataInputStream in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IOException("задовгий рядок");
        return s;
    }

    public static DataInputStream body(byte[] frame) { DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame, 1, frame.length - 1)); return in; }

    public static String newCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LEN; i++) sb.append(CODE_CHARS.charAt(r.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    /** Ім'я гравця: 1..16 літер (латиниця, кирилиця), цифр, _ або -; інакше null. */
    public static String cleanName(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.length() > MAX_NAME) return null;
        for (char c : s.toCharArray()) if (!(c == '_' || c == '-' || (c < 128 && Character.isLetterOrDigit(c)) || (c >= 0x0400 && c <= 0x04FF && Character.isLetter(c)))) return null;
        return s;
    }

    /** Текст чату: без керувальних символів, до MAX_CHAT. */
    public static String cleanChat(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= 32 && c != 127 && !Character.isSurrogate(c)) sb.append(c);
        String t = sb.toString().trim();
        return t.length() > MAX_CHAT ? t.substring(0, MAX_CHAT) : t;
    }

    /** «host», «host:port», «[ipv6]:port» -> {host, port}; null - не адреса. */
    public static Object[] parseAddress(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.length() > 255) return null;
        String host = s; int port = DEFAULT_PORT;
        if (s.startsWith("[")) {
            int e = s.indexOf(']');
            if (e < 0) return null;
            host = s.substring(1, e);
            String rest = s.substring(e + 1);
            if (!rest.isEmpty()) { if (!rest.startsWith(":")) return null; port = port(rest.substring(1)); }
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            host = s.substring(0, s.indexOf(':'));
            port = port(s.substring(s.indexOf(':') + 1));
        }
        if (port <= 0 || host.isEmpty()) return null;
        for (char c : host.toCharArray()) if (!(Character.isLetterOrDigit(c) && c < 128 || c == '.' || c == '-' || c == ':')) return null;
        return new Object[]{host, port};
    }

    private static int port(String p) { try { int v = Integer.parseInt(p); return v > 0 && v < 65536 ? v : -1; } catch (NumberFormatException e) { return -1; } }
}
