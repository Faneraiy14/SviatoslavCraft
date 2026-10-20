package voxelin.api;

import java.util.List;

/** Команди - дані: назва, підказка використання, потрібні права й обробник. Однаково доступні гравцю, модам і (пізніше) скриптам. */
public interface Commands {
    interface Handler { void run(Context ctx) throws CommandException; }

    interface Context {
        Source source();
        WorldAccess world();
        List<String> args();
        /** Позиція джерела {x, y, z} у блоках (для ~ у координатах); для консолі - 0,0,0. */
        long[] origin();
        void reply(String message);
    }

    final class CommandException extends Exception {
        public CommandException(String message) { super(message); }
    }

    void register(String name, String usage, int permission, Handler handler);
}
