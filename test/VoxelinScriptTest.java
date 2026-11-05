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
            newEvents();
            command(ground);
            blockEvent(ground);
            sandbox();
            editorSave();
            blocksProgram(ground);
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
                on("chat", func(ev) { say("чат: " + mapGet(ev, "name") + " " + mapGet(ev, "text")) })
                on("mob_death", func(ev) { say("загинув: " + mapGet(ev, "mob") + " у " + mapGet(ev, "x")) })
                on("join", func(ev) { say("увійшов: " + mapGet(ev, "name") + " гість " + mapGet(ev, "guest")) })
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

    /** Події 1.1.0 (chat, mob_death, join) доходять і до скриптів. */
    static void newEvents() throws Exception {
        // події 1.1.0 доходять і до скриптів
        g.events.post(new voxelin.api.event.ChatEvent(w, "Тест", "привіт", false));
        g.events.post(new voxelin.api.event.MobDeathEvent(w, "voxelin:zombie", false, 7, 64, -3));
        g.events.post(new voxelin.api.event.PlayerJoinEvent(w, "Друг", true));
        tickUntil(() -> saidHas("чат: Тест привіт") && saidHas("загинув: voxelin:zombie у 7") && saidHas("увійшов: Друг гість "), 8000);
        check(saidHas("чат: Тест привіт"), "скрипт отримав chat: " + said);
        check(saidHas("загинув: voxelin:zombie у 7"), "скрипт отримав mob_death");
        check(saidHas("увійшов: Друг гість "), "скрипт отримав join");
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

    /** Редактор блоків: програма -> код NyxilumLang -> справжній nx; команда з повтором, змінні, умови, подія блоку, «коли запускається». */
    static void blocksProgram(int ground) throws Exception {
        voxelin.client.code.BlockProgram p = new voxelin.client.code.BlockProgram();
        voxelin.client.code.BlockProgram.Node start = p.insert(voxelin.client.code.BlockProgram.kind("start"), null);
        voxelin.client.code.BlockProgram.Node hello = p.insert(voxelin.client.code.BlockProgram.kind("say"), start);
        hello.values[0] = "старт \"блоків\" \\ {count}";
        voxelin.client.code.BlockProgram.Node cmd = p.insert(voxelin.client.code.BlockProgram.kind("command"), null);
        cmd.values[0] = "wall";
        voxelin.client.code.BlockProgram.Node rep = p.insert(voxelin.client.code.BlockProgram.kind("repeat"), cmd);
        rep.values[0] = "arg";
        voxelin.client.code.BlockProgram.Node inner = p.insert(voxelin.client.code.BlockProgram.kind("repeat"), rep);
        inner.values[0] = "2";
        voxelin.client.code.BlockProgram.Node put = p.insert(voxelin.client.code.BlockProgram.kind("setblock"), inner);
        put.values[0] = "bricks"; put.values[1] = "4 + j"; put.values[2] = "i"; put.values[3] = "-1";
        voxelin.client.code.BlockProgram.Node inc = p.insert(voxelin.client.code.BlockProgram.kind("changevar"), rep);
        inc.values[0] = "count"; inc.values[1] = "1";
        voxelin.client.code.BlockProgram.Node ifv = p.insert(voxelin.client.code.BlockProgram.kind("ifvar"), cmd);
        ifv.values[0] = "count"; ifv.values[1] = "≥"; ifv.values[2] = "3";
        p.insert(voxelin.client.code.BlockProgram.kind("say"), ifv).values[0] = "стіна {arg}, разом {count}";
        voxelin.client.code.BlockProgram.Node ifb = p.insert(voxelin.client.code.BlockProgram.kind("ifblock"), cmd);
        ifb.values[0] = "4"; ifb.values[1] = "0"; ifb.values[2] = "-1"; ifb.values[3] = "bricks";
        p.insert(voxelin.client.code.BlockProgram.kind("say"), ifb).values[0] = "цегла на місці";
        voxelin.client.code.BlockProgram.Node fill = p.insert(voxelin.client.code.BlockProgram.kind("fill"), cmd);
        fill.values[1] = "-6"; fill.values[2] = "0"; fill.values[3] = "-6"; fill.values[4] = "-5"; fill.values[5] = "1"; fill.values[6] = "-5";
        voxelin.client.code.BlockProgram.Node placed = p.insert(voxelin.client.code.BlockProgram.kind("placed"), null);
        placed.values[0] = "glass";
        voxelin.client.code.BlockProgram.Node pc = p.insert(voxelin.client.code.BlockProgram.kind("changevar"), placed);
        pc.values[0] = "glasses";
        p.insert(voxelin.client.code.BlockProgram.kind("setblock"), pc).values[0] = "stone";
        p.insert(voxelin.client.code.BlockProgram.kind("say"), placed).values[0] = "скла: {glasses}";
        said.clear();
        g.scripts.saveBlocks("blk", p.toJson(), p.toCode("blk"));
        tickUntil(() -> saidHas("старт \"блоків\" \\ 0"), 8000);
        check(saidHas("старт \"блоків\" \\ 0"), "«коли запускається» + текст з лапками й змінною: " + said + " / " + g.scripts.console("blk"));
        long px = 3, py = ground + 1, pz = 3;
        List<String> out = g.commands.execute(Sources.player("Тест"), w, new long[]{px, py, pz}, "/wall 3");
        check(out.isEmpty(), "команда з блоків зареєстрована: " + out);
        tickUntil(() -> saidHas("цегла на місці"), 5000);
        check(saidHas("стіна 3, разом 3"), "змінні й умова: " + said + " / " + g.scripts.console("blk"));
        check(saidHas("цегла на місці"), "якщо на ... є блок");
        int bricks = 0;
        for (int dy = 0; dy < 3; dy++) for (int dx = 4; dx <= 5; dx++) if (w.getBlock(px + dx, (int) py + dy, pz - 1).name().equals("voxelin:bricks")) bricks++;
        check(bricks == 6, "повтор у повторі: стінка 2x3 з лічильниками i, j: " + bricks);
        tickUntil(() -> w.getBlock(px - 5, (int) py + 1, pz - 5).name().equals("voxelin:glass"), 5000); // run - запит, іде після say
        check(w.getBlock(px - 6, (int) py, pz - 6).name().equals("voxelin:glass") && w.getBlock(px - 5, (int) py + 1, pz - 5).name().equals("voxelin:glass"), "заповнити (fill) відносно гравця");
        w.setBlock(Sources.player("Гравець"), 9, ground + 1, 9, g.blocks.byName("voxelin:glass"));
        tickUntil(() -> saidHas("скла: 1"), 5000);
        check(saidHas("скла: 1"), "подія «ставить блок» з позицією від блоку");
        check(w.getBlock(11, ground + 1, 9).name().equals("voxelin:stone"), "у події блоку позиції - від самого блоку");
        g.scripts.stop("blk");
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
