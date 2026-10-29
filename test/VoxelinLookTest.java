import voxelin.client.look.PlayerLook;
import voxelin.client.look.SkinImages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

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
        System.out.println("VoxelinLookTest: " + n + " перевірок OK");
    }
}
