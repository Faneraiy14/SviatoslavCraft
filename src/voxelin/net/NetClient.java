package voxelin.net;

import voxelin.core.*;
import voxelin.core.entity.Mob;

import java.io.*;
import java.net.*;
import java.util.*;

import static voxelin.net.Protocol.*;

/**
 * Гість мережевої гри: з'єднання з хостом і дзеркало його світу. Усе від хоста теж перевіряється (розміри, координати, відомі назви) -
 * зламаний чи злий хост не обвалить гру гостя. poll() - на головному потоці щотіку.
 */
public final class NetClient implements AutoCloseable {
    /** Що хост сказав при вході. */
    public record Welcome(int id, String hostName, String worldName, long seed, WorldInfo.Type type, List<String> palette, Pos spawn, float yaw, float pitch, int view, boolean survival) {}

    /** Інший гравець (хост чи гість): позиція з мережі (target) і показана (pos, плавно доганяє). */
    public static final class Remote {
        public final int id;
        public String name, look;
        public final Pos pos, target;
        public float yaw, pitch;
        /** Пройдена відстань (фаза кроку) і швидкість (сила розмаху), блоків/с. */
        public double walk, speed;
        public Remote(int id, Pos at) { this.id = id; pos = at.copy(); target = at.copy(); }
        /** Нова позиція з мережі. */
        public void set(Pos p, float yaw, float pitch) {
            target.cx = p.cx; target.cz = p.cz; target.x = p.x; target.y = p.y; target.z = p.z;
            this.yaw = Float.isFinite(yaw) ? yaw : 0; this.pitch = Float.isFinite(pitch) ? Math.max(-90, Math.min(90, pitch)) : 0;
        }
        /** Тік: показана позиція доганяє мережеву. */
        public void step() { speed = follow(pos, target, 0.4, w -> walk += w); }
    }

    private final Conn conn;
    public final Welcome welcome;
    public final String name;
    private World world;
    private final Map<Integer, Remote> players = new LinkedHashMap<>();
    private final Map<Integer, Mob> mobs = new HashMap<>();
    private final Map<Mob, Pos> mobTargets = new IdentityHashMap<>();
    private final Deque<String> chat = new ArrayDeque<>();
    /** Дроп від хоста (виживання): {предмет, кількість, зношення}; забирає PlayScreen у інвентар. */
    private final Deque<Object[]> gifts = new ArrayDeque<>();
    private final Map<ChunkPos, Long> refused = new HashMap<>();
    private Pos teleport;
    private String kicked;
    private boolean started;
    /** Остання надіслана позиція й коли - без руху (екран завантаження) раз на секунду повторюємо, щоб хост не вважав нас зниклими. */
    private Pos lastPos;
    private float lastYaw, lastPitch;
    private long lastSent;

    private NetClient(Conn conn, Welcome w, String name) { this.conn = conn; welcome = w; this.name = name; }

    /** Під'єднатись і привітатись (блокує до ~10 с - викликати не з головного потоку). IOException - з поясненням для гравця. */
    public static NetClient connect(String host, int port, String name, String code, String look) throws IOException {
        String nm = cleanName(name);
        if (nm == null) throw new IOException("ім'я: 1-16 літер, цифр, _ або -");
        Socket s = new Socket();
        try { s.connect(new InetSocketAddress(host, port), 5000); }
        catch (UnknownHostException e) { s.close(); throw new IOException("невідома адреса " + host); }
        catch (IOException e) { s.close(); throw new IOException("не вдалось з'єднатись з " + host + ":" + port + " (" + e.getMessage() + ")"); }
        Conn c = new Conn(s);
        try {
            c.writeNow(frame(C_HELLO, o -> { o.writeInt(VERSION); o.writeUTF(code == null ? "" : code.trim()); o.writeUTF(nm); o.writeUTF(look == null ? "" : look); }));
            byte[] f = c.readNow(HANDSHAKE_MS * 2);
            DataInputStream in = body(f);
            if (f[0] == S_REJECT) throw new IOException("хост відмовив: " + str(in, 300));
            if (f[0] != S_WELCOME) throw new IOException("це не хост Voxelin");
            int id = in.readInt();
            String hostName = str(in, 64), world = str(in, 64);
            long seed = in.readLong();
            WorldInfo.Type type;
            try { type = WorldInfo.Type.valueOf(str(in, 32)); } catch (IllegalArgumentException e) { throw new IOException("невідомий тип світу"); }
            int n = in.readInt();
            if (n < 1 || n > 65535) throw new IOException("погана палітра");
            List<String> pal = new ArrayList<>(n);
            for (int i = 0; i < n; i++) pal.add(str(in, 128));
            Pos spawn = NetServer.readPos(in);
            float yaw = in.readFloat(), pitch = in.readFloat();
            int view = Math.max(2, Math.min(MAX_VIEW, in.readInt()));
            boolean survival = in.readBoolean();
            if (spawn == null) throw new IOException("погана точка появи");
            return new NetClient(c, new Welcome(id, hostName, world, seed, type, pal, spawn, Float.isFinite(yaw) ? yaw : 0, Float.isFinite(pitch) ? pitch : 0, view, survival), nm);
        } catch (SocketTimeoutException e) { c.close(); throw new IOException("хост не відповів"); }
        catch (EOFException e) { c.close(); throw new IOException("хост закрив з'єднання"); }
        catch (IOException e) { c.close(); throw e; }
    }

