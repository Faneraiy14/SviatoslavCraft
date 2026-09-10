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

            while (accumulator >= UPDATE_INTERVAL) {
                update.accept(UPDATE_INTERVAL);
                accumulator -= UPDATE_INTERVAL;
            }

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
