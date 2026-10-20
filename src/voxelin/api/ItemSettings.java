package voxelin.api;

/**
 * Опис предмета як даних. Будівник: {@code new ItemSettings().tool(ToolKind.PICKAXE, 2, 4f).durability(132).color(0x8A8A8A)}.
 * Кожен блок автоматично має предмет-блок з тією ж назвою (його ставлять правою кнопкою); окремі предмети - палиці, руди, інструменти, їжа.
 */
public final class ItemSettings {
    int maxStack = 64, durability, tier;
    float speed = 1f;
    ToolKind tool = ToolKind.NONE;
    int food, color = 0xB0B0B0;
    String texture, placesBlock, title;
    float attack = 1f;
    ArmorSlot armorSlot;
    int armorPoints, armorTier;

    /** Скільки в стопці (1..64). Інструменти й усе з міцністю - завжди 1. */
    public ItemSettings maxStack(int n) { maxStack = Math.max(1, Math.min(64, n)); return this; }
    /** Скільки ударів витримує (0 - не ламається). */
    public ItemSettings durability(int d) { durability = Math.max(0, d); if (d > 0) maxStack = 1; return this; }
    /** Інструмент: тип, рівень (дерево 1, камінь 2, залізо 3, алмаз 4) і множник швидкості ламання. */
    public ItemSettings tool(ToolKind kind, int tier, float speed) { tool = kind == null ? ToolKind.NONE : kind; this.tier = Math.max(0, tier); this.speed = Float.isNaN(speed) ? 1f : Math.max(1f, Math.min(100f, speed)); return this; }
    /** Їжа: скільки очок голоду додає (1..20). */
    public ItemSettings food(int points) { food = Math.max(0, Math.min(20, points)); return this; }
    /** Шкода предмета в руці (рука - 1). */
    public ItemSettings attack(float dmg) { attack = Float.isNaN(dmg) ? 1f : Math.max(0f, Math.min(1000f, dmg)); return this; }
    /** Броня: слот, очки захисту (сума 20 на повний комплект = -80% шкоди) і рівень матеріалу (алмаз 4 - потрібен для входу в Енд). */
    public ItemSettings armor(ArmorSlot slot, int points, int tier) { armorSlot = slot; armorPoints = Math.max(0, Math.min(20, points)); armorTier = Math.max(0, tier); if (slot != null) maxStack = 1; return this; }
    public ItemSettings color(int rgb) { color = rgb & 0xFFFFFF; return this; }
    /** Назва текстури з реєстру текстур (іконка); без неї - колір. */
    public ItemSettings texture(String name) { texture = name; return this; }
    /** Предмет ставить цей блок (назва «мод:блок», без двокрапки - блок цього ж мода). */
    public ItemSettings placesBlock(String block) { placesBlock = block; return this; }

    /** Назва для людей (підказка в інвентарі), напр. «Залізна кирка». Без неї - з назви предмета. */
    public ItemSettings title(String t) { title = t; return this; }

    public ItemSettings qualified(String modId) {
        ItemSettings s = new ItemSettings();
        s.title = title; s.attack = attack; s.armorSlot = armorSlot; s.armorPoints = armorPoints; s.armorTier = armorTier; s.maxStack = maxStack; s.durability = durability; s.tier = tier; s.speed = speed; s.tool = tool; s.food = food; s.color = color;
        s.texture = texture == null || texture.indexOf(':') >= 0 ? texture : modId + ":" + texture;
        s.placesBlock = placesBlock == null || placesBlock.indexOf(':') >= 0 ? placesBlock : modId + ":" + placesBlock;
        return s;
    }

    public int maxStackValue() { return maxStack; }
    public int durabilityValue() { return durability; }
    public int tierValue() { return tier; }
    public float speedValue() { return speed; }
    public ToolKind toolKind() { return tool; }
    public int foodValue() { return food; }
    public int colorValue() { return color; }
    public ArmorSlot armorSlotValue() { return armorSlot; }
    public int armorPointsValue() { return armorPoints; }
    public int armorTierValue() { return armorTier; }
    public float attackValue() { return attack; }
    public String titleText() { return title; }
    public String textureName() { return texture; }
    public String placesBlockName() { return placesBlock; }
}