    /** Почати гру: дзеркало світу бере чанки звідси. */
    public void attach(World mirror) {
        world = mirror;
        mirror.setFeed(this::requestChunk);
        conn.start("host");
        started = true;
    }

    private boolean requestChunk(long cx, long cz) {
        ChunkPos k = new ChunkPos(cx, cz);
        Long at = refused.get(k);
        if (at != null && System.currentTimeMillis() - at < 1000) return false; // хост щойно відмовив - не засипати
        refused.remove(k);
        return conn.send(frame(C_CHUNK, o -> { o.writeLong(cx); o.writeLong(cz); }));
    }

    /** Обробити все, що прийшло від хоста (головний потік). */
    public void poll() {
        for (int i = 0; i < 5000; i++) {
            byte[] f = conn.poll();
            if (f == null) break;
            try { handle(f); }
            catch (IOException | RuntimeException e) { kicked = "хост надіслав погані дані: " + e.getMessage(); conn.close(); return; }
        }
        if (refused.size() > 512) refused.clear();
        smooth();
        if (started && System.currentTimeMillis() - lastSent > 1000) sendPos(lastPos != null ? lastPos : welcome.spawn(), lastYaw, lastPitch);
        if (kicked == null && conn.closed()) kicked = conn.error() == null ? "з'єднання розірвано" : conn.error();
        if (kicked == null && System.currentTimeMillis() - conn.lastRecv > TIMEOUT_MS) { kicked = "хост не відповідає"; conn.close(); }
    }

    private void handle(byte[] f) throws IOException {
        DataInputStream in = body(f);
        switch (f[0]) {
            case S_CHUNK -> {
                long cx = in.readLong(), cz = in.readLong();
                int len = in.readInt();
                if (len < 1 || len > MAX_FRAME) throw new IOException("поганий чанк");
                byte[] data = new byte[len];
                in.readFully(data);
                if (World.inRange(cx, cz)) world.deliver(cx, cz, data);
            }
            case S_NOCHUNK -> { long cx = in.readLong(), cz = in.readLong(); refused.put(new ChunkPos(cx, cz), System.currentTimeMillis()); world.undeliver(cx, cz); }
            case S_BLOCK -> { long x = in.readLong(); int y = in.readInt(); long z = in.readLong(); int pal = in.readInt(); if (World.inRange(x >> 4, z >> 4)) world.setRemote(x, y, z, pal); }
            case S_TIME -> {
                long t = in.readLong(), shift = in.readLong(); double minutes = in.readDouble();
                world.info.time = Math.max(0, t); world.info.dayShift = shift;
                if (Double.isFinite(minutes)) world.info.dayMinutes = Math.max(DayCycle.MIN_MINUTES, Math.min(DayCycle.MAX_MINUTES, minutes));
            }
            case S_CHAT -> { if (chat.size() < 100) chat.add(cleanChat(str(in, 1000))); }
            case S_KICK -> { kicked = str(in, 300); conn.close(); }
            case S_TELEPORT -> { Pos p = NetServer.readPos(in); if (p != null) teleport = p; }
            case S_GIVE -> {
                String item = str(in, 96); int count = in.readInt(), damage = in.readInt();
                if (world.itemTypes.get(item) != null && count > 0 && count <= 64 && damage >= 0 && gifts.size() < 256) gifts.add(new Object[]{item, count, damage});
            }
            case S_PLAYERS -> players(in);
            case S_MOBS -> mobs(in);
            default -> { } // новіший хост: невідоме пропускаємо
        }
    }

