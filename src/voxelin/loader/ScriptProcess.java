package voxelin.loader;

import voxelin.core.Json;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * Один скрипт NyxilumLang = окремий процес `nx main.nx` у пісочниці (NX_SANDBOX=1: файли лише в своїй теці, без мережі,
 * процесів і змінних середовища). Обмін - JSON по рядку: гра пише події в stdin, скрипт друкує операції в stdout.
 * Рядок stdout, що не починається з '{', - звичайний print(): іде в консоль скрипта (лог гри).
 * Читання й запис - у власних потоках, щоб завислий скрипт не зупинив гру; рішення «вбити» приймає ScriptHost.
 */
final class ScriptProcess {
    static final int OPS_CAP = 10_000, OUT_CAP = 4096, LINE_CAP = 64 * 1024, CONSOLE_LINES = 40;

    final String id;
    final File dir;
    private final Process proc;
    private final BlockingQueue<Map<String, Object>> ops = new LinkedBlockingQueue<>(OPS_CAP);
    private final BlockingQueue<String> out = new LinkedBlockingQueue<>(OUT_CAP);
    private final Deque<String> console = new ArrayDeque<>();
    /** Причина, з якої скрипт треба зупинити (переповнення тощо); null - усе гаразд. Пишуть потоки, читає ScriptHost. */
    volatile String fault;
    /** Коли почався поточний запис у stdin скрипта (System.nanoTime); 0 - не пише. Довгий запис = скрипт не читає події. */
    private volatile long writingSince;
    private volatile boolean stopped;

    ScriptProcess(String id, File dir, List<String> command, Map<String, String> env) throws IOException {
        this.id = id; this.dir = dir;
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir);
        pb.environment().keySet().removeIf(k -> k.startsWith("NX_"));
        pb.environment().putAll(env);
        proc = pb.start();
        thread("out", this::readOut);
        thread("err", this::readErr);
        thread("in", this::writeIn);
    }

    private void thread(String what, Runnable r) {
        Thread t = new Thread(r, "script-" + id + "-" + what);
        t.setDaemon(true);
        t.start();
    }

    private void readOut() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = readLine(r)) != null; ) {
                String s = line.strip();
                if (s.isEmpty()) continue;
                if (!s.startsWith("{")) { print(s); continue; }
                Object o;
                try { o = Json.parse(s); }
                catch (RuntimeException e) { print("⚠ не JSON: " + cut(s, 120)); continue; }
                if (!(o instanceof Map<?, ?> m)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> op = (Map<String, Object>) m;
                if (!ops.offer(op)) { fault = "забагато операцій (понад " + OPS_CAP + " у черзі)"; return; }
            }
        } catch (IOException ignored) { /* процес завершився */ }
    }

    /** Рядок не довший за LINE_CAP: довший - помилка скрипта, а не причина гри з'їсти всю пам'ять. */
    private String readLine(BufferedReader r) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = r.read()) >= 0; ) {
            if (c == '\n') return b.toString();
            if (b.length() >= LINE_CAP) { fault = "надто довгий рядок виводу (понад " + LINE_CAP / 1024 + " КБ)"; return null; }
            b.append((char) c);
        }
        return b.length() > 0 ? b.toString() : null;
    }

    private void readErr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = readLine(r)) != null; ) if (!line.isBlank()) print(line.strip());
        } catch (IOException ignored) { /* процес завершився */ }
    }

    private void writeIn() {
        try (Writer w = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!stopped) {
                String m = out.poll(200, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                writingSince = System.nanoTime();
                w.write(m); w.write('\n');
                if (out.isEmpty()) w.flush();
                writingSince = 0;
            }
        } catch (IOException | InterruptedException ignored) { /* процес завершився */ }
        writingSince = 0;
    }

    private void print(String s) {
        s = cut(s, 300);
        System.out.println("[script:" + id + "] " + s);
        synchronized (console) { console.addLast(s); while (console.size() > CONSOLE_LINES) console.removeFirst(); }
    }

    static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** Надіслати подію скрипту. Черга повна - скрипт не встигає читати. */
    void send(Map<String, Object> event) {
        if (stopped) return;
        if (!out.offer(Json.write(event))) fault = "не встигає читати події (черга " + OUT_CAP + ")";
    }

    Map<String, Object> poll() { return ops.poll(); }

    Map<String, Object> poll(long micros) throws InterruptedException { return ops.poll(micros, TimeUnit.MICROSECONDS); }

    /** Скільки мілісекунд скрипт не забирає подію, яку йому пишуть (0 - не завис). */
    long stuckMillis() { long s = writingSince; return s == 0 ? 0 : (System.nanoTime() - s) / 1_000_000; }

    boolean alive() { return proc.isAlive(); }

    Integer exitCode() { return proc.isAlive() ? null : proc.exitValue(); }

    long pid() { return proc.pid(); }

    /** Пам'ять процесу в МБ (RSS) - лише Linux (/proc); деінде -1. */
    long memoryMb() {
        File f = new File("/proc/" + proc.pid() + "/status");
        if (!f.isFile()) return -1;
        try {
            for (String l : Files.readAllLines(f.toPath())) if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
        } catch (IOException | NumberFormatException ignored) { /* процес щойно завершився */ }
        return -1;
    }

    List<String> console() { synchronized (console) { return new ArrayList<>(console); } }

    /** Закрити stdin (скрипт бачить кінець вводу й може зберегти свої дані), почекати graceMs, потім завершити примусово. */
    void stop(long graceMs) {
        stopped = true;
        try { proc.getOutputStream().close(); } catch (IOException ignored) { /* уже закрито */ }
        try { if (graceMs > 0) proc.waitFor(graceMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
    }
}
