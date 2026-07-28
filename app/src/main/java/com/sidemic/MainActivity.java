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
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
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
 * Records through a selectable input (CAMCORDER by default, the secondary mic)
 * to work around a failed primary microphone, then shares to messaging apps.
 * No AndroidX; programmatic UI. minSdk 29 / targetSdk 34.
 */
public class MainActivity extends Activity {

    public static final String ACTION_RECORD_NOW = "com.sidemic.action.RECORD";

    private static final int REQ_RECORD_AUDIO = 1;
    private static final int REQ_PICK_CONTACT = 2;
    private static final String PREFS = "sidemic";
    private static final String PREF_SOURCE = "audio_source";
    private static final String PREF_FAV_NAME = "fav_name";
    private static final String PREF_FAV_NUMBER = "fav_number";
    private static final String PREF_QUALITY = "quality";
    private static final String PREF_CHANNELS = "channels";
    private static final String EXTRA_SEND_FAV = "send_to_favorite";
    private static final String REL_PATH = "Music/Sidemic";

    // Quality presets: label, sample rate, bitrate
    private static final String[] QUALITY_LABELS = {"Voice", "High", "Studio"};
    private static final String[] QUALITY_SUBS = {
            "44.1 kHz · 128 kbps — smallest files",
            "48 kHz · 192 kbps — clearer voice",
            "48 kHz · 320 kbps — music-grade",
    };
    private static final int[] QUALITY_RATE = {44100, 48000, 48000};
    private static final int[] QUALITY_BITRATE = {128000, 192000, 320000};

    // ── Brand palette ──────────────────────────────────────────────────
    // Cool graphite with a mint signal colour — studio hardware, not neon.
    private static final int INK        = Color.parseColor("#080B0D");
    private static final int SURFACE    = Color.parseColor("#111619");
    private static final int RAISED     = Color.parseColor("#181F23");
    private static final int HAIRLINE   = Color.parseColor("#232C31");
    private static final int MINT       = Color.parseColor("#3DDCA4");
    private static final int MINT_DEEP  = Color.parseColor("#1B6A50");
    private static final int PAPER      = Color.parseColor("#E8EFEC");
    private static final int MUTED      = Color.parseColor("#7B8A88");
    private static final int LIVE       = Color.parseColor("#F0616B");

    private static final int[] SOURCE_VALUES = {
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
    };
    private static final String[] SOURCE_TITLES = {
            "Camera mic", "Bottom mic", "Voice", "Call", "System",
    };
    private static final String[] SOURCE_SUBS = {
            "rear / top capsule — usually the working one",
            "the one that commonly fails",
            "raw gain, no processing",
            "echo cancellation on",
            "let Android decide",
    };

    /** Share targets. Instagram is excluded: it rejects audio/* intents. */
    private static final String[][] TARGETS = {
            {"WhatsApp", "com.whatsapp", "com.whatsapp.w4b"},
            {"Telegram", "org.telegram.messenger", "org.telegram.messenger.web"},
            {"Signal", "org.thoughtcrime.securesms", ""},
    };

    private MediaRecorder recorder;
    private MediaPlayer player;
    private Uri currentUri;
    private Uri lastRecordingUri;
    private boolean recording = false;
    private boolean monitoring = false;   // Test tone: level only, nothing written
    private long recordStartMs = 0;
    private int selectedIndex = 0;
    private int qualityIndex = 0;
    private boolean stereo = false;
    private boolean autoSendToFav = false;
    private Uri monitorUri;               // scratch row deleted when the test ends