    private void players(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_GUESTS + 1) throw new IOException("забагато гравців");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int id = in.readInt();
            String nm = str(in, 64), look = str(in, 400);
            Pos p = NetServer.readPos(in);
            float yaw = in.readFloat(), pitch = in.readFloat();
            if (p == null || id == welcome.id || !seen.add(id)) continue;
            Remote r = players.computeIfAbsent(id, k -> new Remote(k, p));
            r.name = nm; r.look = look;
            r.set(p, yaw, pitch);
        }
        players.keySet().retainAll(seen);
    }

    private void mobs(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > World.MAX_MOBS) throw new IOException("забагато істот");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int id = in.readInt();
            String type = str(in, 96);
            Pos p = NetServer.readPos(in);
            float body = in.readFloat(), head = in.readFloat();
            int flags = in.readUnsignedByte();
            MobType t = world.mobTypes.get(type);
            if (p == null || t == null || !seen.add(id)) continue;
            Mob m = mobs.get(id);
            if (m != null && m.type != t) { world.removeMob(m); mobTargets.remove(m); m = null; }
            if (m == null) { m = world.spawnMob(t, p); if (m == null) continue; m.home = null; mobs.put(id, m); }
            mobTargets.put(m, p);
            m.bodyYaw = m.yaw = Float.isFinite(body) ? body : 0; m.headYaw = Float.isFinite(head) ? Math.max(-90, Math.min(90, head)) : 0;
            boolean dying = (flags & 1) != 0;
            if (dying) m.deathTicks = m.dying ? m.deathTicks + 4 : 0;
            m.dying = dying; m.hurtTicks = (flags & 2) != 0 ? 2 : 0; m.burning = (flags & 4) != 0;
        }
        for (Iterator<Map.Entry<Integer, Mob>> it = mobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Mob> e = it.next();
            if (!seen.contains(e.getKey())) { world.removeMob(e.getValue()); mobTargets.remove(e.getValue()); it.remove(); }
        }
    }

    /** Показані позиції доганяють мережеві (оновлення приходять 5-10 разів на секунду). */
    private void smooth() {
        for (Remote r : players.values()) r.step();
        for (Map.Entry<Mob, Pos> e : mobTargets.entrySet()) {
            Mob m = e.getKey();
            double before = m.walk;
            double sp = follow(m.pos, e.getValue(), 0.3, w -> m.walk += w);
            double yr = Math.toRadians(m.bodyYaw);
            m.vx = -Math.sin(yr) * sp; m.vz = Math.cos(yr) * sp; // для розмаху ніг (малює PlayScreen за швидкістю)
            if (m.walk == before) { m.vx = 0; m.vz = 0; }
        }
    }

    /** Крок показаної позиції до мережевої; повертає швидкість по горизонталі (блоків/с при 20 тіках). Далеко (телепорт) - одразу. */
    private static double follow(Pos shown, Pos to, double k, java.util.function.DoubleConsumer walked) {
        double d = NetServer.distance(shown, to);
        if (d > 12) { shown.cx = to.cx; shown.cz = to.cz; shown.x = to.x; shown.y = to.y; shown.z = to.z; return 0; }
        double dx = to.relX(shown.cx) - shown.x, dz = to.relZ(shown.cz) - shown.z, dy = to.y - shown.y;
        shown.x += dx * k; shown.z += dz * k; shown.y += dy * k;
        shown.normalize();
        double h = Math.hypot(dx * k, dz * k);
        if (h > 0.001) walked.accept(h);
        return h * 20;
    }

    public Collection<Remote> players() { return players.values(); }

    /** Нові рядки чату (забираються). */
    public List<String> takeChat() { List<String> l = new ArrayList<>(chat); chat.clear(); return l; }

    /** Дроп, що прийшов від хоста (забирається). */
    public List<Object[]> takeGifts() { List<Object[]> l = new ArrayList<>(gifts); gifts.clear(); return l; }

    /** Хост повернув гостя (стрибок позиції) - куди. */
    public Pos takeTeleport() { Pos p = teleport; teleport = null; return p; }

    /** Причина, з якої гру завершено (null - ще граємо). */
    public String kicked() { return kicked; }

    public void sendPos(Pos p, float yaw, float pitch) {
        lastPos = p.copy(); lastYaw = yaw; lastPitch = pitch; lastSent = System.currentTimeMillis();
        conn.send(frame(C_POS, o -> { NetServer.writePos(o, p); o.writeFloat(yaw); o.writeFloat(pitch); })); }
    public void breakBlock(long x, int y, long z, String tool) { conn.send(frame(C_BREAK, o -> { o.writeLong(x); o.writeInt(y); o.writeLong(z); o.writeUTF(tool == null ? "" : tool); })); }
    public void placeBlock(long x, int y, long z, String block) { conn.send(frame(C_PLACE, o -> { o.writeLong(x); o.writeInt(y); o.writeLong(z); o.writeUTF(block); })); }
    public void chat(String text) { String t = cleanChat(text); if (!t.isEmpty()) conn.send(frame(C_CHAT, o -> o.writeUTF(t))); }

    /** Вийти: хост дізнається одразу (а не через таймаут). */
    @Override public void close() {
        if (!started) { try { conn.writeNow(frame(C_BYE, null)); } catch (IOException ignored) { } conn.close(); } // ще не грали (відмова на екрані входу)
        else if (!conn.closed()) { conn.send(frame(C_BYE, null)); conn.closeAfterSend(); }
        if (world != null) world.setFeed((cx, cz) -> false); // дзеркало лишається дзеркалом: нічого не генерує саме
    }
}
