package voxelin.api;

import java.util.function.Consumer;

/**
 * Підписка на події гри. Слухач, що кидає виняток 3 рази поспіль, вимикається (гра й інші моди працюють далі).
 * Події - прості об'єкти з полями (див. voxelin.api.event), щоб їх легко було передати майбутнім скриптам як JSON.
 */
public interface Events {
    <E> void on(Class<E> type, Consumer<E> listener);
}