    private TextView statusText;
    private TextView timerText;
    private LevelMeter meter;
    private Button recordBtn;
    private Button testBtn;
    private Button playBtn;
    private LinearLayout qualityBox;
    private Button channelBtn;
    private final List<View> qualityRows = new ArrayList<>();
    private Button favSendBtn;
    private Button otherShareBtn;
    private Button pickContactBtn;
    private TextView favSummary;
    private LinearLayout listBox;
    private LinearLayout targetsBox;
    private final List<View> sourceRows = new ArrayList<>();
    private final List<Button> destButtons = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable meterTick = new Runnable() {
        @Override public void run() {
            if ((recording || monitoring) && recorder != null) {
                int amp;
                try { amp = recorder.getMaxAmplitude(); } catch (RuntimeException e) { amp = 0; }
                meter.push((float) Math.sqrt(amp / 32767.0));
                if (recording) {
                    long s = (System.currentTimeMillis() - recordStartMs) / 1000;
                    timerText.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
                }
                handler.postDelayed(this, 60);
            }
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ── Scrolling level meter ──────────────────────────────────────────
    private static class LevelMeter extends View {
        private final float[] bars = new float[52];
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
            float gap = w / bars.length * 0.38f;
            float bw = (w - gap * (bars.length - 1)) / bars.length;
            float mid = h / 2f;
            float minH = 2f * getResources().getDisplayMetrics().density;
            for (int i = 0; i < bars.length; i++) {
                float v = bars[(head + i) % bars.length];
                float bh = Math.max(minH, v * h * 0.9f);
                float x = i * (bw + gap);
                p.setColor(live ? blend(MINT_DEEP, MINT, v) : HAIRLINE);
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

    // ── Style helpers ──────────────────────────────────────────────────

    private GradientDrawable rect(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(1) / 2), strokeColor);
        return d;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.US));
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
        b.setBackground(rect(RAISED, 6, HAIRLINE));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        return b;
    }

    private Button solidButton(String text) {
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

    // ── Lifecycle ──────────────────────────────────────────────────────

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
        root.setPadding(dp(24), dp(30), dp(24), dp(44));

        // ── Wordmark ──
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        bar.setBackground(rect(MINT, 2, 0));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(4), dp(22));
        barLp.rightMargin = dp(12);
        brand.addView(bar, barLp);

        TextView wordmark = new TextView(this);
        wordmark.setText("SIDEMIC");
        wordmark.setTextSize(20);
        wordmark.setTextColor(PAPER);
        wordmark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wordmark.setLetterSpacing(0.3f);
        brand.addView(wordmark);
        root.addView(brand);

        TextView tagline = new TextView(this);
        tagline.setText("The spare microphone your phone already has");
        tagline.setTextSize(13);
        tagline.setTextColor(MUTED);
        root.addView(tagline, lp(8));

        root.addView(rule(), thin(26));

