package voxelin.core;

import voxelin.api.Commands;
import voxelin.api.Source;
import voxelin.api.WorldAccess;

import java.util.*;

/** Реєстр і виконання команд. Рядок "/fill ~ ~ ~ 10 5 10 voxelin:stone" -> обробник з аргументами. */
public final class CommandRegistry {
    public static final class Entry {
        public final String name, usage, owner; public final int permission; final Commands.Handler handler;
        Entry(String name, String usage, String owner, int permission, Commands.Handler handler) { this.name = name; this.usage = usage; this.owner = owner; this.permission = permission; this.handler = handler; }
    }

    private final Map<String, Entry> map = new TreeMap<>();

    public synchronized void register(String owner, String name, String usage, int permission, Commands.Handler h) {
        String n = name.startsWith("/") ? name.substring(1) : name;
        if (!n.matches("[a-z0-9_]+")) throw new IllegalArgumentException("погана назва команди: " + name);
        if (map.containsKey(n)) throw new IllegalArgumentException("команда вже є: /" + n + " (" + map.get(n).owner + ")");
        map.put(n, new Entry(n, usage, owner, permission, h));
    }

    public Commands forMod(String modId) {
        return (name, usage, perm, h) -> register(modId, name, usage, perm, h);
    }

    public Collection<Entry> all() { return Collections.unmodifiableCollection(map.values()); }

    /** Виконати. Повертає повідомлення для чату/консолі (помилка теж повідомлення, а не виняток). */
    public List<String> execute(Source src, WorldAccess world, long[] origin, String line) {
        List<String> out = new ArrayList<>();
        String s = line.trim();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.isEmpty()) return out;
        List<String> parts = new ArrayList<>(Arrays.asList(s.split("\\s+")));
        Entry e = map.get(parts.remove(0).toLowerCase(Locale.ROOT));
        if (e == null) { out.add("Невідома команда. /help - список."); return out; }
        if (src.permission() < e.permission) { out.add("Недостатньо прав для /" + e.name); return out; }
        final List<String> args = Collections.unmodifiableList(parts);
        Commands.Context ctx = new Commands.Context() {
            public Source source() { return src; }
            public WorldAccess world() { return world; }
            public List<String> args() { return args; }
            public long[] origin() { return origin == null ? new long[3] : origin.clone(); }
            public void reply(String m) { out.add(m); }
        };
        try { e.handler.run(ctx); }
        catch (Commands.CommandException ex) { out.add(ex.getMessage()); out.add("Використання: /" + e.name + " " + e.usage); }
        catch (Throwable t) { out.add("Помилка команди /" + e.name + ": " + t); }
        return out;
    }

    /** Координата з підтримкою ~ (відносно origin). Приймає цілі long (також у вигляді 1e12 не підтримується - лише цифри). */
    public static long coord(String a, long origin) throws Commands.CommandException {
        try {
            if (a.startsWith("~")) return Math.addExact(origin, a.length() == 1 ? 0 : Long.parseLong(a.substring(1)));
            return Long.parseLong(a);
        } catch (NumberFormatException | ArithmeticException e) { throw new Commands.CommandException("Не число (або надто велике): " + a); }
    }
}
