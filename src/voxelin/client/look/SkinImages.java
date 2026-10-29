package voxelin.client.look;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Картинки скінів і плащів у форматі Minecraft (ARGB, 64x64; старі 64x32 перетворюються - ліва рука й нога віддзеркалюються з правих).
 * Вбудовані малюються кодом (як текстури блоків); свої - PNG у теках skins/ і capes/.
 */
public final class SkinImages {
    private SkinImages() {}

    public static final int W = 64;
    public static final List<String> BUILTIN = List.of("traveler", "keeper", "miner", "kozak");

    public static String title(String builtin) {
        return switch (builtin) { case "traveler" -> "Мандрівник"; case "keeper" -> "Сторож Шва"; case "miner" -> "Шахтар"; case "kozak" -> "Козак"; default -> builtin; };
    }

    // ------------------------------------------------------------------ області граней кубоїда (u,v - кут, w x h x d - розмір у пікселях)

    /** Розгортка Minecraft: зверху [верх][низ], нижче [праворуч][перед][ліворуч][зад]. */
    static void paintBox(int[] img, int u, int v, int w, int h, int d, int top, int side, int front, int back) {
        rect(img, u + d, v, w, d, top); rect(img, u + d + w, v, w, d, shade(top, 0.8f));
        rect(img, u, v + d, d, h, side); rect(img, u + d, v + d, w, h, front); rect(img, u + d + w, v + d, d, h, side); rect(img, u + 2 * d + w, v + d, w, h, back);
    }

    /** Перед кубоїда: лівий верхній кут області «перед». */
    static int fu(int u, int d) { return u + d; }

