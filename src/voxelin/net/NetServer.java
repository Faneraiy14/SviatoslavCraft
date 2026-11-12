package voxelin.net;

import voxelin.api.Source;
import voxelin.core.*;
import voxelin.core.entity.Mob;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static voxelin.net.Protocol.*;

/**
 * Хост мережевої гри: відкритий для мережі світ гравця. Працює на головному потоці гри (tick() щотіку), сокети - у своїх потоках.
 * Гості у творчому режимі чи у виживанні (обирає хост): бачать світ, гравців, істот, час доби; ламають і ставлять блоки, пишуть у чат.
 * У виживанні дроп зламаного блока хост надсилає гостю в інвентар (S_GIVE); інвентар, здоров'я й голод гостя живуть у гостя (крім часу, поки
 * відкрита скриня чи піч - тоді слоти веде хост, див. Session). Хост перевіряє
 * кожен запит: код запрошення, ім'я, відстань до блока, частоту дій, стрибки позиції, розмір кадрів. Гості не виконують команд, їх не
 * атакують істоти, вони не ходять у інші виміри.
 */
public final class NetServer implements AutoCloseable {
    /** Гість на хості. */
    public static final class Guest {
        public final int id;
        public final String name, look;
        final Conn conn;
        final Source src;
        public final Pos pos, spawn;
        public float yaw, pitch;
        final Set<ChunkPos> sent = new HashSet<>();
        final ArrayDeque<ChunkPos> wants = new ArrayDeque<>();
        int actions = ACTION_BURST, chats = 3;
        /** Стан, про який звітує гість (виживання): здоров'я й смерть. */
        volatile double health = 20;
        volatile boolean dead;
        long lastInvSave;
        /** Ціль для істот і стріл (лише у виживанні): удар іде гостю кадром S_HURT, броню й поштовх рахує він. */
        PlayerTarget target;
        /** Відкрита скриня чи піч (одна за раз). */
        Session session;
        /** Останній інвентар від гостя (C_INV) - записується при виході, навіть якщо щойно було збереження. */
        String[] invLines;
        /** Чанки, що кодуються у фоні: зміни блоків у них за цей час (досилаються одразу після чанка). */
        final Map<ChunkPos, List<long[]>> encoding = new HashMap<>();
        Guest(int id, String name, String look, Conn conn, Pos at) {
            this.id = id; this.name = name; this.look = look; this.conn = conn; this.pos = at.copy(); this.spawn = at.copy();
            src = Sources.of(name, Source.Kind.PLAYER, 0, 16);
        }
    }

    /**
     * Скриня чи піч, відкрита гостем. Кліки рахує хост: меню над справжньою блок-сутністю й копією інвентаря гостя (36 слотів),
     * яку гість прислав при відкритті. Поки вікно відкрите, ці слоти в гостя міняє лише хост (S_MENU несе весь стан); при закритті -
     * S_MENU_END з остаточним інвентарем. Так речі не дублюються й не губляться, навіть коли скриню водночас відкрили кілька гравців.
     */
    static final class Session {
        final long x, z; final int y;
        final BlockEntity entity;
        final Inventory inv;
        final Menu menu;
        int seq;
        String last;
        Session(BlockEntity e, Inventory inv, Menu menu) { x = e.x; y = e.y; z = e.z; entity = e; this.inv = inv; this.menu = menu; }
        /** Те, що бачить гість (для «чи змінилось»). */
        String state() { return entity.encode() + (entity instanceof FurnaceEntity f ? "," + f.cookTotal : "") + "#" + String.join(";", Arrays.stream(inv.encode()).map(v -> v == null ? "-" : v).toList()) + "#" + stack(menu.cursor()); }
    }

    static String stack(Inventory.Stack s) { return s == null ? "" : s.item + "|" + s.count + "|" + s.damage; }

    private PlayerTarget targetOf(Guest g) {
        return new PlayerTarget() {
            public Pos body() { Pos c = g.pos.copy(); c.y = g.pos.y - voxelin.core.entity.PlayerEntity.EYE + voxelin.core.entity.PlayerEntity.HEIGHT / 2; return c; }
            public boolean alive() { return !g.dead && !g.conn.closed(); }
            public boolean creative() { return !survival; } // творчі гості: істоти хоста їх не чіпають
            public double health() { return g.health; }
            public void notify(String message) { g.conn.send(chatFrame(message)); }
            public void hurt(double damage, double kx, double kz) { hurt(damage, kx, kz, 0); }
            public void hurt(double damage, double kx, double kz, double pierce) {
                if (!Double.isFinite(damage) || damage <= 0) return;
                g.conn.send(frame(S_HURT, o -> { o.writeDouble(damage); o.writeDouble(kx); o.writeDouble(kz); o.writeDouble(pierce); }));
            }
            public void teleport(long x, int y, long z) {
                Pos p = Pos.ofBlock(x, y + voxelin.core.entity.PlayerEntity.EYE + 0.02, z);
                g.pos.cx = p.cx; g.pos.cz = p.cz; g.pos.x = p.x; g.pos.y = p.y; g.pos.z = p.z;
                g.conn.send(frame(S_TELEPORT, o -> writePos(o, p)));
            }
        };
    }

    // ------------------------------------------------------------------ інвентарі гостей (виживання): у теці світу хоста, guests/<ім'я>.inv

    /** Тека світу (для вимірів - головного світу: інвентар гостя один на всі виміри). */
    private File guestsDir() {
        File d = world.dir, p = d.getParentFile();
        if (p != null && p.getName().equals("dimensions") && p.getParentFile() != null) d = p.getParentFile();
        return new File(d, "guests");
    }

