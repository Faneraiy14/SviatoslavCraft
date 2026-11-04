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
 * Гості - будівельники у творчому режимі: бачать світ, гравців, істот, час доби; ламають і ставлять блоки, пишуть у чат. Хост перевіряє
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
        public final Pos pos;
        public float yaw, pitch;
        final Set<ChunkPos> sent = new HashSet<>();
        final ArrayDeque<ChunkPos> wants = new ArrayDeque<>();
        int actions = ACTION_BURST, chats = 3;
        Guest(int id, String name, String look, Conn conn, Pos at) {
            this.id = id; this.name = name; this.look = look; this.conn = conn; this.pos = at.copy();
            src = Sources.of(name, Source.Kind.PLAYER, 0, 16);
        }
    }

    /** Дій (зламати/поставити) за раз; відновлюється по одній за тік - ~20/с. */
    static final int ACTION_BURST = 20, CHUNKS_PER_TICK = 8, MAX_WANTS = 64;
    /** Відстань від очей гостя до блока, який він міняє (з запасом на затримку). */
    static final double REACH = Raycast.MAX_REACH + 2.5;
    /** Найбільший стрибок позиції за одне повідомлення (блоків); більше - телепорт назад. */
    static final double MAX_STEP = 24, SPAWN_AHEAD = 2.5;

    private final World world;
    private final ServerSocket server;
    public final String code;
    public final int port;
    public final String hostName;
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

    /** Відкрити світ для мережі на порту (0 - будь-який вільний). chatOut - що показати в чаті хоста. */
    public NetServer(World world, int port, String hostName, String hostLook, Consumer<String> chatOut) throws IOException {
        if (world.remote()) throw new IllegalStateException("дзеркало чужого світу не можна відкрити для мережі");
        this.world = world; this.hostName = hostName; this.hostLook = hostLook; this.chatOut = chatOut;
        code = Protocol.newCode();
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
        if (ver != VERSION) return "інша версія гри (протокол " + ver + ", у хоста " + VERSION + ")";
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
        double[] d = Raycast.dir(hostYaw, 0); // гість з'являється за 2.5 блока перед хостом, обличчям до нього
        Pos spawn = hostPos.copy();
        spawn.add(d[0] * SPAWN_AHEAD, 0, d[2] * SPAWN_AHEAD);
        spawn.normalize();
        Guest g = new Guest(nextId++, name, look, c, spawn);
        guests.add(g);
        c.send(frame(S_WELCOME, o -> {
            o.writeInt(g.id); o.writeUTF(hostName); o.writeUTF(world.info.name); o.writeLong(world.seed()); o.writeUTF(world.info.type.name());
            List<String> pal = new ArrayList<>(world.info.palette);
            o.writeInt(pal.size());
            for (String p : pal) o.writeUTF(p);
            writePos(o, spawn); o.writeFloat((hostYaw + 180) % 360); o.writeFloat(0);
            o.writeInt(MAX_VIEW);
        }));
        c.send(timeFrame());
        announce(name + " приєднався до гри.");
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
                if (distance(p, g.pos) > MAX_STEP) { g.conn.send(frame(S_TELEPORT, o -> writePos(o, g.pos))); return; } // стрибок - назад
                g.pos.cx = p.cx; g.pos.cz = p.cz; g.pos.x = p.x; g.pos.y = p.y; g.pos.z = p.z;
                g.yaw = ((yaw % 360) + 360) % 360; g.pitch = Math.max(-90, Math.min(90, pitch));
            }
            case C_CHUNK -> {
                ChunkPos k = new ChunkPos(in.readLong(), in.readLong());
                if (g.wants.size() >= MAX_WANTS || g.wants.contains(k) || !near(g, k, MAX_VIEW + 1)) { g.conn.send(noChunk(k)); return; }
                g.wants.add(k);
            }
            case C_BREAK -> {
                long x = in.readLong(); int y = in.readInt(); long z = in.readLong();
                if (!mayTouch(g, x, y, z) || !world.harvest(g.src, x, y, z, null, false)) resend(g, x, y, z);
            }
            case C_PLACE -> {
                long x = in.readLong(); int y = in.readInt(); long z = in.readLong();
                String name = str(in, 96);
                BlockType t = world.blocks.byName(name);
                int cur = world.blockId(x, y, z);
                boolean ok = mayTouch(g, x, y, z) && t != null && placeable(t) && cur >= 0 && (cur == 0 || world.blocks.get(cur).liquid)
                        && !insidePlayer(x, y, z) && world.setBlock(g.src, x, y, z, t);
                if (!ok) resend(g, x, y, z);
            }
            case C_CHAT -> {
                String text = cleanChat(str(in, 1000));
                if (text.isEmpty()) return;
                if (text.startsWith("/")) { g.conn.send(chatFrame("Команди в чужому світі недоступні.")); return; }
                if (g.chats <= 0) { g.conn.send(chatFrame("Не так швидко.")); return; }
                g.chats--;
                announce("<" + g.name + "> " + text);
            }
            case C_BYE -> drop(g, null);
            default -> throw new IOException("невідомий вид " + f[0]);
        }
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
        bodies.add(hostPos);
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
                byte[] data = world.encodeChunk(c);
                g.conn.send(frame(S_CHUNK, o -> { o.writeLong(k.cx()); o.writeLong(k.cz()); o.writeInt(data.length); o.write(data); }));
                g.sent.add(k);
                budget--; per++;
            }
        }
        rr++;
    }

    private void sendPlayers() {
        for (Guest to : guests) {
            List<Object[]> list = new ArrayList<>();
            list.add(new Object[]{0, hostName, hostLook, hostPos, hostYaw, hostPitch});
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
    public void say(String text) { String t = cleanChat(text); if (!t.isEmpty()) announce("<" + hostName + "> " + t); }

    private void drop(Guest g, String why) {
        if (!guests.remove(g)) return;
        if (why != null && !g.conn.closed()) g.conn.send(frame(S_KICK, o -> o.writeUTF(why)));
        g.conn.closeAfterSend();
        announce(g.name + " вийшов з гри" + (why == null || why.equals("закрито") ? "." : " (" + why + ")."));
    }

    public boolean closed() { return closed; }

    /** Закрити гру для мережі: гостям - «хост закрив гру». */
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Guest g : guests) { g.conn.send(frame(S_KICK, o -> o.writeUTF("хост закрив гру"))); g.conn.closeAfterSend(); }
        guests.clear();
        for (Conn c : handshakes) c.close();
        handshakes.clear();
        for (Socket s; (s = accepted.poll()) != null; ) try { s.close(); } catch (IOException ignored) {}
        try { server.close(); } catch (IOException ignored) {}
        world.removeWatcher(watcher);
        world.setExtraCenters(List.of());
    }
}
