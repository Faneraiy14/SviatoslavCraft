package voxelin.client.look;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Вбудована косметика - звичайні моделі формату Blockbench (Bedrock geometry), лише текст JSON складається кодом, а текстура - палітра
 * кольорів 32x32 (клітинки 4x4). Проходить той самий розбір і малювання, що й косметика гравців, тож є живим прикладом формату.
 * Кубоїди тут задаються у просторі Blockbench (перед -Z, права сторона +X) і при записі в JSON дзеркаляться по X, як робить експорт.
 */
public final class BuiltinCosmetics {
    private BuiltinCosmetics() {}

    /** Назви вбудованої косметики (для перевірки player.properties без розбору моделей). */
    public static final List<String> IDS = List.of("builtin:wreath", "builtin:crown", "builtin:tophat", "builtin:cap", "builtin:horns", "builtin:halo", "builtin:backpack");

    // палітра: індекс -> колір
    private static final int[] PAL = {0xFF3E8E3A, 0xFF24602A, 0xFFD8262E, 0xFFFFD02A, 0xFF2F6FD6, 0xFFF4F1E8, 0xFFE9B638, 0xFF9C7418,
            0xFF1B1B1F, 0xFF7E1A1E, 0xFF7A4E2A, 0xFF4A2E18, 0xFFE8DCC0, 0xFFFFF59A, 0xFF8A8A92, 0xFF8E52D6};
    private static final int GREEN = 0, DGREEN = 1, RED = 2, YELLOW = 3, BLUE = 4, WHITE = 5, GOLD = 6, DGOLD = 7, BLACK = 8, DRED = 9, BROWN = 10, DBROWN = 11, IVORY = 12, GLOW = 13, GRAY = 14, PURPLE = 15;

