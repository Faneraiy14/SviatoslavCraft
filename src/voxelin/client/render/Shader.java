package voxelin.client.render;

import static org.lwjgl.opengl.GL20.*;

/** Програма GLSL: компіляція з повідомленням про помилку (а не тихий чорний екран). */
public final class Shader {
    public final int id;

    public Shader(String vertex, String fragment, String... attribs) {
        int vs = compile(GL_VERTEX_SHADER, vertex), fs = compile(GL_FRAGMENT_SHADER, fragment);
        id = glCreateProgram();
        glAttachShader(id, vs); glAttachShader(id, fs);
        for (int i = 0; i < attribs.length; i++) glBindAttribLocation(id, i, attribs[i]);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) throw new IllegalStateException("шейдер не злінкувався: " + glGetProgramInfoLog(id));
        glDeleteShader(vs); glDeleteShader(fs);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException("шейдер не скомпілювався: " + glGetShaderInfoLog(s));
        return s;
    }

    public int uniform(String name) { return glGetUniformLocation(id, name); }
}
