package br.com.micalternativo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MicAlternativo — grava áudio pela fonte de microfone que ainda funciona
 * (padrão: CAMCORDER, o mic da câmera) e compartilha no WhatsApp.
 * Sem AndroidX, UI 100% programática. minSdk 29 / targetSdk 34.
 */
public class MainActivity extends Activity {

    public static final String ACTION_RECORD_NOW = "br.com.micalternativo.action.RECORD";

    private static final int REQ_RECORD_AUDIO = 1;
    private static final String PREFS = "micalternativo";
    private static final String PREF_SOURCE = "audio_source";
    private static final String REL_PATH = "Music/MicAlternativo";

    // Paleta
    private static final int BG      = Color.parseColor("#0E1613");
    private static final int CARD    = Color.parseColor("#17221D");
    private static final int EDGE    = Color.parseColor("#26362E");
    private static final int ACCENT  = Color.parseColor("#2FBF9A");
    private static final int ACC_DK  = Color.parseColor("#0B2E26");
    private static final int TEXT    = Color.parseColor("#ECF4F0");
    private static final int MUTED   = Color.parseColor("#8FA69D");
    private static final int RED     = Color.parseColor("#E5484D");

    private static final int[] SOURCE_VALUES = {
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
    };
    private static final String[] SOURCE_LABELS = {
            "Microfone da câmera (trás/cima) — recomendado",
            "Microfone principal (o de baixo, defeituoso)",
            "Reconhecimento de voz (som mais cru)",
            "Chamada de vídeo (cancela eco)",
            "Padrão do sistema",
    };

    private MediaRecorder recorder;
    private MediaPlayer player;
    private Uri currentUri;       // gravação em andamento (IS_PENDING=1)
    private Uri lastRecordingUri; // última gravação concluída
    private boolean recording = false;
    private long recordStartMs = 0;

