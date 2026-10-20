import voxelin.client.Settings;

import java.io.File;
import java.nio.file.Files;

/** Налаштування гравця: читання/запис, межі, биті значення, евристика авто-розміру текстур. */
public class VoxelinSettingsTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("vx-set").toFile();
        File f = new File(dir, "settings.properties");
        Settings d = Settings.load(f);
        check(d.textureSize == 0 && d.renderDistance == 12 && d.fov == 70 && d.msaa == 2 && Math.abs(d.mouseSensitivity - 0.12f) < 1e-6 && d.vsync && d.outlines, "типові значення без файла");
        Settings s = new Settings();
        s.textureSize = 32; s.renderDistance = 18; s.fov = 90; s.msaa = 4; s.mouseSensitivity = 0.2f; s.vsync = false; s.outlines = false;
        s.save(f);
        check(f.isFile() && !new File(f.getPath() + ".tmp").exists(), "файл записано атомарно (без .tmp)");
        Settings r = Settings.load(f);
        check(r.textureSize == 32 && r.renderDistance == 18 && r.fov == 90 && r.msaa == 4 && Math.abs(r.mouseSensitivity - 0.2f) < 1e-6 && !r.vsync && !r.outlines, "запис -> читання");
        s.textureSize = 0; s.save(f);
        check(Settings.load(f).textureSize == 0 && new String(Files.readAllBytes(f.toPath())).contains("textureSize=auto"), "авто зберігається як auto");
        Files.write(f.toPath(), ("textureSize=999\nrenderDistance=-5\nfov=abc\nmsaa=7\nmouseSensitivity=NaN\nvsync=maybe\noutlines=false\n").getBytes());
        Settings b = Settings.load(f);
        check(b.textureSize == 64 && b.renderDistance == 4 && b.fov == 70 && b.msaa == 4 && Math.abs(b.mouseSensitivity - 0.12f) < 1e-6 && b.vsync && !b.outlines, "биті значення -> межі/типові: " + b.textureSize + " " + b.renderDistance + " " + b.fov + " " + b.msaa);
        Files.write(f.toPath(), ("renderDistance=1000\nfov=1\nmouseSensitivity=50\ntextureSize=20\nmsaa=-3\n").getBytes());
        b = Settings.load(f);
        check(b.renderDistance == 24 && b.fov == 60 && b.mouseSensitivity <= 0.30001f && b.textureSize == 16 && b.msaa == 0, "значення обрізаються до меж");
        Files.write(f.toPath(), new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 1, 2, 3, '\\', 'u', 'X'});
        Settings g = Settings.load(f);
        check(g.renderDistance == 12, "сміття замість файла - типові");
        File missingDir = new File(dir, "nested/deeper/settings.properties");
        new Settings().save(missingDir);
        check(missingDir.isFile(), "тека для файла створюється");
        // ручні значення за межами після clampAll
        Settings c = new Settings(); c.renderDistance = 99; c.fov = -4; c.msaa = 3; c.mouseSensitivity = Float.NaN; c.textureSize = 48; c.clampAll();
        check(c.renderDistance == 24 && c.fov == 60 && c.msaa == 2 && c.mouseSensitivity == 0.12f && c.textureSize == 32, "clampAll");
        // евристика
        check(Settings.autoTextureSize("NVIDIA GeForce RTX 3060/PCIe/SSE2") == 32 && Settings.autoTextureSize("Mesa NVIDIA GeForce GTX 1050") == 32, "NVIDIA -> 32");
        check(Settings.autoTextureSize("AMD Radeon RX 6600 (navi23)") == 32 && Settings.autoTextureSize("Radeon RX 580 Series") == 32, "Radeon RX -> 32");
        check(Settings.autoTextureSize("AMD Radeon(TM) Graphics (renoir)") == 16 && Settings.autoTextureSize("AMD Radeon Graphics") == 16, "вбудована Radeon Graphics -> 16");
        check(Settings.autoTextureSize("Mesa Intel(R) HD Graphics 4400 (HSW GT2)") == 16 && Settings.autoTextureSize("Intel(R) Iris(R) Xe Graphics") == 16, "Intel -> 16");
        check(Settings.autoTextureSize("llvmpipe (LLVM 15.0.7, 256 bits)") == 16 && Settings.autoTextureSize("") == 16 && Settings.autoTextureSize(null) == 16 && Settings.autoTextureSize("Невідома") == 16, "програмний/невідомий -> 16");
        check(new Settings().resolvedTextureSize("NVIDIA GeForce") == 32 && new Settings().resolvedTextureSize("Intel") == 16, "авто розв'язується");
        Settings fixed = new Settings(); fixed.textureSize = 64;
        check(fixed.resolvedTextureSize("Intel") == 64, "явне значення перемагає авто");
        System.out.println("VoxelinSettingsTest: " + n + " перевірок OK");
    }
}
