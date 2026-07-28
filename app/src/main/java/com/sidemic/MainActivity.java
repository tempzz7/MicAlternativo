package com.sidemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sidemic — the spare microphone your phone already has.
 *
 * Captura áudio por uma entrada selecionável (padrão CAMCORDER, o mic secundário)
 * para contornar microfone principal defeituoso, e envia a apps de mensagem.
 * Sem AndroidX; UI programática. minSdk 29 / targetSdk 34.
 */
public class MainActivity extends Activity {

    public static final String ACTION_RECORD_NOW = "com.sidemic.action.RECORD";

    private static final int REQ_RECORD_AUDIO = 1;
    private static final String PREFS = "sidemic";
    private static final String PREF_SOURCE = "audio_source";
    private static final String PREF_FAV_NAME = "fav_name";
    private static final String PREF_FAV_NUMBER = "fav_number";
    private static final String EXTRA_SEND_FAV = "send_to_favorite";
    private static final String REL_PATH = "Music/Sidemic";

    // ── Identidade visual ──────────────────────────────────────────────
    // Grafite quase-preto de viés quente + âmbar de VU meter analógico.
    private static final int INK        = Color.parseColor("#0B0A09");
    private static final int SURFACE    = Color.parseColor("#141210");
    private static final int HAIRLINE   = Color.parseColor("#2A2622");
    private static final int AMBER      = Color.parseColor("#E8A33D");
    private static final int AMBER_DEEP = Color.parseColor("#8A5A14");
    private static final int PAPER      = Color.parseColor("#F2EDE4");
    private static final int MUTED      = Color.parseColor("#8C837A");
    private static final int LIVE       = Color.parseColor("#D8544A");

    private static final int[] SOURCE_VALUES = {
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
    };
    private static final String[] SOURCE_TITLES = {
            "Câmera", "Principal", "Voz", "Chamada", "Sistema",
    };
    private static final String[] SOURCE_SUBS = {
            "mic de trás/cima — o que funciona",
            "mic de baixo — o defeituoso",
            "ganho cru, sem tratamento",
            "com cancelamento de eco",
            "escolha automática do Android",
    };

    // Destinos sugeridos (só aparecem se instalados)
    private static final String[][] TARGETS = {
            {"WhatsApp", "com.whatsapp", "com.whatsapp.w4b"},
            {"Instagram", "com.instagram.android", ""},
            {"Telegram", "org.telegram.messenger", "org.telegram.messenger.web"},
    };

    private MediaRecorder recorder;
    private MediaPlayer player;
    private Uri currentUri;
    private Uri lastRecordingUri;
    private boolean recording = false;
    private long recordStartMs = 0;
    private int selectedIndex = 0;
    private boolean autoSendToFav = false;

    private TextView statusText;
    private TextView timerText;
    private LevelMeter meter;
    private Button recordBtn;
    private Button playBtn;
    private Button favSendBtn;
    private Button otherShareBtn;
    private EditText favNameField;
    private EditText favNumberField;
    private LinearLayout listBox;
    private LinearLayout targetsBox;
    private final List<View> sourceRows = new ArrayList<>();
    private final List<Button> destButtons = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            if (recording && recorder != null) {
                int amp;
                try { amp = recorder.getMaxAmplitude(); } catch (RuntimeException e) { amp = 0; }
                meter.push((float) Math.sqrt(amp / 32767.0));
                long s = (System.currentTimeMillis() - recordStartMs) / 1000;
                timerText.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
                handler.postDelayed(this, 60);
            }
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ── Medidor de nível: barras que rolam, como um VU digital ─────────
    private static class LevelMeter extends View {
        private final float[] bars = new float[44];
        private int head = 0;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean live = false;

        LevelMeter(android.content.Context c) { super(c); }

        void push(float v) {
            bars[head] = Math.max(0f, Math.min(1f, v));
            head = (head + 1) % bars.length;
            live = true;
            invalidate();
        }