    private TextView statusText;
    private TextView timerText;
    private ProgressBar levelBar;
    private Button recordBtn;
    private Button playBtn;
    private Button shareBtn;
    private Button otherShareBtn;
    private RadioGroup sourceGroup;
    private LinearLayout listBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            if (recording && recorder != null) {
                int amp;
                try { amp = recorder.getMaxAmplitude(); } catch (RuntimeException e) { amp = 0; }
                // escala 0..32767 → 0..100 (raiz quadrada aproxima percepção)
                int pct = (int) (Math.sqrt(amp / 32767.0) * 100);
                levelBar.setProgress(pct);
                long s = (System.currentTimeMillis() - recordStartMs) / 1000;
                timerText.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
                handler.postDelayed(this, 150);
            }
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------- Helpers visuais ----------

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(CARD, 18, EDGE));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        return c;
    }

    private TextView eyebrow(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.ROOT));
        t.setTextSize(11);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private Button pillButton(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        b.setStateListAnimator(null);
        if (filled) {
            b.setBackground(roundRect(ACCENT, 24, 0));
            b.setTextColor(Color.parseColor("#06130F"));
        } else {
            b.setBackground(roundRect(Color.TRANSPARENT, 24, ACCENT));
            b.setTextColor(ACCENT);
        }
        return b;
    }

    private LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));

        // ---- Cabeçalho ----
        TextView hello = eyebrow("Seu microfone reserva");
        root.addView(hello);

        TextView title = new TextView(this);
        title.setText("MicAlternativo");
        title.setTextSize(30);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, wrap(dp(2)));

        TextView subtitle = new TextView(this);
        subtitle.setText("Grave com o microfone que funciona e envie no WhatsApp");
        subtitle.setTextSize(14);
        subtitle.setTextColor(MUTED);
        root.addView(subtitle, wrap(dp(2)));

        // ---- Card 1: fonte ----
        LinearLayout srcCard = card();
        srcCard.addView(eyebrow("1 · Microfone"));
        sourceGroup = new RadioGroup(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int savedSource = prefs.getInt(PREF_SOURCE, MediaRecorder.AudioSource.CAMCORDER);
        for (int i = 0; i < SOURCE_VALUES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(SOURCE_LABELS[i]);
            rb.setTextSize(14);
            rb.setTextColor(TEXT);
            rb.setButtonTintList(ColorStateList.valueOf(ACCENT));
            rb.setId(1000 + i);
            rb.setPadding(dp(4), dp(8), 0, dp(8));
            sourceGroup.addView(rb);
            if (SOURCE_VALUES[i] == savedSource) rb.setChecked(true);
        }
        sourceGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                int idx = id - 1000;
                if (idx >= 0 && idx < SOURCE_VALUES.length) {
                    prefs.edit().putInt(PREF_SOURCE, SOURCE_VALUES[idx]).apply();
                    toast("Microfone salvo como padrão ✓");
                }
            }
        });
        srcCard.addView(sourceGroup, wrap(dp(6)));
        root.addView(srcCard, wrap(dp(20)));

        // ---- Card 2: gravação ----
        LinearLayout recCard = card();
        recCard.addView(eyebrow("2 · Gravar"));

        timerText = new TextView(this);
        timerText.setText("00:00");
        timerText.setTextSize(44);
        timerText.setTextColor(TEXT);
        timerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        timerText.setGravity(Gravity.CENTER_HORIZONTAL);
        recCard.addView(timerText, wrap(dp(8)));

        levelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(100);
        levelBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        levelBar.setProgressBackgroundTintList(ColorStateList.valueOf(EDGE));
        LinearLayout.LayoutParams barLp = wrap(dp(6));
        barLp.height = dp(10);
        levelBar.setBackground(roundRect(EDGE, 6, 0));
        recCard.addView(levelBar, barLp);

        TextView levelHint = new TextView(this);
        levelHint.setText("A barra mexe enquanto você fala? Então esse mic capta ✓");
        levelHint.setTextSize(12);
        levelHint.setTextColor(MUTED);
        levelHint.setGravity(Gravity.CENTER_HORIZONTAL);
        recCard.addView(levelHint, wrap(dp(4)));

        // botão redondo gigante
        recordBtn = new Button(this);
        recordBtn.setText("● GRAVAR");
        recordBtn.setAllCaps(false);
        recordBtn.setTextSize(17);
        recordBtn.setTypeface(Typeface.DEFAULT_BOLD);
        recordBtn.setTextColor(Color.parseColor("#06130F"));
        recordBtn.setBackground(roundRect(ACCENT, 32, 0));
        recordBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams recLp = wrap(dp(14));
        recLp.height = dp(60);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recording) stopRecording(true); else ensurePermissionThenRecord();
            }
        });
        recCard.addView(recordBtn, recLp);

        statusText = new TextView(this);
        statusText.setText("Pronto para gravar");
        statusText.setTextSize(13);
        statusText.setTextColor(MUTED);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        recCard.addView(statusText, wrap(dp(8)));

        root.addView(recCard, wrap(dp(14)));

        // ---- Card 3: ouvir e enviar ----
        LinearLayout sendCard = card();
        sendCard.addView(eyebrow("3 · Conferir e enviar"));

        playBtn = pillButton("▶  Ouvir última gravação", false);
        playBtn.setEnabled(false);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(lastRecordingUri); }
        });
        sendCard.addView(playBtn, wrap(dp(10)));

        shareBtn = pillButton("Enviar no WhatsApp", true);
        shareBtn.setEnabled(false);
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { share(lastRecordingUri); }
        });
        sendCard.addView(shareBtn, wrap(dp(8)));

        otherShareBtn = pillButton("Enviar por outro app", false);
        otherShareBtn.setEnabled(false);
        otherShareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareGeneric(lastRecordingUri); }
        });
        sendCard.addView(otherShareBtn, wrap(dp(8)));

        root.addView(sendCard, wrap(dp(14)));

        // ---- Card 4: histórico ----
        LinearLayout histCard = card();
        histCard.addView(eyebrow("Gravações anteriores"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        histCard.addView(listBox, wrap(dp(6)));
        root.addView(histCard, wrap(dp(14)));

        // ---- Ajuda ----
        Button helpBtn = pillButton("Como funciona / limites", false);
        helpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        });
        root.addView(helpBtn, wrap(dp(16)));

        TextView foot = new TextView(this);
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        foot.setText("v" + v + " · 100% offline · nada sai do seu celular");
        foot.setTextSize(11);
        foot.setTextColor(MUTED);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(foot, wrap(dp(12)));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);

        refreshList();
        publishRecordShortcut();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Atalho do launcher (segurar o ícone) e do bloco rápido: abre já gravando. */
    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_RECORD_NOW.equals(intent.getAction()) && !recording) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (!recording) ensurePermissionThenRecord(); }
            }, 150);
        }
    }

    private void publishRecordShortcut() {
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            Intent i = new Intent(this, MainActivity.class);
            i.setAction(ACTION_RECORD_NOW);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(108, 108,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(ACCENT);
            cv.drawCircle(54, 54, 50, paint);
            paint.setColor(Color.parseColor("#06130F"));
            cv.drawCircle(54, 54, 20, paint);
            android.content.pm.ShortcutInfo si = new android.content.pm.ShortcutInfo.Builder(this, "record_now")
                    .setShortLabel("Gravar agora")
                    .setLongLabel("Gravar agora com o mic que funciona")
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
                    .setIntent(i)
                    .build();
            sm.setDynamicShortcuts(java.util.Collections.singletonList(si));
        } catch (Exception ignored) { }
    }

    // ---------- Permissão ----------

    private void ensurePermissionThenRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Permissão de microfone")
                    .setMessage("O app precisa da permissão de microfone para gravar o áudio que você vai enviar. Nada sai do seu aparelho — o app não usa internet.")
                    .setPositiveButton("Pedir permissão", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int x) {
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
                    .setMessage("A permissão de microfone foi negada permanentemente. Para gravar, abra as configurações do app e permita o Microfone.")
                    .setPositiveButton("Abrir configurações", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int x) {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        }
                    })
                    .setNegativeButton("Fechar", null)
                    .show();
        } else {
            toast("Sem a permissão não dá para gravar.");
        }
    }

    // ---------- Gravação ----------

    private int selectedSource() {
        int idx = sourceGroup.getCheckedRadioButtonId() - 1000;
        if (idx < 0 || idx >= SOURCE_VALUES.length) return MediaRecorder.AudioSource.CAMCORDER;
        return SOURCE_VALUES[idx];
    }

    private void startRecording() {
        stopPlayback();
        ContentResolver cr = getContentResolver();
        String name = "MicAlt_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) { toast("Não consegui criar o arquivo de gravação."); return; }

        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w")) {
            recorder = new MediaRecorder();
            recorder.setAudioSource(selectedSource());
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            // Fonte não suportada, mic ocupado (erro -19) etc. — não travar o app
            cleanupRecorder();
            try { cr.delete(uri, null, null); } catch (Exception ignored) {}
            new AlertDialog.Builder(this)
                    .setTitle("Não deu para gravar com essa fonte")
                    .setMessage("Esse microfone/fonte falhou neste aparelho. Tente outra opção da lista — no A15, a \"Microfone da câmera\" costuma funcionar.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        currentUri = uri;
        recording = true;
        recordStartMs = System.currentTimeMillis();
        recordBtn.setText("■ PARAR");
        recordBtn.setBackground(roundRect(RED, 32, 0));
        recordBtn.setTextColor(TEXT);
        statusText.setText("Gravando… fale voltado para a parte de CIMA/TRÁS do celular");
        statusText.setTextColor(ACCENT);
        playBtn.setEnabled(false);
        shareBtn.setEnabled(false);
        otherShareBtn.setEnabled(false);
        handler.post(meterTick);
    }

    private void stopRecording(boolean keep) {
        recording = false;
        handler.removeCallbacks(meterTick);
        boolean ok = false;
        try {
            if (recorder != null) { recorder.stop(); ok = true; }
        } catch (RuntimeException e) {
            ok = false; // gravação curta demais / sem dados
        } finally {
            cleanupRecorder();
        }

        ContentResolver cr = getContentResolver();
        if (ok && keep && currentUri != null) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(currentUri, cv, null, null);
            lastRecordingUri = currentUri;
            statusText.setText("Gravação salva ✓  Ouça antes de enviar.");
            statusText.setTextColor(ACCENT);
            playBtn.setEnabled(true);
            shareBtn.setEnabled(true);
            otherShareBtn.setEnabled(true);
        } else {
            if (currentUri != null) { try { cr.delete(currentUri, null, null); } catch (Exception ignored) {} }
            if (!ok) { statusText.setText("Gravação muito curta — tente de novo."); statusText.setTextColor(RED); }
        }
        currentUri = null;
        recordBtn.setText("● GRAVAR");
        recordBtn.setBackground(roundRect(ACCENT, 32, 0));
        recordBtn.setTextColor(Color.parseColor("#06130F"));
        levelBar.setProgress(0);
        refreshList();
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    // ---------- Playback ----------

    private void togglePlay(Uri uri) {
        if (uri == null) return;
        if (player != null && player.isPlaying()) { stopPlayback(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { playBtn.setText("▶  Ouvir última gravação"); }
            });
            player.prepare();
            player.start();
            playBtn.setText("■  Parar de ouvir");
        } catch (Exception e) {
            toast("Não consegui tocar essa gravação.");
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (playBtn != null) playBtn.setText("▶  Ouvir última gravação");
    }

    // ---------- Compartilhar ----------

    private void share(Uri uri) {
        if (uri == null) return;
        // Direto no WhatsApp (cai na escolha de contato); fallback: chooser padrão
        String[] waPkgs = {"com.whatsapp", "com.whatsapp.w4b"};
        for (String pkg : waPkgs) {
            try {
                Intent wa = new Intent(Intent.ACTION_SEND);
                wa.setType("audio/mp4");
                wa.putExtra(Intent.EXTRA_STREAM, uri);
                wa.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                wa.setPackage(pkg);
                startActivity(wa);
                return;
            } catch (Exception ignored) { }
        }
        shareGeneric(uri);
    }

    private void shareGeneric(Uri uri) {
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Enviar áudio por…"));
    }

    // ---------- Lista ----------

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
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(roundRect(BG, 12, EDGE));
                row.setPadding(dp(12), dp(8), dp(8), dp(8));
                LinearLayout.LayoutParams rowLp = wrap(dp(8));

                TextView label = new TextView(this);
                long s = durMs / 1000;
                String nice = name.replace("MicAlt_", "").replace(".m4a", "");
                label.setText(nice + " · " + String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
                label.setTextSize(13);
                label.setTextColor(TEXT);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(label, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                Button p = new Button(this);
                p.setText("▶");
                p.setAllCaps(false);
                p.setTextColor(ACCENT);
                p.setTextSize(16);
                p.setBackground(roundRect(Color.TRANSPARENT, 20, ACCENT));
                p.setStateListAnimator(null);
                p.setMinWidth(0); p.setMinimumWidth(0);
                p.setPadding(dp(14), dp(4), dp(14), dp(4));
                final Uri rowUri = uri;
                p.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { togglePlay(rowUri); }
                });
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
                pLp.leftMargin = dp(8);
                row.addView(p, pLp);

                Button sh = new Button(this);
                sh.setText("Enviar");
                sh.setAllCaps(false);
                sh.setTextSize(13);
                sh.setTypeface(Typeface.DEFAULT_BOLD);
                sh.setTextColor(Color.parseColor("#06130F"));
                sh.setBackground(roundRect(ACCENT, 20, 0));
                sh.setStateListAnimator(null);
                sh.setMinWidth(0); sh.setMinimumWidth(0);
                sh.setPadding(dp(16), dp(4), dp(16), dp(4));
                sh.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { share(rowUri); }
                });
                LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
                shLp.leftMargin = dp(8);
                row.addView(sh, shLp);

                listBox.addView(row, rowLp);
                shown++;
            }
        } finally {
            c.close();
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("Nenhuma gravação ainda — toque em ● GRAVAR acima.");
            empty.setTextSize(13);
            empty.setTextColor(MUTED);
            listBox.addView(empty, wrap(dp(4)));
        }
    }

    // ---------- Ajuda ----------

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Como o MicAlternativo funciona")
                .setMessage("Seu celular tem mais de um microfone. Quando o principal (o furinho de baixo) " +
                        "quebra ou entope, os vídeos continuam com som — porque a câmera usa OUTRO microfone.\n\n" +
                        "Este app grava usando esse microfone que funciona (opção \"Microfone da câmera\") " +
                        "e compartilha a gravação no WhatsApp como áudio normal.\n\n" +
                        "O QUE ELE NÃO MUDA:\n" +
                        "• Ligações continuam precisando de viva-voz ou fone com microfone (o Android não " +
                        "permite trocar o microfone de chamadas sem root).\n" +
                        "• O botão de gravar DENTRO do WhatsApp continua usando o mic defeituoso — o fluxo é: " +
                        "grave AQUI e toque em Enviar.\n\n" +
                        "Dica: fale voltado para a parte de cima/trás do aparelho, perto das câmeras. " +
                        "Tudo fica no seu celular; o app não usa internet.")
                .setPositiveButton("Entendi", null)
                .show();
    }

    // ---------- Ciclo de vida ----------

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
