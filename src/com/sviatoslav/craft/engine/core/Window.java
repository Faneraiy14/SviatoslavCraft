package com.sviatoslav.craft.engine.core;

public interface Window {
    void create(String title, int width, int height);
    void swapBuffers();
    boolean shouldClose();
    void destroy();
    long getHandle();
    void setMouseGrabbed(boolean grabbed);
}
