package voxelin.net;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Одне з'єднання: потік читання складає кадри в чергу (головний потік забирає їх poll()), потік запису шле з черги.
 * Повільного одержувача не чекаємо: якщо в черзі на відправку понад MAX_QUEUED байт - з'єднання рветься (а не пам'ять росте без кінця).
 */
public final class Conn implements AutoCloseable {
    public static final long MAX_QUEUED = 16L << 20;
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private final ConcurrentLinkedQueue<byte[]> inbox = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final AtomicLong queued = new AtomicLong();
    private volatile boolean closed;
    private volatile String error;
    public volatile long lastRecv = System.currentTimeMillis();
    private static final byte[] STOP = new byte[0];

    public Conn(Socket s) throws IOException {
        socket = s;
        s.setTcpNoDelay(true);
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
    }

    /** Запустити потоки читання й запису. */
    public Conn start(String name) {
        Thread r = new Thread(this::readLoop, "voxelin-net-in-" + name), w = new Thread(this::writeLoop, "voxelin-net-out-" + name);
        r.setDaemon(true); w.setDaemon(true);
        r.start(); w.start();
        return this;
    }

    /** Прочитати кадр одразу (рукостискання, до start()); таймаут - мілісекунди. */
    public byte[] readNow(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        try { return Protocol.read(in); } finally { socket.setSoTimeout(0); }
    }

    /** Надіслати одразу (рукостискання, до start()). */
    public void writeNow(byte[] frame) throws IOException { Protocol.write(out, frame); out.flush(); }

    private void readLoop() {
        try {
            while (!closed) {
                byte[] f = Protocol.read(in);
                lastRecv = System.currentTimeMillis();
                inbox.add(f);
                if (inbox.size() > 4096) throw new IOException("забагато кадрів без обробки");
            }
        } catch (IOException e) { fail(e.getMessage() == null ? "з'єднання розірвано" : e.getMessage()); }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] f = outbox.take();
                if (f == STOP) break;
                queued.addAndGet(-f.length);
                Protocol.write(out, f);
                if (outbox.isEmpty()) out.flush();
            }
            out.flush();
        } catch (IOException e) { fail("не вдалось надіслати: " + e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { closeSocket(); }
    }

    /** Поставити кадр у чергу на відправку. false - з'єднання закрите або одержувач не встигає (тоді воно рветься). */
    public boolean send(byte[] frame) {
        if (closed) return false;
        if (queued.addAndGet(frame.length) > MAX_QUEUED) { fail("одержувач не встигає"); return false; }
        outbox.add(frame);
        return true;
    }

    public byte[] poll() { return inbox.poll(); }
    public java.net.InetAddress address() { return socket.getInetAddress(); }
    public boolean closed() { return closed; }
    public String error() { return error; }

    private void fail(String why) { if (error == null) error = why; closed = true; closeSocket(); outbox.add(STOP); }

    /** Відправити те, що в черзі (зокрема останній кадр), і закрити. */
    public void closeAfterSend() { if (closed) return; closed = true; outbox.add(STOP); }

    private void closeSocket() { try { socket.close(); } catch (IOException ignored) {} }

    @Override public void close() { if (error == null) error = "закрито"; closed = true; outbox.add(STOP); closeSocket(); }
}
