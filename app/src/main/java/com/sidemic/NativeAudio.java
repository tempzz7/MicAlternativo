package com.sidemic;

/**
 * Ponte para o motor de áudio nativo (libsidemic.so).
 *
 * O autotune em Java ({@link Effects#autoTune}) corrige o tom médio do take
 * inteiro. Este motor trabalha quadro a quadro: rastreia o pitch continuamente,
 * quantiza cada quadro para a escala escolhida e re-sintetiza por PSOLA — é o
 * efeito de correção "duro" que se ouve em produção moderna.
 *
 * Se a biblioteca nativa não carregar (build sem .so para a ABI do aparelho),
 * {@link #isAvailable()} devolve false e o Studio recai no autotune em Java.
 */
public final class NativeAudio {

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("sidemic");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private NativeAudio() { }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Escalas aceitas por {@link #autoTune}. */
    public static final int SCALE_CHROMATIC = 0;
    public static final int SCALE_MAJOR = 1;
    public static final int SCALE_MINOR = 2;

    public static final String[] SCALE_NAMES = {"Chromatic", "Major", "Minor"};
    public static final String[] KEY_NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    };

    /**
     * Correção de pitch quadro a quadro.
     *
     * @param pcm        PCM 16-bit mono
     * @param sampleRate taxa de amostragem
     * @param retune     0..1 — 1.0 trava a nota instantaneamente, valores
     *                   menores deslizam entre as notas
     * @param scale      {@link #SCALE_CHROMATIC}, {@link #SCALE_MAJOR} ou
     *                   {@link #SCALE_MINOR}
     * @param key        tônica, 0 = C … 11 = B
     * @return PCM corrigido, ou o original quando a lib nativa não está presente
     */
    public static short[] autoTune(short[] pcm, int sampleRate, float retune, int scale, int key) {
        if (!AVAILABLE) return pcm;
        try {
            return nativeAutoTune(pcm, sampleRate, retune, scale, key);
        } catch (Throwable t) {
            return pcm;
        }
    }

    /** Frequência fundamental estimada em Hz, ou -1 quando não há tom claro. */
    public static float detectPitch(short[] pcm, int sampleRate) {
        if (!AVAILABLE) return -1f;
        try {
            return nativeDetectPitch(pcm, sampleRate);
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static native short[] nativeAutoTune(short[] pcm, int sampleRate,
                                                 float retune, int scale, int key);

    private static native float nativeDetectPitch(short[] pcm, int sampleRate);
}
