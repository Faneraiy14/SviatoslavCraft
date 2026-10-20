package voxelin.api;

/** Здоров'я й голод гравця для команд і модів. Значення в «половинках сердець»: 20 = десять сердець. */
public interface Vitality {
    double health();
    double maxHealth();
    double hunger();
    boolean dead();
    /** Завдати шкоди (минає невразливість). Смерть - коли здоров'я доходить до 0. */
    void damage(double amount);
    void heal(double amount);
    /** Нагодувати: додає голод (до 20) і трохи насиченості. */
    void feed(double amount);
}
