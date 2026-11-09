package voxelin.server;

import voxelin.core.*;
import voxelin.core.entity.PlayerEntity;
import voxelin.loader.CrashReport;
import voxelin.loader.Game;
import voxelin.net.NetServer;
import voxelin.net.Protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Сервер без вікна: світ тікає 20 разів на секунду, гості заходять через «ГРА ПО МЕРЕЖІ» (адреса + код), як до хоста в паузі,
 * але хоста-гравця нема - ні GL, ні звуку, ні вікна. Для гри через інтернет: запустити на комп'ютері, що не вимикається, і
 * прокинути порт (типово 24555/TCP) на роутері.
 *
 * Налаштування - server.properties у теці запуску (створюється з першим запуском; код запрошення там же, щоб не мінявся між
 * запусками). Прапорці командного рядка мають перевагу; у файл вони потрапляють лише при першому запуску. Консоль (stdin): help, list, say, kick, save, code, stop
 * і будь-яка команда гри з «/» (від імені консолі). Ctrl+C / SIGTERM - збереження й вихід.
 */
public final class DedicatedServer {
    static final String USAGE = """
            Voxelin: сервер без вікна
              ./server.sh [--world НАЗВА] [--seed N] [--flat] [--survival|--creative] [--port N] [--code КОД] [--name ІМ'Я]
                          [--saves ТЕКА] [--mods ТЕКА] [--config server.properties]
            Налаштування - у server.properties (створюється з першим запуском), прапорці - лише на цей запуск.""";

    /** Налаштування сервера: файл + прапорці. */
    public static final class Config {
        public String world = "server", code, name = "Сервер";
        public long seed = new Random().nextLong();
        public boolean flat, survival = true;
        /** Раз на 12 годин дивитись, чи вийшла нова версія (лише повідомлення в консоль; сам сервер не оновлюється). */
        public boolean updateCheck = true;
        /** Звідки брати список версій (тести - свій). */
        public String manifest = voxelin.loader.UpdateCheck.MANIFEST;
        public int port = Protocol.DEFAULT_PORT;
        File saves = new File("saves"), mods = new File("mods"), file = new File("server.properties");
        /** Що з прапорців (на цей запуск, не у файл). */
        final Set<String> explicit = new HashSet<>();

        public static Config parse(String[] a) {
            Config c = new Config();
            Map<String, String> flags = new HashMap<>();
            for (int i = 0; i < a.length; i++) {
                String k = a[i];
                switch (k) {
                    case "--server" -> { }
                    case "--flat" -> flags.put("flat", "true");
                    case "--survival" -> flags.put("mode", "survival");
                    case "--creative" -> flags.put("mode", "creative");
                    case "--no-update-check" -> flags.put("update-check", "false");
                    case "--help", "-h" -> { System.out.println(USAGE); System.exit(0); }
                    case "--world", "--seed", "--port", "--code", "--name", "--saves", "--mods", "--config" -> {
                        if (i + 1 >= a.length) throw new IllegalArgumentException(k + ": бракує значення");
                        flags.put(k.substring(2), a[++i]);
                    }
                    default -> throw new IllegalArgumentException("невідомий параметр " + k + "\n" + USAGE);
                }
            }
            if (flags.containsKey("config")) c.file = new File(flags.remove("config"));
            if (flags.containsKey("saves")) c.saves = new File(flags.remove("saves"));
            if (flags.containsKey("mods")) c.mods = new File(flags.remove("mods"));
            Properties p = new Properties();
            boolean had = c.file.isFile();
            if (had) try (Reader r = new InputStreamReader(new FileInputStream(c.file), StandardCharsets.UTF_8)) { p.load(r); }
                catch (IOException e) { throw new UncheckedIOException("не вдалось прочитати " + c.file, e); }
            for (String key : List.of("world", "seed", "flat", "mode", "port", "code", "name", "update-check")) if (p.containsKey(key)) c.set(key, p.getProperty(key).trim());
            boolean fresh = !had || !p.containsKey("code");
            if (c.code == null) c.code = Protocol.newCode();
            for (Map.Entry<String, String> e : flags.entrySet()) { c.set(e.getKey(), e.getValue()); c.explicit.add(e.getKey()); }
            if (fresh) c.save(); // перший запуск: прапорці стають налаштуваннями (зерно, назва світу); далі прапорці - лише на запуск
            return c;
        }

