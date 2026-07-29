package com.sidemic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sidemic Studio — produção sobre a captura.
 *
 * Cadeia: take de voz → preset de efeito + EQ → (opcional) mixagem sobre uma
 * base → render para M4A na biblioteca. Processamento é offline, em Java,
 * via {@link AudioEngine} e {@link Effects}.
 *
 * Beats: import de qualquer áudio do aparelho, ou busca no Freesound (CC).
 * YouTube está fora por decisão de projeto — os termos do serviço não permitem
 * extrair o áudio, e material com direitos travaria a distribuição do que o
 * usuário produz.
 */
public class StudioActivity extends Activity {

    private static final int REQ_PICK_VOICE = 10;
    private static final int REQ_PICK_BEAT = 11;

    private static final String PREFS = "sidemic";
    private static final String PREF_FREESOUND_KEY = "freesound_key";
    private static final String REL_PATH = "Music/Sidemic";

    // Paleta compartilhada com a tela principal
    private static final int INK      = Color.parseColor("#080B0D");
    private static final int SURFACE  = Color.parseColor("#111619");
    private static final int RAISED   = Color.parseColor("#181F23");
    private static final int HAIRLINE = Color.parseColor("#232C31");
    private static final int MINT     = Color.parseColor("#3DDCA4");
    private static final int MINT_DEEP= Color.parseColor("#1B6A50");
    private static final int PAPER    = Color.parseColor("#E8EFEC");
    private static final int MUTED    = Color.parseColor("#7B8A88");
    private static final int LIVE     = Color.parseColor("#F0616B");

    private Uri voiceUri;
    private String voiceLabel = "";
    private Uri beatUri;
    private String beatLabel = "";
    private Uri renderUri;
    private long awaitingTake = -1;   // _ID de referência ao sair para gravar

    private int presetIndex = 0;
    private double bassDb = 0, midDb = 0, trebleDb = 0;
    private double tuneStrength = 0;   // 0 = desligado; 1 = correção travada
    private int tuneScale = NativeAudio.SCALE_MINOR;
    private int tuneKey = 0;           // 0 = C
    private Button scaleBtn, keyBtn;
    private double beatGain = 0.55, voiceGain = 1.0;

    private TextView voiceSummary, beatSummary, renderStatus;
    private LinearLayout presetBox, beatResults;
    private Button renderBtn, playRenderBtn, shareRenderBtn, previewBtn;
    private java.io.File previewFile;
    private final List<View> presetRows = new ArrayList<>();

    private MediaPlayer preview;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ── estilo ─────────────────────────────────────────────────────────

