package voxelin.core;

import voxelin.api.BlockRef;
import voxelin.api.BlockSettings;

/** Зареєстрований блок: незмінні властивості, які рушій читає щокадру (тому прості поля, без гетерів-обгорток). */
public final class BlockType implements BlockRef {
    public final int id;
    public final String name;
    public final int color;
    public final boolean solid, opaque, liquid, translucent;
    public final int light;
    /** Скільки рівнів світла гасить (15 - непрозорий). */
    public final int absorb;
    public final float hardness;
    public final String texTop, texSide, texBottom;

    BlockType(int id, String name, BlockSettings s) {
        this.id = id; this.name = name;
        color = s.color(); solid = s.isSolid(); opaque = s.isOpaque(); liquid = s.isLiquid(); translucent = s.isTranslucent(); light = s.lightLevel(); hardness = s.hardnessValue();
        absorb = s.absorbValue() >= 0 ? s.absorbValue() : (s.isOpaque() ? 15 : (s.isLiquid() ? 2 : (s.isSolid() && !s.isTranslucent() ? 1 : 0)));
        texTop = s.textureTop(); texSide = s.textureSide(); texBottom = s.textureBottom();
    }

    @Override public String name() { return name; }
    @Override public int id() { return id; }
    public boolean isAir() { return id == 0; }
    @Override public String toString() { return name; }
}
