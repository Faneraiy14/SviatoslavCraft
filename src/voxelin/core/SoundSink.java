package voxelin.core;

/** Куди світ повідомляє про звукові події (клієнт - у рушій звуку). Ядро звук не грає, лише називає подію й місце. */
public interface SoundSink {
    SoundSink NONE = (id, at, volume, pitch) -> {};

    /** id - "voxelin:mob.zombie.hurt" тощо; at - де (null - без позиції). */
    void play(String id, Pos at, float volume, float pitch);
}
