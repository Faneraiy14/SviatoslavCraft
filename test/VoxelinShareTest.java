import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.*;

/** Обмін світами: експорт -> імпорт зберігає світ і скрипти світу; зламані й зловмисні архіви відкидаються без слідів; скрипти світу. */
public class VoxelinShareTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("vx-share").toFile(), saves = new File(root, "saves"), share = new File(root, "share");
        Game g = new Game(saves, null);
        g.createWorld("Острів", 77, WorldInfo.Type.FLAT);
        World w = g.openWorld("Острів");
        w.naturalSpawning = false;
        for (int i = 0; i < 600; i++) { w.update(0, 0, 2); if (w.chunk(0, 0) != null && w.chunk(1, 1) != null) break; Thread.sleep(10); }
        BlockRef gold = g.blocks.byName("voxelin:gold_block") != null ? g.blocks.byName("voxelin:gold_block") : g.blocks.byName("voxelin:bricks");
        w.setBlock(Sources.player("P"), 5, 70, 5, gold);
        w.close();
        File scriptDir = new File(g.worldDir("Острів"), "scripts/greeter");
        scriptDir.mkdirs();
        Files.writeString(new File(scriptDir, "main.nx").toPath(), "import \"../voxelin.nx\"\nfunc main() {\n    vxRun()\n}\n");

        File out = new File(share, "Острів" + WorldArchive.EXT);
        int files = WorldArchive.export(g.worldDir("Острів"), out);
        check(out.isFile() && files >= 3 && !new File(out.getPath() + ".tmp").exists(), "експорт: " + files + " файлів");
        check(WorldArchive.nameOf(out).equals("Острів"), "назва з архіву");
        check(WorldArchive.list(share).size() == 1, "список архівів");

        String name = WorldArchive.importTo(out, saves);
        check(name.equals("Острів 2"), "та сама назва вже є - «Острів 2»: " + name);
        check(g.listWorlds().contains("Острів 2") && WorldInfo.load(g.worldDir("Острів 2")).name.equals("Острів 2"), "імпортований світ у списку, назва в voxelin.world");
        check(new File(g.worldDir("Острів 2"), "scripts/greeter/main.nx").isFile(), "скрипти світу поїхали разом зі світом");
        World w2 = g.openWorld("Острів 2");
        for (int i = 0; i < 600; i++) { w2.update(0, 0, 2); if (w2.chunk(0, 0) != null) break; Thread.sleep(10); }
        check(w2.getBlock(5, 70, 5).name().equals(gold.name()) && w2.seed() == 77, "блоки й зерно збереглися");
        w2.close();
        check(WorldArchive.importTo(out, saves).equals("Острів 3"), "ще раз - «Острів 3»");
        check(WorldArchive.freeName(saves, "Дуже довга назва").length() <= 16, "довга назва вкладається в 16");

        // зловмисні й зламані архіви
        String[][] evil = {
                {"world/../escape.txt", "x"}, {"world//abs", "x"}, {"world/a/../../b", "x"}, {"../x", "x"}, {"world/C:/x", "x"}, {"world\\..\\x", "x"}, {"other/file", "x"}};
        for (String[] e : evil) {
            File z = zip(root, "evil.vxworld", "world/voxelin.world", Files.readString(new File(g.worldDir("Острів"), WorldInfo.FILE).toPath()), e[0], e[1]);
            String err = importErr(z, saves);
            check(err != null, "відкинуто: " + e[0]);
        }
        check(!new File(root, "escape.txt").exists() && !new File(saves, "escape.txt").exists(), "нічого не вилізло за межі");
        check(importErr(zip(root, "empty.vxworld", "world/region/r.0.0.vxr", "x"), saves).contains("нема світу"), "без voxelin.world - відмова");
        Files.writeString(new File(root, "junk.vxworld").toPath(), "це не zip");
        check(importErr(new File(root, "junk.vxworld"), saves) != null, "не ZIP - відмова");
        check(importErr(zip(root, "badinfo.vxworld", "world/voxelin.world", "\u0000\u0001сміття"), saves) != null, "битий voxelin.world - відмова");
        File[] left = saves.listFiles((d, nm) -> nm.startsWith(".import-"));
        check(left == null || left.length == 0, "тимчасові теки прибрано");
        check(g.listWorlds().size() == 3, "зайвих світів не з'явилось: " + g.listWorlds());
        for (String ok : new String[]{"region/r.0.0.vxr", "scripts/a/main.nx", "voxelin.world"}) check(safeOk(ok), "шлях годиться: " + ok);

        // скрипти світу: видно разом із загальними, світ перемагає за назвою
        File gscripts = new File(root, "scripts");
        new File(gscripts, "greeter").mkdirs(); new File(gscripts, "common").mkdirs();
        Files.writeString(new File(gscripts, "greeter/main.nx").toPath(), "// загальний");
        Files.writeString(new File(gscripts, "common/main.nx").toPath(), "// загальний");
        ScriptHost host = new ScriptHost(gscripts, null, new EventBus(), new CommandRegistry(), g.blocks);
        check(host.ids().equals(List.of("common", "greeter")) && !host.inWorld("greeter") && Files.readString(host.mainFile("greeter").toPath()).contains("загальний"), "без світу - лише загальні");
        host.setWorldDir(g.worldDir("Острів 2"));
        check(host.ids().equals(List.of("common", "greeter")) && host.inWorld("greeter") && !host.inWorld("common"), "зі світом: " + host.ids());
        check(Files.readString(host.mainFile("greeter").toPath()).contains("vxRun"), "однакова назва - скрипт світу");
        host.createInWorld("local", "// x");
        check(new File(g.worldDir("Острів 2"), "scripts/local/main.nx").isFile() && host.inWorld("local"), "новий скрипт у теці світу");
        boolean threw = false;
        try { host.createInWorld("common", "x"); } catch (IOException e) { threw = true; }
        check(threw, "не можна створити скрипт з назвою, що вже є");
        System.out.println("VoxelinShareTest: " + n + " перевірок OK");
        System.exit(0);
    }

    static boolean safeOk(String rel) throws Exception {
        java.lang.reflect.Method m = WorldArchive.class.getDeclaredMethod("safe", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, rel);
    }

    static String importErr(File z, File saves) {
        try { WorldArchive.importTo(z, saves); return null; } catch (IOException e) { return e.getMessage(); }
    }

    static File zip(File dir, String name, String... kv) throws IOException {
        File f = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (int i = 0; i < kv.length; i += 2) { z.putNextEntry(new ZipEntry(kv[i])); z.write(kv[i + 1].getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return f;
    }
}
