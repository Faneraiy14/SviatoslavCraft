package voxelin.client.look;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Вигляд гравця: скін, плащ, головний убір. Лежить у player.properties поруч із settings.properties - його пише і гра (екран «Скін і
 * косметика»), і лаунчер (той самий формат), тож змінити вигляд можна будь-де; гра перечитує файл при кожному відкритті екрана.
 * <pre>
 * skin=builtin:traveler       вбудований (SkinImages.BUILTIN), file:моє.png - файл у теці skins/, launcher - скін, переданий лаунчером (--skin)
 * cape=seam                   none або один із CAPES, або file:плащ.png у теці capes/
 * hat=wreath                  none або один із HATS
 * </pre>
 */
public final class PlayerLook {
    public static final List<String> CAPES = List.of("none", "seam", "ukraine", "dragon", "night", "ember");
    public static final List<String> HATS = List.of("none", "wreath", "crown", "tophat", "cap", "horns", "halo");

    public String skin = "builtin:traveler", cape = "none", hat = "none";

    public static String capeTitle(String id) {
        return switch (id) { case "seam" -> "Шов"; case "ukraine" -> "Україна"; case "dragon" -> "Дракон Краю"; case "night" -> "Зоряна ніч"; case "ember" -> "Жар";
            case "none" -> "без плаща"; default -> id.startsWith("file:") ? id.substring(5) : id; };
    }

    public static String hatTitle(String id) {
        return switch (id) { case "wreath" -> "Вінок"; case "crown" -> "Корона"; case "tophat" -> "Циліндр"; case "cap" -> "Кепка"; case "horns" -> "Роги"; case "halo" -> "Німб";
            default -> "без убору"; };
    }

    private static String fileRef(String v) {
        String n = v.substring(5).trim();
        return n.isEmpty() || n.contains("/") || n.contains("\\") || n.startsWith(".") || !n.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? null : "file:" + n; // лише ім'я файла в теці, без шляхів
    }

    /** Привести до допустимого: невідоме - типове. */
    public void clamp() {
        if (skin == null || !(skin.equals("launcher") || (skin.startsWith("builtin:") && SkinImages.BUILTIN.contains(skin.substring(8))) || (skin.startsWith("file:") && fileRef(skin) != null))) skin = "builtin:traveler";
        else if (skin.startsWith("file:")) skin = fileRef(skin);
        if (cape == null || !(CAPES.contains(cape) || (cape.startsWith("file:") && fileRef(cape) != null))) cape = "none";
        else if (cape.startsWith("file:")) cape = fileRef(cape);
        if (hat == null || !HATS.contains(hat)) hat = "none";
    }

    public static PlayerLook load(File f) {
        PlayerLook l = new PlayerLook();
        if (f == null || !f.isFile()) return l;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException | IllegalArgumentException e) { System.out.println("[Voxelin] ⚠️ " + f.getName() + " не прочитано: " + e.getMessage()); return l; }
        l.skin = p.getProperty("skin", l.skin).trim(); l.cape = p.getProperty("cape", l.cape).trim(); l.hat = p.getProperty("hat", l.hat).trim();
        l.clamp();
        return l;
    }

    public void save(File f) {
        clamp();
        Properties p = new Properties();
        p.setProperty("skin", skin); p.setProperty("cape", cape); p.setProperty("hat", hat);
        try {
            File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, "Вигляд гравця Voxelin (пишуть гра й лаунчер): skin=builtin:<назва>|file:<png у skins/>, cape, hat"); }
            voxelin.core.Files2.replace(tmp, f);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати " + f + ": " + e); }
    }

    public PlayerLook copy() { PlayerLook l = new PlayerLook(); l.skin = skin; l.cape = cape; l.hat = hat; return l; }

    @Override public boolean equals(Object o) { return o instanceof PlayerLook l && l.skin.equals(skin) && l.cape.equals(cape) && l.hat.equals(hat); }
    @Override public int hashCode() { return (skin + "|" + cape + "|" + hat).hashCode(); }
}
