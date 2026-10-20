package voxelin.core;

import voxelin.api.BlockSettings;

import java.util.*;

/**
 * Реєстр блоків. id - індекс у масиві цього запуску (0 = повітря). У файлах світу зберігаються НАЗВИ (WorldInfo.blockMap),
 * тож порядок реєстрації модів може змінюватись між запусками без псування світів.
 */
public final class BlockRegistry {
    public static final String AIR = "voxelin:air";
    /** Блок, чию назву збережено у світі, але мода з ним зараз нема (пурпуровий, щоб було видно). */
    public static final String UNKNOWN = "voxelin:unknown";
    private final List<BlockType> types = new ArrayList<>();
    private final Map<String, BlockType> byName = new HashMap<>();
    private BlockType[] array = new BlockType[0];
    private boolean frozen;

    public BlockRegistry() {
        register(AIR, new BlockSettings().solid(false).opaque(false).color(0));
        register(UNKNOWN, new BlockSettings().color(0xFF00FF));
    }

    public BlockType unknown() { return array[1]; }

    public synchronized BlockType register(String fullName, BlockSettings s) {
        if (frozen) throw new IllegalStateException("реєстр блоків уже закритий: " + fullName);
        if (!fullName.matches("[a-z0-9_]+:[a-z0-9_/]+")) throw new IllegalArgumentException("погана назва блока: " + fullName + " (треба mod:name, малі латинські літери, цифри, _)");
        if (byName.containsKey(fullName)) throw new IllegalArgumentException("блок уже зареєстровано: " + fullName);
        if (types.size() >= Short.MAX_VALUE) throw new IllegalStateException("забагато блоків");
        BlockType t = new BlockType(types.size(), fullName, s);
        types.add(t);
        byName.put(fullName, t);
        array = types.toArray(new BlockType[0]);
        return t;
    }

    public void freeze() { frozen = true; }
    public boolean isFrozen() { return frozen; }
    public BlockType get(int id) { return id >= 0 && id < array.length ? array[id] : array[0]; }
    public BlockType byName(String name) { return byName.get(name); }
    public BlockType air() { return array[0]; }
    public int size() { return array.length; }
    /** Масив за id - для гарячих циклів рендера й фізики. */
    public BlockType[] all() { return array; }
}
