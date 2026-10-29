package voxelin.client.look;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 3D-косметика з Blockbench: модель «Bedrock geometry» (.geo.json) + текстура PNG + cosmetic.properties (назва, слот). Координати - як у
 * шаблоні гравця Blockbench (пікселі, ноги в 0, голова 24..32, перед - північ, -Z). Слот визначає, за якою частиною тіла модель рухається:
 * head (голова), body (тулуб, спина).
 * <pre>
 * cosmetics/капелюх/
 *     model.geo.json      Blockbench: File > Export > Export Bedrock Geometry
 *     texture.png         текстура з Blockbench (до 256x256)
 *     cosmetic.properties title=Капелюх мандрівника
 *                         slot=head
 * </pre>
 */
public final class Cosmetic {
    public static final List<String> SLOTS = List.of("head", "body");
    public static final int MAX_CUBES = 512, MAX_TEX = 256;
    static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    public final String id, title, slot;
    public final int texW, texH;
    /** ARGB, texW x texH. */
    public final int[] texture;
    public final List<Bone> bones;

    public static final class Bone {
        public String name, parent;
        public float[] pivot = new float[3], rotation = new float[3];
        public final List<Cube> cubes = new ArrayList<>();
    }

    public static final class Cube {
        public float[] origin, size, pivot, rotation;
        public float inflate;
        public boolean mirror;
        /** UV «коробкою» (u,v) або null - тоді faces: north/south/east/west/up/down -> {u, v, ширина, висота} (від'ємні - віддзеркалено). */
        public float[] boxUv;
        public final Map<String, float[]> faces = new HashMap<>();
    }

    Cosmetic(String id, String title, String slot, int texW, int texH, int[] texture, List<Bone> bones) {
        this.id = id; this.title = title; this.slot = slot; this.texW = texW; this.texH = texH; this.texture = texture; this.bones = bones;
    }

    public int cubeCount() { int n = 0; for (Bone b : bones) n += b.cubes.size(); return n; }

    // ------------------------------------------------------------------ читання

