package com.sidemic;

/**
 * Cadeia de efeitos offline do Sidemic Studio — DSP em Java puro, sem NDK.
 *
 * Tudo opera sobre PCM 16-bit mono. Os algoritmos são clássicos e baratos:
 * pitch shift por overlap-add (WSOLA simplificado), biquads RBJ para EQ e
 * filtros, delay com realimentação para eco, e um Schroeder reverb enxuto.
 *
 * O autotune real (correção para escala) fica para a fase NDK — aqui há um
 * "quantize" aproximado que corrige o pitch médio do take para a nota mais
 * próxima, suficiente para o efeito de brincadeira.
 */
public class Effects {

    // ───────────────────────────── Biquad ─────────────────────────────

    /** Filtro biquad (Robert Bristow-Johnson cookbook). */
    public static class Biquad {
        private double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        public static Biquad lowShelf(double freq, double rate, double gainDb) {
            Biquad f = new Biquad();
            double A = Math.pow(10, gainDb / 40);
            double w = 2 * Math.PI * freq / rate;
            double cs = Math.cos(w), sn = Math.sin(w);
            double beta = Math.sqrt(A) / 1.0;
            double a0 = (A + 1) + (A - 1) * cs + beta * sn;
            f.b0 = A * ((A + 1) - (A - 1) * cs + beta * sn) / a0;
            f.b1 = 2 * A * ((A - 1) - (A + 1) * cs) / a0;
            f.b2 = A * ((A + 1) - (A - 1) * cs - beta * sn) / a0;
            f.a1 = -2 * ((A - 1) + (A + 1) * cs) / a0;
            f.a2 = ((A + 1) + (A - 1) * cs - beta * sn) / a0;
            return f;
        }

        public static Biquad highShelf(double freq, double rate, double gainDb) {
            Biquad f = new Biquad();
            double A = Math.pow(10, gainDb / 40);
            double w = 2 * Math.PI * freq / rate;
            double cs = Math.cos(w), sn = Math.sin(w);
            double beta = Math.sqrt(A);
            double a0 = (A + 1) - (A - 1) * cs + beta * sn;
            f.b0 = A * ((A + 1) + (A - 1) * cs + beta * sn) / a0;
            f.b1 = -2 * A * ((A - 1) + (A + 1) * cs) / a0;
            f.b2 = A * ((A + 1) + (A - 1) * cs - beta * sn) / a0;
            f.a1 = 2 * ((A - 1) - (A + 1) * cs) / a0;
            f.a2 = ((A + 1) - (A - 1) * cs - beta * sn) / a0;
            return f;
        }

        public static Biquad peaking(double freq, double rate, double q, double gainDb) {
            Biquad f = new Biquad();
            double A = Math.pow(10, gainDb / 40);
            double w = 2 * Math.PI * freq / rate;
            double alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha / A;
            f.b0 = (1 + alpha * A) / a0;
            f.b1 = (-2 * Math.cos(w)) / a0;
            f.b2 = (1 - alpha * A) / a0;
            f.a1 = (-2 * Math.cos(w)) / a0;
            f.a2 = (1 - alpha / A) / a0;
            return f;
        }

        public static Biquad highPass(double freq, double rate, double q) {
            Biquad f = new Biquad();
            double w = 2 * Math.PI * freq / rate;
            double alpha = Math.sin(w) / (2 * q);
            double cs = Math.cos(w);
            double a0 = 1 + alpha;
            f.b0 = ((1 + cs) / 2) / a0;
            f.b1 = (-(1 + cs)) / a0;
            f.b2 = ((1 + cs) / 2) / a0;
            f.a1 = (-2 * cs) / a0;
            f.a2 = (1 - alpha) / a0;
            return f;
        }

