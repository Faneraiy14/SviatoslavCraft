package voxelin.api.event;

import voxelin.api.WorldAccess;

/** Повідомлення в чат (не команда) - перед тим, як його побачать інші. cancel() - повідомлення не піде. Можливість "events.chat". */
public final class ChatEvent {
    public final WorldAccess world;
    public final String name, text;
    public final boolean guest;
    private boolean cancelled;
    public ChatEvent(WorldAccess world, String name, String text, boolean guest) { this.world = world; this.name = name; this.text = text; this.guest = guest; }
    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }
}