    static void rect(int[] img, int x, int y, int w, int h, int c) { for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) px(img, x + i, y + j, c); }
    static void px(int[] img, int x, int y, int c) { if (x >= 0 && x < W && y >= 0 && y < W) img[y * W + x] = c; }

    static int shade(int c, float k) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * k)), g = Math.min(255, (int) (((c >> 8) & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Шум відтінку, щоб поверхні не були пласкими (детерміновано). */
    static void grain(int[] img, long seed, float amount) {
        Random r = new Random(seed);
        for (int i = 0; i < img.length; i++) if ((img[i] >>> 24) != 0) img[i] = shade(img[i], 1f - amount + r.nextFloat() * amount * 2);
    }

    // ------------------------------------------------------------------ вбудовані скіни

    public static int[] builtin(String name) {
        int[] img = new int[W * W];
        switch (name) {
            case "keeper" -> person(img, 0xFF6B5A8E, 0xFF2B2140, 0xFF3A2D57, 0xFF2B2140, 0xFF1A1426, 0xFF120E1C, 0xFFC77DFF, true, 0);
            case "miner" -> { person(img, 0xFFC9946B, 0xFF4A3220, 0xFFD9792B, 0xFF3D4A66, 0xFF2E3A52, 0xFF2A2A2E, 0xFF2B2B2B, false, 1); }
            case "kozak" -> { person(img, 0xFFE0AC85, 0xFF1C1C1C, 0xFFF2F0EA, 0xFFF2F0EA, 0xFFB3202A, 0xFF3B2414, 0xFF3A2A1A, false, 2); }
            default -> person(img, 0xFFDDA57E, 0xFF5A3A22, 0xFF2E8B7A, 0xFF34508A, 0xFF2B4373, 0xFF4A4A4A, 0xFF2A2A2A, false, 0);
        }
        grain(img, name.hashCode(), 0.06f);
        return img;
    }

    /**
     * Людина: шкіра, волосся, сорочка, рукави, штани, взуття, очі; hood - каптур (Сторож); extra: 1 каска шахтаря, 2 чуб і вишивка (козак).
     */
    private static void person(int[] img, int skin, int hair, int shirt, int sleeve, int pants, int shoes, int eye, boolean hood, int extra) {
        // голова 8x8x8 (0,0)
        paintBox(img, 0, 0, 8, 8, 8, hair, skin, skin, hair);
        int hx = fu(0, 8), hy = 8; // перед голови: (8,8)
        rect(img, hx, hy, 8, 2, hair); // чубчик
        rect(img, 0, 8, 8, 3, hair); rect(img, 16, 8, 8, 3, hair); // волосся з боків
        px(img, hx + 1, hy + 4, 0xFFFFFFFF); px(img, hx + 2, hy + 4, eye); px(img, hx + 5, hy + 4, eye); px(img, hx + 6, hy + 4, 0xFFFFFFFF); // очі
        rect(img, hx + 3, hy + 6, 2, 1, shade(skin, 0.7f)); // рот
        if (hood) { // каптур: шар поверх голови (32,0), обличчя відкрите й у тіні
            paintBox(img, 32, 0, 8, 8, 8, shirt, shirt, 0, shirt);
            rect(img, 40, 8, 8, 2, shirt); rect(img, 40, 8, 1, 8, shirt); rect(img, 47, 8, 1, 8, shirt);
            rect(img, hx, hy + 2, 8, 6, shade(skin, 0.55f));
            px(img, hx + 2, hy + 4, eye); px(img, hx + 5, hy + 4, eye); px(img, hx + 1, hy + 4, shade(skin, 0.55f)); px(img, hx + 6, hy + 4, shade(skin, 0.55f));
        }
        if (extra == 1) { // каска з ліхтариком
            paintBox(img, 32, 0, 8, 8, 8, 0xFFE8C02A, 0xFFE8C02A, 0, 0xFFE8C02A);
            rect(img, 40, 8, 8, 3, 0xFFE8C02A); rect(img, 40, 11, 8, 5, 0); rect(img, 43, 8, 2, 2, 0xFFFFF6B0);
        }
        if (extra == 2) { // поголена голова з чубом-оселедцем і вусами
            paintBox(img, 0, 0, 8, 8, 8, skin, skin, skin, skin);
            rect(img, 11, 0, 2, 8, hair); rect(img, hx + 3, hy, 2, 2, hair);
            px(img, hx + 1, hy + 4, 0xFFFFFFFF); px(img, hx + 2, hy + 4, 0xFF3A2A1A); px(img, hx + 5, hy + 4, 0xFF3A2A1A); px(img, hx + 6, hy + 4, 0xFFFFFFFF);
            int mus = 0xFF4A3424; rect(img, hx + 1, hy + 6, 6, 1, mus); px(img, hx + 1, hy + 7, mus); px(img, hx + 6, hy + 7, mus); // вуса
        }
        // тулуб 8x12x4 (16,16)
        paintBox(img, 16, 16, 8, 12, 4, shirt, shirt, shirt, shirt);
        if (hood) rect(img, 20, 20, 8, 12, shirt);
        if (extra == 1) { rect(img, 20, 20, 8, 12, 0xFFD9792B); rect(img, 20, 26, 8, 1, 0xFFE8E8E8); rect(img, 22, 20, 1, 12, 0xFF6B6B6B); }
        if (extra == 2) { for (int y = 21; y < 26; y += 2) { px(img, 23, y, 0xFFB3202A); px(img, 24, y, 0xFFB3202A); } px(img, 22, 22, 0xFF1C1C1C); px(img, 25, 22, 0xFF1C1C1C); rect(img, 20, 30, 8, 2, 0xFFB3202A); }
        rect(img, 20, 30, 8, 2, extra == 2 ? 0xFFB3202A : shade(shirt, 0.6f)); // пояс
        // руки 4x12x4: права (40,16), ліва (32,48)
        for (int[] a : new int[][]{{40, 16}, {32, 48}}) {
            paintBox(img, a[0], a[1], 4, 12, 4, sleeve, sleeve, sleeve, sleeve);
            rect(img, a[0], a[1] + 4 + 9, 16, 3, skin); rect(img, a[0] + 8, a[1], 4, 4, skin); // кисть і низ руки
            if (extra == 2) rect(img, a[0], a[1] + 4 + 6, 16, 1, 0xFFB3202A); // вишитий манжет
        }
        // ноги 4x12x4: права (0,16), ліва (16,48)
        for (int[] l : new int[][]{{0, 16}, {16, 48}}) {
            paintBox(img, l[0], l[1], 4, 12, 4, pants, pants, pants, pants);
            rect(img, l[0], l[1] + 4 + 9, 16, 3, shoes); rect(img, l[0] + 8, l[1], 4, 4, shoes);
        }
    }

    // ------------------------------------------------------------------ плащі (64x32, кубоїд 10x16x1 у (0,0))

    public static int[] cape(String id) {
        int[] img = new int[W * 32];
        int a, b;
        switch (id) {
            case "seam" -> { a = 0xFF231A38; b = 0xFF120D1E; }
            case "ukraine" -> { a = 0xFF0057B7; b = 0xFFFFD700; }
            case "dragon" -> { a = 0xFF141018; b = 0xFF2A1F33; }
            case "night" -> { a = 0xFF0E1838; b = 0xFF0A1028; }
            default -> { a = 0xFF8A1E1E; b = 0xFF5E1212; }
        }
        // обидві великі грані (зовнішня (1,1) і внутрішня (12,1)), краї
        for (int y = 0; y < 17; y++) for (int x = 0; x < 22; x++) img[y * W + x] = b;
        for (int y = 1; y < 17; y++) for (int x = 1; x < 11; x++) {
            int c = a;
            if (id.equals("ukraine")) c = y < 9 ? 0xFF0057B7 : 0xFFFFD700;
            if (id.equals("seam") && Math.abs(x - 5.5 - Math.sin(y * 0.9) * 1.6) < 0.8) c = 0xFFC77DFF; // світна тріщина
            if (id.equals("dragon") && (x == 1 || x == 10 || y == 16)) c = 0xFF7A3FB0;
            if (id.equals("night") && ((x * 7 + y * 13) % 17 == 0)) c = 0xFFF5F0C8; // зорі
            if (id.equals("ember") && y > 11 && ((x + y) % 3 == 0)) c = 0xFFFF9A3C;
            img[y * W + x] = c;
            img[y * W + x + 11] = shade(c, 0.7f);
        }
        return img;
    }

    // ------------------------------------------------------------------ файли

    /** PNG -> ARGB 64x64 (скін) або 64x32 (плащ; більші плащі масштабуються вниз). null і попередження, якщо розмір не той. */
    public static int[] readSkin(File f) {
        BufferedImage im = read(f);
        if (im == null) return null;
        if (im.getWidth() != 64 || (im.getHeight() != 64 && im.getHeight() != 32)) { System.out.println("[Voxelin] ⚠️ скін " + f.getName() + ": потрібен PNG 64x64 або 64x32 (формат Minecraft), а він " + im.getWidth() + "x" + im.getHeight()); return null; }
        int[] img = new int[W * W];
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < 64; x++) img[y * W + x] = im.getRGB(x, y);
        if (im.getHeight() == 32) legacy(img);
        return img;
    }

    public static int[] readCape(File f) {
        BufferedImage im = read(f);
        if (im == null) return null;
        if (im.getWidth() % 64 != 0 || im.getHeight() * 2 != im.getWidth()) { System.out.println("[Voxelin] ⚠️ плащ " + f.getName() + ": потрібен PNG 64x32 (або 128x64, 256x128...)"); return null; }
        int k = im.getWidth() / 64;
        int[] img = new int[W * 32];
        for (int y = 0; y < 32; y++) for (int x = 0; x < 64; x++) img[y * W + x] = im.getRGB(x * k, y * k);
        return img;
    }

    private static BufferedImage read(File f) {
        try { BufferedImage im = ImageIO.read(f); if (im == null) System.out.println("[Voxelin] ⚠️ " + f.getName() + ": не PNG"); return im; }
        catch (Exception e) { System.out.println("[Voxelin] ⚠️ " + f.getName() + " не прочитано: " + e.getMessage()); return null; }
    }

    /** Старий скін 64x32: ліва рука/нога - дзеркальні копії правих (кожна грань віддзеркалена, бокові міняються місцями). */
    static void legacy(int[] img) {
        mirrorLimb(img, 0, 16, 16, 48);  // нога
        mirrorLimb(img, 40, 16, 32, 48); // рука
    }

    private static void mirrorLimb(int[] img, int su, int sv, int du, int dv) {
        // верх і низ (4x4 кожен) - дзеркально по горизонталі
        for (int f = 0; f < 2; f++) copyMirror(img, su + 4 + f * 4, sv, du + 4 + f * 4, dv, 4, 4);
        // бокові: праве <-> ліве, перед і зад - дзеркально
        copyMirror(img, su + 8, sv + 4, du, dv + 4, 4, 12);
        copyMirror(img, su + 4, sv + 4, du + 4, dv + 4, 4, 12);
        copyMirror(img, su, sv + 4, du + 8, dv + 4, 4, 12);
        copyMirror(img, su + 12, sv + 4, du + 12, dv + 4, 4, 12);
    }

    private static void copyMirror(int[] img, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img[(dy + y) * W + dx + x] = img[(sy + y) * W + sx + (w - 1 - x)];
    }

    /** PNG-файли в теці (лише імена, відсортовані). */
    public static List<String> list(File dir) {
        File[] fs = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        List<String> out = new ArrayList<>();
        if (fs != null) for (File f : fs) out.add(f.getName());
        Collections.sort(out);
        return out;
    }
}
