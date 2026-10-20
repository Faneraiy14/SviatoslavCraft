package voxelin.core;

import voxelin.api.Source;

/** Стандартні джерела дій. */
public final class Sources {
    private Sources() {}

    public static Source of(String name, Source.Kind kind, int permission, int budget) {
        return new Source() {
            public String name() { return name; }
            public Kind kind() { return kind; }
            public int permission() { return permission; }
            public int blockBudgetPerTick() { return budget; }
            @Override public String toString() { return kind + ":" + name; }
        };
    }

    /** Гравець одиночної гри - господар світу (повні права, як оператор). */
    public static Source player(String name) { return of(name, Source.Kind.PLAYER, 4, 1_000_000); }
    public static Source console() { return of("console", Source.Kind.CONSOLE, 4, Integer.MAX_VALUE); }
    /** Мод: генерація й логіка без ліміту, але з іменем для логів. */
    public static Source mod(String modId) { return of(modId, Source.Kind.MOD, 4, Integer.MAX_VALUE); }
    /** Ядро гри (завантаження, генерація) - не обмежується. */
    public static final Source SYSTEM = of("system", Source.Kind.CONSOLE, 4, Integer.MAX_VALUE);
}
