package com.sidemic;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Engine de áudio offline do Sidemic Studio.
 *
 * Fluxo: decodifica um arquivo (M4A/MP3/WAV/OGG) para PCM 16-bit mono em
 * memória, aplica a cadeia de efeitos, e reencoda para AAC/M4A. Tudo em Java,
 * sem NDK — processamento é offline (arquivo → arquivo), não em tempo real.
 *
 * Limite prático: PCM mono 44.1 kHz gasta ~5,3 MB por minuto, então trechos de
 * até ~10 min cabem confortavelmente na heap de um app comum.
 */
public class AudioEngine {

    public static final int SAMPLE_RATE = 44100;

    /** PCM decodificado + taxa de amostragem de origem. */
    public static class Clip {
        public final short[] samples;
        public final int sampleRate;

        public Clip(short[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public double durationSeconds() {
            return samples.length / (double) sampleRate;
        }
    }

    // ─────────────────────────── Decodificação ───────────────────────────

    /** Decodifica qualquer áudio suportado pelo sistema para PCM mono 16-bit. */
    public static Clip decode(Context ctx, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, uri, null);

        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0 || format == null) throw new IOException("No audio track found");

        ex.selectTrack(track);
        int srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        ShortBuffer out = ShortBuffer.allocate(srcRate * 60);   // cresce conforme necessário
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                if (info.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(outIdx);
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                    int n = sb.remaining();
                    // Downmix para mono na entrada
                    int monoCount = srcChannels > 1 ? n / srcChannels : n;
                    if (out.remaining() < monoCount) out = grow(out, monoCount);
                    if (srcChannels > 1) {
                        for (int i = 0; i + srcChannels <= n; i += srcChannels) {
                            int sum = 0;
                            for (int c = 0; c < srcChannels; c++) sum += sb.get(i + c);
                            out.put((short) clamp(sum / srcChannels));
                        }
                    } else {
                        for (int i = 0; i < n; i++) out.put(sb.get(i));
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }

        codec.stop();
        codec.release();
        ex.release();

        short[] pcm = new short[out.position()];
        out.flip();
        out.get(pcm);
        return new Clip(pcm, srcRate);
    }

    private static ShortBuffer grow(ShortBuffer buf, int needed) {
        int newCap = Math.max(buf.capacity() * 2, buf.position() + needed + 1024);
        ShortBuffer bigger = ShortBuffer.allocate(newCap);
        buf.flip();
        bigger.put(buf);
        return bigger;
    }

    // ─────────────────────────── Codificação ───────────────────────────

    /** Codifica PCM mono para AAC dentro de um container M4A. */
    public static void encodeToM4a(short[] pcm, int sampleRate, int bitrate, FileDescriptor fd)
            throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaMuxer muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean muxing = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int pos = 0;                       // amostra atual
        boolean inputDone = false, outputDone = false;
        long presentationUs = 0;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    buf.clear();
                    int capacityShorts = buf.capacity() / 2;
                    int count = Math.min(capacityShorts, pcm.length - pos);
                    if (count <= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        ShortBuffer sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer();
                        sb.put(pcm, pos, count);
                        codec.queueInputBuffer(inIdx, 0, count * 2, presentationUs, 0);
                        pos += count;
                        presentationUs += (long) (count * 1_000_000L / sampleRate);
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxTrack = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxing = true;
            } else if (outIdx >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIdx);
                if (info.size > 0 && muxing
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    muxer.writeSampleData(muxTrack, buf, info);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }

        codec.stop();
        codec.release();
        if (muxing) muxer.stop();
        muxer.release();
    }

    // ─────────────────────────── Utilitários ───────────────────────────

    public static int clamp(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    public static short clampShort(double v) {
        return (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : (int) v));
    }

    /** Reamostragem linear — usada para casar taxas antes de mixar. */
    public static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) return in;
        int outLen = (int) ((long) in.length * toRate / fromRate);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double srcPos = i * (double) fromRate / toRate;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = srcPos - i0;
            out[i] = clampShort(in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    /** Pico normalizado para um alvo (0..1) — evita clipping e nivela takes. */
    public static void normalize(short[] pcm, double targetPeak) {
        int peak = 0;
        for (short s : pcm) peak = Math.max(peak, Math.abs(s));
        if (peak == 0) return;
        double gain = (targetPeak * 32767.0) / peak;
        if (gain > 8) gain = 8;                       // não explodir ruído de fundo
        for (int i = 0; i < pcm.length; i++) pcm[i] = clampShort(pcm[i] * gain);
    }

    /**
     * Mixa voz sobre a base. A base é reamostrada para a taxa da voz, tem o
     * ganho reduzido (ducking fixo) e é repetida ou cortada para casar a
     * duração da voz.
     */
    public static short[] mix(short[] voice, int voiceRate,
                              short[] beat, int beatRate,
                              double voiceGain, double beatGain) {
        short[] b = resample(beat, beatRate, voiceRate);
        short[] out = new short[voice.length];
        for (int i = 0; i < voice.length; i++) {
            int beatSample = b.length > 0 ? b[i % b.length] : 0;
            out[i] = clampShort(voice[i] * voiceGain + beatSample * beatGain);
        }
        return out;
    }
}
