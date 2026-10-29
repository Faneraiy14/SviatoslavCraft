import voxelin.client.look.BuiltinCosmetics;
import voxelin.client.look.Cosmetic;
import voxelin.client.look.CosmeticLibrary;
import voxelin.client.look.PlayerLook;
import voxelin.client.look.SkinImages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Вигляд гравця без вікна: player.properties (гра й лаунчер), вбудовані скіни й плащі, читання PNG у форматі Minecraft (64x64, старі 64x32). */
public class VoxelinLookTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    static File png(File dir, String name, int w, int h, java.util.function.IntBinaryOperator color) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, color.applyAsInt(x, y));
        File f = new File(dir, name);
        ImageIO.write(im, "png", f);
        return f;
    }

    /** Справжній експорт Blockbench 1.12: кістка з батьком і поворотом, UV коробкою, дзеркало, inflate, коментар. */
    static final String GEO = """
            {
              "format_version": "1.12.0",
              // Blockbench інколи лишає коментарі
              "minecraft:geometry": [{
                "description": {"identifier": "geometry.hat", "texture_width": 32, "texture_height": 32, "visible_bounds_width": 2},
                "bones": [
                  {"name": "head", "pivot": [0, 24, 0]},
                  {"name": "hat", "parent": "head", "pivot": [0, 32, 0], "rotation": [-10, 0, 5],
                   "cubes": [
                     {"origin": [-5, 32, -5], "size": [10, 1, 10], "uv": [0, 0]},
                     {"origin": [-3, 33, -3], "size": [6, 5, 6], "uv": [0, 11], "inflate": 0.25, "mirror": true},
                     {"origin": [1, 38, -1], "size": [1, 3, 1], "pivot": [1.5, 38, -0.5], "rotation": [0, 0, -30],
                      "uv": {"north": {"uv": [24, 0], "uv_size": [1, 3]}, "up": {"uv": [25, 0], "uv_size": [1, -1]}}}
                   ]}
                ]
              }]
            }""";

    static void cosmetics(File dir) throws Exception {
        int[] tex = new int[32 * 32];
        Cosmetic c = Cosmetic.parse("t", "Тест", "head", GEO, 32, 32, tex);
        check(c.bones.size() == 2 && c.cubeCount() == 3, "кістки й кубоїди: " + c.bones.size() + " / " + c.cubeCount());
        Cosmetic.Bone hat = c.bones.get(1);
        check("head".equals(hat.parent) && hat.rotation[0] == -10 && hat.rotation[2] == 5 && hat.pivot[1] == 32, "батько, поворот, опора кістки");
        Cosmetic.Cube q0 = hat.cubes.get(0), q1 = hat.cubes.get(1), q2 = hat.cubes.get(2);
        check(q0.boxUv != null && q0.boxUv[1] == 0 && q0.size[0] == 10 && q0.origin[0] == -5, "UV коробкою");
        check(q1.inflate == 0.25f && q1.mirror && q1.boxUv[1] == 11, "inflate і дзеркало");
        check(q2.boxUv == null && q2.faces.size() == 2 && q2.faces.get("up")[3] == -1 && q2.rotation[2] == -30 && q2.pivot[0] == 1.5f, "UV по гранях (з від'ємним розміром), поворот кубоїда");
        // старий формат 1.8
        Cosmetic old = Cosmetic.parse("o", "o", "body", "{\"format_version\":\"1.8.0\",\"geometry.old\":{\"bones\":[{\"name\":\"b\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}}", 16, 16, new int[256]);
        check(old.cubeCount() == 1 && old.slot.equals("body"), "старий формат geometry.*");
        // зламане - зрозуміла помилка, а не падіння гри
        String[] bad = {"", "{", "[]", "{\"minecraft:geometry\":[]}", "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"x\"}]}]}",
                "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"x\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[999,1,1]}]}]}]}",
                "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"x\",\"cubes\":[{\"origin\":[5000,0,0],\"size\":[1,1,1]}]}]}]}",
                "{\"a\":1} хвіст", "{\"a\":\"\\u12\"}", "[".repeat(100) + "]".repeat(100)};
        for (String b : bad) {
            boolean threw = false;
            try { Cosmetic.parse("b", "b", "head", b, 16, 16, new int[256]); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "зламана модель відкинута: " + (b.length() > 40 ? b.substring(0, 40) : b));
        }
        StringBuilder many = new StringBuilder("{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"x\",\"cubes\":[");
        for (int i = 0; i <= Cosmetic.MAX_CUBES; i++) many.append(i > 0 ? "," : "").append("{\"origin\":[0,0,0],\"size\":[1,1,1]}");
        many.append("]}]}]}");
        boolean threw = false;
        try { Cosmetic.parse("m", "m", "head", many.toString(), 16, 16, new int[256]); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "понад " + Cosmetic.MAX_CUBES + " кубоїдів - ні");
        // вбудована - через той самий розбір
        List<Cosmetic> built = BuiltinCosmetics.all();
        check(built.size() == BuiltinCosmetics.IDS.size(), "вбудованих " + built.size());
        for (Cosmetic b : built) check(BuiltinCosmetics.IDS.contains(b.id) && b.cubeCount() > 0 && Cosmetic.SLOTS.contains(b.slot) && !b.title.isBlank(), "вбудована " + b.id);
        // теки cosmetics/
        File root = new File(dir, "cosmetics");
        File okDir = new File(root, "капелюх"); okDir.mkdirs();
        Files.writeString(new File(okDir, "model.geo.json").toPath(), GEO);
        png(okDir, "texture.png", 32, 32, (x, y) -> 0xFFFF0000);
        Files.writeString(new File(okDir, "cosmetic.properties").toPath(), "title=Капелюх мандрівника\nslot=head\n");
        File noTex = new File(root, "без-текстури"); noTex.mkdirs(); Files.writeString(new File(noTex, "model.geo.json").toPath(), GEO);
        File badSlot = new File(root, "на-нозі"); badSlot.mkdirs(); Files.writeString(new File(badSlot, "model.geo.json").toPath(), GEO); png(badSlot, "texture.png", 32, 32, (x, y) -> -1);
        Files.writeString(new File(badSlot, "cosmetic.properties").toPath(), "slot=leg\n");
        File big = new File(root, "велика-текстура"); big.mkdirs(); Files.writeString(new File(big, "x.geo.json").toPath(), GEO); png(big, "t.png", 512, 512, (x, y) -> -1);
        File broken = new File(root, "зламана"); broken.mkdirs(); Files.writeString(new File(broken, "model.geo.json").toPath(), "{"); png(broken, "texture.png", 16, 16, (x, y) -> -1);
        List<Cosmetic> loaded = Cosmetic.loadAll(root);
        check(loaded.size() == 1 && loaded.get(0).id.equals("file:капелюх") && loaded.get(0).title.equals("Капелюх мандрівника") && loaded.get(0).texture[5] == 0xFFFF0000, "з тек завантажено лише правильну: " + loaded.size());
        CosmeticLibrary lib = new CosmeticLibrary(root);
        check(lib.forSlot("head").get(0).equals("none") && lib.forSlot("head").contains("file:капелюх") && lib.forSlot("head").contains("builtin:wreath") && !lib.forSlot("head").contains("builtin:backpack"), "список для голови");
        check(lib.forSlot("body").contains("builtin:backpack") && lib.title("file:нема").contains("нема") && lib.title("none").equals("нічого"), "список для спини, назви");
        // player.properties: слоти
        File f = new File(dir, "p2.properties");
        Files.writeString(f.toPath(), "head=file:капелюх\nbody=builtin:backpack\n");
        PlayerLook l = PlayerLook.load(f);
        check(l.head.equals("file:капелюх") && l.body.equals("builtin:backpack") && l.slot("head").equals(l.head), "слоти з файла");
        for (String b : new String[]{"file:../x", "file:a/b", "file:.x", "builtin:nope", "file:", "x"}) {
            Files.writeString(f.toPath(), "head=" + b + "\nbody=" + b + "\n");
            PlayerLook pl = PlayerLook.load(f);
            check(pl.head.equals("none") && pl.body.equals("none"), "погана косметика відкинута: " + b);
        }
        l.setSlot("body", "none"); l.save(f);
        check(PlayerLook.load(f).body.equals("none") && PlayerLook.load(f).head.equals("file:капелюх"), "запис слотів");
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("vx-look").toFile();
        File f = new File(dir, "player.properties");
        PlayerLook d = PlayerLook.load(f);
        check(d.skin.equals("builtin:traveler") && d.cape.equals("none"), "без файла - типовий вигляд");
        PlayerLook l = new PlayerLook(); l.skin = "builtin:kozak"; l.cape = "ukraine"; l.save(f);
        check(PlayerLook.load(f).equals(l) && !new File(f.getPath() + ".tmp").exists(), "запис -> читання");
        Files.writeString(f.toPath(), "skin=file:мій скін.png\ncape=file:плащ.png\n"); // так пише лаунчер
        PlayerLook fl = PlayerLook.load(f);
        check(fl.skin.equals("file:мій скін.png") && fl.cape.equals("file:плащ.png"), "файли зі skins/ і capes/ (кирилиця, пробіли)");
        for (String bad : new String[]{"file:../../etc/passwd.png", "file:/abs/x.png", "file:sub\\\\x.png", "file:.hidden.png", "file:x.jpg", "file:", "builtin:nope", "http://x", ""}) {
            Files.writeString(f.toPath(), "skin=" + bad + "\ncape=" + bad + "\n");
            PlayerLook b = PlayerLook.load(f);
            check(b.skin.equals("builtin:traveler") && b.cape.equals("none"), "погане значення відкинуто: " + bad);
        }
        Files.writeString(f.toPath(), "skin=launcher\ncape=seam\n");
        check(PlayerLook.load(f).skin.equals("launcher"), "скін лаунчера");
        Files.write(f.toPath(), new byte[]{(byte) 0xFF, 0, '\\', 'u', 'Z'});
        check(PlayerLook.load(f).skin.equals("builtin:traveler"), "сміття - типовий вигляд");
        check(PlayerLook.capeTitle("ukraine").equals("Україна") && PlayerLook.capeTitle("file:x.png").equals("x.png"), "назви плащів");

        // вбудовані
        for (String s : SkinImages.BUILTIN) {
            int[] img = SkinImages.builtin(s);
            check(img.length == 64 * 64, "скін " + s + " 64x64");
            boolean opaque = true;
            int[][] regions = {{8, 8, 8, 8}, {20, 20, 8, 12}, {44, 20, 4, 12}, {36, 52, 4, 12}, {4, 20, 4, 12}, {20, 52, 4, 12}}; // перед: голова, тулуб, руки, ноги
            for (int[] r : regions) for (int y = r[1]; y < r[1] + r[3]; y++) for (int x = r[0]; x < r[0] + r[2]; x++) if ((img[y * 64 + x] >>> 24) != 255) opaque = false;
            check(opaque, "скін " + s + ": основні грані непрозорі");
            check(!SkinImages.title(s).equals(s), "у скіна " + s + " є назва українською");
        }
        check(java.util.Arrays.equals(SkinImages.builtin("kozak"), SkinImages.builtin("kozak")), "вбудовані детерміновані");
        for (String c : PlayerLook.CAPES) if (!c.equals("none")) {
            int[] img = SkinImages.cape(c);
            check(img.length == 64 * 32 && (img[64 * 5 + 5] >>> 24) == 255, "плащ " + c);
        }

        // PNG
        File skins = new File(dir, "skins"); skins.mkdirs();
        File modern = png(skins, "a.png", 64, 64, (x, y) -> 0xFF000000 | (x << 16) | (y << 8));
        int[] m = SkinImages.readSkin(modern);
        check(m != null && m[10 * 64 + 20] == (0xFF000000 | (20 << 16) | (10 << 8)), "64x64 читається піксель у піксель");
        File old = png(skins, "b.png", 64, 32, (x, y) -> 0xFF000000 | (x << 16) | (y << 8));
        int[] o = SkinImages.readSkin(old);
        check(o != null && o.length == 64 * 64, "старий 64x32 перетворено на 64x64");
        // ліва нога (16,48): перед (20..24, 52..64) = дзеркало переду правої (4..8, 20..32)
        check(o[52 * 64 + 20] == (0xFF000000 | (7 << 16) | (20 << 8)) && o[52 * 64 + 23] == (0xFF000000 | (4 << 16) | (20 << 8)), "ліва нога - дзеркало правої");
        // ліва рука (32,48): її права сторона (32..36) = ліва сторона правої руки (48..52), віддзеркалена
        check(o[52 * 64 + 32] == (0xFF000000 | (51 << 16) | (20 << 8)), "бокові грані руки міняються місцями");
        check(SkinImages.readSkin(png(skins, "c.png", 50, 50, (x, y) -> -1)) == null, "50x50 - не скін");
        check(SkinImages.readSkin(png(skins, "d.png", 128, 128, (x, y) -> -1)) == null, "HD-скіни поки ні");
        File junk = new File(skins, "e.png"); Files.writeString(junk.toPath(), "не png");
        check(SkinImages.readSkin(junk) == null && SkinImages.readSkin(new File(skins, "nope.png")) == null, "сміття й відсутній файл - null без падіння");
        check(SkinImages.list(skins).equals(java.util.List.of("a.png", "b.png", "c.png", "d.png", "e.png")) && SkinImages.list(new File(dir, "nope")).isEmpty(), "список PNG у теці");
        File capes = new File(dir, "capes"); capes.mkdirs();
        int[] hd = SkinImages.readCape(png(capes, "hd.png", 128, 64, (x, y) -> x < 20 ? 0xFFFF0000 : 0xFF0000FF));
        check(hd != null && hd[64 * 3 + 5] == 0xFFFF0000 && hd[64 * 3 + 30] == 0xFF0000FF, "плащ 128x64 зменшено до 64x32");
        check(SkinImages.readCape(png(capes, "bad.png", 64, 64, (x, y) -> -1)) == null, "плащ 64x64 - ні");
        cosmetics(dir);
        System.out.println("VoxelinLookTest: " + n + " перевірок OK");
    }
}
