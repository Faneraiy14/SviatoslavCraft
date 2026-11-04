package voxelin.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/**
 * Обмін світами: увесь світ (регіони, виміри, предмети, журнал, а також скрипти світу в &lt;світ&gt;/scripts/) - один файл .vxworld (ZIP).
 * Архів беруть у інших людей, тож імпорт недовірливий: лише відносні шляхи без «..», обмеження кількості й розміру файлів (захист від
 * ZIP-бомб), обов'язковий voxelin.world; розпаковується в тимчасову теку й лише потім стає світом. Скрипти зі світу працюють у тій самій
 * пісочниці nx, що й решта (файли - лише свої, без мережі).
 */
public final class WorldArchive {
    private WorldArchive() {}

    public static final String EXT = ".vxworld", MANIFEST = "voxelin-share.properties", ROOT = "world/";
    public static final int FORMAT = 1, MAX_ENTRIES = 100_000;
    public static final long MAX_BYTES = 2L << 30, MAX_ENTRY = 512L << 20;

    /** Записати світ у файл (атомарно; .tmp поруч). Пропускаються session.lock і тимчасові файли. Повертає кількість файлів. */
    public static int export(File worldDir, File out) throws IOException {
        if (!WorldInfo.exists(worldDir)) throw new IOException("це не світ Voxelin: " + worldDir);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(out.getPath() + ".tmp");
        int n = 0;
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            Properties p = new Properties();
            WorldInfo info = WorldInfo.load(worldDir);
            p.setProperty("format", String.valueOf(FORMAT));
            p.setProperty("name", info.name);
            p.setProperty("exported", String.valueOf(System.currentTimeMillis()));
            z.putNextEntry(new ZipEntry(MANIFEST));
            StringWriter sw = new StringWriter();
            p.store(sw, "Voxelin world");
            z.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            List<File> files = new ArrayList<>();
            collect(worldDir, files);
            String base = worldDir.getCanonicalPath() + File.separator;
            for (File f : files) {
                String rel = f.getCanonicalPath().substring(base.length()).replace(File.separatorChar, '/');
                if (rel.endsWith(".tmp") || rel.equals("session.lock")) continue;
                z.putNextEntry(new ZipEntry(ROOT + rel));
                Files.copy(f.toPath(), z);
                z.closeEntry();
                n++;
            }
        }
        Files2.replace(tmp, out);
        return n;
    }

    private static void collect(File d, List<File> out) throws IOException {
        File[] fs = d.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue; // посилання за межі світу не пакуємо
            if (f.isDirectory()) collect(f, out); else if (f.isFile()) out.add(f);
        }
    }

    /** Назва світу з архіву (з manifest; без нього - з імені файла). */
    public static String nameOf(File archive) {
        try (ZipFile z = new ZipFile(archive)) {
            ZipEntry e = z.getEntry(MANIFEST);
            if (e != null && e.getSize() < 64 * 1024) {
                Properties p = new Properties();
                try (Reader r = new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8)) { p.load(r); }
                String n = p.getProperty("name", "").trim();
                if (WorldInfo.isValidName(n)) return n;
            }
        } catch (IOException | IllegalArgumentException ignored) { /* нижче - з імені файла */ }
        String f = archive.getName();
        if (f.endsWith(EXT)) f = f.substring(0, f.length() - EXT.length());
        f = f.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        if (f.length() > 16) f = f.substring(0, 16).trim();
        return WorldInfo.isValidName(f) ? f : "Імпорт";
    }

    /** Вільна назва в saves/: «Назва», «Назва 2», ... (до 16 символів). */
    public static String freeName(File savesDir, String want) {
        if (!WorldInfo.isValidName(want)) want = "Імпорт";
        if (!new File(savesDir, want).exists()) return want;
        for (int i = 2; i < 1000; i++) {
            String suffix = " " + i, base = want.length() + suffix.length() > 16 ? want.substring(0, 16 - suffix.length()).trim() : want;
            String n = base + suffix;
            if (WorldInfo.isValidName(n) && !new File(savesDir, n).exists()) return n;
        }
        throw new IllegalStateException("не знайшлось вільної назви");
    }

    /** Розпакувати архів у новий світ у savesDir. Повертає назву створеного світу. IOException - з поясненням, що не так з архівом. */
    public static String importTo(File archive, File savesDir) throws IOException {
        String name = freeName(savesDir, nameOf(archive));
        File target = new File(savesDir, name), tmp = new File(savesDir, ".import-" + System.nanoTime());
        if (!tmp.mkdirs()) throw new IOException("не вдалось створити тимчасову теку");
        try {
            unzip(archive, tmp);
            if (!WorldInfo.exists(tmp)) throw new IOException("в архіві нема світу (" + WorldInfo.FILE + ")");
            WorldInfo info;
            try { info = WorldInfo.load(tmp); } catch (RuntimeException e) { throw new IOException("пошкоджений " + WorldInfo.FILE + ": " + e.getMessage()); }
            info.name = name;
            info.save(tmp);
            if (!tmp.renameTo(target)) throw new IOException("не вдалось перемістити світ у " + target);
            return name;
        } finally {
            if (tmp.exists()) deleteTree(tmp);
        }
    }

    private static void unzip(File archive, File into) throws IOException {
        String root = into.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw new IOException("забагато файлів в архіві");
                String n = e.getName();
                if (n.equals(MANIFEST) || e.isDirectory()) continue;
                if (!n.startsWith(ROOT)) throw new IOException("чужий файл в архіві: " + n);
                String rel = n.substring(ROOT.length());
                if (!safe(rel)) throw new IOException("недопустимий шлях в архіві: " + n);
                File f = new File(into, rel);
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("шлях виходить за межі світу: " + n);
                f.getParentFile().mkdirs();
                long size = 0;
                try (OutputStream o = new FileOutputStream(f)) {
                    for (int r; (r = z.read(buf)) > 0; ) {
                        size += r; total += r;
                        if (size > MAX_ENTRY) throw new IOException("файл в архіві завеликий: " + n);
                        if (total > MAX_BYTES) throw new IOException("архів розпаковується в понад " + (MAX_BYTES >> 30) + " ГБ");
                        o.write(buf, 0, r);
                    }
                }
            }
        } catch (ZipException e) { throw new IOException("не ZIP або пошкоджений: " + e.getMessage()); }
    }

    /** Відносний шлях з / без «..», «.», порожніх частин, керувальних символів, двокрапок і зворотних похилих. */
    static boolean safe(String rel) {
        if (rel.isEmpty() || rel.length() > 400 || rel.startsWith("/") || rel.contains("\\") || rel.contains(":")) return false;
        for (String part : rel.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (char c : part.toCharArray()) if (c < 32) return false;
        }
        return true;
    }

    public static void deleteTree(File f) {
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) { File[] fs = f.listFiles(); if (fs != null) for (File c : fs) deleteTree(c); }
        f.delete();
    }

    /** Файли .vxworld у теці (новіші вгорі). */
    public static List<File> list(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(EXT) && new File(d, n).isFile());
        List<File> out = new ArrayList<>(fs == null ? List.of() : Arrays.asList(fs));
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }
}
