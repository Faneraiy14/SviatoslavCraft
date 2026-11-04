import voxelin.api.*;
import voxelin.core.*;
import voxelin.loader.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Скрипти NyxilumLang у справжніх процесах nx: події й операції, свої команди, ліміт блоків за тік, пісочниця (без чужих файлів),
 * зависання й падіння, перезапуск після збереження файла, on("stop") при закритті. Без nx у PATH (VOXELIN_NX) - пропускається.
 */
public class VoxelinScriptTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); dump(); System.exit(1); } }
    static Game g;
    static World w;
    static File scripts;
    static final List<String> said = Collections.synchronizedList(new ArrayList<>());

    static final class Fake implements PlayerTarget {
        Pos pos;
        Fake(Pos p) { pos = p; }
        public Pos body() { return pos; }
        public boolean alive() { return true; }
        public boolean creative() { return false; }
        public void hurt(double d, double kx, double kz) {}
        public void notify(String m) { said.add(m); }
    }

    public static void main(String[] a) throws Exception {
        if (ScriptHost.findNx() == null) { System.out.println("VoxelinScriptTest: пропущено - nx не знайдено"); return; }
        File root = Files.createTempDirectory("vx-script").toFile();
        g = new Game(new File(root, "saves"), null);
        scripts = new File(root, "scripts");
        g.enableScripts(scripts, null);
        write("builder", BUILDER);
        g.createWorld("s", 5, WorldInfo.Type.FLAT);
        w = g.openWorld("s");
        w.naturalSpawning = false;
        for (int i = 0; i < 900; i++) { w.update(0, 0, 3); boolean all = true; for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (w.chunk(dx, dz) == null) all = false; if (all) break; Thread.sleep(10); }
        int ground = 0;
        for (int y = 200; y > 0; y--) if (w.blockId(3, y, 3) > 0) { ground = y; break; }
        w.setPlayerTarget(new Fake(Pos.ofBlock(3, ground + 1 + 0.9, 3)));
        String groundName = w.getBlock(3, ground, 3).name();

        try {
            basics(groundName, ground);
            budget();
            command(ground);
            blockEvent(ground);
            sandbox();
            editorSave();
            hang();
            crashAndReload();
            stopSaves();
        } finally { g.close(); }
        System.out.println("VoxelinScriptTest: " + n + " перевірок OK");
    }

    // ------------------------------------------------------------------ скрипти

    static final String BUILDER = """
            import "../voxelin.nx"

            var started = false
            var blocks = 0

            func onTick(ev) {
                if (started) {
                    return null
                }
                started = true
                var p = player()
                var below = getBlock(floor(mapGet(p, "x")), floor(mapGet(p, "y")) - 1, floor(mapGet(p, "z")))
                say("старт: " + below)
                setBlock(0, 70, 0, "bricks")
                var lines = run("/setblock 1 70 0 voxelin:stone")
                say("run: " + len(lines))
                var i = 0
                while (i < 600) {
                    setBlock(i % 30, 80, floor(i / 30), "voxelin:stone")
                    i = i + 1
                }
                return null
            }

            func onPillar(ev) {
                var h = toInt(mapGet(ev, "args")[0])
                var i = 0
                while (i < h) {
                    setBlock(mapGet(ev, "x") + 2, mapGet(ev, "y") + i, mapGet(ev, "z"), "voxelin:bricks")
                    i = i + 1
                }
                say("стовп " + h + " від " + mapGet(ev, "by"))
            }

            func onBlock(ev) {
                blocks = blocks + 1
                say("блок: " + mapGet(ev, "new") + " від " + mapGet(ev, "by"))
            }

            func main() {
                on("tick", onTick)
                on("block", onBlock)
                command("pillar", "<висота>", onPillar)
                vxRun()
            }
            """;

    static void basics(String groundName, int ground) throws Exception {
        tickUntil(() -> saidHas("run: "), 8000);
        check(saidHas("старт: " + groundName), "getBlock під гравцем і player(): " + said);
        check(w.getBlock(0, 70, 0).name().equals("voxelin:bricks"), "setBlock скрипта (коротка назва блока)");
        check(w.getBlock(1, 70, 0).name().equals("voxelin:stone"), "run(\"/setblock ...\") від імені скрипта");
        check(saidHas("run: 1"), "run повертає рядки відповіді команди");
        ScriptHost.Info i = info("builder");
        check(i != null && i.state == ScriptHost.State.RUNNING, "builder працює");
        check(new File(scripts, "voxelin.nx").isFile(), "гра поклала помічник scripts/voxelin.nx");
    }

    static void budget() throws Exception {
        // 600 setBlock за раз: не більше 256 за тік
        tickUntil(() -> count(80) > 0, 3000);
        int first = count(80);
        check(first > 0 && first <= 256, "ліміт блоків за тік: " + first);
        tickUntil(() -> count(80) == 600, 3000);
        check(count(80) == 600, "решта блоків - у наступних тіках: " + count(80));
    }

    static int count(int y) { int c = 0; for (int x = 0; x < 30; x++) for (int z = 0; z < 20; z++) if (w.getBlock(x, y, z).name().equals("voxelin:stone")) c++; return c; }

    /** Вікно коду: «+ НОВИЙ» пише шаблон через ScriptHost.save - скрипт одразу запускається, команда працює; зміна тексту - перезапуск. */
    static void editorSave() throws Exception {
        g.scripts.save("tmpl", ScriptHost.template("tmpl"));
        tickUntil(() -> { List<String> o = g.commands.execute(Sources.player("Тест"), w, null, "/tmpl"); return o.isEmpty() && saidHas("Привіт із скрипта tmpl"); }, 8000);
        check(saidHas("Привіт із скрипта tmpl"), "шаблон нового скрипта працює: /tmpl");
        check(g.scripts.ids().contains("tmpl") && g.scripts.info("tmpl").state == ScriptHost.State.RUNNING, "новий скрипт у списку й працює");
        g.scripts.save("tmpl", ScriptHost.template("tmpl").replace("Привіт із скрипта", "Змінено:"));
        tickUntil(() -> { g.commands.execute(Sources.player("Тест"), w, null, "/tmpl"); return saidHas("Змінено: tmpl"); }, 8000);
        check(saidHas("Змінено: tmpl"), "збереження у вікні коду перезапускає скрипт");
        g.scripts.stop("tmpl");
        check(g.scripts.info("tmpl").state == ScriptHost.State.STOPPED, "кнопка ЗУПИНИТИ");
    }

    static void command(int ground) throws Exception {
        List<String> out = g.commands.execute(Sources.player("Тест"), w, new long[]{3, ground + 1, 3}, "/pillar 4");
        check(out.isEmpty(), "команда скрипта зареєстрована: " + out);
        tickUntil(() -> saidHas("стовп 4 від Тест"), 3000);
        int h = 0;
        while (w.getBlock(5, ground + 1 + h, 3).name().equals("voxelin:bricks")) h++;
        check(h == 4, "стовп з 4 блоків біля гравця: " + h);
        check(g.commands.execute(Sources.player("Тест"), w, null, "/script list").stream().anyMatch(l -> l.startsWith("builder [RUNNING]")), "/script list");
    }

    static void blockEvent(int ground) throws Exception {
        BlockRef glass = g.blocks.byName("voxelin:glass");
        w.setBlock(Sources.player("Гравець"), 7, ground + 1, 7, glass);
        tickUntil(() -> saidHas("блок: voxelin:glass від Гравець"), 3000);
        check(saidHas("блок: voxelin:glass від Гравець"), "подія block про зміну гравцем");
        said.clear();
        tick(20);
        check(said.stream().noneMatch(s -> s.startsWith("блок:")), "власні зміни скрипта подію block не породжують: " + said);
    }

    static void sandbox() throws Exception {
        File secret = new File(scripts.getParentFile(), "secret.txt");
        Files.writeString(secret.toPath(), "таємниця");
        write("snoop", """
                import "../voxelin.nx"
                func main() {
                    writeFile("own.txt", "моє")
                    say("своє: " + readFile("own.txt"))
                    try {
                        say("чуже: " + readFile("../../secret.txt"))
                    } catch (e) {
                        say("чуже заборонено")
                    }
                    try {
                        httpGet("http://127.0.0.1:9/")
                        say("мережа є")
                    } catch (e) {
                        say("мережі нема")
                    }
                    vxRun()
                }
                """);
        tickUntil(() -> saidHas("мережі нема") || saidHas("мережа є"), 8000);
        check(saidHas("своє: моє"), "файли у власній теці дозволені: " + said);
        check(saidHas("чуже заборонено") && !saidHas("таємниця"), "чужі файли заборонені");
        check(saidHas("мережі нема"), "мережа заборонена");
    }

    static void hang() throws Exception {
        write("loop", """
                import "../voxelin.nx"
                func main() {
                    on("second", func(ev) {
                        var i = 0
                        while (true) {
                            i = i + 1
                        }
                    })
                    vxRun()
                }
                """);
        tickUntil(() -> info("loop") != null && info("loop").state == ScriptHost.State.KILLED, 15000);
        ScriptHost.Info i = info("loop");
        check(i != null && i.state == ScriptHost.State.KILLED && i.message.contains("не відповідає"), "завислий скрипт зупинено: " + i);
        check(saidHas("Скрипт «loop» зупинено"), "гравцю сказано про зупинку");
        check(info("builder").state == ScriptHost.State.RUNNING, "інші скрипти працюють далі");
    }

    static void crashAndReload() throws Exception {
        write("broken", "import \"../voxelin.nx\"\nfunc main() {\n    var x = mapGet(null, \"a\")\n    vxRun()\n}\n");
        tickUntil(() -> info("broken") != null && info("broken").state == ScriptHost.State.CRASHED, 8000);
        ScriptHost.Info i = info("broken");
        check(i != null && i.state == ScriptHost.State.CRASHED, "скрипт з помилкою позначено як впалий: " + i);
        List<String> log = g.commands.execute(Sources.player("Тест"), w, null, "/script log broken");
        check(log.stream().anyMatch(l -> l.contains("Object reference") || l.contains("Error")), "/script log показує помилку: " + log);
        said.clear();
        Thread.sleep(1100); // різний час зміни файла навіть на ФС із секундною точністю
        write("broken", "import \"../voxelin.nx\"\nfunc main() {\n    say(\"виправлено\")\n    vxRun()\n}\n");
        tickUntil(() -> saidHas("виправлено"), 8000);
        check(saidHas("виправлено") && info("broken").state == ScriptHost.State.RUNNING, "збережений файл перезапускає скрипт (і впалий теж)");
    }

    static void stopSaves() throws Exception {
        write("saver", """
                import "../voxelin.nx"
                var seconds = 0
                func main() {
                    on("second", func(ev) { seconds = seconds + 1 })
                    on("stop", func(ev) { writeFile("state.txt", "секунд " + seconds) })
                    say("saver готовий")
                    vxRun()
                }
                """);
        tickUntil(() -> saidHas("saver готовий"), 8000);
        tick(45);
        g.close();
        File st = new File(scripts, "saver/state.txt");
        check(st.isFile() && Files.readString(st.toPath()).startsWith("секунд "), "on(\"stop\") встигає зберегти дані при закритті");
        check(info("builder").state == ScriptHost.State.STOPPED, "після закриття скрипти зупинено");
    }

    // ------------------------------------------------------------------ дрібне

    static void write(String id, String code) throws Exception {
        File d = new File(scripts, id);
        d.mkdirs();
        Files.write(new File(d, "main.nx").toPath(), code.getBytes(StandardCharsets.UTF_8));
    }

    static ScriptHost.Info info(String id) { for (ScriptHost.Info i : g.scripts.list()) if (i.id.equals(id)) return i; return null; }

    static boolean saidHas(String s) { synchronized (said) { for (String x : said) if (x.contains(s)) return true; } return false; }

    static void tick(int k) throws Exception { for (int i = 0; i < k; i++) { w.tick(); Thread.sleep(2); } }

    static void tickUntil(BooleanSupplier ok, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end) { w.tick(); Thread.sleep(3); }
    }

    static void dump() {
        System.out.println("сказано: " + said);
        if (g != null && g.scripts != null) for (ScriptHost.Info i : g.scripts.list()) System.out.println("  " + i);
    }
}