        void set(String key, String v) {
            switch (key) {
                case "world" -> { if (v.isEmpty() || v.contains("/") || v.contains("\\") || v.startsWith(".")) throw new IllegalArgumentException("world: погана назва світу"); world = v; }
                case "seed" -> { try { seed = Long.parseLong(v); } catch (NumberFormatException e) { seed = v.hashCode(); } }
                case "flat" -> flat = Boolean.parseBoolean(v);
                case "mode" -> { if (!v.equals("survival") && !v.equals("creative")) throw new IllegalArgumentException("mode: survival або creative"); survival = v.equals("survival"); }
                case "port" -> { int n = Integer.parseInt(v); if (n < 0 || n > 65535) throw new IllegalArgumentException("port: 1-65535 (0 - будь-який вільний)"); port = n; }
                case "code" -> { if (!NetServer.validCode(v)) throw new IllegalArgumentException("code: " + Protocol.CODE_LEN + "-16 латинських літер і цифр"); code = v.toUpperCase(Locale.ROOT); }
                case "update-check" -> updateCheck = !v.equalsIgnoreCase("false");
                case "name" -> { String n = Protocol.cleanName(v); if (n == null) throw new IllegalArgumentException("name: 1-16 літер, цифр, _ або -"); name = n; }
                default -> throw new IllegalArgumentException(key);
            }
        }

