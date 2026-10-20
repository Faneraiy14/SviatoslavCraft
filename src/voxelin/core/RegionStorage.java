package voxelin.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Чанки на диску: saves/<світ>/region/r.<rx>.<rz>.vxr - до 32x32 чанків в одному файлі.
 * Формат файлу: int MAGIC, int FORMAT, int кількість, далі записи (byte lx, byte lz, int довжина, стиснуті байти чанка).
 * Чанк (до стиснення Deflate): byte CHUNK_FORMAT, short маска секцій, для кожної непорожньої секції 4096 short номерів палітри.
 * Регіон тримається в пам'яті стиснутим; flush() переписує змінені регіони атомарно (tmp -> rename).
 * Битий файл НЕ видаляється - перейменовується в *.corrupt-<час>, а чанки генеруються заново.
 */
public final class RegionStorage {
    public static final int MAGIC = 0x56585231; // "VXR1"
    public static final int FORMAT = 1;
    static final int CHUNK_FORMAT = 1;

    private final File dir;
    private final Map<Long, Map<Integer, byte[]>> regions = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    public RegionStorage(File worldDir) { this.dir = new File(worldDir, "region"); }

    private static long rkey(int rx, int rz) { return ((long) rx << 32) ^ (rz & 0xFFFFFFFFL); }
    private File file(int rx, int rz) { return new File(dir, "r." + rx + "." + rz + ".vxr"); }

    private Map<Integer, byte[]> region(int rx, int rz) {
        long k = rkey(rx, rz);
        Map<Integer, byte[]> m = regions.get(k);
        if (m != null) return m;
        m = new HashMap<>();
        File f = file(rx, rz);
        if (f.isFile()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
                if (in.readInt() != MAGIC) throw new IOException("не файл регіону Voxelin");
                int fmt = in.readInt();
                if (fmt > FORMAT) throw new IOException("новіший формат регіону: " + fmt);
                int n = in.readInt();
                if (n < 0 || n > 1024) throw new IOException("погана кількість чанків: " + n);
                for (int i = 0; i < n; i++) {
                    int lx = in.readUnsignedByte(), lz = in.readUnsignedByte(), len = in.readInt();
                    if (lx > 31 || lz > 31 || len < 0 || len > (1 << 24)) throw new IOException("пошкоджений запис чанка");
                    byte[] b = new byte[len];
                    in.readFully(b);
                    m.put(lx | (lz << 5), b);
                }
            } catch (IOException e) {
                File bad = new File(dir, f.getName() + ".corrupt-" + System.currentTimeMillis());
                boolean moved = f.renameTo(bad);
                System.out.println("[Voxelin] ⚠️ регіон " + f.getName() + " пошкоджено (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "), " + (moved ? "відкладено як " + bad.getName() : "не вдалось відкласти") + "; чанки згенеруються заново");
                m = new HashMap<>();
            }
        }
        regions.put(k, m);
        return m;
    }

    /** Прочитати чанк (номери палітри перекладаються в id цього запуску через p2rt). null - чанка на диску нема або він битий. */
    public synchronized Chunk load(int cx, int cz, int[] p2rt) {
        byte[] data = region(cx >> 5, cz >> 5).get((cx & 31) | ((cz & 31) << 5));
        if (data == null) return null;
        try { return decode(cx, cz, data, p2rt); }
        catch (IOException e) { System.out.println("[Voxelin] ⚠️ чанк " + cx + "," + cz + " пошкоджено: " + e.getMessage() + " - генерую заново"); return null; }
    }

    /** Записати чанк (у пам'ять регіону; на диск - у flush()). rt2p - id запуску -> номер палітри. */
    public void save(Chunk c, int[] rt2p) {
        byte[] data = encode(c, rt2p);
        synchronized (this) {
            region(c.cx >> 5, c.cz >> 5).put((c.cx & 31) | ((c.cz & 31) << 5), data);
            dirty.add(rkey(c.cx >> 5, c.cz >> 5));
        }
    }

    /** Записати змінені регіони на диск атомарно. */
    public void flush() throws IOException {
        List<long[]> todo = new ArrayList<>();
        List<Map<Integer, byte[]>> maps = new ArrayList<>();
        synchronized (this) {
            for (long k : dirty) { todo.add(new long[]{k}); maps.add(new HashMap<>(regions.get(k))); }
            dirty.clear();
        }
        if (todo.isEmpty()) return;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("не вдалось створити " + dir);
        for (int i = 0; i < todo.size(); i++) {
            long k = todo.get(i)[0];
            int rx = (int) (k >> 32), rz = (int) k;
            File f = file(rx, rz), tmp = new File(dir, f.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeInt(maps.get(i).size());
                for (Map.Entry<Integer, byte[]> e : maps.get(i).entrySet()) {
                    out.writeByte(e.getKey() & 31); out.writeByte(e.getKey() >> 5); out.writeInt(e.getValue().length); out.write(e.getValue());
                }
            } catch (IOException e) { synchronized (this) { dirty.add(k); } throw e; }
            Files2.replace(tmp, f);
        }
    }

    static byte[] encode(Chunk c, int[] rt2p) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bos, new Deflater(Deflater.BEST_SPEED)))) {
                out.writeByte(CHUNK_FORMAT);
                int mask = 0;
                for (int sy = 0; sy < Chunk.SECTIONS; sy++) { Section s = c.section(sy); if (s != null && !s.isEmpty()) mask |= 1 << sy; }
                out.writeShort(mask);
                for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
                    if ((mask & (1 << sy)) == 0) continue;
                    short[] b = c.section(sy).raw();
                    for (short v : b) out.writeShort(v >= 0 && v < rt2p.length ? rt2p[v] : 0);
                }
            }
            return bos.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static Chunk decode(int cx, int cz, byte[] data, int[] p2rt) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            int fmt = in.readUnsignedByte();
            if (fmt != CHUNK_FORMAT) throw new IOException("невідомий формат чанка " + fmt);
            int mask = in.readUnsignedShort();
            Chunk c = new Chunk(cx, cz);
            for (int sy = 0; sy < Chunk.SECTIONS; sy++) {
                if ((mask & (1 << sy)) == 0) continue;
                Section s = new Section();
                for (int i = 0; i < Section.VOLUME; i++) {
                    int p = in.readUnsignedShort();
                    s.blocks[i] = (short) (p < p2rt.length ? p2rt[p] : 0); // номер поза палітрою - лише в битому файлі
                }
                s.recount();
                c.setSection(sy, s);
            }
            return c;
        } catch (EOFException e) { throw new IOException("обрізані дані чанка"); }
    }
}