        public static Biquad lowPass(double freq, double rate, double q) {
            Biquad f = new Biquad();
            double w = 2 * Math.PI * freq / rate;
            double alpha = Math.sin(w) / (2 * q);
            double cs = Math.cos(w);
            double a0 = 1 + alpha;
            f.b0 = ((1 - cs) / 2) / a0;
            f.b1 = (1 - cs) / a0;
            f.b2 = ((1 - cs) / 2) / a0;
            f.a1 = (-2 * cs) / a0;
            f.a2 = (1 - alpha) / a0;
            return f;
        }

        public double process(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x;
            y2 = y1; y1 = y;
            return y;
        }

        public void apply(short[] pcm) {
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = AudioEngine.clampShort(process(pcm[i]));
            }
        }
    }

    // ─────────────────────────── Equalização ───────────────────────────

    /** EQ de 3 bandas: graves (shelf 200 Hz), médios (peak 1 kHz), agudos (shelf 4 kHz). */
    public static void equalize(short[] pcm, int rate, double bassDb, double midDb, double trebleDb) {
        if (bassDb != 0) Biquad.lowShelf(200, rate, bassDb).apply(pcm);
        if (midDb != 0) Biquad.peaking(1000, rate, 0.9, midDb).apply(pcm);
        if (trebleDb != 0) Biquad.highShelf(4000, rate, trebleDb).apply(pcm);
    }

    // ─────────────────────────── Pitch shift ───────────────────────────

    /**
     * Deslocamento de tom preservando a duração, por overlap-add com janelas
     * de Hann. `semitones` positivo sobe, negativo desce.
     */
    public static short[] pitchShift(short[] pcm, int rate, double semitones) {
        if (semitones == 0 || pcm.length == 0) return pcm;
        double factor = Math.pow(2, semitones / 12.0);

        int window = 2048;
        int hop = window / 4;
        int outHop = (int) Math.round(hop * factor);
        if (outHop < 1) outHop = 1;

        // 1) Time-stretch por overlap-add: muda a duração, mantém o tom
        int stretchedLen = (int) (pcm.length / (double) hop * outHop) + window;
        double[] acc = new double[stretchedLen];
        double[] norm = new double[stretchedLen];
        double[] win = new double[window];
        for (int i = 0; i < window; i++) {
            win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (window - 1));
        }

        int outPos = 0;
        for (int inPos = 0; inPos + window < pcm.length; inPos += hop) {
            for (int i = 0; i < window; i++) {
                int o = outPos + i;
                if (o >= stretchedLen) break;
                acc[o] += pcm[inPos + i] * win[i];
                norm[o] += win[i];
            }
            outPos += outHop;
        }
        short[] stretched = new short[Math.min(outPos + window, stretchedLen)];
        for (int i = 0; i < stretched.length; i++) {
            double v = norm[i] > 1e-6 ? acc[i] / norm[i] : 0;
            stretched[i] = AudioEngine.clampShort(v);
        }

        // 2) Reamostra de volta ao comprimento original: agora o tom mudou
        return AudioEngine.resample(stretched,
                (int) Math.round(rate * factor), rate);
    }

    /**
     * "Autotune" aproximado: mede o tom médio por autocorrelação, calcula a
     * distância até o semitom mais próximo e corrige o take inteiro. Não é
     * correção nota a nota — isso vem com o motor em C++.
     */
    public static short[] autoTune(short[] pcm, int rate, double strength) {
        double f0 = detectPitch(pcm, rate);
        if (f0 <= 0) return pcm;
        double midi = 69 + 12 * (Math.log(f0 / 440.0) / Math.log(2));
        double target = Math.round(midi);
        double correction = (target - midi) * Math.max(0, Math.min(1, strength));
        if (Math.abs(correction) < 0.02) return pcm;
        return pitchShift(pcm, rate, correction);
    }

    /** Autocorrelação simples sobre um trecho central — bom o bastante para voz. */
    public static double detectPitch(short[] pcm, int rate) {
        int minLag = rate / 500;      // 500 Hz
        int maxLag = rate / 70;       // 70 Hz
        int start = Math.max(0, pcm.length / 2 - rate / 2);
        int len = Math.min(rate, pcm.length - start);
        if (len < maxLag * 2) return -1;

        double best = 0;
        int bestLag = -1;
        for (int lag = minLag; lag < maxLag; lag++) {
            double sum = 0;
            for (int i = 0; i < len - lag; i += 2) {
                sum += (double) pcm[start + i] * pcm[start + i + lag];
            }
            if (sum > best) { best = sum; bestLag = lag; }
        }
        return bestLag > 0 ? rate / (double) bestLag : -1;
    }

    // ─────────────────────────── Tempo/espaço ───────────────────────────

    /** Eco com realimentação. */
    public static void echo(short[] pcm, int rate, double delayMs, double feedback, double mix) {
        int delay = (int) (rate * delayMs / 1000.0);
        if (delay <= 0 || delay >= pcm.length) return;
        for (int i = delay; i < pcm.length; i++) {
            double wet = pcm[i - delay] * feedback;
            pcm[i] = AudioEngine.clampShort(pcm[i] * (1 - mix * 0.5) + wet * mix);
        }
    }

    /** Reverb Schroeder enxuto: 4 combs em paralelo + 2 all-pass em série. */
    public static void reverb(short[] pcm, int rate, double roomSize, double mix) {
        int[] combDelays = {
                (int) (rate * 0.0297 * roomSize),
                (int) (rate * 0.0371 * roomSize),
                (int) (rate * 0.0411 * roomSize),
                (int) (rate * 0.0437 * roomSize),
        };
        double[] combGains = {0.76, 0.72, 0.70, 0.68};

        double[] wet = new double[pcm.length];
        for (int c = 0; c < combDelays.length; c++) {
            int d = combDelays[c];
            if (d <= 0 || d >= pcm.length) continue;
            double g = combGains[c];
            double[] buf = new double[pcm.length];
            for (int i = 0; i < pcm.length; i++) {
                double delayed = i >= d ? buf[i - d] : 0;
                buf[i] = pcm[i] + delayed * g;
                wet[i] += buf[i] * 0.25;
            }
        }

        int[] apDelays = {(int) (rate * 0.005), (int) (rate * 0.0017)};
        for (int a = 0; a < apDelays.length; a++) {
            int d = apDelays[a];
            if (d <= 0 || d >= wet.length) continue;
            double g = 0.7;
            double[] buf = new double[wet.length];
            for (int i = 0; i < wet.length; i++) {
                double delayed = i >= d ? buf[i - d] : 0;
                buf[i] = wet[i] + delayed * g;
                wet[i] = delayed - g * buf[i];
            }
        }

        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = AudioEngine.clampShort(pcm[i] * (1 - mix) + wet[i] * mix);
        }
    }

    // ─────────────────────────── Caráter ───────────────────────────

    /** Saturação suave (tanh) — engorda a voz sem estourar. */
    public static void drive(short[] pcm, double amount) {
        if (amount <= 0) return;
        double k = 1 + amount * 9;
        double norm = Math.tanh(k);
        for (int i = 0; i < pcm.length; i++) {
            double x = pcm[i] / 32768.0;
            pcm[i] = AudioEngine.clampShort(Math.tanh(k * x) / norm * 32767);
        }
    }

    /** Vibrato/ondulação de tom por linha de atraso modulada — base do "robot". */
    public static short[] tremoloPitch(short[] pcm, int rate, double rateHz, double depthMs) {
        int maxDelay = (int) (rate * depthMs / 1000.0) + 2;
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            double mod = (1 + Math.sin(2 * Math.PI * rateHz * i / rate)) * 0.5;
            double srcPos = i - mod * maxDelay;
            int i0 = (int) Math.floor(srcPos);
            double frac = srcPos - i0;
            short a = (i0 >= 0 && i0 < pcm.length) ? pcm[i0] : 0;
            short b = (i0 + 1 >= 0 && i0 + 1 < pcm.length) ? pcm[i0 + 1] : 0;
            out[i] = AudioEngine.clampShort(a * (1 - frac) + b * frac);
        }
        return out;
    }

    /** Modulação em anel — timbre metálico de rádio/robô. */
    public static void ringMod(short[] pcm, int rate, double freq, double mix) {
        for (int i = 0; i < pcm.length; i++) {
            double carrier = Math.sin(2 * Math.PI * freq * i / rate);
            pcm[i] = AudioEngine.clampShort(pcm[i] * (1 - mix) + pcm[i] * carrier * mix);
        }
    }

    // ─────────────────────────── Presets ───────────────────────────

    public static final String[] PRESET_NAMES = {
            "Clean", "Radio", "Deep", "Chipmunk", "Robot",
            "Trap", "Alien", "Megaphone", "Cave", "Demon",
    };

    public static final String[] PRESET_SUBS = {
            "just level and clarity",
            "AM broadcast, band-limited",
            "octave down, chest weight",
            "octave up, cartoon voice",
            "ring modulated, metallic",
            "tuned, driven, roomy",
            "detuned wobble, wide",
            "clipped mids, loud-hailer",
            "long tail reverb",
            "way down, distorted",
    };

    /** Aplica um preset. Retorna PCM novo (alguns efeitos mudam o array). */
    public static short[] applyPreset(short[] pcm, int rate, int preset) {
        short[] s = pcm.clone();
        switch (preset) {
            case 0: // Clean
                Biquad.highPass(80, rate, 0.7).apply(s);
                equalize(s, rate, 1, 0, 2);
                AudioEngine.normalize(s, 0.92);
                break;
            case 1: // Radio
                Biquad.highPass(400, rate, 0.8).apply(s);
                Biquad.lowPass(3000, rate, 0.8).apply(s);
                drive(s, 0.35);
                AudioEngine.normalize(s, 0.9);
                break;
            case 2: // Deep
                s = pitchShift(s, rate, -5);
                equalize(s, rate, 6, -1, -2);
                AudioEngine.normalize(s, 0.9);
                break;
            case 3: // Chipmunk
                s = pitchShift(s, rate, 7);
                equalize(s, rate, -4, 1, 3);
                AudioEngine.normalize(s, 0.9);
                break;
            case 4: // Robot
                ringMod(s, rate, 55, 0.85);
                Biquad.lowPass(4500, rate, 0.7).apply(s);
                drive(s, 0.25);
                AudioEngine.normalize(s, 0.88);
                break;
            case 5: // Trap
                s = autoTune(s, rate, 1.0);
                equalize(s, rate, 3, -1, 3);
                drive(s, 0.3);
                echo(s, rate, 180, 0.32, 0.28);
                reverb(s, rate, 0.8, 0.18);
                AudioEngine.normalize(s, 0.94);
                break;
            case 6: // Alien
                s = tremoloPitch(s, rate, 6.5, 3.2);
                ringMod(s, rate, 120, 0.4);
                reverb(s, rate, 1.2, 0.25);
                AudioEngine.normalize(s, 0.9);
                break;
            case 7: // Megaphone
                Biquad.highPass(600, rate, 1.0).apply(s);
                Biquad.lowPass(3500, rate, 1.0).apply(s);
                drive(s, 0.8);
                echo(s, rate, 90, 0.25, 0.2);
                AudioEngine.normalize(s, 0.95);
                break;
            case 8: // Cave
                reverb(s, rate, 1.8, 0.45);
                echo(s, rate, 320, 0.4, 0.35);
                equalize(s, rate, 2, -2, -3);
                AudioEngine.normalize(s, 0.88);
                break;
            case 9: // Demon
                s = pitchShift(s, rate, -9);
                drive(s, 0.7);
                reverb(s, rate, 1.4, 0.3);
                equalize(s, rate, 5, 0, -4);
                AudioEngine.normalize(s, 0.92);
                break;
            default:
                break;
        }
        return s;
    }
}