    private File invFile(String name) { return new File(guestsDir(), name.toLowerCase(Locale.ROOT) + ".inv"); }

    /** Збережений інвентар гостя: SIZE рядків слотів, 4 броні, вибраний слот; null - нема. */
    String[] loadInv(String name) {
        File f = invFile(name);
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try {
            List<String> l = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            return l.size() == Inventory.SIZE + 5 ? l.toArray(new String[0]) : null;
        } catch (IOException e) { return null; }
    }

    private void saveInv(String name, String[] lines) {
        try {
            File d = guestsDir();
            d.mkdirs();
            File f = invFile(name), tmp = new File(d, f.getName() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(), String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files2.replace(tmp, f);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ інвентар гостя " + name + ": " + e); }
    }

    /** Дій (зламати/поставити) за раз; відновлюється по одній за тік - ~20/с. */
    static final int ACTION_BURST = 20, CHUNKS_PER_TICK = 8, MAX_WANTS = 64;
    /** Відстань від очей гостя до блока, який він міняє (з запасом на затримку). */
    static final double REACH = Raycast.MAX_REACH + 2.5;
    /** Найбільший стрибок позиції за одне повідомлення (блоків); більше - телепорт назад. */
    static final double MAX_STEP = 24, SPAWN_AHEAD = 2.5;

    private final World world;
    private final ServerSocket server;
    /** Кодування чанків для гостей - у фоні (тік світу не чекає на стиснення). */
    private final java.util.concurrent.ExecutorService encoder = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "voxelin-net-encode"); t.setDaemon(true); return t; });
    private record Encoded(Guest g, ChunkPos k, byte[] data) {}
    private final ConcurrentLinkedQueue<Encoded> encoded = new ConcurrentLinkedQueue<>();
    public final String code;
    public final int port;
    public final String hostName;
    /** Режим гостей: true - виживання, false - творчий. */
    public final boolean survival;
    private final Random rng = new Random();
    public volatile String hostLook;
    private final Consumer<String> chatOut;
    private final ConcurrentLinkedQueue<Socket> accepted = new ConcurrentLinkedQueue<>();
    private final List<Conn> handshakes = new ArrayList<>();
    private final Map<Conn, Long> handshakeSince = new IdentityHashMap<>();
    private final List<Guest> guests = new ArrayList<>();
    private final Map<Mob, Integer> mobIds = new IdentityHashMap<>();
    /** Хибні коди з адреси: {скільки, коли останній}. Після WRONG_LIMIT - відмова на LOCK_MS (код не підібрати перебором). */
    private final Map<InetAddress, long[]> wrong = new HashMap<>();
    static final int WRONG_LIMIT = 5;
    static final long LOCK_MS = 60_000;
    private final World.BlockWatcher watcher = this::blockChanged;
    private int nextId = 1, nextMob = 1, rr;
    private long tick;
    private volatile boolean closed;
    private final Pos hostPos = new Pos();
    private float hostYaw, hostPitch;
    /** Сервер без вікна: точка появи гостей (хоста-гравця нема). null - звичайний хост. */
    private final java.util.function.Supplier<Pos> spawnPoint;

    /** Чи це сервер без вікна (без хоста-гравця). */
    public boolean dedicated() { return spawnPoint != null; }

    /** Свій код: CODE_LEN..16 латинських літер і цифр. */
    public static boolean validCode(String c) {
        if (c == null || c.length() < CODE_LEN || c.length() > 16) return false;
        for (char ch : c.toCharArray()) if (!(ch < 128 && Character.isLetterOrDigit(ch))) return false;
        return true;
    }

    /** Сервер без вікна: тік без хоста-гравця. */
    public void tick() { if (!dedicated()) throw new IllegalStateException("лише для сервера без вікна"); tick(hostPos, 0, 0); }

    /** Від'єднати гостя на прохання хоста (консоль сервера). */
    public boolean kick(String name, String why) {
        for (Guest g : new ArrayList<>(guests)) if (g.name.equalsIgnoreCase(name)) { drop(g, why == null || why.isBlank() ? "від'єднав хост" : why); return true; }
        return false;
    }

    /** Відкрити світ для мережі на порту (0 - будь-який вільний). chatOut - що показати в чаті хоста. */
    /** Вміст модів, який отримує кожен гість протоколу 5+ (ModLoader.content(); задає власник одразу після створення). */
    private volatile List<byte[]> contentFrames = List.of();
    private volatile int contentCount;
    public void setContent(List<voxelin.loader.ModLoader.ContentOp> ops) { contentFrames = ContentCodec.frames(ops); contentCount = ops.size(); }
    public int contentCount() { return contentCount; }

    public NetServer(World world, int port, String hostName, String hostLook, Consumer<String> chatOut) throws IOException { this(world, port, hostName, hostLook, chatOut, false); }

    public NetServer(World world, int port, String hostName, String hostLook, Consumer<String> chatOut, boolean survival) throws IOException { this(world, port, hostName, hostLook, chatOut, survival, null, null); }

    /**
     * fixedCode - свій код запрошення (сервер без вікна: код не міняється між запусками; null - випадковий). spawnPoint - не null для сервера
     * без вікна: хоста-гравця нема (його не видно гостям, він не займає місця), гості з'являються в цій точці.
     */
    public NetServer(World world, int port, String hostName, String hostLook, Consumer<String> chatOut, boolean survival, String fixedCode, java.util.function.Supplier<Pos> spawnPoint) throws IOException {
        this.survival = survival;
        this.spawnPoint = spawnPoint;
        if (fixedCode != null && !validCode(fixedCode)) throw new IllegalArgumentException("код запрошення: " + CODE_LEN + "-16 літер і цифр");
        if (world.remote()) throw new IllegalStateException("дзеркало чужого світу не можна відкрити для мережі");
        this.world = world; this.hostName = hostName; this.hostLook = hostLook; this.chatOut = chatOut;
        code = fixedCode != null ? fixedCode.toUpperCase(Locale.ROOT) : Protocol.newCode();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        this.port = server.getLocalPort();
        world.addWatcher(watcher);
        Thread t = new Thread(this::acceptLoop, "voxelin-net-accept");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                if (accepted.size() > 16) { s.close(); continue; } // хтось засипає з'єднаннями
                accepted.add(s);
            } catch (IOException e) { if (closed) return; }
        }
    }

    public List<Guest> guests() { return Collections.unmodifiableList(guests); }

    /** Команди гри: гість пише «/...» - виконується від його імені з правами гостя (лише команди для всіх; задає власник). null - команд нема. */
    private volatile CommandRegistry commands;
    public void setCommands(CommandRegistry c) { commands = c; }

    private Guest guest(String name) { for (Guest g : guests) if (g.name.equalsIgnoreCase(name)) return g; return null; }

    /** Гості для сервісу Players модів (Game.players.attach). */
    public final voxelin.api.Players players = new voxelin.api.Players() {
        public List<String> online() { List<String> out = new ArrayList<>(); for (Guest g : guests) out.add(g.name); return out; }
        public boolean isOnline(String name) { return name != null && guest(name) != null; }
        public long[] position(String name) {
            Guest g = guest(name);
            return g == null ? null : new long[]{g.pos.blockX(), (int) Math.floor(g.pos.y - voxelin.core.entity.PlayerEntity.EYE + 0.05), g.pos.blockZ()};
        }
        public boolean teleport(String name, long x, int y, long z) {
            Guest g = guest(name);
            if (g == null || y < 0 || y > world.height() + 64) return false;
            Pos to = Pos.ofBlock(x, y + voxelin.core.entity.PlayerEntity.EYE, z);
            if (!World.inRange(to.cx, to.cz)) return false;
            g.pos.cx = to.cx; g.pos.cz = to.cz; g.pos.x = to.x; g.pos.y = to.y; g.pos.z = to.z;
            g.conn.send(frame(S_TELEPORT, o -> writePos(o, to)));
            return true;
        }
        public boolean message(String name, String text) { Guest g = guest(name); if (g == null) return false; g.conn.send(chatFrame(cleanChat(text))); return true; }
        public boolean give(String name, String item, int count) { Guest g = guest(name); if (g == null || world.itemTypes.get(item) == null) return false; NetServer.this.give(g, item, count, 0); return true; }
        public boolean kick(String name, String reason) { return guest(name) != null && NetServer.this.kick(name, reason); }
    };

    /** Адреси цього комп'ютера в локальній мережі (для підказки хосту). */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
            }
        } catch (SocketException ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ тік (головний потік)

    /** Щотіку: нові з'єднання, запити гостей, чанки, розсилка гравців/істот/часу. host* - де зараз хост. */
    public void tick(Pos host, float yaw, float pitch) {
        if (closed) return;
        tick++;
        hostPos.cx = host.cx; hostPos.cz = host.cz; hostPos.x = host.x; hostPos.y = host.y; hostPos.z = host.z;
        hostYaw = yaw; hostPitch = pitch;
        for (Socket s; (s = accepted.poll()) != null; ) {
            if (handshakes.size() >= 16) { try { s.close(); } catch (IOException ignored) {} continue; } // не більше 16 незнайомців водночас
            try { Conn c = new Conn(s).start("guest"); handshakes.add(c); handshakeSince.put(c, System.currentTimeMillis()); }
            catch (IOException e) { try { s.close(); } catch (IOException ignored) {} }
        }
        handshakes();
        for (Guest g : new ArrayList<>(guests)) {
            if (g.actions < ACTION_BURST) g.actions++;
            if (tick % 40 == 0 && g.chats < 3) g.chats++;
            for (int i = 0; i < 200; i++) {
                byte[] f = g.conn.poll();
                if (f == null) break;
                try { handle(g, f); } catch (IOException | RuntimeException e) { drop(g, "поганий запит: " + e.getMessage()); break; }
            }
            if (!g.conn.closed() && System.currentTimeMillis() - g.conn.lastRecv > TIMEOUT_MS) drop(g, "не відповідає");
            if (g.conn.closed()) drop(g, g.conn.error());
        }
        for (Guest g : guests) if (g.session != null) sessionTick(g);
        deliverEncoded();
        sendChunks();
        if (tick % 2 == 0) sendPlayers();
        if (tick % 4 == 0) sendMobs();
        if (tick % 20 == 0) broadcast(timeFrame());
        List<long[]> centers = new ArrayList<>();
        for (Guest g : guests) centers.add(new long[]{g.pos.cx, g.pos.cz, MAX_VIEW + 1}); // +1 - запас на рух між оновленнями
        world.setExtraCenters(centers);
    }

    private void handshakes() {
        for (Iterator<Conn> it = handshakes.iterator(); it.hasNext(); ) {
            Conn c = it.next();
            byte[] f = c.poll();
            if (f == null) {
                if (c.closed() || System.currentTimeMillis() - handshakeSince.get(c) > HANDSHAKE_MS) { c.close(); it.remove(); handshakeSince.remove(c); }
                continue;
            }
            it.remove(); handshakeSince.remove(c);
            String why;
            try { why = hello(c, f); } catch (IOException | RuntimeException e) { why = "поганий запит"; }
            if (why != null) { final String w = why; c.send(frame(S_REJECT, o -> o.writeUTF(w))); c.closeAfterSend(); }
        }
    }

    /** Привітання гостя; повертає причину відмови або null (гостя прийнято). */
    private String hello(Conn c, byte[] f) throws IOException {
        if (f[0] != C_HELLO) return "спершу привітання";
        DataInputStream in = body(f);
        int ver = in.readInt();
        String code = str(in, 16), name = cleanName(str(in, 64)), look = str(in, 400);
        if (ver < MIN_VERSION || ver > VERSION) return "інша версія гри (протокол " + ver + ", у хоста " + VERSION + ")";
        long[] w = wrong.get(c.address());
        long now = System.currentTimeMillis();
        if (w != null && now - w[1] > LOCK_MS) { wrong.remove(c.address()); w = null; }
        if (w != null && w[0] >= WRONG_LIMIT) return "забагато спроб - зачекай хвилину";
        if (!this.code.equalsIgnoreCase(code.trim())) {
            if (wrong.size() > 1000) wrong.clear();
            long[] e = wrong.computeIfAbsent(c.address(), k -> new long[2]);
            e[0]++; e[1] = now;
            return "неправильний код запрошення";
        }
        if (name == null) return "ім'я: 1-16 літер, цифр, _ або -";
        if (name.equalsIgnoreCase(hostName) || guests.stream().anyMatch(g -> g.name.equalsIgnoreCase(name))) return "ім'я " + name + " вже зайняте";
        if (guests.size() >= MAX_GUESTS) return "світ повний (" + MAX_GUESTS + " гостей)";
        Pos spawn;
        if (spawnPoint != null) spawn = spawnPoint.get().copy(); // сервер без вікна: на точці появи світу
        else {
            double[] d = Raycast.dir(hostYaw, 0); // гість з'являється за 2.5 блока перед хостом, обличчям до нього
            spawn = hostPos.copy();
            spawn.add(d[0] * SPAWN_AHEAD, 0, d[2] * SPAWN_AHEAD);
            spawn.normalize();
        }
        Guest g = new Guest(nextId++, name, look, c, spawn);
        guests.add(g);
        c.send(frame(S_WELCOME, o -> {
            o.writeInt(g.id); o.writeUTF(hostName); o.writeUTF(world.info.name); o.writeLong(world.seed()); o.writeUTF(world.info.type.name());
            List<String> pal = new ArrayList<>(world.info.palette);
            o.writeInt(pal.size());
            for (String p : pal) o.writeUTF(p);
            writePos(o, spawn); o.writeFloat((hostYaw + 180) % 360); o.writeFloat(0);
            o.writeInt(MAX_VIEW);
            o.writeBoolean(survival);
            o.writeBoolean(dedicated()); // з 1.2.1, у кінці: старі клієнти його просто не читають
            if (ver >= 5) o.writeInt(contentFrames.size()); // протокол 5: скільки кадрів вмісту модів іде далі
        }));
        if (ver >= 5) { for (byte[] cf : contentFrames) c.send(cf); c.send(frame(S_CONTENT_END, null)); }
        c.send(timeFrame());
        Pos back = dedicated() ? loadPos(name) : null; // сервер без вікна: повернути туди, де гість вийшов (відродження - як і раніше на точці появи)
        if (back != null) { g.pos.cx = back.cx; g.pos.cz = back.cz; g.pos.x = back.x; g.pos.y = back.y; g.pos.z = back.z; c.send(frame(S_TELEPORT, o -> writePos(o, back))); }
        if (survival) {
            String[] inv = loadInv(name);
            if (inv != null) c.send(frame(S_INV, o -> { o.writeInt(inv.length); for (String l : inv) o.writeUTF(l.length() > 200 ? "" : l); }));
            g.invLines = inv; // що в гостя зараз (якщо зникне без C_INV, з цим і злиємо вікно скрині)
            g.target = targetOf(g);
            world.addTarget(g.target);
        }
        announce(name + " приєднався до гри.");
        world.events().post(new voxelin.api.event.PlayerJoinEvent(world, name, true));
        return null;
    }

    private byte[] timeFrame() { return frame(S_TIME, o -> { o.writeLong(world.info.time); o.writeLong(world.info.dayShift); o.writeDouble(world.info.dayMinutes); }); }

    static void writePos(DataOutputStream o, Pos p) throws IOException { o.writeLong(p.cx); o.writeLong(p.cz); o.writeDouble(p.x); o.writeDouble(p.y); o.writeDouble(p.z); }

    /** Позиція з мережі: скінченні числа, чанк у межах світу; null - погана. */
    static Pos readPos(DataInputStream in) throws IOException {
        long cx = in.readLong(), cz = in.readLong();
        double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 64 || Math.abs(z) > 64 || y < -128 || y > 1024) return null;
        Pos p = new Pos(cx, cz, x, y, z);
        return World.inRange(p.cx, p.cz) ? p : null;
    }

    private void handle(Guest g, byte[] f) throws IOException {
        DataInputStream in = body(f);
        switch (f[0]) {
            case C_POS -> {
                Pos p = readPos(in);
                float yaw = in.readFloat(), pitch = in.readFloat();
                if (p == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
                if (distance(p, g.pos) > MAX_STEP && distance(p, g.spawn) > 3) { g.conn.send(frame(S_TELEPORT, o -> writePos(o, g.pos))); return; } // стрибок (крім відродження в точці появи) - назад
                g.pos.cx = p.cx; g.pos.cz = p.cz; g.pos.x = p.x; g.pos.y = p.y; g.pos.z = p.z;
                g.yaw = ((yaw % 360) + 360) % 360; g.pitch = Math.max(-90, Math.min(90, pitch));
            }
            case C_CHUNK -> {
                ChunkPos k = new ChunkPos(in.readLong(), in.readLong());
                if (g.encoding.containsKey(k)) return; // уже в дорозі
                if (g.wants.size() >= MAX_WANTS || g.wants.contains(k) || !near(g, k, MAX_VIEW + 1)) { g.conn.send(noChunk(k)); return; }
                g.wants.add(k);
            }
            case C_BREAK -> {
                long x = in.readLong(); int y = in.readInt(); long z = in.readLong();
                ItemType tool = world.itemTypes.get(str(in, 96)); // інструмент у руці гостя (інвентар - у гостя: впливає лише на дроп)
                int id = world.blockId(x, y, z);
                BlockType b = id > 0 ? world.blocks.get(id) : null;
                int boardCount = b != null && world.boards.isBoards(b) ? Long.bitCount(world.boards.mask(x, y, z)) : 0;
                if (!mayTouch(g, x, y, z)) { resend(g, x, y, z); return; }
                BlockEntity be = survival ? world.detachBlockEntity(x, y, z) : null; // вміст скрині/печі - тому, хто зламав (речі на землі хоста гість не підбере)
                if (!world.harvest(g.src, x, y, z, null, false)) { if (be != null) world.attachBlockEntity(be); resend(g, x, y, z); return; }
                if (be != null) for (Inventory.Stack st : be.contents()) give(g, st.item, st.count, st.damage);
                if (survival && b != null) {
                    if (boardCount > 0) give(g, World.boardItem(b), boardCount, 0);
                    for (Inventory.Stack st : Mining.drops(b, tool, rng)) give(g, st.item, st.count, st.damage);
                }
            }
            case C_PLACE -> {
                long x = in.readLong(); int y = in.readInt(); long z = in.readLong();
                String name = str(in, 96);
                BlockType t = world.blocks.byName(name);
                int cur = world.blockId(x, y, z);
                boolean ok = mayTouch(g, x, y, z) && t != null && placeable(t) && world.replaceable(cur) && world.canStay(t, x, y, z)
                        && !insidePlayer(x, y, z) && world.setBlock(g.src, x, y, z, t);
                if (!ok) resend(g, x, y, z);
            }
            case C_CHAT -> {
                String text = cleanChat(str(in, 1000));
                if (text.isEmpty()) return;
                if (g.chats <= 0) { g.conn.send(chatFrame("Не так швидко.")); return; }
                if (text.startsWith("/")) {
                    CommandRegistry cr = commands;
                    if (cr == null) { g.conn.send(chatFrame("Команди в чужому світі недоступні.")); return; }
                    g.chats--;
                    long[] feet = players.position(g.name);
                    chatOut.accept(g.name + ": " + text); // хост/консоль бачить, хто що виконує
                    for (String r : cr.execute(g.src, world, feet, text)) g.conn.send(chatFrame(r.length() > MAX_CHAT ? r.substring(0, MAX_CHAT) : r));
                    return;
                }
                g.chats--;
                if (world.events().post(new voxelin.api.event.ChatEvent(world, g.name, text, true)).isCancelled()) return; // мод не пропустив
                announce("<" + g.name + "> " + text);
            }
            case C_STATE -> {
                double h = in.readDouble(); boolean dead = in.readBoolean();
                g.health = Double.isFinite(h) ? Math.max(0, Math.min(20, h)) : 20; g.dead = dead;
            }
            case C_INV -> {
                int n = in.readInt();
                if (n != Inventory.SIZE + 5) throw new IOException("поганий інвентар");
                String[] lines = new String[n];
                for (int i = 0; i < n; i++) lines[i] = str(in, 200).replace("\n", "").replace("\r", "");
                g.invLines = lines;
                long now = System.currentTimeMillis();
                if (!survival || now - g.lastInvSave < 3000) return; // не частіше раз на 3 с (решту запише вихід)
                g.lastInvSave = now;
                saveInv(g.name, lines);
            }
            case C_OPEN -> {
                long x = in.readLong(); int y = in.readInt(); long z = in.readLong();
                String[] lines = readSlots(in);
                if (g.session != null) endSession(g);
                if (!openSession(g, x, y, z, lines)) g.conn.send(frame(S_MENU_END, o -> writeSlots(o, lines))); // не вийшло - гість закриває вікно, речі як були
            }
            case C_MENU -> {
                int seq = in.readInt(), op = in.readUnsignedByte(), slot = in.readInt(), arg = in.readInt();
                boolean shift = in.readBoolean();
                Session ss = g.session;
                if (ss == null) return; // вікно вже закрите (скриню зламали) - S_MENU_END у дорозі
                ss.seq = seq;
                if (g.actions > 0 && slot >= 0 && slot < ss.menu.size()) {
                    g.actions--;
                    switch (op) {
                        case Menu.OP_CLICK -> ss.menu.click(slot, arg == 1 ? 1 : 0, shift);
                        case Menu.OP_DOUBLE -> ss.menu.doubleClick(slot);
                        case Menu.OP_SWAP -> ss.menu.swapHotbar(slot, arg);
                        case Menu.OP_SORT -> ss.inv.sortMain();
                        default -> { }
                    }
                }
                sendMenu(g); // і після відхиленого кліку: гість дізнається, що нічого не змінилось
            }
            case C_CLOSE -> { if (g.session != null) endSession(g); }
            case C_BYE -> drop(g, null);
            default -> throw new IOException("невідомий вид " + f[0]);
        }
    }

    static String[] readSlots(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n != Inventory.SIZE) throw new IOException("погані слоти");
        String[] l = new String[n];
        for (int i = 0; i < n; i++) { String v = str(in, 200); l[i] = v.isEmpty() ? null : v; }
        return l;
    }

    static void writeSlots(DataOutputStream o, String[] l) throws IOException { o.writeInt(l.length); for (String v : l) o.writeUTF(v == null ? "" : v); }

    private boolean openSession(Guest g, long x, int y, long z, String[] lines) {
        if (g.dead || !mayTouch(g, x, y, z)) return false;
        BlockEntity e = world.chestAt(x, y, z);
        if (e == null) e = world.furnaceAt(x, y, z);
        if (e == null) return false;
        // кілька гравців в одній скрині - можна: усі меню над тією самою сутністю, кліки йдуть по черзі на головному потоці
        Inventory inv = new Inventory(world.itemTypes);
        inv.decode(lines, 0);
        Menu m = e instanceof ChestEntity c ? new ChestMenu(world.itemTypes, inv, c) : new FurnaceMenu(world.itemTypes, world.recipes, inv, (FurnaceEntity) e);
        g.session = new Session(e, inv, m);
        sendMenu(g);
        return true;
    }

    private void sendMenu(Guest g) {
        Session ss = g.session;
        ss.last = ss.state();
        String kind = ss.entity.kind(), data = ss.entity.encode(), cur = stack(ss.menu.cursor());
        int cook = ss.entity instanceof FurnaceEntity f ? f.cookTotal : 0;
        String[] slots = ss.inv.encode();
        g.conn.send(frame(S_MENU, o -> {
            o.writeInt(ss.seq); o.writeUTF(kind); o.writeLong(ss.x); o.writeInt(ss.y); o.writeLong(ss.z);
            writeSlots(o, slots); o.writeUTF(cur); o.writeUTF(data); o.writeInt(cook);
        }));
    }

    /** Щотіку: вікно ще дійсне (блок на місці, гість поруч і живий)? Вміст змінився (піч горить, інший гравець переклав) - шлемо. */
    private void sessionTick(Guest g) {
        Session ss = g.session;
        boolean ok = !g.dead && world.blockEntityAt(ss.x, ss.y, ss.z) == ss.entity && distance(Pos.ofBlock(ss.x, ss.y + 0.5, ss.z), g.pos) <= REACH + 2;
        if (!ok) { endSession(g); return; }
        if (!ss.state().equals(ss.last)) sendMenu(g);
    }

    /** Закрити вікно гостя: курсор - у його інвентар (що не влізло - у скриню, інакше на землю), гостю - остаточний інвентар. */
    private void endSession(Guest g) {
        Session ss = g.session;
        g.session = null;
        Inventory.Stack left = ss.menu.closeCursor(ss.inv);
        if (left != null && ss.entity instanceof ChestEntity c) for (int i = 0; i < ChestEntity.SIZE && left != null; i++) if (c.get(i) == null) { c.set(i, left); left = null; }
        if (left != null) world.spawnItem(Pos.ofBlock(ss.x, ss.y + 1.2, ss.z), left.item, left.count, left.damage, true);
        String[] slots = ss.inv.encode();
        if (g.invLines != null) for (int i = 0; i < Inventory.SIZE; i++) g.invLines[i] = slots[i] == null ? "" : slots[i];
        if (!g.conn.closed()) g.conn.send(frame(S_MENU_END, o -> writeSlots(o, slots)));
    }

    /** Гість іде: вікно закривається, останній інвентар - на диск (виживання). */
    private void farewell(Guest g) {
        if (g.session != null) endSession(g);
        if (survival && g.invLines != null) saveInv(g.name, g.invLines);
        if (dedicated()) savePos(g);
    }

    // ------------------------------------------------------------------ позиція гостя (сервер без вікна): guests/<ім'я>.pos

    private File posFile(String name) { return new File(guestsDir(), name.toLowerCase(Locale.ROOT) + ".pos"); }

    private void savePos(Guest g) {
        try {
            File d = guestsDir();
            d.mkdirs();
            File f = posFile(g.name), tmp = new File(d, f.getName() + ".tmp");
            String line = g.pos.cx + " " + g.pos.cz + " " + g.pos.x + " " + g.pos.y + " " + g.pos.z;
            java.nio.file.Files.write(tmp.toPath(), line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files2.replace(tmp, f);
        } catch (IOException e) { System.out.println("[Voxelin] ⚠️ позиція гостя " + g.name + ": " + e); }
    }

    /** Де гість вийшов минулого разу; null - нема або файл зіпсовано. */
    Pos loadPos(String name) {
        File f = posFile(name);
        if (!f.isFile() || f.length() > 256) return null;
        try {
            String[] p = java.nio.file.Files.readString(f.toPath()).trim().split("\\s+");
            if (p.length != 5) return null;
            double x = Double.parseDouble(p[2]), y = Double.parseDouble(p[3]), z = Double.parseDouble(p[4]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 64 || Math.abs(z) > 64 || y < 0 || y > 1024) return null;
            Pos pos = new Pos(Long.parseLong(p[0]), Long.parseLong(p[1]), x, y, z);
            return World.inRange(pos.cx, pos.cz) ? pos : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    private void give(Guest g, String item, int count, int damage) {
        if (item == null || count <= 0) return;
        g.conn.send(frame(S_GIVE, o -> { o.writeUTF(item); o.writeInt(count); o.writeInt(damage); }));
    }

    private static byte[] noChunk(ChunkPos k) { return frame(S_NOCHUNK, o -> { o.writeLong(k.cx()); o.writeLong(k.cz()); }); }
    private static byte[] chatFrame(String s) { return frame(S_CHAT, o -> o.writeUTF(s)); }

    /** Чи може гість змінити блок: частота, висота, чанк у нього є, у межах руки. */
    private boolean mayTouch(Guest g, long x, int y, long z) {
        if (g.actions <= 0 || y < 0 || y >= Chunk.HEIGHT || !World.inRange(x >> 4, z >> 4)) return false;
        g.actions--;
        if (!g.sent.contains(new ChunkPos(x >> 4, z >> 4))) return false;
        return distance(Pos.ofBlock(x, y + 0.5, z), g.pos) <= REACH;
    }

    /** Гості ставлять лише звичайні блоки, які дає якийсь предмет: не незламні (основа, портали), не дошки, не «невідомий». */
    private boolean placeable(BlockType t) {
        if (t.id == 0 || t == world.blocks.unknown() || t.hardness < 0 || world.boards.isBoards(t) || t.name.equals(Infection.EGG) || t.name.equals(Infection.CORE)) return false; // і не яйце й ядро зараження
        for (ItemType it : world.itemTypes.all()) if (t.name.equals(it.placesBlock)) return true;
        return false;
    }

    /** Блок не ставиться в тіло хоста чи іншого гостя. */
    private boolean insidePlayer(long x, int y, long z) {
        List<Pos> bodies = new ArrayList<>();
        if (!dedicated()) bodies.add(hostPos);
        for (Guest o : guests) bodies.add(o.pos);
        for (Pos p : bodies) {
            if (p.blockX() != x || p.blockZ() != z) continue;
            double feet = p.y - voxelin.core.entity.PlayerEntity.EYE;
            if (y + 1 > feet && y < feet + voxelin.core.entity.PlayerEntity.HEIGHT) return true;
        }
        return false;
    }

    /** Відстань у блоках (без переповнення для далеких чанків). */
    static double distance(Pos a, Pos b) {
        if (Math.abs(a.cx - b.cx) > 64 || Math.abs(a.cz - b.cz) > 64) return Double.MAX_VALUE;
        double dx = a.relX(b.cx) - b.x, dz = a.relZ(b.cz) - b.z, dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean near(Guest g, ChunkPos k, int r) {
        long dx = k.cx() - g.pos.cx, dz = k.cz() - g.pos.cz; // обидва в межах MAX_CHUNK - різниця не переповнюється
        return Math.abs(dx) <= r && Math.abs(dz) <= r;
    }

    /** Чи завантажить хост цей чанк довкола гостя (те саме коло, що в World.update). */
    private static boolean loadable(Guest g, ChunkPos k) {
        long dx = k.cx() - g.pos.cx, dz = k.cz() - g.pos.cz, r = MAX_VIEW + 1;
        return Math.abs(dx) <= r && Math.abs(dz) <= r && dx * dx + dz * dz <= r * r + r;
    }

    /** Гостю - справжній стан блока (його запит відхилено). */
    private void resend(Guest g, long x, int y, long z) {
        int id = world.blockId(x, y, z);
        if (id >= 0 && y >= 0 && y < Chunk.HEIGHT && g.sent.contains(new ChunkPos(x >> 4, z >> 4))) g.conn.send(blockFrame(x, y, z, world.paletteOf(id)));
    }

    private static byte[] blockFrame(long x, int y, long z, int pal) { return frame(S_BLOCK, o -> { o.writeLong(x); o.writeInt(y); o.writeLong(z); o.writeInt(pal); }); }

    private void blockChanged(long x, int y, long z) {
        if (guests.isEmpty()) return;
        ChunkPos k = new ChunkPos(x >> 4, z >> 4);
        byte[] f = null;
        for (Guest g : guests) {
            List<long[]> pend = g.encoding.get(k);
            if (pend != null) { if (pend.size() < 4096) pend.add(new long[]{x, y, z}); continue; } // чанк ще кодується - після нього
            if (!g.sent.contains(k)) continue;
            if (f == null) f = blockFrame(x, y, z, world.paletteOf(Math.max(0, world.blockId(x, y, z))));
            g.conn.send(f);
        }
    }

    /** Чанки гостям: по черзі, не більше CHUNKS_PER_TICK за тік на всіх (кодування - на головному потоці). */
    private void sendChunks() {
        int budget = CHUNKS_PER_TICK;
        if (guests.isEmpty()) return;
        for (int n = 0; n < guests.size() && budget > 0; n++) {
            Guest g = guests.get((rr + n) % guests.size());
            g.sent.removeIf(k -> !near(g, k, MAX_VIEW + 4)); // гість їх уже вивантажив
            int per = 0;
            for (Iterator<ChunkPos> it = g.wants.iterator(); it.hasNext() && budget > 0 && per < 4; ) {
                ChunkPos k = it.next();
                if (!near(g, k, MAX_VIEW + 2)) { it.remove(); g.conn.send(noChunk(k)); continue; }
                Chunk c = world.chunk(k.cx(), k.cz());
                if (c == null && !loadable(g, k)) { it.remove(); g.conn.send(noChunk(k)); continue; } // хост його не вантажить - хай гість спитає пізніше
                if (c == null) continue; // хост ще вантажить довкола гостя
                it.remove();
                if (g.encoding.size() >= 16) { g.wants.addFirst(k); break; } // фон не встигає - решту пізніше
                java.util.function.Supplier<byte[]> job = world.encodeLater(c);
                g.encoding.put(k, new ArrayList<>());
                encoder.execute(() -> { try { encoded.add(new Encoded(g, k, job.get())); } catch (RuntimeException e) { encoded.add(new Encoded(g, k, null)); } });
                budget--; per++;
            }
        }
        rr++;
    }

    /** Готові чанки - гостям, одразу за ними - блоки, що змінились, поки чанк кодувався (їхній поточний стан). */
    private void deliverEncoded() {
        for (Encoded e; (e = encoded.poll()) != null; ) {
            Guest g = e.g();
            List<long[]> pend = g.encoding.remove(e.k());
            if (pend == null || !guests.contains(g)) continue;
            if (e.data() == null) { g.conn.send(noChunk(e.k())); continue; } // хай попросить знову
            final ChunkPos k = e.k(); final byte[] data = e.data();
            g.conn.send(frame(S_CHUNK, o -> { o.writeLong(k.cx()); o.writeLong(k.cz()); o.writeInt(data.length); o.write(data); }));
            g.sent.add(k);
            for (long[] b : pend) g.conn.send(blockFrame(b[0], (int) b[1], b[2], world.paletteOf(Math.max(0, world.blockId(b[0], (int) b[1], b[2])))));
        }
    }

    private void sendPlayers() {
        for (Guest to : guests) {
            List<Object[]> list = new ArrayList<>();
            if (!dedicated()) list.add(new Object[]{0, hostName, hostLook, hostPos, hostYaw, hostPitch});
            for (Guest g : guests) if (g != to) list.add(new Object[]{g.id, g.name, g.look, g.pos, g.yaw, g.pitch});
            to.conn.send(frame(S_PLAYERS, o -> {
                o.writeInt(list.size());
                for (Object[] p : list) { o.writeInt((int) p[0]); o.writeUTF((String) p[1]); o.writeUTF((String) p[2]); writePos(o, (Pos) p[3]); o.writeFloat((float) p[4]); o.writeFloat((float) p[5]); }
            }));
        }
    }

    private void sendMobs() {
        if (guests.isEmpty()) { mobIds.clear(); return; }
        Set<Mob> alive = Collections.newSetFromMap(new IdentityHashMap<>());
        alive.addAll(world.mobs());
        mobIds.keySet().retainAll(alive); // зниклі
        for (Guest to : guests) {
            List<Mob> near = new ArrayList<>();
            for (Mob m : world.mobs()) if (near.size() < 200 && distance(m.pos, to.pos) < MAX_VIEW * 16) near.add(m);
            to.conn.send(frame(S_MOBS, o -> {
                o.writeInt(near.size());
                for (Mob m : near) {
                    o.writeInt(mobIds.computeIfAbsent(m, k -> nextMob++)); o.writeUTF(m.type.name); writePos(o, m.pos);
                    o.writeFloat(m.bodyYaw); o.writeFloat(m.headYaw); o.writeByte((m.dying ? 1 : 0) | (m.hurtTicks > 0 ? 2 : 0) | (m.burning ? 4 : 0));
                }
            }));
        }
    }

    private void broadcast(byte[] f) { for (Guest g : guests) g.conn.send(f); }

    /** Повідомлення всім: гостям і в чат хоста. */
    private void announce(String s) { broadcast(chatFrame(s)); if (chatOut != null) chatOut.accept(s); }

    /** Хост пише в чат. */
    public void say(String text) {
        String t = cleanChat(text);
        if (t.isEmpty() || world.events().post(new voxelin.api.event.ChatEvent(world, hostName, t, false)).isCancelled()) return;
        announce("<" + hostName + "> " + t);
    }

    private void drop(Guest g, String why) {
        if (!guests.remove(g)) return;
        farewell(g);
        if (g.target != null) world.removeTarget(g.target);
        if (why != null && !g.conn.closed()) g.conn.send(frame(S_KICK, o -> o.writeUTF(why)));
        g.conn.closeAfterSend();
        announce(g.name + " вийшов з гри" + (why == null || why.equals("закрито") ? "." : " (" + why + ")."));
        world.events().post(new voxelin.api.event.PlayerLeaveEvent(world, g.name, true, why));
    }

    public boolean closed() { return closed; }

    /** Закрити гру для мережі: гостям - «хост закрив гру». */
    @Override public void close() { close(dedicated() ? "сервер зупинено" : "хост закрив гру"); }

    /** Закрити з поясненням для гостей (сервер аварійно зупинився - вони побачать, чому). */
    public void close(String why) {
        if (closed) return;
        closed = true;
        for (Guest g : guests) farewell(g);
        for (Guest g : guests) { g.conn.send(frame(S_KICK, o -> o.writeUTF(why))); g.conn.closeAfterSend(); }
        for (Guest g : guests) if (g.target != null) world.removeTarget(g.target);
        for (Guest g : guests) world.events().post(new voxelin.api.event.PlayerLeaveEvent(world, g.name, true, why));
        guests.clear();
        for (Conn c : handshakes) c.close();
        handshakes.clear();
        for (Socket s; (s = accepted.poll()) != null; ) try { s.close(); } catch (IOException ignored) {}
        try { server.close(); } catch (IOException ignored) {}
        encoder.shutdownNow();
        world.removeWatcher(watcher);
        world.setExtraCenters(List.of());
    }
}
