package voxelin.api.event;

import voxelin.api.ChunkData;

/**
 * Щойно згенерований чанк, ще до появи у світі (фоновий потік генерації!). Мод може дописати в нього блоки - руди, структури.
 * Чанк, завантажений з диска, цієї події не викликає.
 */
public final class ChunkGeneratedEvent {
    public final ChunkData chunk;
    public final long seed;
    public ChunkGeneratedEvent(ChunkData chunk, long seed) { this.chunk = chunk; this.seed = seed; }
}
