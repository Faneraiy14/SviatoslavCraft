package voxelin.loader;

import voxelin.api.Players;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Сервіс Players для модів: збирає гравців з кількох джерел - хост-гравець (PlayScreen) і гості (NetServer).
 * Хто відкриває світ, той додає своє джерело (attach) і прибирає його при закритті (detach). Порожній - нікого нема.
 */
public final class PlayersHub implements Players {
    private final List<Players> sources = new CopyOnWriteArrayList<>();

    public void attach(Players p) { if (p != null && !sources.contains(p)) sources.add(p); }
    public void detach(Players p) { sources.remove(p); }

    private Players who(String name) {
        if (name == null) return null;
        for (Players p : sources) if (p.isOnline(name)) return p;
        return null;
    }

    @Override public List<String> online() { List<String> out = new ArrayList<>(); for (Players p : sources) out.addAll(p.online()); return out; }
    @Override public boolean isOnline(String name) { return who(name) != null; }
    @Override public long[] position(String name) { Players p = who(name); return p == null ? null : p.position(name); }
    @Override public boolean teleport(String name, long x, int y, long z) { Players p = who(name); return p != null && p.teleport(name, x, y, z); }
    @Override public boolean message(String name, String text) { Players p = who(name); return p != null && text != null && p.message(name, text.length() > 200 ? text.substring(0, 200) : text); }
    @Override public boolean give(String name, String item, int count) { Players p = who(name); return p != null && item != null && count >= 1 && count <= 64 && p.give(name, item, count); }
    @Override public boolean kick(String name, String reason) { Players p = who(name); return p != null && p.kick(name, reason == null ? "" : reason); }
}
