// Sidemic — motor de autotune nativo.
//
// Ao contrário do "autotune aproximado" em Java (que mede um tom médio e
// desloca o take inteiro), este motor trabalha quadro a quadro:
//
//   1. rastreia o pitch continuamente (autocorrelação normalizada, estilo YIN
//      simplificado, com interpolação parabólica no pico);
//   2. quantiza cada quadro para a nota mais próxima dentro de uma escala;
//   3. re-sintetiza por PSOLA — sobrepõe grãos de um período centrados nas
//      marcas de pitch, reposicionados no período alvo.
//
// O parâmetro `retune` controla a velocidade da correção: 1.0 fixa o tom
// instantaneamente (o efeito duro, tipo T-Pain), valores menores deslizam.

#include <jni.h>
#include <cmath>
#include <cstring>
#include <vector>
#include <algorithm>

namespace {

constexpr float kMinHz = 70.0f;
constexpr float kMaxHz = 700.0f;

// Máscaras de escala: 12 semitons, true = nota pertence à escala.
// 0 = cromática (toda nota vale), 1 = maior, 2 = menor natural.
const bool kScales[3][12] = {
    {true, true, true, true, true, true, true, true, true, true, true, true},
    {true, false, true, false, true, true, false, true, false, true, false, true},
    {true, false, true, true, false, true, false, true, true, false, true, false},
};

inline float midiFromHz(float hz) {
    return 69.0f + 12.0f * std::log2(hz / 440.0f);
}

inline float hzFromMidi(float midi) {
    return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
}

// Nota mais próxima que pertence à escala, respeitando a tônica (key: 0=C..11=B).
float snapToScale(float midi, int scale, int key) {
    const bool *mask = kScales[scale];
    float best = midi;
    float bestDist = 1e9f;
    // Procura candidatos ±2 semitons em torno do valor medido
    for (int cand = static_cast<int>(std::floor(midi)) - 2;
         cand <= static_cast<int>(std::ceil(midi)) + 2; ++cand) {
        int pc = ((cand - key) % 12 + 12) % 12;
        if (!mask[pc]) continue;
        float dist = std::fabs(midi - cand);
        if (dist < bestDist) {
            bestDist = dist;
            best = static_cast<float>(cand);
        }
    }
    return best;
}

// Autocorrelação normalizada (NSDF). Retorna o período em amostras, ou -1.
float detectPeriod(const float *x, int n, int sampleRate) {
    const int minLag = static_cast<int>(sampleRate / kMaxHz);
    const int maxLag = std::min(static_cast<int>(sampleRate / kMinHz), n / 2);
    if (maxLag <= minLag) return -1.0f;

    // Energia do quadro: quadros silenciosos não têm pitch definido.
    double energy = 0.0;
    for (int i = 0; i < n; ++i) energy += static_cast<double>(x[i]) * x[i];
    if (energy < 1e-4 * n) return -1.0f;

    float bestValue = 0.0f;
    int bestLag = -1;
    std::vector<float> nsdf(maxLag + 1, 0.0f);

    for (int lag = minLag; lag <= maxLag; ++lag) {
        double acf = 0.0, norm = 0.0;
        const int m = n - lag;
        for (int i = 0; i < m; ++i) {
            acf += static_cast<double>(x[i]) * x[i + lag];
            norm += static_cast<double>(x[i]) * x[i] +
                    static_cast<double>(x[i + lag]) * x[i + lag];
        }
        float v = norm > 1e-9 ? static_cast<float>(2.0 * acf / norm) : 0.0f;
        nsdf[lag] = v;
        if (v > bestValue) {
            bestValue = v;
            bestLag = lag;
        }
    }

    // Limiar de clareza: abaixo disso o quadro é ruído/consoante, não tom.
    if (bestLag < 0 || bestValue < 0.35f) return -1.0f;

    // Interpolação parabólica no pico para precisão sub-amostra
    float refined = static_cast<float>(bestLag);
    if (bestLag > minLag && bestLag < maxLag) {
        float a = nsdf[bestLag - 1], b = nsdf[bestLag], c = nsdf[bestLag + 1];
        float denom = 2.0f * (2.0f * b - a - c);
        if (std::fabs(denom) > 1e-9f) refined += (c - a) / denom;
    }
    return refined;
}

} // namespace

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_sidemic_NativeAudio_nativeAutoTune(JNIEnv *env, jclass,
                                      jshortArray input, jint sampleRate,
                                      jfloat retune, jint scale, jint key) {
    const jsize n = env->GetArrayLength(input);
    if (n <= 0) return input;
    if (scale < 0 || scale > 2) scale = 0;
    key = ((key % 12) + 12) % 12;
    retune = std::max(0.0f, std::min(1.0f, retune));

    std::vector<jshort> in(n);
    env->GetShortArrayRegion(input, 0, n, in.data());

    // PCM 16-bit → float normalizado
    std::vector<float> x(n);
    for (jsize i = 0; i < n; ++i) x[i] = in[i] / 32768.0f;

    std::vector<float> out(n, 0.0f);
    std::vector<float> weight(n, 0.0f);

    const int frame = std::max(1024, sampleRate / 20);   // ~50 ms
    const int hop = frame / 4;

    float lastPeriod = -1.0f;

    for (int start = 0; start + frame < n; start += hop) {
        float period = detectPeriod(&x[start], frame, sampleRate);
        if (period <= 0.0f) {
            // Sem tom detectável: copia o quadro cru (consoantes, respiração)
            for (int i = 0; i < frame && start + i < n; ++i) {
                float w = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * i / (frame - 1));
                out[start + i] += x[start + i] * w;
                weight[start + i] += w;
            }
            lastPeriod = -1.0f;
            continue;
        }

        // Suaviza saltos bruscos do detector entre quadros vizinhos
        if (lastPeriod > 0.0f && std::fabs(period - lastPeriod) > lastPeriod * 0.35f) {
            period = 0.5f * (period + lastPeriod);
        }
        lastPeriod = period;

        const float hz = sampleRate / period;
        const float midi = midiFromHz(hz);
        const float snapped = snapToScale(midi, scale, key);
        const float targetMidi = midi + (snapped - midi) * retune;
        const float targetHz = hzFromMidi(targetMidi);
        const float targetPeriod = sampleRate / targetHz;

        // PSOLA: grãos de dois períodos, janelados, realocados no novo período
        const int grain = static_cast<int>(period * 2.0f);
        if (grain < 8 || grain >= frame) continue;

        float readPos = 0.0f;
        for (float writePos = 0.0f; writePos + grain < frame; writePos += targetPeriod) {
            const int r = start + static_cast<int>(readPos);
            const int w0 = start + static_cast<int>(writePos);
            for (int i = 0; i < grain; ++i) {
                const int ri = r + i, wi = w0 + i;
                if (ri >= n || wi >= n) break;
                const float win = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * i / (grain - 1));
                out[wi] += x[ri] * win;
                weight[wi] += win;
            }
            readPos += period;
            if (readPos + grain >= frame) readPos = 0.0f;   // reusa o quadro
        }
    }

    // Normaliza a sobreposição e converte de volta para PCM
    std::vector<jshort> result(n);
    for (jsize i = 0; i < n; ++i) {
        float v = weight[i] > 1e-6f ? out[i] / weight[i] : x[i];
        v *= 32767.0f;
        result[i] = static_cast<jshort>(std::max(-32768.0f, std::min(32767.0f, v)));
    }

    jshortArray outArray = env->NewShortArray(n);
    env->SetShortArrayRegion(outArray, 0, n, result.data());
    return outArray;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_sidemic_NativeAudio_nativeDetectPitch(JNIEnv *env, jclass,
                                         jshortArray input, jint sampleRate) {
    const jsize n = env->GetArrayLength(input);
    if (n < 2048) return -1.0f;

    std::vector<jshort> in(n);
    env->GetShortArrayRegion(input, 0, n, in.data());

    // Analisa um trecho central de ~100 ms
    const int frame = std::min<int>(n, std::max(2048, sampleRate / 10));
    const int start = std::max(0, (n - frame) / 2);

    std::vector<float> x(frame);
    for (int i = 0; i < frame; ++i) x[i] = in[start + i] / 32768.0f;

    const float period = detectPeriod(x.data(), frame, sampleRate);
    return period > 0.0f ? sampleRate / period : -1.0f;
}
