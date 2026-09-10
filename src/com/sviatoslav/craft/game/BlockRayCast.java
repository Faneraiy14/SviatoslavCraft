package com.sviatoslav.craft.game;

import com.sviatoslav.craft.engine.world.Block;
import com.sviatoslav.craft.engine.world.World;

// РЕАЛЬНА фіча, якої НЕ було в чернетці DeepSeek (там таблиця можливостей
// стверджувала "логіка є, не прив'язана до кліку" - але жодного raycast'а
// в коді не існувало взагалі). Простий покроковий марш уздовж напрямку
// погляду (не найшвидший метод (як DDA/voxel traversal), але для дистанції
// в кілька блоків і кроку 0.05 - більш ніж досить точний і простий).
public final class BlockRayCast {
    public static final float MAX_REACH = 6.0f;
    private static final float STEP = 0.05f;

    public record Hit(int hitX, int hitY, int hitZ, int placeX, int placeY, int placeZ) {}

    public static Hit cast(float ox, float oy, float oz, float yawDeg, float pitchDeg, World world) {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);
        // Узгоджено з Camera.moveForward (sin(yaw), 0, -cos(yaw)) при pitch=0;
        // вертикальна складова - "миша вгору = дивимось вгору" (перевірено
        // логікою обробки дельти миші в SviatoslavCraft.updateGame).
        float dx = (float) (Math.sin(yaw) * Math.cos(pitch));
        float dy = (float) (-Math.sin(pitch));
        float dz = (float) (-Math.cos(yaw) * Math.cos(pitch));

        int lastAirX = (int) Math.floor(ox), lastAirY = (int) Math.floor(oy), lastAirZ = (int) Math.floor(oz);

        for (float t = 0; t < MAX_REACH; t += STEP) {
            float px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);

            Block b = world.getBlock(bx, by, bz);
            if (b != null && b.getType() != Block.Type.AIR) {
                return new Hit(bx, by, bz, lastAirX, lastAirY, lastAirZ);
            }
            lastAirX = bx; lastAirY = by; lastAirZ = bz;
        }
        return null; // нічого в межах досяжності
    }
}