    /** Тека косметики. null і пояснення в консолі, якщо щось не так (гра не падає). */
    public static Cosmetic load(File dir) {
        String name = dir.getName();
        try {
            File geo = findGeo(dir);
            if (geo == null) throw new IllegalArgumentException("нема model.geo.json (Blockbench: Export Bedrock Geometry)");
            if (geo.length() > 1_000_000) throw new IllegalArgumentException("модель більша за 1 МБ");
            Properties p = new Properties();
            File props = new File(dir, "cosmetic.properties");
            if (props.isFile()) try (var r = new java.io.InputStreamReader(new java.io.FileInputStream(props), StandardCharsets.UTF_8)) { p.load(r); }
            String slot = p.getProperty("slot", "head").trim();
            if (!SLOTS.contains(slot)) throw new IllegalArgumentException("slot=" + slot + " - можна " + SLOTS);
            File tex = new File(dir, "texture.png");
            if (!tex.isFile()) { File[] pngs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png")); tex = pngs != null && pngs.length > 0 ? pngs[0] : null; }
            if (tex == null) throw new IllegalArgumentException("нема texture.png");
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(tex);
            if (im == null) throw new IllegalArgumentException(tex.getName() + " - не PNG");
            if (im.getWidth() > MAX_TEX || im.getHeight() > MAX_TEX) throw new IllegalArgumentException("текстура більша за " + MAX_TEX + "x" + MAX_TEX);
            int[] argb = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            return parse("file:" + name, p.getProperty("title", name).trim(), slot, Files.readString(geo.toPath(), StandardCharsets.UTF_8), im.getWidth(), im.getHeight(), argb);
        } catch (Exception e) {
            System.out.println("[Voxelin] ⚠️ косметика " + name + " пропущена: " + e.getMessage());
            return null;
        }
    }

    private static File findGeo(File dir) {
        File m = new File(dir, "model.geo.json");
        if (m.isFile()) return m;
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".geo.json") || n.toLowerCase(Locale.ROOT).endsWith(".json") && !n.equals("cosmetic.json"));
        return fs != null && fs.length > 0 ? fs[0] : null;
    }

    /** Розібрати текст .geo.json (формат 1.12+ з "minecraft:geometry" або старий 1.8/1.10 з "geometry.*"). */
    @SuppressWarnings("unchecked")
    public static Cosmetic parse(String id, String title, String slot, String json, int texW, int texH, int[] texture) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?, ?> r)) throw new IllegalArgumentException("корінь не об'єкт");
        Map<String, Object> geo = null;
        Object list = r.get("minecraft:geometry");
        if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) geo = (Map<String, Object>) l.get(0);
        else for (Map.Entry<?, ?> e : r.entrySet()) if (String.valueOf(e.getKey()).startsWith("geometry.") && e.getValue() instanceof Map) { geo = (Map<String, Object>) e.getValue(); break; }
        if (geo == null) throw new IllegalArgumentException("не Bedrock geometry (нема minecraft:geometry)");
        List<Bone> bones = new ArrayList<>();
        int cubes = 0;
        Object bl = geo.get("bones");
        if (bl instanceof List<?> bs) for (Object o : bs) {
            if (!(o instanceof Map<?, ?> bm)) continue;
            Bone b = new Bone();
            b.name = String.valueOf(bm.get("name"));
            b.parent = bm.get("parent") instanceof String s ? s : null;
            b.pivot = vec(bm.get("pivot"), 0);
            b.rotation = vec(bm.get("rotation"), 0);
            if (bm.get("cubes") instanceof List<?> cl) for (Object co : cl) {
                if (!(co instanceof Map<?, ?> cm)) continue;
                if (++cubes > MAX_CUBES) throw new IllegalArgumentException("більше " + MAX_CUBES + " кубоїдів");
                Cube c = new Cube();
                c.origin = vec(cm.get("origin"), 0);
                c.size = vec(cm.get("size"), 0);
                for (float v : c.size) if (v < 0 || v > 64) throw new IllegalArgumentException("розмір кубоїда поза 0..64");
                for (float v : c.origin) if (Math.abs(v) > 128) throw new IllegalArgumentException("кубоїд надто далеко від гравця (>128 px)");
                c.pivot = cm.containsKey("pivot") ? vec(cm.get("pivot"), 0) : null;
                c.rotation = cm.containsKey("rotation") ? vec(cm.get("rotation"), 0) : null;
                c.inflate = num(cm.get("inflate"), 0);
                if (Math.abs(c.inflate) > 8) throw new IllegalArgumentException("inflate поза -8..8");
                c.mirror = Boolean.TRUE.equals(cm.get("mirror")) || Boolean.TRUE.equals(bm.get("mirror"));
                Object uv = cm.get("uv");
                if (uv instanceof List<?> ul) c.boxUv = new float[]{num(ul.size() > 0 ? ul.get(0) : null, 0), num(ul.size() > 1 ? ul.get(1) : null, 0)};
                else if (uv instanceof Map<?, ?> fm) {
                    for (String f : FACES) if (fm.get(f) instanceof Map<?, ?> fu) {
                        float[] a = vec(fu.get("uv"), 0), sz = fu.containsKey("uv_size") ? vec(fu.get("uv_size"), 0) : new float[]{0, 0};
                        c.faces.put(f, new float[]{a[0], a[1], sz[0], sz[1]});
                    }
                } else c.boxUv = new float[]{0, 0};
                b.cubes.add(c);
            }
            bones.add(b);
        }
        if (cubes == 0) throw new IllegalArgumentException("у моделі нема жодного кубоїда");
        return new Cosmetic(id, title, slot, texW, texH, texture, bones);
    }

    private static float num(Object o, float def) { return o instanceof Number n && Double.isFinite(n.doubleValue()) ? n.floatValue() : def; }

    private static float[] vec(Object o, float def) {
        float[] v = {def, def, def};
        if (o instanceof List<?> l) for (int i = 0; i < 3 && i < l.size(); i++) v[i] = num(l.get(i), def);
        return v;
    }

    /** Усі теки з косметикою (відсортовані за назвою). */
    public static List<Cosmetic> loadAll(File root) {
        List<Cosmetic> out = new ArrayList<>();
        File[] ds = root == null ? null : root.listFiles(File::isDirectory);
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) { Cosmetic c = load(d); if (c != null) out.add(c); }
        return out;
    }
}
