package voxelin.loader;

import voxelin.api.BlockRef;
import voxelin.api.Commands;
import voxelin.api.Source;
import voxelin.api.WorldAccess;
import voxelin.api.event.BlockChangeEvent;
import voxelin.api.event.TickEvent;
import voxelin.core.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Скрипти NyxilumLang (рівень 3 «повний код»): кожна тека scripts/<назва>/ з main.nx - окремий процес nx у пісочниці.
 * Гра шле події (tick, second, block, command, world), скрипт відповідає операціями (say, setBlock, getBlock, run, ...).
 * Усі зміни світу - від імені джерела SCRIPT (права 2, не більше BLOCKS_PER_TICK блоків за тік).
 *
 * Ліміти: процес, що 5 с не забирає події, або займає понад MEMORY_MB пам'яті (Linux), зупиняється; операції обробляються
 * в межах TIME_BUDGET_US мікросекунд на тік для всіх скриптів разом, решта чекає наступного тіку. Збережений файл .nx
 * у теці скрипта перезапускає його (hot reload). Скрипти стартують з першим тіком світу і перезапускаються, коли відкрито
 * інший світ; перехід між вимірами - подія "world".
 */
public final class ScriptHost {
    public static final int API = 1;
    static final int BLOCKS_PER_TICK = 256, STUCK_TICKS = 100, MAX_OPS_PER_TICK = 4000, STUCK_MS = 5000, MEMORY_MB = 512;
    static final long TIME_BUDGET_US = 4000, REPLY_WAIT_US = 300;
    private static final Set<String> EVENTS = Set.of("tick", "second", "block", "world");

    public enum State { RUNNING, STOPPED, CRASHED, KILLED, NO_NX }

    /** Стан скрипта для /script list і тестів. */
    public static final class Info {
        public final String id; public State state = State.STOPPED; public String message;
        Info(String id) { this.id = id; }
        @Override public String toString() { return id + " [" + state + "]" + (message == null ? "" : " " + message); }
    }

    private final class Script {
        final String id; final File dir; final Info info;
        final Source source;
        final Set<String> subs = new HashSet<>();
        final Set<String> commands = new HashSet<>();
        ScriptProcess proc;
        long stamp;
        Map<String, Object> held; // операція, відкладена до наступного тіку (вичерпано ліміт блоків)
        int blocksThisTick;
        long pingN, pongN, pingTick, pingNanos; // пінг раз на секунду; без відповіді STUCK_TICKS ігрового часу І STUCK_MS справжнього - завис
        // (пауза гри не рахується - тіки стоять; справжній час потрібен, бо тіки бувають швидші за годинник: тести, наздоганяння після лагу,
        // а холодний старт nx на завантаженій машині триває секунди)
        Script(String id, File dir) { this.id = id; this.dir = dir; this.info = new Info(id); this.source = Sources.of("script:" + id, Source.Kind.SCRIPT, 2, BLOCKS_PER_TICK); }
        String owner() { return "script:" + id; }
    }

    private final File dir;
    private final List<String> nx;
    private final CommandRegistry commands;
    private final BlockRegistry blocks;
    private final Map<String, Script> scripts = new TreeMap<>();
    private WorldAccess world;
    private String worldName;
    private long tick;

    /** nx - команда запуску інтерпретатора (null - знайти самому: змінна VOXELIN_NX, потім nx у PATH). */
    public ScriptHost(File dir, List<String> nx, EventBus events, CommandRegistry commands, BlockRegistry blocks) {
        this.dir = dir;
        this.nx = nx != null ? nx : findNx();
        this.commands = commands;
        this.blocks = blocks;
        events.on("scripts", TickEvent.class, this::onTick);
        events.on("scripts", BlockChangeEvent.class, this::onBlock);
        commands.register("scripts", "script", "list | reload <назва|all> | stop <назва> | log <назва>", 0, this::command);
    }

