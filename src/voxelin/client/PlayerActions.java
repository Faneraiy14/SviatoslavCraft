package voxelin.client;

import java.util.List;

/**
 * Усе, що гравець робить зі світом, клієнт робить ЛИШЕ через цей інтерфейс. В одиночній грі (LocalActions) виклики
 * одразу йдуть у ядро; для мультиплеєра з'явиться реалізація, що надсилає ті самі дії на сервер, - решта клієнта не зміниться.
 */
public interface PlayerActions {
    /** Зламати блок інструментом tool (назва предмета або null); drops - чи випадає дроп (у творчому - ні). false - не можна (незламний, подія скасувала, чанк не завантажено). */
    boolean breakBlock(long x, int y, long z, String tool, boolean drops);
    /** Поставити блок за назвою з вибраного слота. */
    boolean placeBlock(long x, int y, long z, String block);
    /** Виконати команду; відповіді для показу в чаті. */
    List<String> command(String line, long[] origin);
}
