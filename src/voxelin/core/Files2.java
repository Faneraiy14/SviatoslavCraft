package voxelin.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;

/** Атомарна заміна файлу: tmp -> ціль (на Windows rename не перезаписує - тому Files.move з REPLACE_EXISTING). */
public final class Files2 {
    private Files2() {}
    public static void replace(File tmp, File target) throws IOException {
        try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
}