        void reset() {
            for (int i = 0; i < bars.length; i++) bars[i] = 0f;
            live = false;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float gap = w / bars.length * 0.34f;
            float bw = (w - gap * (bars.length - 1)) / bars.length;
            float mid = h / 2f;
            float minH = 2f * getResources().getDisplayMetrics().density;
            for (int i = 0; i < bars.length; i++) {
                float v = bars[(head + i) % bars.length];
                float bh = Math.max(minH, v * h * 0.92f);
                float x = i * (bw + gap);
                p.setColor(live ? blend(AMBER_DEEP, AMBER, v) : HAIRLINE);
                c.drawRoundRect(x, mid - bh / 2f, x + bw, mid + bh / 2f, bw / 2f, bw / 2f, p);
            }
        }

        private static int blend(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
            int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
            int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
            return Color.rgb(r, g, bl);
        }
    }

    // ── Helpers de estilo ──────────────────────────────────────────────

    private GradientDrawable rect(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(1) / 2), strokeColor);
        return d;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextSize(10);
        t.setTextColor(MUTED);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.22f);
        return t;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(HAIRLINE);
        return v;
    }

    private LinearLayout.LayoutParams lp(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private LinearLayout.LayoutParams thin(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        p.topMargin = dp(topMarginDp);
        return p;
    }

    private Button ghostButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(PAPER);
        b.setBackground(rect(SURFACE, 4, HAIRLINE));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private Button solidButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(INK);
        b.setBackground(rect(AMBER, 4, 0));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private EditText field(String hint, String value, boolean phone) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15);
        e.setTextColor(PAPER);
        e.setHintTextColor(MUTED);
        e.setSingleLine(true);
        e.setBackground(rect(SURFACE, 4, HAIRLINE));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (phone) e.setInputType(InputType.TYPE_CLASS_PHONE);
        return e;
    }

    // ── Ciclo de vida ──────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(INK);
        w.setNavigationBarColor(INK);

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int saved = prefs.getInt(PREF_SOURCE, MediaRecorder.AudioSource.CAMCORDER);
        for (int i = 0; i < SOURCE_VALUES.length; i++) if (SOURCE_VALUES[i] == saved) selectedIndex = i;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(INK);
        root.setPadding(dp(24), dp(28), dp(24), dp(40));

        // ── Marca ──
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackground(rect(AMBER, 5, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(10);
        brand.addView(dot, dotLp);

        TextView wordmark = new TextView(this);
        wordmark.setText("SIDEMIC");
        wordmark.setTextSize(19);
        wordmark.setTextColor(PAPER);
        wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wordmark.setLetterSpacing(0.28f);
        brand.addView(wordmark);
        root.addView(brand);

        TextView tagline = new TextView(this);
        tagline.setText("O microfone reserva do seu telefone");
        tagline.setTextSize(14);
        tagline.setTextColor(MUTED);
        root.addView(tagline, lp(6));

        root.addView(rule(), thin(24));

        // ── Entrada ──
        root.addView(label("Entrada"), lp(22));
        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < SOURCE_VALUES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));

            View pip = new View(this);
            pip.setTag("pip");
            LinearLayout.LayoutParams pipLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            pipLp.rightMargin = dp(12);
            row.addView(pip, pipLp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(SOURCE_TITLES[i]);
            t1.setTextSize(15);
            t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(SOURCE_SUBS[i]);
            t2.setTextSize(12);
            t2.setTextColor(MUTED);
            texts.addView(t2);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedIndex = idx;
                    prefs.edit().putInt(PREF_SOURCE, SOURCE_VALUES[idx]).apply();
                    paintSources();
                }
            });
            sourceRows.add(row);
            sources.addView(row, lp(i == 0 ? 8 : 6));
        }
        root.addView(sources);
        paintSources();

        root.addView(rule(), thin(24));

        // ── Captura ──
        root.addView(label("Captura"), lp(22));

        timerText = new TextView(this);
        timerText.setText("00:00");
        timerText.setTextSize(56);
        timerText.setTextColor(PAPER);
        timerText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        root.addView(timerText, lp(10));

        meter = new LevelMeter(this);
        LinearLayout.LayoutParams meterLp = lp(14);
        meterLp.height = dp(56);
        root.addView(meter, meterLp);

        statusText = new TextView(this);
        statusText.setText("Toque em capturar e fale voltado para as câmeras");
        statusText.setTextSize(12);
        statusText.setTextColor(MUTED);
        root.addView(statusText, lp(10));

        recordBtn = new Button(this);
        recordBtn.setText("Capturar");
        recordBtn.setAllCaps(false);
        recordBtn.setTextSize(16);
        recordBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        recordBtn.setTextColor(INK);
        recordBtn.setBackground(rect(AMBER, 4, 0));
        recordBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams recLp = lp(18);
        recLp.height = dp(58);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recording) stopRecording(true); else ensurePermissionThenRecord();
            }
        });
        root.addView(recordBtn, recLp);

        // ── Destino ──
        root.addView(rule(), thin(28));
        root.addView(label("Destino"), lp(22));

        playBtn = ghostButton("Ouvir a última captura");
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(lastRecordingUri); }
        });
        root.addView(playBtn, lp(10));

        favSendBtn = solidButton("Enviar ao favorito");
        favSendBtn.setVisibility(View.GONE);
        favSendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareToFavorite(lastRecordingUri); }
        });
        root.addView(favSendBtn, lp(8));

        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetsBox, lp(0));
        buildTargets();

        otherShareBtn = ghostButton("Outro aplicativo");
        otherShareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareGeneric(lastRecordingUri); }
        });
        root.addView(otherShareBtn, lp(8));
        destButtons.add(playBtn);
        destButtons.add(favSendBtn);
        destButtons.add(otherShareBtn);
        setDestinationEnabled(false);

        // ── Contato rápido ──
        root.addView(rule(), thin(28));
        root.addView(label("Contato rápido"), lp(22));

        TextView favHint = new TextView(this);
        favHint.setText("Quem recebe seus áudios com mais frequência. Cria um atalho de capturar-e-enviar na tela inicial.");
        favHint.setTextSize(12);
        favHint.setTextColor(MUTED);
        root.addView(favHint, lp(8));

        favNameField = field("Nome", prefs.getString(PREF_FAV_NAME, ""), false);
        root.addView(favNameField, lp(12));
        favNumberField = field("DDD + número", prefs.getString(PREF_FAV_NUMBER, ""), true);
        root.addView(favNumberField, lp(8));

        Button favSaveBtn = ghostButton("Salvar contato");
        favSaveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveFavorite(); }
        });
        root.addView(favSaveBtn, lp(10));

        // ── Biblioteca ──
        root.addView(rule(), thin(28));
        root.addView(label("Biblioteca"), lp(22));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, lp(8));

        // ── Rodapé ──
        root.addView(rule(), thin(28));
        Button helpBtn = ghostButton("Como funciona");
        helpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        });
        root.addView(helpBtn, lp(22));

        TextView foot = new TextView(this);
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        foot.setText("Sidemic " + v + " · sem internet, sem conta, sem anúncio");
        foot.setTextSize(11);
        foot.setTextColor(MUTED);
        root.addView(foot, lp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.addView(root);
        setContentView(scroll);

        refreshList();
        applyFavoriteToUi();
        publishShortcuts();
        handleIntent(getIntent());
    }

    /** Um botão por app de mensagem instalado (WhatsApp, Instagram, Telegram). */
    private void buildTargets() {
        targetsBox.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String[] t : TARGETS) {
            final String pkg = firstInstalled(pm, t[1], t[2]);
            if (pkg == null) continue;
            Button b = solidButton("Enviar no " + t[0]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { shareTo(lastRecordingUri, pkg); }
            });
            targetsBox.addView(b, lp(8));
            destButtons.add(b);
        }
    }

    private String firstInstalled(PackageManager pm, String... pkgs) {
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            try { pm.getPackageInfo(p, 0); return p; } catch (Exception ignored) { }
        }
        return null;
    }

    private void paintSources() {
        for (int i = 0; i < sourceRows.size(); i++) {
            View row = sourceRows.get(i);
            boolean on = (i == selectedIndex);
            row.setBackground(rect(on ? SURFACE : INK, 4, on ? AMBER_DEEP : HAIRLINE));
            View pip = row.findViewWithTag("pip");
            if (pip != null) pip.setBackground(rect(on ? AMBER : HAIRLINE, 3, 0));
            LinearLayout texts = (LinearLayout) ((LinearLayout) row).getChildAt(1);
            ((TextView) texts.getChildAt(0)).setTextColor(on ? PAPER : MUTED);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_RECORD_NOW.equals(intent.getAction()) && !recording) {
            autoSendToFav = intent.getBooleanExtra(EXTRA_SEND_FAV, false);
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (!recording) ensurePermissionThenRecord(); }
            }, 150);
        }
    }

    // ── Atalhos ────────────────────────────────────────────────────────

    private Icon shortcutIcon() {
        Bitmap bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(INK);
        c.drawCircle(54, 54, 54, p);
        p.setColor(AMBER);
        c.drawRoundRect(46, 24, 62, 62, 8, 8, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        c.drawArc(34, 40, 74, 78, 0, 180, false, p);
        return Icon.createWithBitmap(bmp);
    }

    private void publishShortcuts() {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) return;
            List<ShortcutInfo> list = new ArrayList<>();

            Intent i = new Intent(this, MainActivity.class);
            i.setAction(ACTION_RECORD_NOW);
            list.add(new ShortcutInfo.Builder(this, "record_now")
                    .setShortLabel("Capturar")
                    .setLongLabel("Capturar com o mic reserva")
                    .setIcon(shortcutIcon())
                    .setIntent(i)
                    .build());

            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            String favName = p.getString(PREF_FAV_NAME, "");
            String favNumber = p.getString(PREF_FAV_NUMBER, "");
            if (!favName.isEmpty() && !favNumber.isEmpty()) {
                Intent fi = new Intent(this, MainActivity.class);
                fi.setAction(ACTION_RECORD_NOW);
                fi.putExtra(EXTRA_SEND_FAV, true);
                list.add(new ShortcutInfo.Builder(this, "record_fav")
                        .setShortLabel("Áudio p/ " + favName)
                        .setLongLabel("Capturar e enviar para " + favName)
                        .setIcon(shortcutIcon())
                        .setIntent(fi)
                        .build());
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) { }
    }

    // ── Permissão ──────────────────────────────────────────────────────

    private void ensurePermissionThenRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Acesso ao microfone")
                    .setMessage("O Sidemic precisa do microfone para capturar o áudio que você vai enviar. Nada sai do aparelho — o app não usa internet.")
                    .setPositiveButton("Permitir", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int x) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                        }
                    })
                    .setNegativeButton("Agora não", null)
                    .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req != REQ_RECORD_AUDIO) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissão bloqueada")
                    .setMessage("O acesso ao microfone foi negado permanentemente. Abra as configurações do app e ative Microfone.")
                    .setPositiveButton("Configurações", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int x) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton("Fechar", null)
                    .show();
        } else {
            toast("Sem a permissão não é possível capturar.");
        }
    }

    // ── Captura ────────────────────────────────────────────────────────

    private void startRecording() {
        stopPlayback();
        ContentResolver cr = getContentResolver();
        String name = "Sidemic_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) { toast("Não foi possível criar o arquivo."); return; }

        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w")) {
            recorder = new MediaRecorder();
            recorder.setAudioSource(SOURCE_VALUES[selectedIndex]);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            cleanupRecorder();
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            new AlertDialog.Builder(this)
                    .setTitle("Entrada indisponível")
                    .setMessage("Esta entrada falhou neste aparelho. Escolha outra na lista — \"Câmera\" costuma ser a que funciona.")
                    .setPositiveButton("Entendi", null)
                    .show();
            return;
        }

        currentUri = uri;
        recording = true;
        recordStartMs = System.currentTimeMillis();
        recordBtn.setText("Parar");
        recordBtn.setBackground(rect(LIVE, 4, 0));
        recordBtn.setTextColor(PAPER);
        statusText.setText("Capturando · entrada " + SOURCE_TITLES[selectedIndex].toLowerCase(Locale.ROOT));
        statusText.setTextColor(AMBER);
        setDestinationEnabled(false);
        handler.post(meterTick);
    }

    private void stopRecording(boolean keep) {
        recording = false;
        handler.removeCallbacks(meterTick);
        boolean ok = false;
        try {
            if (recorder != null) { recorder.stop(); ok = true; }
        } catch (RuntimeException e) {
            ok = false;
        } finally {
            cleanupRecorder();
        }

        ContentResolver cr = getContentResolver();
        if (ok && keep && currentUri != null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(currentUri, cv, null, null);
            lastRecordingUri = currentUri;
            statusText.setText("Captura salva — ouça antes de enviar");
            statusText.setTextColor(MUTED);
            setDestinationEnabled(true);
            if (autoSendToFav) {
                autoSendToFav = false;
                shareToFavorite(lastRecordingUri);
            }
        } else {
            if (currentUri != null) { try { cr.delete(currentUri, null, null); } catch (Exception ignored) { } }
            if (!ok) {
                statusText.setText("Captura curta demais — tente novamente");
                statusText.setTextColor(LIVE);
            }
        }
        currentUri = null;
        recordBtn.setText("Capturar");
        recordBtn.setBackground(rect(AMBER, 4, 0));
        recordBtn.setTextColor(INK);
        meter.reset();
        refreshList();
    }

    private void setDestinationEnabled(boolean on) {
        for (Button b : destButtons) {
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    // ── Reprodução ─────────────────────────────────────────────────────

    private void togglePlay(Uri uri) {
        if (uri == null) return;
        if (player != null && player.isPlaying()) { stopPlayback(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { playBtn.setText("Ouvir a última captura"); }
            });
            player.prepare();
            player.start();
            playBtn.setText("Parar reprodução");
        } catch (Exception e) {
            toast("Não foi possível reproduzir.");
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (playBtn != null) playBtn.setText("Ouvir a última captura");
    }

    // ── Favorito e envio ───────────────────────────────────────────────

    private String normalizeNumber(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return "";
        if (d.length() <= 11) d = "55" + d;
        return d;
    }

    private void saveFavorite() {
        String name = favNameField.getText().toString().trim();
        String number = normalizeNumber(favNumberField.getText().toString());
        if (number.length() < 12) { toast("Informe DDD + número"); return; }
        if (name.isEmpty()) name = "favorito";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_FAV_NAME, name)
                .putString(PREF_FAV_NUMBER, number)
                .apply();
        toast("Contato salvo");
        applyFavoriteToUi();
        publishShortcuts();
    }

    private void applyFavoriteToUi() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = p.getString(PREF_FAV_NAME, "");
        String number = p.getString(PREF_FAV_NUMBER, "");
        if (name.isEmpty() || number.isEmpty()) {
            favSendBtn.setVisibility(View.GONE);
        } else {
            favSendBtn.setVisibility(View.VISIBLE);
            favSendBtn.setText("Enviar para " + name);
        }
    }

    /**
     * Abre a conversa do favorito no WhatsApp com o áudio anexado.
     * O extra "jid" não é documentado: versões que o ignoram apenas
     * exibem o seletor de conversa normal.
     */
    private void shareToFavorite(Uri uri) {
        if (uri == null) return;
        String number = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_FAV_NUMBER, "");
        if (number.isEmpty()) { shareGeneric(uri); return; }
        String[] pkgs = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String pkg : pkgs) {
            try {
                Intent wa = new Intent(Intent.ACTION_SEND);
                wa.setType("audio/mp4");
                wa.putExtra(Intent.EXTRA_STREAM, uri);
                wa.putExtra("jid", number + "@s.whatsapp.net");
                wa.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                wa.setPackage(pkg);
                startActivity(wa);
                return;
            } catch (Exception ignored) { }
        }
        shareGeneric(uri);
    }

    private void shareTo(Uri uri, String pkg) {
        if (uri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio/mp4");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setPackage(pkg);
            startActivity(i);
        } catch (Exception e) {
            shareGeneric(uri);
        }
    }

    private void shareGeneric(Uri uri) {
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Enviar por"));
    }

    // ── Biblioteca ─────────────────────────────────────────────────────

    private void refreshList() {
        listBox.removeAllViews();
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
        };
        Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                proj,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Audio.Media.IS_PENDING + "=0",
                new String[]{REL_PATH + "%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC");
        if (c == null) return;
        int shown = 0;
        try {
            while (c.moveToNext() && shown < 20) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long durMs = c.getLong(2);
                final Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(12), 0, dp(12));

                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t1 = new TextView(this);
                t1.setText(prettyStamp(name.replace("Sidemic_", "").replace(".m4a", "")));
                t1.setTextSize(14);
                t1.setTextColor(PAPER);
                t1.setSingleLine(true);
                t1.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(t1);
                TextView t2 = new TextView(this);
                long s = durMs / 1000;
                t2.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
                t2.setTextSize(12);
                t2.setTextColor(MUTED);
                texts.addView(t2);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button p = new Button(this);
                p.setText("Ouvir");
                p.setAllCaps(false);
                p.setTextSize(13);
                p.setTextColor(PAPER);
                p.setBackground(rect(SURFACE, 4, HAIRLINE));
                p.setStateListAnimator(null);
                p.setMinWidth(0); p.setMinimumWidth(0);
                p.setPadding(dp(14), dp(8), dp(14), dp(8));
                p.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { togglePlay(uri); }
                });
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
                pLp.leftMargin = dp(8);
                row.addView(p, pLp);

                Button sh = new Button(this);
                sh.setText("Enviar");
                sh.setAllCaps(false);
                sh.setTextSize(13);
                sh.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                sh.setTextColor(INK);
                sh.setBackground(rect(AMBER, 4, 0));
                sh.setStateListAnimator(null);
                sh.setMinWidth(0); sh.setMinimumWidth(0);
                sh.setPadding(dp(14), dp(8), dp(14), dp(8));
                sh.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { shareGeneric(uri); }
                });
                LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
                shLp.leftMargin = dp(8);
                row.addView(sh, shLp);

                listBox.addView(row);
                if (shown < 19) listBox.addView(rule(), thin(0));
                shown++;
            }
        } finally {
            c.close();
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("Nenhuma captura ainda.");
            empty.setTextSize(13);
            empty.setTextColor(MUTED);
            listBox.addView(empty, lp(4));
        }
    }

    /** 20260728_193045 → 28/07 · 19:30 */
    private String prettyStamp(String s) {
        try {
            return s.substring(6, 8) + "/" + s.substring(4, 6) + " · "
                    + s.substring(9, 11) + ":" + s.substring(11, 13);
        } catch (Exception e) {
            return s;
        }
    }

    // ── Ajuda ──────────────────────────────────────────────────────────

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Como funciona")
                .setMessage("Seu telefone tem mais de um microfone. Quando o principal (o de baixo) " +
                        "quebra ou entope, os vídeos continuam com som — porque a câmera usa outro microfone.\n\n" +
                        "O Sidemic captura por esse microfone reserva (entrada \"Câmera\") e envia a gravação " +
                        "ao WhatsApp, Instagram ou Telegram como áudio comum.\n\n" +
                        "LIMITES REAIS\n" +
                        "· Ligações continuam exigindo viva-voz ou fone com microfone — o Android não permite " +
                        "trocar a entrada de chamadas sem root.\n" +
                        "· O botão de gravar dentro do WhatsApp segue usando o microfone com defeito. " +
                        "O caminho é capturar aqui e enviar.\n" +
                        "· Um fone com microfone redireciona tudo, inclusive chamadas.\n\n" +
                        "Fale voltado para a parte de cima/trás do aparelho. Nada sai do seu telefone.")
                .setPositiveButton("Fechar", null)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (recording) stopRecording(true);
        stopPlayback();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
