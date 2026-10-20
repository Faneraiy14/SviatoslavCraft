package voxelin.client;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/** Знімок кадру (dev: --screenshot файл.png): читає кадр до swapBuffers і пише PNG. */
final class Screenshot {
    private Screenshot() {}

    static void save(Window w, String path) {
        try {
            int fw = w.fbWidth(), fh = w.fbHeight();
            ByteBuffer buf = BufferUtils.createByteBuffer(fw * fh * 3);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, fw, fh, GL_RGB, GL_UNSIGNED_BYTE, buf);
            BufferedImage img = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < fh; y++) for (int x = 0; x < fw; x++) {
                int i = ((fh - 1 - y) * fw + x) * 3;
                img.setRGB(x, y, ((buf.get(i) & 255) << 16) | ((buf.get(i + 1) & 255) << 8) | (buf.get(i + 2) & 255));
            }
            File f = new File(path);
            if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
            ImageIO.write(img, "png", f);
            System.out.println("[Voxelin] знімок: " + f.getAbsolutePath() + " (" + fw + "x" + fh + ")");
        } catch (Throwable t) { System.out.println("[Voxelin] ⚠️ знімок не вдався: " + t); }
    }
}
