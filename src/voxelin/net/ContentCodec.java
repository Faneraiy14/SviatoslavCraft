package voxelin.net;

import voxelin.api.*;
import voxelin.loader.ModLoader.ContentOp;

import java.io.*;
import java.util.*;

/**
 * Вміст модів для гостя (S_CONTENT): блоки, предмети, рецепти, паливо, мітки, текстури й вигляд істот - як дані, без коду мода.
 * Запис - через гетери *Settings, читання - через ті самі будівники, що й у модів (вони обрізають значення до меж), тож злий хост
 * не підсуне гостю NaN чи гігантські числа. Назви перевіряють реєстри гостя (ті самі правила, що й для модів).
 * Нове поле в Settings: допиши його В КІНЕЦЬ запису свого виду і читай у try/catch EOF - старі гості просто не побачать нове.
 */
public final class ContentCodec {
    private ContentCodec() {}

    /** Межі на весь вміст сервера (перевіряє гість). */
    public static final int MAX_OPS = 50_000, MAX_TEXTURES = 4096, MAX_PARTS = 64, MAX_LIST = 256;
    public static final long MAX_BYTES = 12L << 20; // черга відправки хоста рве з'єднання на 16 МБ
    /** Скільки байтів даних у одному кадрі S_CONTENT (кадр до 1 МБ). */
    static final int BATCH_BYTES = 256 << 10;

    /** Розкласти вміст на кадри S_CONTENT (кожен - кілька записів). */
    public static List<byte[]> frames(List<ContentOp> ops) {
        List<byte[]> out = new ArrayList<>();
        List<byte[]> batch = new ArrayList<>();
        int bytes = 0;
        for (ContentOp op : ops) {
            byte[] one = encode(op);
            if (one == null) continue;
            if (!batch.isEmpty() && bytes + one.length > BATCH_BYTES) { out.add(pack(batch)); batch.clear(); bytes = 0; }
            batch.add(one);
            bytes += one.length;
        }
        if (!batch.isEmpty()) out.add(pack(batch));
        return out;
    }

    private static byte[] pack(List<byte[]> batch) {
        return Protocol.frame(Protocol.S_CONTENT, o -> { o.writeInt(batch.size()); for (byte[] b : batch) { o.writeInt(b.length); o.write(b); } });
    }

