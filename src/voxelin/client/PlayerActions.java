package voxelin.client;

import java.util.List;

/**
 * Усе, що гравець робить зі світом, клієнт робить ЛИШЕ через цей інтерфейс. В одиночній грі (LocalActions) виклики
 * одразу йдуть у ядро; для мультиплеєра з'явиться реалізація, що надсилає ті самі дії на сервер, - решта клієнта не зміниться.
 */
public interface PlayerActions {
    /** Зламати блок; false - не можна (незламний, подія скасувала, чанк не завантажено). */
    boolean breakBlock(int x, int y, int z);
    /** Поставити блок за назвою з вибраного слота. */
    boolean placeBlock(int x, int y, int z, String block);
    /** Виконати команду; відповіді для показу в чаті. */
    List<String> command(String line, int[] origin);
}
