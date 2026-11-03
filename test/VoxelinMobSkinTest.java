import voxelin.api.*;
import voxelin.client.render.MobSkins;
import voxelin.core.*;
import voxelin.core.entity.*;
import voxelin.loader.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Моделі мобів: ролі частин (голова, руки), візерунки, генерація текстур і заміна своїм PNG, поворот голови й тіла, змах руки. */
public class VoxelinMobSkinTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static Game g;

    public static void main(String[] a) throws Exception {
        g = new Game(new File(Files.createTempDirectory("vx-skin").toFile(), "saves"), null);
        api(); vanilla(); textures(); png(); animation();
        System.out.println("VoxelinMobSkinTest: " + n + " перевірок OK");
    }

    static void api() {
        MobSettings s = new MobSettings().part(0, 0, 0, 1, 1, 1, 0xFF0000).pattern("fur").head(0, 1, 0, 0.5f, 0.5f, 0.5f, 0x00FF00).face(0x123456)
                .part(0, 1.5f, 0, 0.1f, 0.1f, 0.1f, 0).onHead().armPart(0.4f, 0.5f, 0, 0.2f, 0.5f, 0.2f, 0, true).pattern("nope");
        List<MobSettings.Part> p = s.partList();
        check(p.get(0).role == MobSettings.Part.BODY && "fur".equals(p.get(0).pattern), "тіло з візерунком");
        check(p.get(1).role == MobSettings.Part.HEAD && p.get(1).face, "head(): голова з обличчям");
        check(p.get(2).role == MobSettings.Part.HEAD && !p.get(2).face, "onHead(): на голові, без обличчя");
        check(p.get(3).arm() && p.get(3).role == MobSettings.Part.LIMB && p.get(3).pattern == null, "armPart: рука; невідомий візерунок - рівний колір");
        check(s.eyeColor() == 0x123456 && s.qualified("m").eyeColor() == 0x123456 && s.qualified("m").partList().get(1).face, "face() і копія для мода");
        check(new MobSettings().legPart(0, 0, 0, 1, 1, 1, 0, true).partList().get(0).role == MobSettings.Part.LIMB
                && new MobSettings().wingPart(0, 0, 0, 1, 1, 1, 0, true).partList().get(0).role == MobSettings.Part.WING, "старі legPart/wingPart отримують роль самі");
        check(new MobSettings.Part(0, 0, 0, 1, 1, 1, 0, 0).role == MobSettings.Part.BODY, "старий конструктор частини працює");
    }

    static void vanilla() {
        for (MobType t : g.mobTypes.all()) {
            int heads = 0, faces = 0, patterned = 0;
            for (MobSettings.Part p : t.parts) { if (p.role == MobSettings.Part.HEAD) heads++; if (p.face) faces++; if (p.pattern != null) patterned++; }
            check(heads > 0, "у " + t.name + " є голова");
            check(faces <= 1, "обличчя - не більше одного: " + t.name);
            check(patterned * 2 >= t.parts.size(), "більшість частин " + t.name + " з візерунком: " + patterned + "/" + t.parts.size());
        }
        int arms = 0; for (MobSettings.Part p : g.mobTypes.get("voxelin:zombie").parts) if (p.arm()) arms++;
        check(arms == 2, "у зомбі дві руки");
    }

    static void textures() {
        MobType z = g.mobTypes.get("voxelin:zombie");
        int head = -1; for (int i = 0; i < z.parts.size(); i++) if (z.parts.get(i).face) head = i;
        int[] a = MobSkins.generate(z, head, false), b = MobSkins.generate(z, head, false), f = MobSkins.generate(z, head, true);
        check(a.length == 256 && Arrays.equals(a, b), "текстура стабільна (те саме зерно)");
        int diff = 0; for (int i = 0; i < 256; i++) if (a[i] != f[i]) diff++;
        check(diff >= 8 && diff <= 40, "обличчя - очі й рот на плитці: " + diff + " пікселів");
        check((f[3 + 6 * 16] & 0xFFFFFF) == 0x1A2A12, "око кольору face()");
        for (MobType t : g.mobTypes.all()) for (int i = 0; i < t.parts.size(); i++) {
            int[] px = MobSkins.generate(t, i, false);
            int rgb = t.parts.get(i).rgb; long sr = 0, sg = 0, sb = 0;
            for (int c : px) { check((c >>> 24) == 0xFF, "непрозора"); sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; }
            check(Math.abs(sr / 256 - ((rgb >> 16) & 255)) <= 40 && Math.abs(sg / 256 - ((rgb >> 8) & 255)) <= 40 && Math.abs(sb / 256 - (rgb & 255)) <= 40, "середній колір плитки близький до кольору частини: " + t.name + " #" + i);
        }
        // різні візерунки - різні плитки
        MobRegistry reg = new MobRegistry();
        Set<String> seen = new HashSet<>();
        for (String pat : MobSettings.PATTERNS) {
            MobSettings s = new MobSettings().part(0, 0, 0, 1, 1, 1, 0x808080).pattern(pat).head(0, 1, 0, 1, 1, 1, 0x808080);
            MobType t = reg.register("voxelin:t_" + pat, s);
            seen.add(Arrays.toString(MobSkins.generate(t, 0, false)));
        }
        check(seen.size() == MobSettings.PATTERNS.size(), "усі " + MobSettings.PATTERNS.size() + " візерунків різні");
    }

    static void png() throws Exception {
        File dir = Files.createTempDirectory("vx-skinpng").toFile();
        File mobs = new File(new File(dir, "voxelin"), "mobs"); mobs.mkdirs();
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) img.setRGB(x, y, x < 16 ? 0xFFFF0000 : 0xFF0000FF);
        ImageIO.write(img, "png", new File(mobs, "zombie_3.png"));
        int[] px = MobSkins.load(dir, "voxelin:zombie", 3, false);
        check(px != null && px[0] == 0xFFFF0000 && px[15] == 0xFF0000FF, "свій PNG 32x32 масштабується до 16x16");
        check(MobSkins.load(dir, "voxelin:zombie", 4, false) == null && MobSkins.load(dir, "voxelin:zombie", 3, true) == null, "інших частин і обличчя нема - генеруються");
        Files.writeString(new File(mobs, "zombie_5.png").toPath(), "не png");
        check(MobSkins.load(dir, "voxelin:zombie", 5, false) == null, "битий PNG - пропускається");
        check(MobSkins.load(dir, "voxelin:../x", 0, false) == null, "назва з .. - відхиляється");
    }

    static final class Fake implements PlayerTarget {
        Pos pos; Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return false; }
        public void hurt(double d, double kx, double kz) {}
    }

    static void animation() throws Exception {
        g.createWorld("an", 3, WorldInfo.Type.FLAT);
        World w = g.openWorld("an");
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int S = 0; for (int y = 200; y > 0; y--) if (w.blockId(0, y, 0) > 0) { S = y; break; }
        w.setTime(w.dayTicks() / 4);
        Mob c = w.spawnMob(g.mobTypes.get("voxelin:villager_farmer"), new Pos(0, 0, 0.5, S + 1, 0.5));
        c.talkTicks = 1000; // стоїть на місці
        c.setYaw(0); // дивиться на -z
        Fake f = new Fake(new Pos(0, 0, 4.5, S + 1.9, 0.5)); w.setPlayerTarget(f); // гравець праворуч (+x): 90 градусів
        for (int i = 0; i < 40; i++) w.tick();
        check(Math.abs(c.headYaw - 60) < 2, "голова повертається до гравця, але не більше 60: " + c.headYaw);
        f.pos = new Pos(0, 0, 0.5, S + 1.9, -4.5); // попереду
        for (int i = 0; i < 40; i++) w.tick();
        check(Math.abs(c.headYaw) < 2, "гравець попереду - голова прямо: " + c.headYaw);
        f.pos = new Pos(0, 0, 0.5, S + 6, -3.5);
        for (int i = 0; i < 40; i++) w.tick();
        check(c.headPitch > 20, "гравець угорі - голова піднімається: " + c.headPitch);
        f.pos = new Pos(0, 0, 0.5, S + 1.9, 30.5); // далеко - дивиться прямо
        for (int i = 0; i < 40; i++) w.tick();
        check(Math.abs(c.headYaw) < 2 && Math.abs(c.headPitch) < 2, "далекий гравець - голова прямо");
        // тіло доповертається плавно
        c.talkTicks = 0;
        c.yaw = 170; c.bodyYaw = 0;
        Mob z = w.spawnMob(g.mobTypes.get("voxelin:zombie"), new Pos(0, 0, 10.5, S + 1, 10.5));
        z.setYaw(0); z.yaw = 90;
        java.lang.reflect.Method look = Mob.class.getDeclaredMethod("look", PlayerTarget.class, Mob.class); look.setAccessible(true);
        look.invoke(z, null, null);
        check(Math.abs(z.bodyYaw - 18) < 0.01, "тіло повертається не більше 18 градусів за тік: " + z.bodyYaw);
        for (int i = 0; i < 10; i++) look.invoke(z, null, null);
        check(Math.abs(z.bodyYaw - 90) < 0.01, "і доходить до напряму руху");
        z.yaw = -170; z.bodyYaw = 170; look.invoke(z, null, null);
        check(Math.abs(Math.abs(z.bodyYaw) - 172) < 0.01 || Math.abs(Math.abs(z.bodyYaw) - 180) < 0.01, "поворот через 180 - коротшим шляхом: " + z.bodyYaw);
        // змах руки при ударі
        w.setTime((long) (w.dayTicks() * 0.78));
        Fake near = new Fake(new Pos(0, 0, 11.5, S + 1.9, 10.5)); w.setPlayerTarget(near);
        boolean swung = false;
        for (int i = 0; i < 60 && !swung; i++) { w.setTime((long) (w.dayTicks() * 0.78)); w.tick(); if (z.swingTicks > 0) swung = true; }
        check(swung, "зомбі змахує рукою, коли б'є");
        w.close();
    }
}