    /** Один запис: мод, вид, назва, дані виду. null - вид, якого гостю знати не треба. */
    static byte[] encode(ContentOp op) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
            DataOutputStream o = new DataOutputStream(bos);
            o.writeUTF(op.mod()); o.writeUTF(op.kind()); o.writeUTF(op.name());
            switch (op.kind()) {
                case "block" -> block(o, (BlockSettings) op.data());
                case "item" -> item(o, (ItemSettings) op.data());
                case "recipe" -> recipe(o, (RecipeSettings) op.data());
                case "fuel" -> o.writeFloat((Float) op.data());
                case "tag" -> { String[] t = (String[]) op.data(); o.writeShort(t.length); for (String s : t) o.writeUTF(s); }
                case "texture" -> { for (int c : (int[]) op.data()) o.writeInt(c); }
                case "mob" -> mob(o, (MobSettings) op.data());
                default -> { return null; }
            }
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Прочитати кадр S_CONTENT у список записів (data - відновлені *Settings, Float, String[] або int[256]). */
    public static List<ContentOp> read(byte[] frame) throws IOException {
        DataInputStream in = Protocol.body(frame);
        int n = in.readInt();
        if (n < 0 || n > MAX_OPS) throw new IOException("забагато вмісту в кадрі");
        List<ContentOp> out = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            if (len < 0 || len > Protocol.MAX_FRAME) throw new IOException("поганий запис вмісту");
            byte[] b = new byte[len];
            in.readFully(b);
            out.add(decode(new DataInputStream(new ByteArrayInputStream(b))));
        }
        return out;
    }

    static ContentOp decode(DataInputStream in) throws IOException {
        String mod = Protocol.str(in, 32), kind = Protocol.str(in, 16), name = Protocol.str(in, 128);
        if (!mod.matches("[a-z0-9_]{1,32}") || ModLoaderVanilla.is(mod)) throw new IOException("поганий мод у вмісті: " + mod);
        Object data = switch (kind) {
            case "block" -> block(in);
            case "item" -> item(in);
            case "recipe" -> recipe(in);
            case "fuel" -> { float f = in.readFloat(); if (!Float.isFinite(f) || f <= 0 || f > 3600) throw new IOException("погане паливо"); yield f; }
            case "tag" -> { int k = in.readUnsignedShort(); if (k > MAX_LIST) throw new IOException("велика мітка"); String[] t = new String[k]; for (int i = 0; i < k; i++) t[i] = Protocol.str(in, 128); yield t; }
            case "texture" -> { int[] px = new int[256]; for (int i = 0; i < 256; i++) px[i] = in.readInt(); yield px; }
            case "mob" -> mob(in);
            default -> throw new IOException("невідомий вид вмісту: " + kind);
        };
        return new ContentOp(mod, kind, name, data);
    }

    // ------------------------------------------------------------------ блоки

    static void block(DataOutputStream o, BlockSettings s) throws IOException {
        o.writeInt(s.color()); opt(o, s.textureTop()); opt(o, s.textureSide()); opt(o, s.textureBottom());
        o.writeBoolean(s.isSolid()); o.writeBoolean(s.isOpaque()); o.writeBoolean(s.isLiquid()); o.writeBoolean(s.isTranslucent());
        o.writeByte(s.lightLevel()); o.writeByte(s.absorbValue()); o.writeFloat(s.hardnessValue());
        o.writeUTF(s.toolKind().name()); o.writeByte(s.minTierValue()); o.writeBoolean(s.hasNoItem()); o.writeBoolean(s.hasNoDrops());
        opt(o, s.titleText()); opt(o, s.interactKind()); opt(o, s.shapeValue()); opt(o, s.soundValue());
        List<BlockSettings.Drop> d = s.dropList();
        o.writeShort(d == null ? -1 : d.size());
        if (d != null) for (BlockSettings.Drop x : d) drop(o, x);
    }

    static BlockSettings block(DataInputStream in) throws IOException {
        BlockSettings s = new BlockSettings().color(in.readInt());
        String top = opt(in, 128), side = opt(in, 128), bottom = opt(in, 128);
        s.textures(top, side, bottom);
        boolean solid = in.readBoolean(), opaque = in.readBoolean(), liquid = in.readBoolean(), translucent = in.readBoolean();
        s.solid(solid).opaque(opaque);
        if (translucent) s.translucent(true);
        if (liquid) s.liquid(true);
        s.light(in.readByte());
        int absorb = in.readByte();
        if (absorb >= 0) s.absorb(absorb);
        s.hardness(finite(in.readFloat(), 1f));
        s.tool(tool(Protocol.str(in, 16))).minTier(in.readByte());
        if (in.readBoolean()) s.noItem();
        if (in.readBoolean()) s.noDrops();
        String title = opt(in, 64), interact = opt(in, 32), shape = opt(in, 32), sound = opt(in, 32);
        if (title != null) s.title(title);
        if (interact != null) s.interact(interact);
        if (shape != null) s.shape(shape);
        if (sound != null) s.sound(sound);
        int nd = in.readShort();
        if (nd > MAX_LIST) throw new IOException("забагато дропу");
        for (int i = 0; i < nd; i++) { BlockSettings.Drop d = drop(in); s.drops(d.item, d.min, d.max, d.chance); }
        return s;
    }

    // ------------------------------------------------------------------ предмети

    static void item(DataOutputStream o, ItemSettings s) throws IOException {
        o.writeByte(s.maxStackValue()); o.writeInt(s.durabilityValue());
        o.writeUTF(s.toolKind().name()); o.writeByte(s.tierValue()); o.writeFloat(s.speedValue());
        o.writeByte(s.foodValue()); o.writeFloat(s.attackValue());
        opt(o, s.armorSlotValue() == null ? null : s.armorSlotValue().name()); o.writeByte(s.armorPointsValue()); o.writeByte(s.armorTierValue());
        o.writeBoolean(s.fireproofValue()); o.writeInt(s.colorValue());
        opt(o, s.textureName()); opt(o, s.placesBlockName()); opt(o, s.titleText());
    }

    static ItemSettings item(DataInputStream in) throws IOException {
        ItemSettings s = new ItemSettings().maxStack(in.readByte());
        int dur = in.readInt();
        s.durability(Math.min(dur, 1_000_000));
        ToolKind tk = tool(Protocol.str(in, 16));
        int tier = in.readByte();
        s.tool(tk, Math.min(tier, 16), finite(in.readFloat(), 1f));
        s.food(in.readByte()).attack(finite(in.readFloat(), 1f));
        String slot = opt(in, 16);
        int pts = in.readByte(), atier = in.readByte();
        if (slot != null) {
            ArmorSlot a;
            try { a = ArmorSlot.valueOf(slot); } catch (IllegalArgumentException e) { throw new IOException("поганий слот броні"); }
            s.armor(a, pts, Math.min(atier, 16));
        }
        if (in.readBoolean()) s.fireproof();
        s.color(in.readInt());
        String tex = opt(in, 128), places = opt(in, 128), title = opt(in, 64);
        if (tex != null) s.texture(tex);
        if (places != null) s.placesBlock(places);
        if (title != null) s.title(title);
        return s;
    }

    // ------------------------------------------------------------------ рецепти

    static void recipe(DataOutputStream o, RecipeSettings r) throws IOException {
        if (r.isSmelting()) { o.writeByte(2); o.writeUTF(r.smeltInputItem()); o.writeFloat(r.smeltSeconds()); }
        else if (r.isShaped()) {
            o.writeByte(0);
            String[] rows = r.rows();
            o.writeByte(rows.length);
            for (String row : rows) o.writeUTF(row);
            o.writeByte(r.keys().size());
            for (Map.Entry<Character, String> e : r.keys().entrySet()) { o.writeChar(e.getKey()); o.writeUTF(e.getValue()); o.writeByte(r.perCell(e.getKey())); }
            o.writeBoolean(r.mirrorAllowed());
        } else {
            o.writeByte(1);
            List<String> ing = r.ingredients();
            o.writeByte(ing.size());
            for (String s : ing) o.writeUTF(s);
        }
        opt(o, r.resultItem()); o.writeByte(r.resultCount());
    }

    static RecipeSettings recipe(DataInputStream in) throws IOException {
        RecipeSettings r;
        try {
            int kind = in.readByte();
            if (kind == 2) r = RecipeSettings.smelting(Protocol.str(in, 128), finite(in.readFloat(), 10f));
            else if (kind == 0) {
                int n = in.readByte();
                if (n < 1 || n > 3) throw new IOException("погана форма рецепта");
                String[] rows = new String[n];
                for (int i = 0; i < n; i++) rows[i] = Protocol.str(in, 3);
                r = RecipeSettings.shaped(rows);
                int k = in.readByte();
                if (k < 0 || k > 9) throw new IOException("забагато ключів рецепта");
                for (int i = 0; i < k; i++) { char c = in.readChar(); String ing = Protocol.str(in, 128); r.key(c, ing, in.readByte()); }
                if (!in.readBoolean()) r.noMirror();
            } else if (kind == 1) {
                int n = in.readByte();
                if (n < 1 || n > 9) throw new IOException("поганий безформний рецепт");
                String[] ing = new String[n];
                for (int i = 0; i < n; i++) ing[i] = Protocol.str(in, 128);
                r = RecipeSettings.shapeless(ing);
            } else throw new IOException("невідомий рецепт");
        } catch (IllegalArgumentException e) { throw new IOException("поганий рецепт: " + e.getMessage()); }
        String res = opt(in, 128);
        int count = in.readByte();
        if (res != null) r.result(res, count);
        return r;
    }

    // ------------------------------------------------------------------ істоти (гостю - вигляд і розмір; поведінку рахує хост)

    static void mob(DataOutputStream o, MobSettings s) throws IOException {
        o.writeFloat(s.healthValue()); o.writeFloat(s.widthValue()); o.writeFloat(s.heightValue());
        o.writeUTF(s.behaviorValue().name()); o.writeBoolean(s.flyingValue()); o.writeBoolean(s.bossValue()); o.writeBoolean(s.invulnerableValue());
        opt(o, s.titleText()); o.writeInt(s.eyeColor());
        List<MobSettings.Part> parts = s.partList();
        o.writeShort(parts.size());
        for (MobSettings.Part p : parts) {
            o.writeFloat(p.cx); o.writeFloat(p.y0); o.writeFloat(p.cz); o.writeFloat(p.w); o.writeFloat(p.h); o.writeFloat(p.d);
            o.writeInt(p.rgb); o.writeByte(p.swing); o.writeByte(p.role); opt(o, p.pattern); o.writeBoolean(p.face);
        }
    }

    static MobSettings mob(DataInputStream in) throws IOException {
        MobSettings s = new MobSettings().health(in.readFloat());
        float w = in.readFloat(), h = in.readFloat();
        s.size(finite(w, 0.6f), finite(h, 1f));
        try { s.behavior(MobSettings.Behavior.valueOf(Protocol.str(in, 16))); } catch (IllegalArgumentException e) { throw new IOException("погана поведінка істоти"); }
        s.flying(in.readBoolean()).boss(in.readBoolean()).invulnerable(in.readBoolean());
        String title = opt(in, 64);
        if (title != null) s.title(title);
        int eyes = in.readInt();
        if (eyes >= 0) s.face(eyes);
        int n = in.readShort();
        if (n < 0 || n > MAX_PARTS) throw new IOException("забагато частин істоти");
        for (int i = 0; i < n; i++) {
            float cx = part(in), y0 = part(in), cz = part(in), pw = size(in), ph = size(in), pd = size(in);
            int rgb = in.readInt(), swing = in.readByte(), role = in.readByte();
            String pattern = opt(in, 16);
            boolean face = in.readBoolean();
            // через ті самі будівники, що й у модів (без нового API): вид частини - за swing, голова з обличчям - head()
            if (face && role == MobSettings.Part.HEAD) s.head(cx, y0, cz, pw, ph, pd, rgb);
            else {
                switch (swing) {
                    case 1, 2 -> s.legPart(cx, y0, cz, pw, ph, pd, rgb, swing == 2);
                    case 3, 4 -> s.wingPart(cx, y0, cz, pw, ph, pd, rgb, swing == 3);
                    case 5, 6 -> s.armPart(cx, y0, cz, pw, ph, pd, rgb, swing == 5);
                    default -> s.part(cx, y0, cz, pw, ph, pd, rgb);
                }
                if (role == MobSettings.Part.HEAD) s.onHead();
            }
            if (pattern != null) s.pattern(pattern);
        }
        return s;
    }

    private static float part(DataInputStream in) throws IOException { float v = in.readFloat(); if (!Float.isFinite(v) || Math.abs(v) > 8) throw new IOException("частина істоти поза межами"); return v; }
    private static float size(DataInputStream in) throws IOException { float v = in.readFloat(); if (!Float.isFinite(v) || v < 0 || v > 8) throw new IOException("поганий розмір частини істоти"); return v; }

    // ------------------------------------------------------------------ дрібниці

    private static void drop(DataOutputStream o, BlockSettings.Drop d) throws IOException { o.writeUTF(d.item); o.writeShort(d.min); o.writeShort(d.max); o.writeFloat(d.chance); }
    private static BlockSettings.Drop drop(DataInputStream in) throws IOException { return new BlockSettings.Drop(Protocol.str(in, 128), in.readShort(), in.readShort(), in.readFloat()); }

    private static void opt(DataOutputStream o, String s) throws IOException { o.writeBoolean(s != null); if (s != null) o.writeUTF(s); }
    private static String opt(DataInputStream in, int max) throws IOException { return in.readBoolean() ? Protocol.str(in, max) : null; }

    private static float finite(float v, float dflt) { return Float.isFinite(v) ? v : dflt; }

    private static ToolKind tool(String n) throws IOException {
        try { return ToolKind.valueOf(n); } catch (IllegalArgumentException e) { throw new IOException("поганий інструмент: " + n); }
    }

    /** Вбудований вміст гість має свій - сервер його не надсилає, і гість не приймає «voxelin:...» від сервера. */
    private static final class ModLoaderVanilla { static boolean is(String mod) { return voxelin.loader.ModLoader.VANILLA.equals(mod); } }
}