    static int[] palette() {
        int[] img = new int[32 * 32];
        Random r = new Random(7);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int idx = (y / 4) * 8 + x / 4;
            int c = idx < PAL.length ? PAL[idx] : 0;
            if (c != 0 && idx != GLOW) c = SkinImages.shade(c, 0.93f + r.nextFloat() * 0.14f);
            img[y * 32 + x] = c;
        }
        return img;
    }

    /** Модель: кістки з кубоїдами. */
    private static final class Geo {
        final StringBuilder bones = new StringBuilder();
        StringBuilder cubes;
        String bone;

        Geo bone(String name, float px, float py, float pz, float rx, float ry, float rz) {
            close();
            bone = String.format(Locale.ROOT, "{\"name\":\"%s\",\"pivot\":[%s,%s,%s],\"rotation\":[%s,%s,%s],\"cubes\":[", name, f(-px), f(py), f(pz), f(-rx), f(-ry), f(rz));
            cubes = new StringBuilder();
            return this;
        }

        /** Кубоїд у просторі Blockbench: мінімальний кут (x,y,z), розмір, колір з палітри, поворот навколо центру (градуси). */
        Geo cube(float x, float y, float z, float w, float h, float d, int color, float rx, float ry, float rz) {
            if (cubes.length() > 0) cubes.append(',');
            int u = (color % 8) * 4, v = (color / 8) * 4;
            StringBuilder faces = new StringBuilder();
            for (String f : Cosmetic.FACES) { if (faces.length() > 0) faces.append(','); faces.append(String.format(Locale.ROOT, "\"%s\":{\"uv\":[%d,%d],\"uv_size\":[4,4]}", f, u, v)); }
            cubes.append(String.format(Locale.ROOT, "{\"origin\":[%s,%s,%s],\"size\":[%s,%s,%s]", f(-(x + w)), f(y), f(z), f(w), f(h), f(d)));
            if (rx != 0 || ry != 0 || rz != 0) cubes.append(String.format(Locale.ROOT, ",\"pivot\":[%s,%s,%s],\"rotation\":[%s,%s,%s]", f(-(x + w / 2)), f(y + h / 2), f(z + d / 2), f(-rx), f(-ry), f(rz)));
            cubes.append(",\"uv\":{").append(faces).append("}}");
            return this;
        }

        Geo cube(float x, float y, float z, float w, float h, float d, int color) { return cube(x, y, z, w, h, d, color, 0, 0, 0); }

        private void close() { if (bone != null) { if (bones.length() > 0) bones.append(','); bones.append(bone).append(cubes).append("]}"); bone = null; } }

        String json(String id) {
            close();
            return "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.voxelin." + id + "\",\"texture_width\":32,\"texture_height\":32},\"bones\":[" + bones + "]}]}";
        }

        private static String f(float v) { return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.3f", v); }
    }

    /** Кільце з чотирьох брусків навколо голови на висоті y, товщина t, відступ від голови out. */
    private static void ring(Geo g, float y, float h, float out, float t, int color) {
        float a = -4 - out, b = 8 + 2 * out;
        g.cube(a, y, a, b, h, t, color).cube(a, y, -a - t, b, h, t, color).cube(a, y, a + t, t, h, b - 2 * t, color).cube(-a - t, y, a + t, t, h, b - 2 * t, color);
    }

    public static List<Cosmetic> all() {
        int[] tex = palette();
        List<Cosmetic> out = new ArrayList<>();
        // Вінок: зелене кільце з квітами і стрічками, що спадають на спину
        Geo wreath = new Geo().bone("wreath", 0, 24, 0, 0, 0, 0);
        ring(wreath, 31, 1.5f, 0.6f, 1.2f, DGREEN);
        int[] flowers = {RED, YELLOW, BLUE, RED, WHITE, YELLOW, RED, BLUE};
        float[][] at = {{-3.5f, -5.2f}, {-0.8f, -5.4f}, {2f, -5.2f}, {4.2f, -3f}, {4.2f, 1f}, {-5.6f, -2.5f}, {-5.6f, 1.5f}, {0f, 3.8f}};
        for (int i = 0; i < at.length; i++) wreath.cube(at[i][0], 31.8f, at[i][1], 1.8f, 1.8f, 1.8f, flowers[i], 0, i * 23, 0).cube(at[i][0] + 0.55f, 33.2f, at[i][1] + 0.55f, 0.7f, 0.5f, 0.7f, YELLOW);
        wreath.cube(-3f, 32.2f, -5.8f, 1f, 1f, 1f, GREEN).cube(2.6f, 32.4f, -5.9f, 1f, 1f, 1f, GREEN);
        wreath.bone("ribbons", 0, 31, 4.6f, 12, 0, 0);
        int[] rib = {BLUE, YELLOW, RED, GREEN};
        for (int i = 0; i < rib.length; i++) wreath.cube(-2.6f + i * 1.4f, 17, 4.6f, 1.1f, 14, 0.3f, rib[i], 0, 0, (i - 1.5f) * 3);
        out.add(Cosmetic.parse("builtin:wreath", "Вінок", "head", wreath.json("wreath"), 32, 32, tex));
        // Корона: золотий обідок, зубці, камені
        Geo crown = new Geo().bone("crown", 0, 24, 0, 0, 0, 0);
        ring(crown, 31.5f, 2.2f, 0.5f, 1f, GOLD);
        for (int i = 0; i < 4; i++) {
            float p = -4.5f + i * 2.67f;
            crown.cube(p, 33.7f, -4.5f, 1, 2, 1, GOLD).cube(p, 33.7f, 3.5f, 1, 2, 1, GOLD).cube(-4.5f, 33.7f, p, 1, 2, 1, GOLD).cube(3.5f, 33.7f, p, 1, 2, 1, GOLD);
        }
        crown.cube(-0.7f, 32.1f, -4.9f, 1.4f, 1.2f, 0.5f, RED).cube(-3.2f, 32.3f, -4.8f, 0.9f, 0.9f, 0.4f, BLUE).cube(2.3f, 32.3f, -4.8f, 0.9f, 0.9f, 0.4f, BLUE).cube(-0.5f, 35.6f, -4.7f, 1, 1, 1, RED);
        out.add(Cosmetic.parse("builtin:crown", "Корона", "head", crown.json("crown"), 32, 32, tex));
        // Циліндр: широкі поля, високий верх, червона стрічка
        Geo top = new Geo().bone("tophat", 0, 24, 0, -4, 0, 3).cube(-6.5f, 32, -6.5f, 13, 0.8f, 13, BLACK).cube(-4, 32.8f, -4, 8, 8, 8, BLACK).cube(-4.15f, 33f, -4.15f, 8.3f, 1.6f, 8.3f, DRED);
        out.add(Cosmetic.parse("builtin:tophat", "Циліндр", "head", top.json("tophat"), 32, 32, tex));
        // Кепка: купол і козирок уперед
        Geo cap = new Geo().bone("cap", 0, 24, 0, 0, 0, 0).cube(-4.4f, 30.5f, -4.4f, 8.8f, 2.6f, 8.8f, BLUE).cube(-3.6f, 33.1f, -3.6f, 7.2f, 0.8f, 7.2f, BLUE).cube(-0.5f, 33.9f, -0.5f, 1, 0.5f, 1, WHITE)
                .cube(-4.2f, 30.6f, -8.6f, 8.4f, 0.7f, 4.4f, DGREEN, -8, 0, 0).cube(-4.45f, 30.6f, -4.45f, 8.9f, 0.6f, 8.9f, WHITE);
        out.add(Cosmetic.parse("builtin:cap", "Кепка", "head", cap.json("cap"), 32, 32, tex));
        // Роги: дві вигнуті частини з кількох кубоїдів (кожна - своя кістка з нахилом)
        Geo horns = new Geo();
        for (int side = -1; side <= 1; side += 2) { // ріг росте з тімені вгору й трохи назовні; сегменти звужуються й загинаються назад
            float bx = side * 2.6f;
            horns.bone(side < 0 ? "horn_left" : "horn_right", bx, 32, 0, -10, 0, side * -22)
                    .cube(bx - 1.1f, 31.5f, -1.6f, 2.2f, 2.2f, 2.2f, IVORY)
                    .cube(bx - 0.85f, 33.6f, -1.2f, 1.7f, 2f, 1.7f, IVORY)
                    .cube(bx - 0.6f, 35.4f, -0.6f, 1.2f, 1.8f, 1.2f, GRAY)
                    .cube(bx - 0.35f, 37f, 0.2f, 0.7f, 1.4f, 0.7f, BLACK);
        }
        out.add(Cosmetic.parse("builtin:horns", "Роги", "head", horns.json("horns"), 32, 32, tex));
        // Німб: світне кільце над головою, трохи нахилене
        Geo halo = new Geo().bone("halo", 0, 37, 0, -10, 0, 0);
        ring(halo, 36.5f, 0.6f, 1.2f, 0.9f, GLOW);
        out.add(Cosmetic.parse("builtin:halo", "Німб", "head", halo.json("halo"), 32, 32, tex));
        // Рюкзак: на спині, з клапаном, кишенею й скруткою ковдри зверху
        Geo pack = new Geo().bone("backpack", 0, 24, 0, 0, 0, 0)
                .cube(-3.6f, 13, 2.1f, 7.2f, 9.5f, 3.4f, BROWN).cube(-3.8f, 19.5f, 2f, 7.6f, 3.4f, 3.8f, DBROWN).cube(-2.4f, 14, 5.4f, 4.8f, 3.6f, 1.2f, DBROWN)
                .cube(-0.6f, 19.2f, 5.7f, 1.2f, 1.2f, 0.3f, GOLD).cube(-4.4f, 22.4f, 2.4f, 8.8f, 2.4f, 2.4f, DRED)
                .cube(-3.4f, 13.5f, 1.6f, 1, 10.5f, 0.6f, DBROWN).cube(2.4f, 13.5f, 1.6f, 1, 10.5f, 0.6f, DBROWN);
        out.add(Cosmetic.parse("builtin:backpack", "Рюкзак", "body", pack.json("backpack"), 32, 32, tex));
        return out;
    }
}