    private GradientDrawable rect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1) / 2), stroke);
        return d;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.US));
        t.setTextSize(10);
        t.setTextColor(MUTED);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.24f);
        return t;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(HAIRLINE);
        return v;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams thin(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        p.topMargin = dp(top);
        return p;
    }

    private Button ghost(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(PAPER);
        b.setBackground(rect(RAISED, 6, HAIRLINE));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        return b;
    }

    private Button solid(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(INK);
        b.setBackground(rect(MINT, 6, 0));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        return b;
    }

    private TextView summaryBox(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(MUTED);
        t.setBackground(rect(SURFACE, 6, HAIRLINE));
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        return t;
    }

    // ── ciclo de vida ──────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(INK);
        w.setNavigationBarColor(INK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(INK);
        root.setPadding(dp(24), dp(30), dp(24), dp(44));

        // Cabeçalho
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackground(rect(MINT, 2, 0));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(4), dp(22));
        barLp.rightMargin = dp(12);
        brand.addView(bar, barLp);
        TextView wordmark = new TextView(this);
        wordmark.setText("STUDIO");
        wordmark.setTextSize(20);
        wordmark.setTextColor(PAPER);
        wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wordmark.setLetterSpacing(0.3f);
        brand.addView(wordmark);
        root.addView(brand);

        TextView tagline = new TextView(this);
        tagline.setText("Voice effects, EQ and beats — all offline on your phone");
        tagline.setTextSize(13);
        tagline.setTextColor(MUTED);
        root.addView(tagline, lp(8));

        root.addView(rule(), thin(26));

        // ── 1. Voz ──
        root.addView(sectionLabel("1 · Vocal take"), lp(24));
        voiceSummary = summaryBox("No take selected");
        root.addView(voiceSummary, lp(10));

        Button recordVoice = solid("Record now");
        recordVoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { recordNow(); }
        });
        root.addView(recordVoice, lp(10));

        Button libraryVoice = ghost("Pick from my Sidemic takes");
        libraryVoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFromLibrary(true); }
        });
        root.addView(libraryVoice, lp(8));

        Button pickVoice = ghost("Browse files (Downloads, etc.)");
        pickVoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAudio(REQ_PICK_VOICE); }
        });
        root.addView(pickVoice, lp(8));

        // ── 2. Preset ──
        root.addView(rule(), thin(28));
        root.addView(sectionLabel("2 · Voice preset"), lp(24));
        presetBox = new LinearLayout(this);
        presetBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Effects.PRESET_NAMES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));

            View pip = new View(this);
            pip.setTag("pip");
            LinearLayout.LayoutParams pipLp = new LinearLayout.LayoutParams(dp(7), dp(7));
            pipLp.rightMargin = dp(13);
            row.addView(pip, pipLp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(Effects.PRESET_NAMES[i]);
            t1.setTextSize(15);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(Effects.PRESET_SUBS[i]);
            t2.setTextSize(12);
            t2.setTextColor(MUTED);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { presetIndex = idx; paintPresets(); }
            });
            presetRows.add(row);
            presetBox.addView(row, lp(i == 0 ? 10 : 6));
        }
        root.addView(presetBox);
        paintPresets();

        previewBtn = ghost("Preview this effect (first 6 s)");
        previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { previewEffect(); }
        });
        root.addView(previewBtn, lp(10));

        // ── 3. Autotune ──
        root.addView(rule(), thin(28));
        root.addView(sectionLabel("3 · Pitch correction"), lp(24));

        TextView tuneHint = new TextView(this);
        tuneHint.setText(NativeAudio.isAvailable()
                ? "Frame-by-frame correction: every note snaps to the scale. Push it to 100% for the hard, robotic sound."
                : "Native engine unavailable on this device — falling back to average-pitch correction.");
        tuneHint.setTextSize(12);
        tuneHint.setTextColor(MUTED);
        root.addView(tuneHint, lp(8));

        root.addView(tuneSlider(), lp(12));

        LinearLayout tuneRow = new LinearLayout(this);
        tuneRow.setOrientation(LinearLayout.HORIZONTAL);

        scaleBtn = ghost("Scale · Minor");
        scaleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tuneScale = (tuneScale + 1) % NativeAudio.SCALE_NAMES.length;
                scaleBtn.setText("Scale · " + NativeAudio.SCALE_NAMES[tuneScale]);
            }
        });
        LinearLayout.LayoutParams halfL = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        halfL.rightMargin = dp(6);
        tuneRow.addView(scaleBtn, halfL);

        keyBtn = ghost("Key · C");
        keyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tuneKey = (tuneKey + 1) % 12;
                keyBtn.setText("Key · " + NativeAudio.KEY_NAMES[tuneKey]);
            }
        });
        LinearLayout.LayoutParams halfR = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        halfR.leftMargin = dp(6);
        tuneRow.addView(keyBtn, halfR);

        root.addView(tuneRow, lp(10));

        // ── 4. EQ ──
        root.addView(rule(), thin(28));
        root.addView(sectionLabel("4 · Equaliser"), lp(24));
        root.addView(eqSlider("Bass", 0), lp(12));
        root.addView(eqSlider("Mid", 1), lp(10));
        root.addView(eqSlider("Treble", 2), lp(10));

        // ── 4. Beat ──
        root.addView(rule(), thin(28));
        root.addView(sectionLabel("5 · Beat"), lp(24));
        beatSummary = summaryBox("No beat — vocal only");
        root.addView(beatSummary, lp(10));

        Button libraryBeat = ghost("Pick from my Sidemic library");
        libraryBeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFromLibrary(false); }
        });
        root.addView(libraryBeat, lp(8));

        Button pickBeat = ghost("Browse files (Downloads, etc.)");
        pickBeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickAudio(REQ_PICK_BEAT); }
        });
        root.addView(pickBeat, lp(8));

        Button findBeat = ghost("Find a beat — Freesound (Creative Commons)");
        findBeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptBeatSearch(); }
        });
        root.addView(findBeat, lp(8));

        Button clearBeat = ghost("Remove beat");
        clearBeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                beatUri = null; beatLabel = "";
                beatSummary.setText("No beat — vocal only");
                beatSummary.setTextColor(MUTED);
            }
        });
        root.addView(clearBeat, lp(8));

        beatResults = new LinearLayout(this);
        beatResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(beatResults, lp(4));

        root.addView(beatMixSlider(), lp(14));

        // ── 5. Render ──
        root.addView(rule(), thin(28));
        root.addView(sectionLabel("6 · Render"), lp(24));

        renderStatus = summaryBox("Pick a take, choose a preset, then render");
        root.addView(renderStatus, lp(10));

        renderBtn = solid("Render track");
        renderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { render(); }
        });
        root.addView(renderBtn, lp(10));

        playRenderBtn = ghost("Play result");
        playRenderBtn.setEnabled(false);
        playRenderBtn.setAlpha(0.35f);
        playRenderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePreview(renderUri); }
        });
        root.addView(playRenderBtn, lp(8));

        shareRenderBtn = solid("Share result");
        shareRenderBtn.setEnabled(false);
        shareRenderBtn.setAlpha(0.35f);
        shareRenderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareRender(); }
        });
        root.addView(shareRenderBtn, lp(8));

        TextView foot = new TextView(this);
        foot.setText("Processing happens on your phone. Freesound results are Creative Commons — check each licence before publishing.");
        foot.setTextSize(11);
        foot.setTextColor(MUTED);
        root.addView(foot, lp(20));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void paintPresets() {
        for (int i = 0; i < presetRows.size(); i++) {
            View row = presetRows.get(i);
            boolean on = (i == presetIndex);
            row.setBackground(rect(on ? SURFACE : INK, 6, on ? MINT_DEEP : HAIRLINE));
            View pip = row.findViewWithTag("pip");
            if (pip != null) pip.setBackground(rect(on ? MINT : HAIRLINE, 4, 0));
            LinearLayout texts = (LinearLayout) ((LinearLayout) row).getChildAt(1);
            ((TextView) texts.getChildAt(0)).setTextColor(on ? PAPER : MUTED);
        }
    }

    /** Slider de EQ: −12 a +12 dB. */
    private View eqSlider(final String name, final int band) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView label = new TextView(this);
        label.setText(name + "  0 dB");
        label.setTextSize(13);
        label.setTextColor(PAPER);
        box.addView(label);

        SeekBar sb = new SeekBar(this);
        sb.setMax(24);
        sb.setProgress(12);
        sb.getProgressDrawable().setTint(MINT);
        sb.getThumb().setTint(MINT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                double db = p - 12;
                if (band == 0) bassDb = db;
                else if (band == 1) midDb = db;
                else trebleDb = db;
                label.setText(name + "  " + (db > 0 ? "+" : "") + (int) db + " dB");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        return box;
    }

    private View tuneSlider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView label = new TextView(this);
        label.setText("Correction  off");
        label.setTextSize(13);
        label.setTextColor(PAPER);
        box.addView(label);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(0);
        sb.getProgressDrawable().setTint(MINT);
        sb.getThumb().setTint(MINT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tuneStrength = p / 100.0;
                label.setText(p == 0 ? "Correction  off" : "Correction  " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        return box;
    }

    /**
     * Cadeia comum de processamento — usada pelo preview e pelo render, para
     * que o que se ouve na prévia seja exatamente o que sai no arquivo.
     */
    private short[] processChain(short[] samples, int rate) {
        short[] pcm = Effects.applyPreset(samples, rate, presetIndex);
        if (tuneStrength > 0.01) {
            pcm = NativeAudio.isAvailable()
                    ? NativeAudio.autoTune(pcm, rate, (float) tuneStrength, tuneScale, tuneKey)
                    : Effects.autoTune(pcm, rate, tuneStrength);
        }
        Effects.equalize(pcm, rate, bassDb, midDb, trebleDb);
        return pcm;
    }

    private View beatMixSlider() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView label = new TextView(this);
        label.setText("Beat level  55%");
        label.setTextSize(13);
        label.setTextColor(PAPER);
        box.addView(label);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(55);
        sb.getProgressDrawable().setTint(MINT);
        sb.getThumb().setTint(MINT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                beatGain = p / 100.0;
                label.setText("Beat level  " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        return box;
    }

    // ── seleção de arquivos ────────────────────────────────────────────

    /**
     * Lista o que já está em Music/Sidemic — takes gravados e beats baixados —
     * sem passar pelo seletor de arquivos do sistema.
     */
    private void pickFromLibrary(final boolean forVoice) {
        final List<Uri> uris = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.DURATION,
                },
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Audio.Media.IS_PENDING + "=0",
                new String[]{REL_PATH + "%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC");

        if (c != null) {
            try {
                while (c.moveToNext() && uris.size() < 60) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    long dur = c.getLong(2) / 1000;
                    uris.add(android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY), id));
                    labels.add(name + "   " + String.format(Locale.US, "%d:%02d", dur / 60, dur % 60));
                }
            } finally {
                c.close();
            }
        }

        if (uris.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Library is empty")
                    .setMessage("Record a take on the main screen, or use \"Browse files\" to load audio "
                            + "from Downloads or anywhere else on your phone.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(forVoice ? "Pick a vocal take" : "Pick a beat")
                .setItems(labels.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                Uri u = uris.get(which);
                                String name = labels.get(which).split("   ")[0];
                                if (forVoice) {
                                    voiceUri = u;
                                    voiceLabel = name;
                                    voiceSummary.setText(name);
                                    voiceSummary.setTextColor(PAPER);
                                } else {
                                    beatUri = u;
                                    beatLabel = name;
                                    beatSummary.setText(name);
                                    beatSummary.setTextColor(PAPER);
                                }
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Abre a tela de captura já gravando; ao voltar, o take mais recente da
     * biblioteca é adotado automaticamente como voz.
     */
    private void recordNow() {
        awaitingTake = latestTakeId();
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(MainActivity.ACTION_RECORD_NOW);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        toast("Record your take, then come back here");
    }

    /** Maior _ID em Music/Sidemic — marca d'água para detectar um take novo. */
    private long latestTakeId() {
        Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Audio.Media.IS_PENDING + "=0",
                new String[]{REL_PATH + "%"},
                MediaStore.Audio.Media._ID + " DESC");
        if (c == null) return -1;
        try {
            return c.moveToFirst() ? c.getLong(0) : -1;
        } finally {
            c.close();
        }
    }

    /** Adota o take gravado enquanto o Studio estava em segundo plano. */
    private void adoptNewTakeIfAny() {
        if (awaitingTake < 0) return;
        Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME},
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Audio.Media.IS_PENDING + "=0",
                new String[]{REL_PATH + "%"},
                MediaStore.Audio.Media._ID + " DESC");
        if (c == null) return;
        try {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                if (id > awaitingTake) {
                    voiceUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY), id);
                    voiceLabel = c.getString(1);
                    voiceSummary.setText(voiceLabel + "  ·  just recorded");
                    voiceSummary.setTextColor(PAPER);
                    awaitingTake = -1;
                }
            }
        } finally {
            c.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        adoptNewTakeIfAny();
    }

    private void pickAudio(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("No file picker available.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        String name = displayName(uri);
        if (req == REQ_PICK_VOICE) {
            voiceUri = uri;
            voiceLabel = name;
            voiceSummary.setText(name);
            voiceSummary.setTextColor(PAPER);
        } else if (req == REQ_PICK_BEAT) {
            beatUri = uri;
            beatLabel = name;
            beatSummary.setText(name);
            beatSummary.setTextColor(PAPER);
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "audio";
    }

    // ── Beat Finder (Freesound) ────────────────────────────────────────

    private void promptBeatSearch() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final String key = p.getString(PREF_FREESOUND_KEY, "");
        if (key.isEmpty()) {
            showKeyDialog();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("trap beat, boom bap, 140 bpm…");
        input.setTextColor(PAPER);
        input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this)
                .setTitle("Find a beat")
                .setView(input)
                .setPositiveButton("Search", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        searchFreesound(input.getText().toString().trim(), key);
                    }
                })
                .setNeutralButton("Change API key", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showKeyDialog(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showKeyDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Freesound API key");
        input.setTextColor(PAPER);
        input.setHintTextColor(MUTED);
        new AlertDialog.Builder(this)
                .setTitle("Freesound API key")
                .setMessage("Beat search uses Freesound's official API — free Creative Commons audio. "
                        + "Create a free account at freesound.org, request an API key in your profile "
                        + "under 'API credentials', and paste it here. It is stored only on this phone.")
                .setView(input)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String k = input.getText().toString().trim();
                        if (k.isEmpty()) return;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(PREF_FREESOUND_KEY, k).apply();
                        toast("Key saved");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void searchFreesound(final String query, final String key) {
        if (query.isEmpty()) return;
        beatResults.removeAllViews();
        TextView loading = summaryBox("Searching…");
        beatResults.addView(loading, lp(8));

        pool.execute(new Runnable() {
            @Override public void run() {
                final List<String[]> found = new ArrayList<>();   // {name, previewUrl, licence}
                String error = null;
                HttpURLConnection conn = null;
                try {
                    String url = "https://freesound.org/apiv2/search/text/?query="
                            + URLEncoder.encode(query, "UTF-8")
                            + "&filter=duration:[10 TO 240]"
                            + "&fields=name,previews,license"
                            + "&page_size=8&token=" + URLEncoder.encode(key, "UTF-8");
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    int code = conn.getResponseCode();
                    if (code == 401 || code == 403) {
                        error = "Freesound rejected the API key.";
                    } else if (code != 200) {
                        error = "Freesound returned HTTP " + code;
                    } else {
                        StringBuilder sb = new StringBuilder();
                        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                        r.close();
                        JSONObject root = new JSONObject(sb.toString());
                        JSONArray results = root.optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject o = results.getJSONObject(i);
                                JSONObject prev = o.optJSONObject("previews");
                                if (prev == null) continue;
                                String u = prev.optString("preview-hq-mp3", prev.optString("preview-lq-mp3", ""));
                                if (u.isEmpty()) continue;
                                found.add(new String[]{o.optString("name", "beat"), u,
                                        o.optString("license", "")});
                            }
                        }
                    }
                } catch (Exception e) {
                    error = "Network error: " + e.getClass().getSimpleName();
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final String err = error;
                ui.post(new Runnable() {
                    @Override public void run() { showBeatResults(found, err); }
                });
            }
        });
    }

    private void showBeatResults(List<String[]> found, String error) {
        beatResults.removeAllViews();
        if (error != null) {
            TextView t = summaryBox(error);
            t.setTextColor(LIVE);
            beatResults.addView(t, lp(8));
            return;
        }
        if (found.isEmpty()) {
            beatResults.addView(summaryBox("Nothing found — try another word."), lp(8));
            return;
        }
        for (final String[] item : found) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(rect(SURFACE, 6, HAIRLINE));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(item[0]);
            t1.setTextSize(14);
            t1.setTextColor(PAPER);
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(shortLicence(item[2]));
            t2.setTextSize(11);
            t2.setTextColor(MUTED);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button use = solid("Use");
            use.setPadding(dp(14), dp(8), dp(14), dp(8));
            use.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { downloadBeat(item[0], item[1]); }
            });
            LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
            useLp.leftMargin = dp(8);
            row.addView(use, useLp);

            beatResults.addView(row, lp(8));
        }
    }

    private String shortLicence(String url) {
        if (url == null) return "";
        if (url.contains("zero")) return "CC0 — public domain";
        if (url.contains("by-nc")) return "CC BY-NC — non-commercial, credit required";
        if (url.contains("/by/")) return "CC BY — credit required";
        return "Creative Commons";
    }

    /** Baixa o preview do Freesound para a biblioteca e o adota como base. */
    private void downloadBeat(final String name, final String url) {
        renderStatus.setText("Downloading beat…");
        pool.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                Uri saved = null;
                HttpURLConnection conn = null;
                try {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    String safe = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
                    if (safe.isEmpty()) safe = "beat";
                    cv.put(MediaStore.Audio.Media.DISPLAY_NAME, "Beat_" + safe + ".mp3");
                    cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
                    cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
                    cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                    if (uri == null) throw new Exception("MediaStore insert failed");

                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(30000);
                    InputStream in = conn.getInputStream();
                    OutputStream out = cr.openOutputStream(uri);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();

                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    saved = uri;
                } catch (Exception e) {
                    error = "Download failed: " + e.getClass().getSimpleName();
                } finally {
                    if (conn != null) conn.disconnect();
                }

                final String err = error;
                final Uri result = saved;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (err != null) {
                            renderStatus.setText(err);
                            renderStatus.setTextColor(LIVE);
                        } else {
                            beatUri = result;
                            beatLabel = name;
                            beatSummary.setText(name + "  ·  from Freesound");
                            beatSummary.setTextColor(PAPER);
                            renderStatus.setText("Beat ready — render when you are");
                            renderStatus.setTextColor(MUTED);
                            beatResults.removeAllViews();
                        }
                    }
                });
            }
        });
    }

    // ── Render ─────────────────────────────────────────────────────────

    private void render() {
        if (voiceUri == null) { toast("Pick a vocal take first."); return; }
        renderBtn.setEnabled(false);
        renderBtn.setAlpha(0.35f);
        renderStatus.setText("Rendering… this runs on your phone, give it a moment");
        renderStatus.setTextColor(MINT);

        pool.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                Uri out = null;
                try {
                    AudioEngine.Clip voice = AudioEngine.decode(StudioActivity.this, voiceUri);
                    short[] pcm = processChain(voice.samples, voice.sampleRate);

                    if (beatUri != null) {
                        AudioEngine.Clip beat = AudioEngine.decode(StudioActivity.this, beatUri);
                        pcm = AudioEngine.mix(pcm, voice.sampleRate,
                                beat.samples, beat.sampleRate, voiceGain, beatGain);
                    }
                    AudioEngine.normalize(pcm, 0.95);

                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                    cv.put(MediaStore.Audio.Media.DISPLAY_NAME,
                            "Studio_" + Effects.PRESET_NAMES[presetIndex] + "_" + stamp + ".m4a");
                    cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                    cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
                    cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                    if (uri == null) throw new Exception("MediaStore insert failed");

                    ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w");
                    AudioEngine.encodeToM4a(pcm, voice.sampleRate, 192000, pfd.getFileDescriptor());
                    pfd.close();

                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    out = uri;
                } catch (Throwable t) {
                    error = "Render failed: " + t.getClass().getSimpleName();
                }

                final String err = error;
                final Uri result = out;
                ui.post(new Runnable() {
                    @Override public void run() {
                        renderBtn.setEnabled(true);
                        renderBtn.setAlpha(1f);
                        if (err != null) {
                            renderStatus.setText(err);
                            renderStatus.setTextColor(LIVE);
                            return;
                        }
                        renderUri = result;
                        renderStatus.setText("Rendered — saved to Music/Sidemic");
                        renderStatus.setTextColor(PAPER);
                        playRenderBtn.setEnabled(true);
                        playRenderBtn.setAlpha(1f);
                        shareRenderBtn.setEnabled(true);
                        shareRenderBtn.setAlpha(1f);
                    }
                });
            }
        });
    }

    /**
     * Audição rápida: processa só os primeiros segundos com o preset e o EQ
     * atuais, grava num arquivo de cache e toca. Serve para escolher o efeito
     * sem esperar o render do take inteiro.
     */
    private void previewEffect() {
        if (voiceUri == null) { toast("Pick or record a take first."); return; }
        if (preview != null && preview.isPlaying()) { stopPreview(); return; }

        previewBtn.setEnabled(false);
        previewBtn.setAlpha(0.35f);
        previewBtn.setText("Preparing preview…");

        pool.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                java.io.File outFile = null;
                try {
                    AudioEngine.Clip voice = AudioEngine.decode(StudioActivity.this, voiceUri);
                    int maxSamples = voice.sampleRate * 6;          // 6 segundos
                    short[] head = voice.samples.length > maxSamples
                            ? java.util.Arrays.copyOf(voice.samples, maxSamples)
                            : voice.samples;

                    short[] pcm = processChain(head, voice.sampleRate);

                    if (beatUri != null) {
                        AudioEngine.Clip beat = AudioEngine.decode(StudioActivity.this, beatUri);
                        pcm = AudioEngine.mix(pcm, voice.sampleRate,
                                beat.samples, beat.sampleRate, voiceGain, beatGain);
                    }
                    AudioEngine.normalize(pcm, 0.95);

                    outFile = new java.io.File(getCacheDir(), "preview.m4a");
                    if (outFile.exists() && !outFile.delete()) {
                        throw new java.io.IOException("cache file locked");
                    }
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                    AudioEngine.encodeToM4a(pcm, voice.sampleRate, 128000, fos.getFD());
                    fos.close();
                } catch (Throwable t) {
                    error = "Preview failed: " + t.getClass().getSimpleName();
                }

                final String err = error;
                final java.io.File result = outFile;
                ui.post(new Runnable() {
                    @Override public void run() {
                        previewBtn.setEnabled(true);
                        previewBtn.setAlpha(1f);
                        if (err != null) {
                            previewBtn.setText("Preview this effect (first 6 s)");
                            renderStatus.setText(err);
                            renderStatus.setTextColor(LIVE);
                            return;
                        }
                        previewFile = result;
                        playPreviewFile();
                    }
                });
            }
        });
    }

    private void playPreviewFile() {
        if (previewFile == null || !previewFile.exists()) return;
        stopPreview();
        try {
            preview = new MediaPlayer();
            preview.setDataSource(previewFile.getAbsolutePath());
            preview.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    previewBtn.setText("Preview this effect (first 6 s)");
                }
            });
            preview.prepare();
            preview.start();
            previewBtn.setText("Stop preview");
        } catch (Exception e) {
            toast("Could not play the preview.");
            stopPreview();
        }
    }

    private void togglePreview(Uri uri) {
        if (uri == null) return;
        if (preview != null && preview.isPlaying()) {
            stopPreview();
            return;
        }
        stopPreview();
        try {
            preview = new MediaPlayer();
            preview.setDataSource(this, uri);
            preview.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { playRenderBtn.setText("Play result"); }
            });
            preview.prepare();
            preview.start();
            playRenderBtn.setText("Stop");
        } catch (Exception e) {
            toast("Could not play the result.");
            stopPreview();
        }
    }

    private void stopPreview() {
        if (preview != null) {
            try { preview.stop(); } catch (Exception ignored) { }
            try { preview.release(); } catch (Exception ignored) { }
            preview = null;
        }
        if (playRenderBtn != null) playRenderBtn.setText("Play result");
        if (previewBtn != null && previewBtn.isEnabled()) {
            previewBtn.setText("Preview this effect (first 6 s)");
        }
    }

    private void shareRender() {
        if (renderUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/mp4");
        send.putExtra(Intent.EXTRA_STREAM, renderUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share track"));
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
