package com.sviatoslav.craft.engine.core;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class GameLoop {
    private static final int TPS = 60;
    private static final double UPDATE_INTERVAL = 1.0 / TPS;
    private int fps = 0;
    private int frameCount = 0;
    private double lastFpsTime = 0;

    public void start(Consumer<Double> update, Runnable render, BooleanSupplier shouldClose) {
        double lastTime = System.nanoTime() / 1e9;
        double accumulator = 0;

        while (!shouldClose.getAsBoolean()) {
            double currentTime = System.nanoTime() / 1e9;
            double deltaTime = currentTime - lastTime;
            lastTime = currentTime;
            accumulator += deltaTime;

            // РЕАЛЬНИЙ РИЗИК ("спіраль смерті" фіксованого кроку): якщо
            // ОДИН кадр раптом загальмував (напр. через важку перебудову
            // чанка), накопичувач міг вимагати одразу КІЛЬКА update()
            // поспіль "надолужити" час - а кожен update() сам коштує
            // якийсь час, тому спроба наздогнати могла ЩЕ БІЛЬШЕ
            // подовжити той самий кадр, який і так уже загальмував.
            // Обмежуємо кількість наздоганяючих кроків за кадр - решту
            // накопиченого часу просто відкидаємо (гра трохи "сповільниться"
            // замість того, щоб намагатись наздогнати ціною ще довшого
            // фрізу).
            int steps = 0;
            while (accumulator >= UPDATE_INTERVAL && steps < 5) {
                update.accept(UPDATE_INTERVAL);
                accumulator -= UPDATE_INTERVAL;
                steps++;
            }
            if (steps == 5) accumulator = 0;

            render.run();

            frameCount++;
            if (currentTime - lastFpsTime >= 1.0) {
                fps = frameCount;
                frameCount = 0;
                lastFpsTime = currentTime;
            }
        }
    }

    public int getFPS() { return fps; }
}
