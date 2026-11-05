package voxelin.api;

import java.util.List;

/**
 * Гравці в грі - хост (якщо він грає) і гості сервера. Отримати: {@code ctx.api(Players.class)} (можливість "service.players").
 * Саме цим мод-«плагін» на сервері діє на того, хто написав його команду: {@code players.teleport(ctx.source().name(), x, y, z)}.
 * Імена - без різниці великих і малих літер. Викликати з потоку світу (обробники команд і подій там і працюють).
 * false / null - такого гравця зараз нема (вийшов) або дія для нього недоступна.
 */
public interface Players {
    /** Імена тих, хто зараз у грі. */
    List<String> online();
    boolean isOnline(String name);
    /** Де гравець: блок, у якому його ноги, {x, y, z} або null (teleport(name, x, y, z) поверне його туди ж). */
    long[] position(String name);
    /** Перенести гравця (ноги - у блок x, y, z; по x/z - у центр блока). */
    boolean teleport(String name, long x, int y, long z);
    /** Написати гравцю в чат (бачить лише він; до 200 символів). */
    boolean message(String name, String text);
    /** Дати предмет у інвентар (count 1..64; невідомий предмет - false). */
    boolean give(String name, String item, int count);
    /** Відключити гостя з поясненням (хоста-гравця відключити не можна - false). */
    boolean kick(String name, String reason);
}
