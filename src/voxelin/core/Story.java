package voxelin.core;

/**
 * Записки Сторожа Шва: лежать у скринях його покинутих стоянок у Тихих пустищах (структура keeper_camp).
 * Кожна прочитана записка (ПКМ) відкриває НАСТУПНИЙ ще не відкритий запис серії voxelin:keeper_note_1..N - тож історія
 * читається по порядку, хоч би в якій стоянці знайшлась записка. Абзаци, що починаються з «~», журнал малює перекресленими:
 * це не Сторож їх писав (гра ніколи не каже, хто).
 */
public final class Story {
    public static final String NOTE = "voxelin:keeper_note", PREFIX = "voxelin:keeper_note_";

    private Story() {}

    /** Скільки записок у серії (зареєстровані записи keeper_note_1, _2, ... без пропусків). */
    public static int notes(RecordRegistry reg) {
        int n = 0;
        while (reg.get(PREFIX + (n + 1)) != null) n++;
        return n;
    }

    /** Прочитати записку: відкрити наступний запис серії. Повертає його id або null - усі вже прочитано (записка не витрачається). */
    public static String read(World w) {
        int n = notes(w.records);
        for (int i = 1; i <= n; i++) {
            String id = PREFIX + i;
            if (!w.info.records.contains(id)) { w.unlockRecord(id); return id; }
        }
        return null;
    }

    /** Абзац, написаний «не Сторожем»: журнал малює його перекресленим. */
    public static boolean struck(String paragraph) { return paragraph.startsWith("~"); }
}
