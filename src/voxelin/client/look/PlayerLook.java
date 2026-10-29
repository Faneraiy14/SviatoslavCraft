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
 * head=builtin:wreath         3D-косметика на голові: none, builtin:<назва> або file:<тека в cosmetics/> (моделі Blockbench)
 * body=builtin:backpack       те саме на тулубі/спині
 * </pre>
 */
public final class PlayerLook {
    public static final List<String> CAPES = List.of("none", "seam", "ukraine", "dragon", "night", "ember");
    public String skin = "builtin:traveler", cape = "none", head = "none", body = "none";

    public static String capeTitle(String id) {
        return switch (id) { case "seam" -> "Шов"; case "ukraine" -> "Україна"; case "dragon" -> "Дракон Краю"; case "night" -> "Зоряна ніч"; case "ember" -> "Жар";
            case "none" -> "без плаща"; default -> id.startsWith("file:") ? id.substring(5) : id; };
    }

    private static String fileRef(String v) {
        String n = v.substring(5).trim();
        return n.isEmpty() || n.contains("/") || n.contains("\\") || n.startsWith(".") || !n.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? null : "file:" + n; // лише ім'я файла в теці, без шляхів
    }

    /** Косметика: none, builtin:<відома назва> або file:<ім'я теки> (без шляхів). Чи є така тека - перевіряє бібліотека при показі. */
    private static String cosmetic(String v) {
        if (v == null) return "none";
        if (v.startsWith("builtin:") && BuiltinCosmetics.IDS.contains(v)) return v;
        if (v.startsWith("file:")) { String n = v.substring(5).trim(); if (!n.isEmpty() && !n.contains("/") && !n.contains("\\") && !n.startsWith(".")) return "file:" + n; }
        return "none";
    }

    /** Привести до допустимого: невідоме - типове. */
    public void clamp() {
        if (skin == null || !(skin.equals("launcher") || (skin.startsWith("builtin:") && SkinImages.BUILTIN.contains(skin.substring(8))) || (skin.startsWith("file:") && fileRef(skin) != null))) skin = "builtin:traveler";
        else if (skin.startsWith("file:")) skin = fileRef(skin);
        if (cape == null || !(CAPES.contains(cape) || (cape.startsWith("file:") && fileRef(cape) != null))) cape = "none";
        else if (cape.startsWith("file:")) cape = fileRef(cape);
        head = cosmetic(head); body = cosmetic(body);
    }

    public static PlayerLook load(File f) {
        PlayerLook l = new PlayerLook();
        if (f == null || !f.isFile()) return l;
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { p.load(r); }
        catch (IOException | IllegalArgumentException e) { System.out.println("[Voxelin] ⚠️ " + f.getName() + " не прочитано: " + e.getMessage()); return l; }
        l.skin = p.getProperty("skin", l.skin).trim(); l.cape = p.getProperty("cape", l.cape).trim(); l.head = p.getProperty("head", l.head).trim(); l.body = p.getProperty("body", l.body).trim();
        l.clamp();
        return l;
    }

    public void save(File f) {
        clamp();
        Properties p = new Properties();
        p.setProperty("skin", skin); p.setProperty("cape", cape); p.setProperty("head", head); p.setProperty("body", body);
        try {
            File dir = f.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { p.store(w, "Вигляд гравця Voxelin (пишуть гра й лаунчер): skin=builtin:<назва>|file:<png у skins/>|launcher, cape, head, body (3D-косметика)"); }
            voxelin.core.Files2.replace(tmp, f);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ не вдалось записати " + f + ": " + e); }
    }

    public PlayerLook copy() { PlayerLook l = new PlayerLook(); l.skin = skin; l.cape = cape; l.head = head; l.body = body; return l; }

    /** Косметика в слоті (Cosmetic.SLOTS). */
    public String slot(String slot) { return slot.equals("body") ? body : head; }
    public void setSlot(String slot, String id) { if (slot.equals("body")) body = id; else head = id; }

    @Override public boolean equals(Object o) { return o instanceof PlayerLook l && l.skin.equals(skin) && l.cape.equals(cape) && l.head.equals(head) && l.body.equals(body); }
    @Override public int hashCode() { return (skin + "|" + cape + "|" + head + "|" + body).hashCode(); }
}