        void save() {
            String text = "# Voxelin: сервер без вікна. Зміни набувають сили після перезапуску.\n"
                    + "# world - тека світу в saves/ (нема - створиться з seed і flat); mode - survival або creative (режим гостей)\n"
                    + "# code - код запрошення (" + Protocol.CODE_LEN + "-16 латинських літер і цифр): його вводять гості разом з адресою\n"
                    + "# update-check - писати в консоль, коли вийде нова версія Voxelin (сам сервер не оновлюється ніколи)\n"
                    + "world=" + world + "\nseed=" + seed + "\nflat=" + flat + "\nmode=" + (survival ? "survival" : "creative")
                    + "\nport=" + port + "\ncode=" + code + "\nname=" + name + "\nupdate-check=" + updateCheck + "\n";
            try {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null) parent.mkdirs();
                java.nio.file.Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) { System.out.println("[server] ⚠️ не вдалось записати " + file + ": " + e); }
        }
    }

    public final Config cfg;
    public final Game game;
    public final World world;
    public final NetServer net;
    private final long spawnX, spawnZ;
    private volatile Pos spawn;
    private final ConcurrentLinkedQueue<String> console = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private volatile Thread loop;
    private final Object closeLock = new Object();
    private boolean closed;
    /** Скільки тривав останній тік (нс) і найдовший за хвилину - для «status». */
    private volatile long tickNs, worstNs;

    public DedicatedServer(Config cfg) throws IOException {
        this.cfg = cfg;
        game = new Game(cfg.saves, cfg.mods);
        if (!WorldInfo.exists(game.worldDir(cfg.world))) {
            game.createWorld(cfg.world, cfg.seed, cfg.flat ? WorldInfo.Type.FLAT : WorldInfo.Type.NORMAL);
            log("створено світ «" + cfg.world + "» (зерно " + cfg.seed + (cfg.flat ? ", плаский" : "") + ")");
        }
        world = game.openWorld(cfg.world);
        world.asyncPaths = true; // тік сервера не чекає пошуку шляху
        long[] land = world.findLand(0, 0); // не в морі
        spawnX = land[0]; spawnZ = land[1];
        loadSpawn();
        spawn = findSpawn();
        NetServer n;
        try { n = new NetServer(world, cfg.port, cfg.name, "", this::log, cfg.survival, cfg.code, () -> spawn); }
        catch (IOException e) { world.close(); game.close(); throw new IOException("порт " + cfg.port + " зайнятий чи недоступний: " + e.getMessage(), e); }
        n.setContent(game.mods.content()); // гості бачать вміст модів сервера без самих модів
        n.setCommands(game.commands); // гість пише /команду мода - виконується тут, з правами гостя
        game.players.attach(n.players); // моди бачать гостей (сервіс Players)
        net = n;
    }

    /** Чанки біля точки появи - до того, як прийде перший гість (інакше його поставило б у ще не згенерований світ). */
    private void loadSpawn() {
        long cx = Math.floorDiv(spawnX, 16L), cz = Math.floorDiv(spawnZ, 16L);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (world.chunk(cx, cz) == null && System.nanoTime() < until) {
            world.update(cx, cz, SPAWN_RADIUS);
            LockSupport.parkNanos(10_000_000);
        }
        if (world.chunk(cx, cz) == null) log("⚠️ точка появи не завантажилась за 60 с");
    }

    static final int SPAWN_RADIUS = 2;

    private Pos findSpawn() {
        int y = Math.max(1, world.surfaceY(spawnX, spawnZ) + 1);
        while (y < Chunk.HEIGHT - 3 && (world.isSolid(spawnX, y, spawnZ) || world.isSolid(spawnX, y + 1, spawnZ))) y++;
        return Pos.ofBlock(spawnX, y + PlayerEntity.EYE + 0.02, spawnZ);
    }

    /** Куди йдуть повідомлення сервера (тести можуть перехопити). */
    public java.util.function.Consumer<String> out = System.out::println;

    void log(String s) { out.accept("[server] " + s); }

    /** Точка появи гостей. */
    public Pos spawn() { return spawn.copy(); }

    // ------------------------------------------------------------------ тік

    /** Головний цикл (у потоці voxelin-world): 20 тіків на секунду, без наздоганяння пачкою після довгого тіку. */
    public void run() {
        loop = Thread.currentThread();
        final long step = (long) (0.05 * 1e9);
        long next = System.nanoTime(), minute = next;
        int errors = 0;
        while (running) {
            long now = System.nanoTime();
            if (now < next) { LockSupport.parkNanos(next - now); continue; }
            next += step;
            if (now - next > 5 * step) next = now;
            world.lock.lock();
            try {
                long t0 = System.nanoTime();
                step();
                tickNs = System.nanoTime() - t0;
                worstNs = Math.max(worstNs, tickNs);
                errors = 0;
            } catch (Throwable e) {
                if (errors++ < 3) { log("⚠️ тік: " + e); e.printStackTrace(System.out); }
                // нестача пам'яті тощо або тік ламається безперервно (~10 с) - зупинитись чесно: звіт, гостям - чому
                if (e instanceof VirtualMachineError || errors > 200) fatal(e);
            } finally { world.lock.unlock(); }
            if (now - minute > 60_000_000_000L) { minute = now; worstNs = 0; }
        }
        shutdown();
    }

    private long ticks;

    private void step() {
        for (String line; (line = console.poll()) != null; ) command(line);
        world.update(Math.floorDiv(spawnX, 16L), Math.floorDiv(spawnZ, 16L), SPAWN_RADIUS); // точка появи - завжди завантажена; гості - через setExtraCenters
        world.tick();
        net.tick();
        if (++ticks % 100 == 0) spawn = findSpawn(); // на точці появи могли щось збудувати
    }

    // ------------------------------------------------------------------ консоль

    void command(String raw) {
        String line = raw.trim();
        if (line.isEmpty()) return;
        String word = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT), rest = line.contains(" ") ? line.substring(line.indexOf(' ') + 1).trim() : "";
        switch (word) {
            case "help", "?" -> log("""
                    команди: list - хто в грі · say ТЕКСТ (або просто текст) - у чат · kick ІМ'Я [ЧОМУ] · save - зберегти світ · code - код і адреси
                             status - тік і чанки · update - чи є нова версія · stop - зберегти й вийти · /КОМАНДА - команда гри від імені консолі (/time, /weather, /gamerule ...)""");
            case "list" -> {
                List<NetServer.Guest> gs = net.guests();
                if (gs.isEmpty()) log("у грі нікого (до " + Protocol.MAX_GUESTS + " гостей)");
                else log("у грі " + gs.size() + "/" + Protocol.MAX_GUESTS + ": " + String.join(", ", gs.stream().map(g -> g.name + " (" + g.pos.blockX() + " " + (int) g.pos.y + " " + g.pos.blockZ() + ")").toList()));
            }
            case "say" -> { if (!rest.isEmpty()) net.say(rest); }
            case "kick" -> {
                if (rest.isEmpty()) { log("kick ІМ'Я [ЧОМУ]"); return; }
                String[] p = rest.split("\\s+", 2);
                if (!net.kick(p[0], p.length > 1 ? p[1] : null)) log("гостя " + p[0] + " нема");
            }
            case "save" -> { world.autosave(); log("світ збережено"); }
            case "code" -> printInvite();
            case "status" -> log(String.format(Locale.ROOT, "тік %.2f мс (найдовший за хвилину %.1f), чанків %d, істот %d, гостей %d, день %d",
                    tickNs / 1e6, worstNs / 1e6, world.chunkCount(), world.mobs().size(), net.guests().size(), world.dayNumber()));
            case "update" -> checkUpdates(true);
            case "stop", "exit", "quit" -> { log("зупинка..."); running = false; }
            default -> {
                if (line.startsWith("/")) {
                    long[] at = {spawnX, (long) spawn.y, spawnZ};
                    for (String out : game.commands.execute(Sources.console(), world, at, line)) log(out);
                } else net.say(line);
            }
        }
    }

    void printInvite() {
        log("код запрошення: " + net.code + " · порт " + net.port + " · режим гостей: " + (cfg.survival ? "виживання" : "творчий"));
        List<String> a = NetServer.lanAddresses();
        if (!a.isEmpty()) log("у локальній мережі: " + String.join(", ", a.stream().map(s -> s + (net.port == Protocol.DEFAULT_PORT ? "" : ":" + net.port)).toList()));
        log("через інтернет: прокинь порт " + net.port + "/TCP на роутері й дай друзям свою зовнішню адресу");
    }

    private void readConsole() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String l; running && (l = r.readLine()) != null; ) console.add(l);
        } catch (IOException ignored) { }
        // кінець stdin (запуск у фоні, </dev/null) - сервер працює далі, зупинка - сигналом
    }

    /** Про яку версію вже сказали (щоб не повторювати кожні 12 годин). */
    private volatile String announced;

    /** Перевірити оновлення у фоні; asked - з консолі (тоді «оновлень нема» теж кажемо). */
    void checkUpdates(boolean asked) {
        Thread t = new Thread(() -> {
            voxelin.loader.UpdateCheck.Update u = voxelin.loader.UpdateCheck.check(cfg.manifest, GameVersion.VERSION, Protocol.VERSION, voxelin.api.VoxelinApi.VERSION, true);
            if (u == null) { if (asked) log("оновлень нема (або не вдалось перевірити) - зараз " + GameVersion.VERSION); return; }
            if (!asked && u.version().equals(announced)) return;
            announced = u.version();
            for (String l : u.lines()) log("🔔 " + l);
        }, "voxelin-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** Чому зупинились (для гостей) і з яким кодом вийти; null - звичайна зупинка. */
    private volatile String stopReason;
    volatile int exitCode;

    private void fatal(Throwable e) {
        CrashReport.Explanation x = CrashReport.explain(e, game.mods);
        CrashReport.report(new File("crash-reports"), "сервер, світ «" + cfg.world + "»", e, game.mods, Map.of("Гостей", String.valueOf(net.guests().size())), this::log);
        stopReason = "сервер аварійно зупинився: " + x.title() + ". Спробуй зайти пізніше.";
        exitCode = 1;
        running = false;
    }

    /** Зберегти все й закрити (один раз; з циклу або з обробника Ctrl+C). */
    void shutdown() {
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
        }
        world.lock.lock();
        try {
            if (stopReason != null) net.close(stopReason); else net.close();
            world.close();
        } finally { world.lock.unlock(); }
        game.close();
        log("світ збережено, сервер зупинено");
    }

    /** Зупинити ззовні (тести): цикл закінчить тік, збереже світ і вийде. */
    public void stop() {
        running = false;
        Thread t = loop;
        if (t != null) { LockSupport.unpark(t); try { t.join(20_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    /** Додати рядок консолі (тести). */
    public void enqueue(String line) { console.add(line); }

    public static void main(String[] args) {
        Config cfg;
        try { cfg = Config.parse(args); }
        catch (RuntimeException e) { System.out.println("[server] ⚠️ " + e.getMessage()); System.exit(2); return; }
        System.setProperty("java.awt.headless", "true");
        DedicatedServer s;
        try { s = new DedicatedServer(cfg); }
        catch (IOException e) { System.out.println("[server] ⚠️ не вдалось запустити: " + e.getMessage()); System.exit(1); return; }
        catch (Throwable e) { CrashReport.report(new File("crash-reports"), "запуск сервера", e, null, null, System.out::println); System.exit(1); return; }
        s.log("Voxelin " + voxelin.client.Voxelin.VERSION + " · світ «" + cfg.world + "» · модів " + s.game.mods.mods().size() + (s.net.contentCount() > 0 ? " (їхній вміст гості отримують від сервера: записів " + s.net.contentCount() + ")" : ""));
        s.printInvite();
        if (s.game.lastBackup != null) s.log("світ збережено іншою версією гри - перед відкриттям зроблено копію: " + s.game.lastBackup);
        if (cfg.updateCheck) {
            Thread uc = new Thread(() -> { while (s.running) { s.checkUpdates(false); try { Thread.sleep(12L * 3600 * 1000); } catch (InterruptedException e) { return; } } }, "voxelin-update-timer");
            uc.setDaemon(true);
            uc.start();
        }
        s.log("консоль: help - список команд");
        Thread worldThread = new Thread(s::run, "voxelin-world");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (s.running) { s.log("сигнал - зупинка..."); s.stop(); } }, "voxelin-stop"));
        Thread in = new Thread(s::readConsole, "voxelin-console");
        in.setDaemon(true);
        in.start();
        worldThread.start();
        try { worldThread.join(); } catch (InterruptedException ignored) { }
        System.exit(s.exitCode);
    }
}