        // ── Input ──
        root.addView(sectionLabel("Input"), lp(24));
        LinearLayout sources = new LinearLayout(this);
        sources.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < SOURCE_VALUES.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(13), dp(14), dp(13));

            View pip = new View(this);
            pip.setTag("pip");
            LinearLayout.LayoutParams pipLp = new LinearLayout.LayoutParams(dp(7), dp(7));
            pipLp.rightMargin = dp(13);
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
            sources.addView(row, lp(i == 0 ? 10 : 6));
        }
        root.addView(sources);
        paintSources();

        root.addView(rule(), thin(26));

        // ── Capture ──
        root.addView(sectionLabel("Capture"), lp(24));

        timerText = new TextView(this);
        timerText.setText("00:00");
        timerText.setTextSize(58);
        timerText.setTextColor(PAPER);
        timerText.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        root.addView(timerText, lp(12));

        meter = new LevelMeter(this);
        LinearLayout.LayoutParams meterLp = lp(16);
        meterLp.height = dp(60);
        root.addView(meter, meterLp);

        statusText = new TextView(this);
        statusText.setText("Speak toward the top/back of the phone");
        statusText.setTextSize(12);
        statusText.setTextColor(MUTED);
        root.addView(statusText, lp(12));

        recordBtn = new Button(this);
        recordBtn.setText("Record");
        recordBtn.setAllCaps(false);
        recordBtn.setTextSize(16);
        recordBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        recordBtn.setTextColor(INK);
        recordBtn.setBackground(rect(MINT, 6, 0));
        recordBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams recLp = lp(20);
        recLp.height = dp(60);
        recordBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recording) stopRecording(true); else ensurePermissionThenRecord();
            }
        });
        root.addView(recordBtn, recLp);

        testBtn = ghostButton("Test tone — check the input without recording");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (monitoring) stopMonitor(); else ensurePermissionThenMonitor();
            }
        });
        root.addView(testBtn, lp(10));

        // ── Send ──
        root.addView(rule(), thin(30));
        root.addView(sectionLabel("Send"), lp(24));

        playBtn = ghostButton("Play last take");
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(lastRecordingUri); }
        });
        root.addView(playBtn, lp(12));

        favSendBtn = solidButton("Send to favourite");
        favSendBtn.setVisibility(View.GONE);
        favSendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareToFavorite(lastRecordingUri); }
        });
        root.addView(favSendBtn, lp(8));

        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetsBox, lp(0));
        buildTargets();

        otherShareBtn = ghostButton("Other app…");
        otherShareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareGeneric(lastRecordingUri); }
        });
        root.addView(otherShareBtn, lp(8));
        destButtons.add(playBtn);
        destButtons.add(favSendBtn);
        destButtons.add(otherShareBtn);
        setDestinationEnabled(false);

        // ── Favourite ──
        root.addView(rule(), thin(30));
        root.addView(sectionLabel("Quick contact"), lp(24));

        TextView favHint = new TextView(this);
        favHint.setText("Pick the person you send voice notes to most. Adds a record-and-send shortcut on your home screen.");
        favHint.setTextSize(12);
        favHint.setTextColor(MUTED);
        root.addView(favHint, lp(8));

        favSummary = new TextView(this);
        favSummary.setTextSize(15);
        favSummary.setTextColor(PAPER);
        favSummary.setBackground(rect(SURFACE, 6, HAIRLINE));
        favSummary.setPadding(dp(14), dp(13), dp(14), dp(13));
        root.addView(favSummary, lp(12));

        pickContactBtn = ghostButton("Choose from contacts");
        pickContactBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickContact(); }
        });
        root.addView(pickContactBtn, lp(10));

        // ── Advanced ──
        root.addView(rule(), thin(30));
        root.addView(sectionLabel("Advanced"), lp(24));

        TextView advHint = new TextView(this);
        advHint.setText("Capture format. Studio quality makes larger files but keeps detail for music work.");
        advHint.setTextSize(12);
        advHint.setTextColor(MUTED);
        root.addView(advHint, lp(8));

        qualityIndex = prefs.getInt(PREF_QUALITY, 0);
        stereo = prefs.getBoolean(PREF_CHANNELS, false);

        qualityBox = new LinearLayout(this);
        qualityBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < QUALITY_LABELS.length; i++) {
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
            TextView q1 = new TextView(this);
            q1.setText(QUALITY_LABELS[i]);
            q1.setTextSize(15);
            q1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(q1);
            TextView q2 = new TextView(this);
            q2.setText(QUALITY_SUBS[i]);
            q2.setTextSize(12);
            q2.setTextColor(MUTED);
            texts.addView(q2);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    qualityIndex = idx;
                    prefs.edit().putInt(PREF_QUALITY, idx).apply();
                    paintQuality();
                }
            });
            qualityRows.add(row);
            qualityBox.addView(row, lp(i == 0 ? 10 : 6));
        }
        root.addView(qualityBox);
        paintQuality();

        channelBtn = ghostButton("");
        channelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stereo = !stereo;
                prefs.edit().putBoolean(PREF_CHANNELS, stereo).apply();
                paintChannels();
            }
        });
        root.addView(channelBtn, lp(10));
        paintChannels();

        // ── Library ──
        root.addView(rule(), thin(30));
        root.addView(sectionLabel("Library"), lp(24));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, lp(10));

        // ── Footer ──
        root.addView(rule(), thin(30));
        Button helpBtn = ghostButton("How it works");
        helpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHelp(); }
        });
        root.addView(helpBtn, lp(24));

        TextView foot = new TextView(this);
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        foot.setText("Sidemic " + v + " · offline · no account · no ads");
        foot.setTextSize(11);
        foot.setTextColor(MUTED);
        root.addView(foot, lp(18));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.addView(root);
        setContentView(scroll);

        refreshList();
        applyFavoriteToUi();
        publishShortcuts();
        handleIntent(getIntent());
    }

    /** One button per installed messaging app that accepts audio intents. */
    private void buildTargets() {
        targetsBox.removeAllViews();
        PackageManager pm = getPackageManager();
        for (String[] t : TARGETS) {
            final String pkg = firstInstalled(pm, t[1], t[2]);
            if (pkg == null) continue;
            Button b = solidButton("Send on " + t[0]);
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

    private void paintQuality() {
        for (int i = 0; i < qualityRows.size(); i++) {
            View row = qualityRows.get(i);
            boolean on = (i == qualityIndex);
            row.setBackground(rect(on ? SURFACE : INK, 6, on ? MINT_DEEP : HAIRLINE));
            View pip = row.findViewWithTag("pip");
            if (pip != null) pip.setBackground(rect(on ? MINT : HAIRLINE, 4, 0));
            LinearLayout texts = (LinearLayout) ((LinearLayout) row).getChildAt(1);
            ((TextView) texts.getChildAt(0)).setTextColor(on ? PAPER : MUTED);
        }
    }

    private void paintChannels() {
        channelBtn.setText(stereo ? "Channels · Stereo" : "Channels · Mono");
    }

    private void paintSources() {
        for (int i = 0; i < sourceRows.size(); i++) {
            View row = sourceRows.get(i);
            boolean on = (i == selectedIndex);
            row.setBackground(rect(on ? SURFACE : INK, 6, on ? MINT_DEEP : HAIRLINE));
            View pip = row.findViewWithTag("pip");
            if (pip != null) pip.setBackground(rect(on ? MINT : HAIRLINE, 4, 0));
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

    // ── Shortcuts ──────────────────────────────────────────────────────

    private Icon shortcutIcon() {
        Bitmap bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(INK);
        c.drawCircle(54, 54, 54, p);
        p.setColor(MINT);
        c.drawRoundRect(46, 26, 62, 62, 8, 8, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        c.drawArc(34, 42, 74, 80, 0, 180, false, p);
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
                    .setShortLabel("Record")
                    .setLongLabel("Record with the spare mic")
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
                        .setShortLabel("To " + favName)
                        .setLongLabel("Record and send to " + favName)
                        .setIcon(shortcutIcon())
                        .setIntent(fi)
                        .build());
            }
            sm.setDynamicShortcuts(list);
        } catch (Exception ignored) { }
    }

    // ── Permission ─────────────────────────────────────────────────────

    private void ensurePermissionThenRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Microphone access")
                    .setMessage("Sidemic needs the microphone to record the audio you are about to send. Nothing leaves your phone — the app has no internet access.")
                    .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int x) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                        }
                    })
                    .setNegativeButton("Not now", null)
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
                    .setTitle("Permission blocked")
                    .setMessage("Microphone access was denied permanently. Open the app settings and enable Microphone.")
                    .setPositiveButton("Settings", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int x) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } else {
            toast("Recording needs the microphone permission.");
        }
    }

    // ── Capture ────────────────────────────────────────────────────────

    private void startRecording() {
        if (monitoring) stopMonitor();
        stopPlayback();
        ContentResolver cr = getContentResolver();
        String name = "Sidemic_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) { toast("Could not create the recording file."); return; }

        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w")) {
            recorder = new MediaRecorder();
            recorder.setAudioSource(SOURCE_VALUES[selectedIndex]);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(stereo ? 2 : 1);
            recorder.setAudioSamplingRate(QUALITY_RATE[qualityIndex]);
            recorder.setAudioEncodingBitRate(QUALITY_BITRATE[qualityIndex]);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            cleanupRecorder();
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            new AlertDialog.Builder(this)
                    .setTitle("Input unavailable")
                    .setMessage("This input failed on your device. Pick another one — \"Camera mic\" is usually the one that works.")
                    .setPositiveButton("Got it", null)
                    .show();
            return;
        }

        currentUri = uri;
        recording = true;
        recordStartMs = System.currentTimeMillis();
        recordBtn.setText("Stop");
        recordBtn.setBackground(rect(LIVE, 6, 0));
        recordBtn.setTextColor(PAPER);
        statusText.setText("Recording · " + SOURCE_TITLES[selectedIndex].toLowerCase(Locale.US));
        statusText.setTextColor(MINT);
        setDestinationEnabled(false);
        handler.post(meterTick);
    }

    // ── Test tone: live level, nothing kept ────────────────────────────

    private void ensurePermissionThenMonitor() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissionThenRecord();   // same rationale flow; user can tap test again
            return;
        }
        startMonitor();
    }

    /**
     * MediaRecorder needs a sink to report amplitude, so the test writes to a
     * scratch MediaStore row that is deleted the moment monitoring stops. The
     * row stays IS_PENDING=1 throughout, so it never appears in the library.
     */
    private void startMonitor() {
        if (recording) return;
        stopPlayback();
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, "Sidemic_monitor.m4a");
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH);
        cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) { toast("Could not start the test."); return; }

        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w")) {
            recorder = new MediaRecorder();
            recorder.setAudioSource(SOURCE_VALUES[selectedIndex]);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(stereo ? 2 : 1);
            recorder.setAudioSamplingRate(QUALITY_RATE[qualityIndex]);
            recorder.setAudioEncodingBitRate(QUALITY_BITRATE[qualityIndex]);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            cleanupRecorder();
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            new AlertDialog.Builder(this)
                    .setTitle("Input unavailable")
                    .setMessage("This input failed on your device. Pick another one — \"Camera mic\" is usually the one that works.")
                    .setPositiveButton("Got it", null)
                    .show();
            return;
        }

        monitorUri = uri;
        monitoring = true;
        testBtn.setText("Stop test");
        statusText.setText("Testing " + SOURCE_TITLES[selectedIndex].toLowerCase(Locale.US)
                + " — speak and watch the meter");
        statusText.setTextColor(MINT);
        recordBtn.setEnabled(false);
        recordBtn.setAlpha(0.35f);
        handler.post(meterTick);
    }

    private void stopMonitor() {
        monitoring = false;
        handler.removeCallbacks(meterTick);
        try { if (recorder != null) recorder.stop(); } catch (RuntimeException ignored) { }
        cleanupRecorder();
        if (monitorUri != null) {
            try { getContentResolver().delete(monitorUri, null, null); } catch (Exception ignored) { }
            monitorUri = null;
        }
        testBtn.setText("Test tone — check the input without recording");
        statusText.setText("Test finished — nothing was saved");
        statusText.setTextColor(MUTED);
        recordBtn.setEnabled(true);
        recordBtn.setAlpha(1f);
        meter.reset();
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
            statusText.setText("Take saved — play it before sending");
            statusText.setTextColor(MUTED);
            setDestinationEnabled(true);
            if (autoSendToFav) {
                autoSendToFav = false;
                shareToFavorite(lastRecordingUri);
            }
        } else {
            if (currentUri != null) { try { cr.delete(currentUri, null, null); } catch (Exception ignored) { } }
            if (!ok) {
                statusText.setText("Take too short — try again");
                statusText.setTextColor(LIVE);
            }
        }
        currentUri = null;
        recordBtn.setText("Record");
        recordBtn.setBackground(rect(MINT, 6, 0));
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

    // ── Playback ───────────────────────────────────────────────────────

    private void togglePlay(Uri uri) {
        if (uri == null) return;
        if (player != null && player.isPlaying()) { stopPlayback(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { playBtn.setText("Play last take"); }
            });
            player.prepare();
            player.start();
            playBtn.setText("Stop playback");
        } catch (Exception e) {
            toast("Could not play this take.");
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (playBtn != null) playBtn.setText("Play last take");
    }

    // ── Favourite contact ──────────────────────────────────────────────

    /** Opens the system contact picker — the only sanctioned way to choose a person. */
    private void pickContact() {
        try {
            Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            startActivityForResult(i, REQ_PICK_CONTACT);
        } catch (Exception e) {
            toast("No contacts app available.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_CONTACT || res != RESULT_OK || data == null || data.getData() == null) return;
        Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
        }, null, null, null);
        if (c == null) return;
        try {
            if (c.moveToFirst()) {
                String name = c.getString(0);
                String number = normalizeNumber(c.getString(1));
                if (number.length() < 10) { toast("That contact has no usable number."); return; }
                if (name == null || name.trim().isEmpty()) name = "favourite";
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(PREF_FAV_NAME, name.trim())
                        .putString(PREF_FAV_NUMBER, number)
                        .apply();
                applyFavoriteToUi();
                publishShortcuts();
                toast("Saved: " + name.trim());
            }
        } finally {
            c.close();
        }
    }

    /** Digits only; assumes Brazil (+55) when the user picks a local-format number. */
    private String normalizeNumber(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) d = d.substring(2);
        if (d.isEmpty()) return "";
        if (d.length() <= 11) d = "55" + d;
        return d;
    }

    private void applyFavoriteToUi() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String name = p.getString(PREF_FAV_NAME, "");
        String number = p.getString(PREF_FAV_NUMBER, "");
        if (name.isEmpty() || number.isEmpty()) {
            favSendBtn.setVisibility(View.GONE);
            favSummary.setText("No contact selected");
            favSummary.setTextColor(MUTED);
            pickContactBtn.setText("Choose from contacts");
        } else {
            favSendBtn.setVisibility(View.VISIBLE);
            favSendBtn.setText("Send to " + name);
            favSummary.setText(name + "  ·  +" + number);
            favSummary.setTextColor(PAPER);
            pickContactBtn.setText("Change contact");
        }
    }

    /**
     * Opens the favourite's WhatsApp thread with the audio attached.
     * The "jid" extra is undocumented: builds that ignore it simply show
     * WhatsApp's own conversation picker.
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
            toast("That app refused the audio — using the system share sheet.");
            shareGeneric(uri);
        }
    }

    private void shareGeneric(Uri uri) {
        if (uri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/mp4");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Send with"));
    }

    // ── Library ────────────────────────────────────────────────────────

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
            while (c.moveToNext() && shown < 25) {
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

                row.addView(iconButton("Play", PAPER, RAISED, new View.OnClickListener() {
                    @Override public void onClick(View v) { togglePlay(uri); }
                }), rowBtnLp());

                row.addView(iconButton("Send", INK, MINT, new View.OnClickListener() {
                    @Override public void onClick(View v) { shareGeneric(uri); }
                }), rowBtnLp());

                final String label = prettyStamp(name.replace("Sidemic_", "").replace(".m4a", ""));
                row.addView(iconButton("✕", LIVE, RAISED, new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmDelete(uri, label); }
                }), rowBtnLp());

                listBox.addView(row);
                if (shown < 24) listBox.addView(rule(), thin(0));
                shown++;
            }
        } finally {
            c.close();
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No takes yet.");
            empty.setTextSize(13);
            empty.setTextColor(MUTED);
            listBox.addView(empty, lp(4));
        }
    }

    private Button iconButton(String text, int fg, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(rect(bg, 6, bg == RAISED ? HAIRLINE : 0));
        b.setStateListAnimator(null);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(13), dp(8), dp(13), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams rowBtnLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        p.leftMargin = dp(7);
        return p;
    }

    private void confirmDelete(final Uri uri, String label) {
        new AlertDialog.Builder(this)
                .setTitle("Delete take?")
                .setMessage(label + " will be removed from your phone. This cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int x) { deleteTake(uri); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteTake(Uri uri) {
        if (uri == null) return;
        if (uri.equals(lastRecordingUri)) {
            stopPlayback();
            lastRecordingUri = null;
            setDestinationEnabled(false);
        }
        try {
            int n = getContentResolver().delete(uri, null, null);
            toast(n > 0 ? "Take deleted" : "Could not delete that take");
        } catch (Exception e) {
            toast("Could not delete that take");
        }
        refreshList();
    }

    /** 20260728_193045 → Jul 28 · 19:30 */
    private String prettyStamp(String s) {
        try {
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};
            int m = Integer.parseInt(s.substring(4, 6));
            return months[m - 1] + " " + s.substring(6, 8) + " · "
                    + s.substring(9, 11) + ":" + s.substring(11, 13);
        } catch (Exception e) {
            return s;
        }
    }

    // ── Help ───────────────────────────────────────────────────────────

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("How it works")
                .setMessage("Your phone has more than one microphone. When the primary one " +
                        "(the pinhole at the bottom) fails, videos still record sound — because the " +
                        "camera uses a different capsule.\n\n" +
                        "Sidemic records through that spare capsule (\"Camera mic\") and hands the " +
                        "file to WhatsApp, Telegram or Signal as a normal audio message.\n\n" +
                        "HONEST LIMITS\n" +
                        "· Phone calls still need speakerphone or a headset — Android does not let " +
                        "an app change the call input without root.\n" +
                        "· WhatsApp's own record button keeps using the broken mic. Record here, then send.\n" +
                        "· Instagram rejects audio files from other apps, so it is not listed.\n" +
                        "· A headset with a microphone reroutes everything, calls included.\n\n" +
                        "Speak toward the top/back of the phone. Nothing leaves your device.")
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (monitoring) stopMonitor();
        if (recording) stopRecording(true);
        stopPlayback();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