    /** Команда запуску nx або null, якщо інтерпретатор не знайдено. */
    public static List<String> findNx() {
        String env = System.getenv("VOXELIN_NX");
        if (env != null && !env.isBlank()) return List.of(env.trim());
        String path = System.getenv("PATH");
        if (path == null) return null;
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String d : path.split(File.pathSeparator)) for (String n : win ? new String[]{"nx.exe", "nx.cmd", "nx.bat", "Nx.exe"} : new String[]{"nx", "Nx"}) {
            File f = new File(d, n);
            if (f.isFile() && f.canExecute()) return List.of(f.getAbsolutePath());
        }
        return null;
    }

    public boolean hasNx() { return nx != null; }

    public File dir() { return dir; }

    public List<Info> list() { List<Info> r = new ArrayList<>(); for (Script s : scripts.values()) r.add(s.info); return r; }

    // ------------------------------------------------------------------ для вікна коду в грі

    /** Чи годиться назва скрипта (тека scripts/<назва>/). */
    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9_]{1,32}"); }

    /** Назви скриптів на диску (теки з main.nx), за абеткою - і тих, що ще не запущені. */
    public List<String> ids() {
        TreeSet<String> out = new TreeSet<>(scripts.keySet());
        File[] ds = dir.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) if (validId(d.getName()) && new File(d, "main.nx").isFile()) out.add(d.getName());
        return new ArrayList<>(out);
    }

    public Info info(String id) { Script s = scripts.get(id); return s == null ? null : s.info; }

    /** Останні рядки виводу й помилок (живого процесу або останнього запуску). */
    public List<String> console(String id) {
        Script s = scripts.get(id);
        if (s != null && s.proc != null) return s.proc.console();
        return lastConsole.getOrDefault(id, List.of());
    }

    public File mainFile(String id) { return new File(new File(dir, id), "main.nx"); }

    /** Програма з блоків (редактор блоків); якщо файла нема - скрипт пишеться кодом. */
    public File blocksFile(String id) { return new File(new File(dir, id), "blocks.json"); }

    /** Зберегти програму з блоків і згенерований з неї код (спершу blocks.json - перезапуск іде від зміни main.nx). */
    public void saveBlocks(String id, String blocksJson, String code) throws IOException {
        if (!validId(id)) throw new IOException("назва скрипта - малі латинські літери, цифри, _ (до 32)");
        write(blocksFile(id), blocksJson);
        save(id, code);
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
        voxelin.core.Files2.replace(tmp, f);
    }

    /** Записати main.nx (атомарно) і одразу перезапустити скрипт (не чекаючи перевірки раз на секунду). */
    public void save(String id, String text) throws IOException {
        if (!validId(id)) throw new IOException("назва скрипта - малі латинські літери, цифри, _ (до 32)");
        write(mainFile(id), text);
        Script s = scripts.get(id);
        if (world == null) return;
        if (s == null) rescan();
        else start(s);
    }

    /** Зупинити скрипт (як /script stop). */
    public void stop(String id) { Script s = scripts.get(id); if (s != null) stop(s, State.STOPPED, "зупинено у вікні коду"); }

    /** Шаблон нового скрипта. */
    public static String template(String id) {
        return "import \"../voxelin.nx\"\n\n"
                + "// /" + id + " - приклад команди; збережи (Ctrl+S) - і скрипт перезапуститься\n"
                + "func on_" + id + "(ev) {\n"
                + "    say(\"Привіт із скрипта " + id + "!\")\n"
                + "}\n\n"
                + "func main() {\n"
                + "    command(\"" + id + "\", \"\", on_" + id + ")\n"
                + "    vxRun()\n"
                + "}\n";
    }

    // ------------------------------------------------------------------ життєвий цикл

    private void onTick(TickEvent e) {
        tick = e.tick;
        if (world != e.world) worldChanged(e.world);
        if (tick % 20 == 0) rescan();
        long deadline = System.nanoTime() + TIME_BUDGET_US * 1000;
        for (Script s : scripts.values()) {
            if (s.proc == null) continue;
            s.blocksThisTick = 0;
            drain(s, deadline);
            if (s.proc == null || !health(s)) continue;
            if (s.subs.contains("tick")) s.proc.send(event("tick", "t", tick));
            if (tick % 20 == 0 && s.subs.contains("second")) s.proc.send(event("second", "t", tick));
            if (tick % 20 == 0) { if (s.pongN == s.pingN) { s.pingTick = tick; s.pingNanos = System.nanoTime(); } s.proc.send(event("ping", "n", ++s.pingN)); }
        }
    }

    private void worldChanged(WorldAccess w) {
        boolean sameWorld = worldName != null && worldName.equals(w.name());
        world = w;
        if (sameWorld) {
            for (Script s : scripts.values()) if (s.proc != null && s.subs.contains("world")) s.proc.send(worldEvent("world"));
            return;
        }
        worldName = w.name();
        stopAll();
        scripts.clear();
        rescan();
    }

    /** Знайти теки скриптів; нові запустити, змінені перезапустити, зниклі зупинити. */
    private void rescan() {
        if (world == null) return;
        Set<String> seen = new HashSet<>();
        File[] ds = dir.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) {
            String id = d.getName().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]{1,32}") || !new File(d, "main.nx").isFile()) continue;
            seen.add(id);
            Script s = scripts.get(id);
            if (s == null) { s = new Script(id, d); scripts.put(id, s); start(s); continue; }
            if (stamp(d) != s.stamp) { notify("Скрипт «" + id + "» змінено - перезапуск"); start(s); } // і впалий: виправив і зберіг - запуститься
        }
        for (Iterator<Script> it = scripts.values().iterator(); it.hasNext(); ) {
            Script s = it.next();
            if (!seen.contains(s.id)) { stop(s, State.STOPPED, "теку видалено"); it.remove(); }
        }
    }

    /** Найновіший час зміни .nx у теці скрипта (і спільного помічника не рахуємо - його пише гра). */
    private static long stamp(File d) {
        long t = 0;
        File[] fs = d.listFiles((x, n) -> n.endsWith(".nx"));
        if (fs != null) for (File f : fs) t = Math.max(t, f.lastModified() ^ f.length() * 31);
        return t;
    }

    private void start(Script s) {
        if (s.proc != null) stop(s, State.STOPPED, null);
        s.stamp = stamp(s.dir);
        if (nx == null) {
            s.info.state = State.NO_NX;
            s.info.message = "не знайдено nx (NyxilumLang) - встанови його або вкажи шлях у VOXELIN_NX";
            System.out.println("[Voxelin] ⚠️ скрипт «" + s.id + "»: " + s.info.message);
            return;
        }
        try {
            writeHelper();
            List<String> cmd = new ArrayList<>(nx);
            cmd.add("main.nx");
            Map<String, String> env = new HashMap<>();
            env.put("NX_SANDBOX", "1");
            env.put("NX_EXIT_WITH_PARENT", "1");
            env.put("NX_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
            s.proc = new ScriptProcess(s.id, s.dir, cmd, env);
            s.info.state = State.RUNNING; s.info.message = null;
            s.subs.clear(); s.held = null; s.pingN = s.pongN = 0;
            Map<String, Object> st = worldEvent("start");
            st.put("api", API); st.put("script", s.id);
            s.proc.send(st);
            System.out.println("[Voxelin] скрипт «" + s.id + "» запущено (pid " + s.proc.pid() + ")");
        } catch (IOException e) {
            s.proc = null;
            s.info.state = State.CRASHED; s.info.message = "не вдалось запустити: " + e.getMessage();
            notify("Скрипт «" + s.id + "»: " + s.info.message);
        }
    }

    private void writeHelper() throws IOException {
        File f = new File(dir, ScriptHelper.FILE);
        byte[] want = ScriptHelper.TEXT.getBytes(StandardCharsets.UTF_8);
        if (f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), want)) return;
        Files.write(f.toPath(), want);
    }

    private void stop(Script s, State state, String why) {
        commands.unregisterOwner(s.owner());
        s.commands.clear();
        if (s.proc != null) {
            ScriptProcess p = s.proc;
            s.proc = null;
            p.stop(state == State.STOPPED ? 500 : 0); // кінець вводу: помічник викликає on("stop") і скрипт виходить сам
            lastConsole.put(s.id, p.console());
        }
        s.info.state = state; s.info.message = why;
    }

    /** Зупинити всі скрипти (закриття гри). Скрипт бачить кінець вводу і має пів секунди, щоб зберегти свої дані. */
    public void stopAll() {
        for (Script s : scripts.values()) if (s.proc != null) stop(s, State.STOPPED, null);
    }

    /** false - скрипт зупинено (впав, завис, переповнив пам'ять). */
    private boolean health(Script s) {
        ScriptProcess p = s.proc;
        String why = null; State st = State.KILLED;
        if (p.fault != null) why = p.fault;
        else if (!p.alive()) {
            st = State.CRASHED;
            List<String> c = p.console();
            why = "завершився (код " + p.exitCode() + ")" + (c.isEmpty() ? "" : ": " + c.get(c.size() - 1));
        } else if (p.stuckMillis() > STUCK_MS || s.pongN < s.pingN && tick - s.pingTick > STUCK_TICKS && System.nanoTime() - s.pingNanos > STUCK_MS * 1_000_000L) why = "не відповідає понад " + STUCK_MS / 1000 + " с (нескінченний цикл?)";
        else if (tick % 100 == 0) { long mb = p.memoryMb(); if (mb > MEMORY_MB) why = "забагато пам'яті: " + mb + " МБ (межа " + MEMORY_MB + ")"; }
        if (why == null) return true;
        stop(s, st, why);
        notify("Скрипт «" + s.id + "» зупинено: " + (why.endsWith(".") ? why.substring(0, why.length() - 1) : why) + ". /script log " + s.id + " - його вивід");
        return false;
    }

    // ------------------------------------------------------------------ операції скрипта

    /** Обробити операції скрипта. Якщо щойно надіслали відповідь, коротко чекаємо наступну операцію - так getBlock у циклі
     *  не розтягується на тік на кожен блок. Загальний бюджет часу - спільний для всіх скриптів. */
    private void drain(Script s, long deadline) {
        boolean replied = false;
        for (int n = 0; n < MAX_OPS_PER_TICK && s.proc != null; n++) {
            Map<String, Object> op = s.held;
            if (op != null) s.held = null;
            else op = s.proc.poll();
            if (op == null && replied) {
                long left = (deadline - System.nanoTime()) / 1000;
                if (left <= 0) return;
                try { op = s.proc.poll(Math.min(left, REPLY_WAIT_US)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (op == null) return;
            Boolean r = apply(s, op);
            if (r == null) { s.held = op; return; } // ліміт блоків - продовжимо наступного тіку
            replied = r;
            if (System.nanoTime() > deadline) return;
        }
    }

    /** true - надіслано відповідь, false - ні, null - операцію треба повторити в наступному тіку. */
    private Boolean apply(Script s, Map<String, Object> op) {
        String kind = str(op.get("op"));
        Object rid = op.get("rid");
        try {
            switch (kind) {
                case "ready" -> { return false; }
                case "pong" -> { long n = lng(op.get("n")); if (n > s.pongN && n <= s.pingN) { s.pongN = n; s.pingTick = tick; s.pingNanos = System.nanoTime(); } return false; }
                case "say" -> { notify(ScriptProcess.cut(str(op.get("text")), 200)); return false; }
                case "log" -> { System.out.println("[script:" + s.id + "] " + ScriptProcess.cut(str(op.get("text")), 300)); return false; }
                case "subscribe" -> {
                    String ev = str(op.get("event"));
                    if (EVENTS.contains(ev)) s.subs.add(ev);
                    else error(s, "невідома подія «" + ev + "» (є: tick, second, block, world, stop)");
                    return false;
                }
                case "command" -> { registerCommand(s, str(op.get("name")), str(op.get("usage"))); return false; }
                case "setBlock" -> {
                    if (s.blocksThisTick >= BLOCKS_PER_TICK) return null;
                    BlockRef b = block(str(op.get("block")));
                    boolean ok = false;
                    if (b == null) error(s, "невідомий блок «" + op.get("block") + "»");
                    else { s.blocksThisTick++; ok = world.setBlock(s.source, lng(op.get("x")), (int) lng(op.get("y")), lng(op.get("z")), b); }
                    if (rid == null) return false;
                    Map<String, Object> r = reply(rid); r.put("ok", ok); s.proc.send(r); return true;
                }
                case "getBlock" -> {
                    BlockRef b = world.getBlock(lng(op.get("x")), (int) lng(op.get("y")), lng(op.get("z")));
                    Map<String, Object> r = reply(rid); r.put("block", b == null ? null : b.name()); s.proc.send(r); return true;
                }
                case "run" -> {
                    long[] at = playerBlock();
                    List<String> lines = commands.execute(s.source, world, at, str(op.get("cmd")));
                    if (rid == null) { for (String l : lines) System.out.println("[script:" + s.id + "] " + l); return false; }
                    Map<String, Object> r = reply(rid); r.put("lines", lines); s.proc.send(r); return true;
                }
                case "player" -> { Map<String, Object> r = reply(rid); r.put("player", player()); s.proc.send(r); return true; }
                case "time" -> {
                    Map<String, Object> r = reply(rid);
                    r.put("time", world.time()); r.put("dayTicks", world.dayTicks()); r.put("day", world.dayTicks() <= 0 ? 0 : world.time() / world.dayTicks());
                    s.proc.send(r); return true;
                }
                default -> { error(s, "невідома операція «" + kind + "»"); if (rid != null) { s.proc.send(reply(rid)); return true; } return false; }
            }
        } catch (RuntimeException e) {
            error(s, "операція «" + kind + "»: " + e.getMessage());
            if (rid != null && s.proc != null) { s.proc.send(reply(rid)); return true; }
            return false;
        }
    }

    private void registerCommand(Script s, String name, String usage) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        if (s.commands.contains(n)) return;
        try {
            commands.register(s.owner(), n, usage, 0, ctx -> {
                if (s.proc == null) throw new Commands.CommandException("Скрипт «" + s.id + "» не працює");
                Map<String, Object> ev = event("command", "name", n);
                ev.put("args", ctx.args());
                long[] o = ctx.origin();
                ev.put("x", o[0]); ev.put("y", o[1]); ev.put("z", o[2]);
                ev.put("by", ctx.source().name());
                s.proc.send(ev);
            });
            s.commands.add(n);
        } catch (IllegalArgumentException e) { error(s, e.getMessage()); }
    }

    private void onBlock(BlockChangeEvent e) {
        if (e.source.kind() != Source.Kind.PLAYER) return; // зміни самих скриптів, модів і генерації не шлемо - інакше лавина
        for (Script s : scripts.values()) if (s.proc != null && s.subs.contains("block")) {
            Map<String, Object> ev = event("block", "x", e.x);
            ev.put("y", e.y); ev.put("z", e.z);
            ev.put("old", e.oldBlock == null ? null : e.oldBlock.name());
            ev.put("new", e.newBlock == null ? null : e.newBlock.name());
            ev.put("by", e.source.name());
            s.proc.send(ev);
        }
    }

    // ------------------------------------------------------------------ /script

    private void command(Commands.Context ctx) throws Commands.CommandException {
        List<String> a = ctx.args();
        String what = a.isEmpty() ? "list" : a.get(0);
        switch (what) {
            case "list" -> {
                if (scripts.isEmpty()) ctx.reply("Скриптів нема. Тека: " + dir.getAbsolutePath() + " (scripts/<назва>/main.nx)");
                for (Script s : scripts.values()) ctx.reply(s.info.toString());
                if (nx == null) ctx.reply("nx не знайдено - скрипти не запускаються");
            }
            case "reload" -> {
                if (a.size() < 2) throw new Commands.CommandException("Котрий скрипт?");
                if (a.get(1).equals("all")) { for (Script s : scripts.values()) start(s); rescan(); ctx.reply("Перезапущено всі скрипти"); return; }
                Script s = need(a.get(1));
                start(s);
                ctx.reply("Скрипт «" + s.id + "»: " + s.info.state);
            }
            case "stop" -> {
                if (a.size() < 2) throw new Commands.CommandException("Котрий скрипт?");
                Script s = need(a.get(1));
                stop(s, State.STOPPED, "зупинено командою");
                ctx.reply("Скрипт «" + s.id + "» зупинено. /script reload " + s.id + " - запустити знову");
            }
            case "log" -> {
                if (a.size() < 2) throw new Commands.CommandException("Котрий скрипт?");
                Script s = need(a.get(1));
                List<String> c = s.proc != null ? s.proc.console() : lastConsole.getOrDefault(s.id, List.of());
                if (c.isEmpty()) ctx.reply("(вивід порожній)");
                for (String l : c.subList(Math.max(0, c.size() - 10), c.size())) ctx.reply(l);
            }
            default -> throw new Commands.CommandException("Невідома дія: " + what);
        }
    }

    private final Map<String, List<String>> lastConsole = new HashMap<>();

    private Script need(String id) throws Commands.CommandException {
        Script s = scripts.get(id.toLowerCase(Locale.ROOT));
        if (s == null) throw new Commands.CommandException("Нема скрипта «" + id + "» (/script list)");
        return s;
    }

    // ------------------------------------------------------------------ дрібне

    private void error(Script s, String m) {
        System.out.println("[script:" + s.id + "] ⚠ " + m);
        if (s.proc != null) s.proc.send(event("error", "text", m));
    }

    private void notify(String m) {
        System.out.println("[Voxelin] " + m);
        if (world instanceof World w) w.notifyPlayer(m);
    }

    private BlockRef block(String name) {
        if (name == null || name.isEmpty()) return null;
        return blocks.byName(name.contains(":") ? name : "voxelin:" + name);
    }

    private long[] playerBlock() {
        if (world instanceof World w && w.playerTarget() != null) {
            Pos p = w.playerTarget().body();
            return new long[]{p.blockX(), (long) Math.floor(p.y - 0.9), p.blockZ()};
        }
        return new long[3];
    }

    private Map<String, Object> player() {
        if (!(world instanceof World w) || w.playerTarget() == null) return null;
        PlayerTarget t = w.playerTarget();
        Pos p = t.body();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.relX(0)); m.put("y", p.y - 0.9); m.put("z", p.relZ(0));
        m.put("health", t.health()); m.put("alive", t.alive()); m.put("creative", t.creative());
        m.put("dimension", w.dimension());
        return m;
    }

    private Map<String, Object> worldEvent(String kind) {
        Map<String, Object> m = event(kind);
        m.put("world", world.name()); m.put("seed", Long.toString(world.seed()));
        m.put("dimension", world instanceof World w ? w.dimension() : "overworld");
        return m;
    }

    private static Map<String, Object> event(String kind) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ev", kind); return m; }

    private static Map<String, Object> event(String kind, String k, Object v) { Map<String, Object> m = event(kind); m.put(k, v); return m; }

    private static Map<String, Object> reply(Object rid) { Map<String, Object> m = event("reply"); m.put("rid", rid); return m; }

    private static String str(Object o) { return o == null ? "" : o instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : o.toString(); }

    private static long lng(Object o) {
        if (o instanceof Number n) { double d = n.doubleValue(); if (Double.isNaN(d)) throw new IllegalArgumentException("координата не число"); return (long) Math.floor(d); }
        if (o instanceof String s) { try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { /* нижче */ } }
        throw new IllegalArgumentException("координата не число: " + o);
    }
}
