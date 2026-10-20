package voxelin.core;

import voxelin.api.Events;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Події: слухач, що кидає виняток 3 рази ПОСПІЛЬ, вимикається - решта гри й інші моди працюють далі. */
public final class EventBus {
    private static final int MAX_FAILS = 3;

    private static final class L {
        final String owner; final Consumer<Object> fn; int fails; boolean dead;
        L(String owner, Consumer<Object> fn) { this.owner = owner; this.fn = fn; }
    }

    private final Map<Class<?>, List<L>> map = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <E> void on(String owner, Class<E> type, Consumer<E> listener) {
        map.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(new L(owner, (Consumer<Object>) listener));
    }

    public <E> E post(E event) {
        List<L> ls = map.get(event.getClass());
        if (ls == null) return event;
        for (L l : ls) {
            if (l.dead) continue;
            try { l.fn.accept(event); l.fails = 0; }
            catch (Throwable t) {
                if (++l.fails >= MAX_FAILS) { l.dead = true; System.out.println("[Voxelin] ⚠️ слухач " + event.getClass().getSimpleName() + " мода «" + l.owner + "» вимкнено після " + MAX_FAILS + " помилок поспіль: " + t); }
                else System.out.println("[Voxelin] ⚠️ мод «" + l.owner + "», подія " + event.getClass().getSimpleName() + ": " + t);
            }
        }
        return event;
    }

    public boolean has(Class<?> type) { List<L> ls = map.get(type); return ls != null && !ls.isEmpty(); }

    /** Обгортка для конкретного мода (owner у повідомленнях). */
    public Events forMod(String modId) {
        return new Events() { public <E> void on(Class<E> type, Consumer<E> l) { EventBus.this.on(modId, type, l); } };
    }
}
