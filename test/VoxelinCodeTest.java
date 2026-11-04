import voxelin.client.code.CodeBuffer;
import voxelin.client.code.Highlight;
import voxelin.loader.ScriptHost;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/** Вікно коду без вікна: редагування (відступи, виділення, буфер обміну, скасування), підсвітка, збереження скрипта на диск. */
public class VoxelinCodeTest {
    static int n;
    static void check(boolean ok, String what) { n++; if (!ok) { System.out.println("FAIL: " + what); System.exit(1); } }
    static final List<String> clip = new ArrayList<>();
    static boolean key(CodeBuffer b, int k, boolean ctrl, boolean shift) { return b.key(k, ctrl, shift, clip.isEmpty() ? "" : clip.get(clip.size() - 1), clip::add); }
    static boolean key(CodeBuffer b, int k) { return key(b, k, false, false); }

    public static void main(String[] a) throws Exception {
        // друк, Enter з відступом, автозакривна логіка «}»
        CodeBuffer b = new CodeBuffer("");
        b.type("func main() {");
        key(b, GLFW_KEY_ENTER);
        check(b.line(1).equals("    ") && b.col() == 4, "Enter після { - відступ 4: '" + b.line(1) + "'");
        b.type("say(\"hi\")");
        key(b, GLFW_KEY_ENTER);
        check(b.line(2).equals("    "), "Enter зберігає відступ");
        b.type("}");
        check(b.line(2).equals("}"), "} вирівнюється під відкривну: '" + b.line(2) + "'");
        check(b.text().equals("func main() {\n    say(\"hi\")\n}") && b.modified(), "текст: " + b.text());
        // {|} -> три рядки
        CodeBuffer br = new CodeBuffer("if (x) {}");
        br.moveTo(0, 8, false);
        key(br, GLFW_KEY_ENTER);
        check(br.text().equals("if (x) {\n    \n}") && br.row() == 1 && br.col() == 4, "Enter між {} розгортає блок: " + br.text().replace("\n", "|"));
        // Backspace у відступі - до кратного 4, на початку рядка - злиття
        CodeBuffer bs = new CodeBuffer("        x");
        bs.moveTo(0, 8, false);
        key(bs, GLFW_KEY_BACKSPACE);
        check(bs.line(0).equals("    x") && bs.col() == 4, "Backspace у відступі стирає 4");
        CodeBuffer j = new CodeBuffer("ab\ncd");
        j.moveTo(1, 0, false);
        key(j, GLFW_KEY_BACKSPACE);
        check(j.text().equals("abcd") && j.col() == 2, "Backspace на початку рядка зливає рядки");
        key(j, GLFW_KEY_BACKSPACE, true, false);
        check(j.text().equals("cd") && j.col() == 0, "Ctrl+Backspace стирає слово: " + j.text());
        // скасування: друк поспіль - один крок
        CodeBuffer u = new CodeBuffer("x");
        u.moveTo(0, 1, false);
        u.type("yz");
        key(u, GLFW_KEY_ENTER);
        u.type("w");
        key(u, GLFW_KEY_Z, true, false);
        check(u.text().equals("xyz\n"), "Ctrl+Z скасовує останній друк: " + u.text().replace("\n", "|"));
        key(u, GLFW_KEY_Z, true, false);
        check(u.text().equals("xyz"), "Ctrl+Z - Enter");
        key(u, GLFW_KEY_Z, true, false);
        check(u.text().equals("x"), "Ctrl+Z - друк одним кроком");
        key(u, GLFW_KEY_Y, true, false);
        check(u.text().equals("xyz"), "Ctrl+Y повертає");
        // виділення Shift+стрілки, копіювання, вставка з табами й \r\n
        CodeBuffer s = new CodeBuffer("hello world\nsecond");
        s.moveTo(0, 6, false);
        key(s, GLFW_KEY_END, false, true);
        check("world".equals(s.selectedText()), "Shift+End виділяє: " + s.selectedText());
        key(s, GLFW_KEY_C, true, false);
        check(clip.get(clip.size() - 1).equals("world"), "Ctrl+C");
        key(s, GLFW_KEY_DOWN, false, true);
        check(s.selectedText().equals("world\nsecond"), "виділення через рядок");
        key(s, GLFW_KEY_X, true, false);
        check(s.text().equals("hello ") && clip.get(clip.size() - 1).equals("world\nsecond"), "Ctrl+X: " + s.text());
        clip.add("a\r\n\tb");
        key(s, GLFW_KEY_V, true, false);
        check(s.text().equals("hello a\n    b") && s.row() == 1 && s.col() == 5, "Ctrl+V: \\r\\n і таби нормалізовано: " + s.text().replace("\n", "|"));
        // Ctrl+A і друк замінює все
        key(s, GLFW_KEY_A, true, false);
        s.type("z");
        check(s.text().equals("z"), "Ctrl+A + друк");
        // Tab / Shift+Tab по виділених рядках, коментування
        CodeBuffer t = new CodeBuffer("a\nb\nc");
        t.moveTo(0, 0, false); t.moveTo(1, 1, true);
        key(t, GLFW_KEY_TAB);
        check(t.text().equals("    a\n    b\nc"), "Tab відступає виділені рядки: " + t.text().replace("\n", "|"));
        key(t, GLFW_KEY_TAB, false, true);
        check(t.text().equals("a\nb\nc"), "Shift+Tab повертає");
        key(t, GLFW_KEY_SLASH, true, false);
        check(t.text().equals("// a\n// b\nc"), "Ctrl+/ коментує: " + t.text().replace("\n", "|"));
        key(t, GLFW_KEY_SLASH, true, false);
        check(t.text().equals("a\nb\nc"), "Ctrl+/ ще раз знімає коментар");
        CodeBuffer tt = new CodeBuffer("ab");
        tt.moveTo(0, 1, false);
        key(tt, GLFW_KEY_TAB);
        check(tt.text().equals("a   b"), "Tab без виділення - до позиції кратної 4");
        // рух: Home до першого символу, Ctrl+стрілки, вгору/вниз пам'ятає стовпець
        CodeBuffer m = new CodeBuffer("    foo bar\nx\n    longer line");
        m.moveTo(0, 11, false);
        key(m, GLFW_KEY_HOME);
        check(m.col() == 4, "Home - до першого символу");
        key(m, GLFW_KEY_HOME);
        check(m.col() == 0, "Home вдруге - на початок");
        m.moveTo(0, 11, false);
        key(m, GLFW_KEY_LEFT, true, false);
        check(m.col() == 8, "Ctrl+Left - на початок слова");
        key(m, GLFW_KEY_DOWN); key(m, GLFW_KEY_DOWN);
        check(m.row() == 2 && m.col() == 8, "вниз через короткий рядок пам'ятає стовпець");
        key(m, GLFW_KEY_D, true, false);
        check(m.lineCount() == 4 && m.line(3).equals("    longer line"), "Ctrl+D дублює рядок");
        // межі: порожній буфер, рух за краї, ліміт розміру
        CodeBuffer e = new CodeBuffer(null);
        for (int k : new int[]{GLFW_KEY_LEFT, GLFW_KEY_UP, GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE, GLFW_KEY_PAGE_DOWN, GLFW_KEY_END}) key(e, k);
        check(e.text().isEmpty() && e.row() == 0 && e.col() == 0, "порожній буфер витримує все");
        e.type("x".repeat(10));
        clip.add("y".repeat(CodeBuffer.MAX_CHARS));
        key(e, GLFW_KEY_V, true, false);
        check(e.text().length() == CodeBuffer.MAX_CHARS, "розмір обмежено " + CodeBuffer.MAX_CHARS);
        check(!key(e, GLFW_KEY_F, false, false), "невідома клавіша не обробляється");

        // підсвітка
        List<Highlight.Span> sp = Highlight.spans("    var x = len(\"a // b\") + 42 // кінець");
        check(kinds(sp).equals("TEXT KEYWORD TEXT CALL TEXT STRING TEXT NUMBER TEXT COMMENT"), "підсвітка: " + kinds(sp));
        int covered = 0; for (Highlight.Span x : sp) covered += x.to() - x.from();
        check(covered == "    var x = len(\"a // b\") + 42 // кінець".length(), "проміжки покривають рядок");
        check(kinds(Highlight.spans("x2 = \"незакритий")).equals("TEXT STRING"), "незакритий рядок і цифра в імені");
        check(Highlight.spans("").isEmpty(), "порожній рядок");

        // збереження скрипта (без світу - лише файл)
        File dir = Files.createTempDirectory("vx-code").toFile();
        ScriptHost host = new ScriptHost(dir, null, new voxelin.core.EventBus(), new voxelin.core.CommandRegistry(), null);
        check(host.ids().isEmpty(), "спершу скриптів нема");
        host.save("my_tool", ScriptHost.template("my_tool"));
        check(host.ids().equals(List.of("my_tool")) && Files.readString(host.mainFile("my_tool").toPath()).contains("command(\"my_tool\""), "збережено в scripts/my_tool/main.nx");
        boolean threw = false;
        try { host.save("../evil", "x"); } catch (java.io.IOException ex) { threw = true; }
        check(threw && !new File(dir.getParentFile(), "evil").exists(), "назва з ../ - відмова");
        for (String bad : new String[]{"", "A", "з", "a b", "x".repeat(33)}) check(!ScriptHost.validId(bad), "погана назва: " + bad);
        System.out.println("VoxelinCodeTest: " + n + " перевірок OK");
    }

    static String kinds(List<Highlight.Span> sp) { StringBuilder sb = new StringBuilder(); for (Highlight.Span s : sp) sb.append(sb.length() > 0 ? " " : "").append(s.kind()); return sb.toString(); }
}
