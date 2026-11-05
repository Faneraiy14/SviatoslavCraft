package voxelin.loader;

import java.util.*;
import java.util.function.Function;

/**
 * Сервіси API (ctx.api(Клас)): інтерфейс з voxelin.api -> фабрика, що отримує id мода (кожен мод - свій екземпляр, свої права).
 * Нова частина API = новий інтерфейс у voxelin.api + один рядок provide(...) у Game + рядок у VoxelinApi.FEATURES.
 * Ядро гри й ModContext при цьому не змінюються.
 */
public final class ServiceRegistry {
    private final Map<Class<?>, Function<String, ?>> map = new LinkedHashMap<>();

    public synchronized <T> void provide(Class<T> api, Function<String, ? extends T> factory) {
        if (!api.isInterface() || !api.getName().startsWith("voxelin.api.")) throw new IllegalArgumentException("сервіс - інтерфейс з voxelin.api: " + api.getName());
        if (map.putIfAbsent(api, factory) != null) throw new IllegalStateException("сервіс уже є: " + api.getName());
    }

    /** Екземпляр сервісу для мода або null. */
    public synchronized <T> T get(Class<T> api, String modId) {
        Function<String, ?> f = map.get(api);
        return f == null ? null : api.cast(f.apply(modId));
    }

    public synchronized Set<Class<?>> all() { return Collections.unmodifiableSet(new LinkedHashSet<>(map.keySet())); }
}
