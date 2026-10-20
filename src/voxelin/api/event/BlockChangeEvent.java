package voxelin.api.event;

import voxelin.api.BlockRef;
import voxelin.api.Source;
import voxelin.api.WorldAccess;

/** Перед зміною блока (ламання, постановка, команда, мод). cancel() - зміна не відбудеться. */
public final class BlockChangeEvent {
    public final WorldAccess world;
    public final Source source;
    public final long x, z;
    public final int y;
    public final BlockRef oldBlock, newBlock;
    private boolean cancelled;

    public BlockChangeEvent(WorldAccess world, Source source, long x, int y, long z, BlockRef oldBlock, BlockRef newBlock) {
        this.world = world; this.source = source; this.x = x; this.y = y; this.z = z; this.oldBlock = oldBlock; this.newBlock = newBlock;
    }

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
