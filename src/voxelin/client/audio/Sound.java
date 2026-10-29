package voxelin.client.audio;

/** Звук: моно PCM float у -1..1, частота RATE. Незмінний після створення. */
public final class Sound {
    public static final int RATE = 44100;
    final float[] pcm;

    public Sound(float[] pcm) {
        float[] c = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) { float v = pcm[i]; c[i] = Float.isNaN(v) ? 0 : Math.max(-1f, Math.min(1f, v)); } // NaN і кліпінг - ніколи
        this.pcm = c;
    }

    public int length() { return pcm.length; }
    public float sample(int i) { return pcm[i]; }
    public double seconds() { return pcm.length / (double) RATE; }
}
