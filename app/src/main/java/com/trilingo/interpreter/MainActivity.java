package com.trilingo.interpreter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQ_MIC = 1001;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Translator> translators = new HashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private SpeechRecognizer recognizer;
    private LanguageIdentifier languageIdentifier;
    private boolean running = false;
    private boolean paused = false;
    private String detectedLanguage = null;
    private boolean speechModelsRequested = false;

    private TextView status;
    private TextView partial;
    private LinearLayout timeline;
    private ScrollView scroll;
    private Button start;
    private Button pause;
    private Button stop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        languageIdentifier = LanguageIdentification.getClient();
        buildUi();
        setStatus("พร้อม");
        refreshButtons();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 249, 252));

        final int baseLeft = dp(18);
        final int baseTop = dp(8);
        final int baseRight = dp(18);
        final int baseBottom = dp(12);

        root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int topInset;
            int bottomInset;
            int leftInset;
            int rightInset;

            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                topInset = bars.top;
                bottomInset = bars.bottom;
                leftInset = bars.left;
                rightInset = bars.right;
            } else {
                topInset = windowInsets.getSystemWindowInsetTop();
                bottomInset = windowInsets.getSystemWindowInsetBottom();
                leftInset = windowInsets.getSystemWindowInsetLeft();
                rightInset = windowInsets.getSystemWindowInsetRight();
            }

            view.setPadding(
                    baseLeft + leftInset,
                    baseTop + topInset + dp(4),
                    baseRight + rightInset,
                    baseBottom + bottomInset + dp(12)
            );
            return windowInsets;
        });

        TextView title = new TextView(this);
        title.setText("TriLingo Meeting");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(20, 28, 40));
        root.addView(title, full());

        TextView subtitle = new TextView(this);
        subtitle.setText("Thai • 中文 • English\nAI Group Conversation Interpreter");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(90, 100, 115));
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle, full());

        status = new TextView(this);
        status.setTextSize(15);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.rgb(21, 101, 192));
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(status, full());

        partial = new TextView(this);
        partial.setTextSize(20);
        partial.setTextColor(Color.DKGRAY);
        partial.setPadding(dp(10), dp(8), dp(10), dp(12));
        root.addView(partial, full());

        scroll = new ScrollView(this);
        timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(timeline, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("กดเริ่มครั้งเดียว ระบบจะฟังไทย จีน และอังกฤษอัตโนมัติ\nเมื่อได้ยินภาษาใด ระบบจะแปลเป็นอีก 2 ภาษาให้ทันที");
        hint.setTextSize(13);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(4), dp(8), dp(4), dp(14));
        root.addView(hint, full());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        start = makeButton("🎙 เริ่ม");
        pause = makeButton("⏸ พัก");
        stop = makeButton("⏹ หยุด");

        start.setOnClickListener(v -> startSession());
        pause.setOnClickListener(v -> togglePause());
        stop.setOnClickListener(v -> stopSession());

        controls.addView(start, weighted());
        controls.addView(pause, weighted());
        controls.addView(stop, weighted());

        LinearLayout.LayoutParams controlParams = full();
        controlParams.setMargins(0, dp(4), 0, dp(4));
        root.addView(controls, controlParams);

        setContentView(root);
        root.requestApplyInsets();
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(54));
        return b;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private void refreshButtons() {
        start.setEnabled(!running);
        pause.setEnabled(running);
        stop.setEnabled(running);
        pause.setText(paused ? "▶ ทำต่อ" : "⏸ พัก");
    }

    private void startSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "อุปกรณ์นี้ไม่มีบริการ Speech Recognition", Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        paused = false;
        ensureRecognizer();
        refreshButtons();
        beginRecognition();
    }

    private void ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            requestSpeechModels();
        }
    }

    private void beginRecognition() {
        if (!running || paused || recognizer == null) return;

        detectedLanguage = null;
        partial.setText("");
        setStatus("🟢 กำลังฟัง...");

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);

        if (Build.VERSION.SDK_INT >= 34) {
            // Start with Thai as the base model, then switch immediately when
            // Chinese or English speech is detected.
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH");
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    new ArrayList<>(Arrays.asList("th-TH", "zh-CN", "en-US"))
            );
            i.putExtra(
                    RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                    RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE
            );
            i.putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                    new ArrayList<>(Arrays.asList("th-TH", "zh-CN", "en-US"))
            );
        }

        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            setStatus("เริ่มฟังไม่สำเร็จ: " + safe(e));
            scheduleRestart(1200);
        }
    }

    private void requestSpeechModels() {
        if (speechModelsRequested || recognizer == null || Build.VERSION.SDK_INT < 33) return;
        speechModelsRequested = true;

        String[] locales = {"th-TH", "zh-CN", "en-US"};
        for (String locale : locales) {
            Intent modelIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            modelIntent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            );
            modelIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
            try {
                recognizer.triggerModelDownload(modelIntent);
            } catch (Exception ignored) {
                // Some recognition services do not expose downloadable models.
                // Online recognition and language switching can still work.
            }
        }
    }

    private void togglePause() {
        if (!running) return;
        paused = !paused;
        if (paused) {
            if (recognizer != null) recognizer.cancel();
            setStatus("⏸ พักการฟัง");
        } else {
            beginRecognition();
        }
        refreshButtons();
    }

    private void stopSession() {
        running = false;
        paused = false;
        if (recognizer != null) recognizer.cancel();
        partial.setText("");
        setStatus("หยุดแล้ว");
        refreshButtons();
    }

    private void scheduleRestart(long delay) {
        handler.postDelayed(() -> {
            if (running && !paused) beginRecognition();
        }, delay);
    }

    @Override public void onReadyForSpeech(Bundle params) { setStatus("🟢 กำลังฟัง..."); }
    @Override public void onBeginningOfSpeech() { setStatus("🎙 กำลังพูด..."); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { setStatus("กำลังประมวลผล..."); }

    @Override
    public void onError(int error) {
        if (!running || paused) return;
        long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 1000 : 450;
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            setStatus("กำลังเริ่มฟังใหม่...");
        }
        scheduleRestart(delay);
    }

    @Override
    public void onResults(Bundle results) {
        String text = firstText(results);
        partial.setText("");
        if (text != null && !text.trim().isEmpty()) {
            processUtterance(text.trim(), detectedLanguage);
        }
        if (running && !paused) scheduleRestart(250);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = firstText(partialResults);
        if (text != null) partial.setText("“" + text + "”");
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    public void onLanguageDetection(Bundle results) {
        if (Build.VERSION.SDK_INT >= 34 && results != null) {
            String tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE);
            detectedLanguage = normalize(tag);
        }
    }

    private String firstText(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private void processUtterance(String original, String sourceHint) {
        Card card = addCard(original, sourceHint);
        setStatus("กำลังตรวจภาษาและแปล...");

        if (supported(sourceHint)) {
            card.setLanguage(sourceHint);
            translateAll(original, sourceHint, card);
            return;
        }

        languageIdentifier.identifyLanguage(original)
                .addOnSuccessListener(code -> {
                    String source = normalize(code);
                    if (!supported(source)) {
                        card.setError("ตรวจจับภาษาไม่ได้");
                        if (running && !paused) setStatus("🟢 กำลังฟัง...");
                        return;
                    }
                    card.setLanguage(source);
                    translateAll(original, source, card);
                })
                .addOnFailureListener(e -> {
                    card.setError("ตรวจจับภาษาไม่สำเร็จ");
                    if (running && !paused) setStatus("🟢 กำลังฟัง...");
                });
    }

    private void translateAll(String text, String source, Card card) {
        final String[] out = new String[3];
        final int[] done = {0};

        translateOne(text, source, "th", value -> {
            out[0] = value; finish(card, out, ++done[0]);
        });
        translateOne(text, source, "zh", value -> {
            out[1] = value; finish(card, out, ++done[0]);
        });
        translateOne(text, source, "en", value -> {
            out[2] = value; finish(card, out, ++done[0]);
        });
    }

    private synchronized void finish(Card card, String[] out, int count) {
        if (count != 3) return;
        runOnUiThread(() -> {
            card.setTranslations(out[0], out[1], out[2]);
            if (running && !paused) setStatus("🟢 กำลังฟัง...");
        });
    }

    private interface TextCallback { void onDone(String text); }

    private void translateOne(String text, String source, String target, TextCallback cb) {
        if (source.equals(target)) {
            cb.onDone(text);
            return;
        }

        Translator translator = translator(source, target);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> translator.translate(text)
                        .addOnSuccessListener(cb::onDone)
                        .addOnFailureListener(e -> cb.onDone("[แปลไม่สำเร็จ]")))
                .addOnFailureListener(e -> cb.onDone("[ดาวน์โหลดโมเดลไม่สำเร็จ]"));
    }

    private Translator translator(String source, String target) {
        String key = source + ">" + target;
        Translator t = translators.get(key);
        if (t != null) return t;

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(ml(source))
                .setTargetLanguage(ml(target))
                .build();
        t = Translation.getClient(options);
        translators.put(key, t);
        return t;
    }

    private String ml(String code) {
        switch (code) {
            case "th": return TranslateLanguage.THAI;
            case "zh": return TranslateLanguage.CHINESE;
            default: return TranslateLanguage.ENGLISH;
        }
    }

    private String normalize(String tag) {
        if (tag == null) return null;
        String s = tag.toLowerCase(Locale.ROOT);
        if (s.startsWith("th")) return "th";
        if (s.startsWith("zh") || s.startsWith("cmn")) return "zh";
        if (s.startsWith("en")) return "en";
        return null;
    }

    private boolean supported(String s) {
        return "th".equals(s) || "zh".equals(s) || "en".equals(s);
    }

    private String label(String s) {
        if ("th".equals(s)) return "TH";
        if ("zh".equals(s)) return "中文";
        if ("en".equals(s)) return "EN";
        return "AUTO";
    }

    private Card addCard(String original, String source) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams bp = full();
        bp.setMargins(0, dp(5), 0, dp(7));
        timeline.addView(box, bp);

        TextView header = new TextView(this);
        header.setText("Speaker • " + clock.format(new Date()));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(80, 90, 105));
        box.addView(header, full());

        TextView originalView = new TextView(this);
        originalView.setText(original);
        originalView.setTextSize(21);
        originalView.setTypeface(Typeface.DEFAULT_BOLD);
        originalView.setTextColor(Color.rgb(20, 25, 32));
        originalView.setPadding(0, dp(8), 0, dp(8));
        box.addView(originalView, full());

        TextView th = line("🇹🇭 กำลังแปล...");
        TextView zh = line("🇨🇳 กำลังแปล...");
        TextView en = line("🇬🇧 Translating...");
        box.addView(th, full());
        box.addView(zh, full());
        box.addView(en, full());

        handler.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return new Card(header, th, zh, en);
    }

    private TextView line(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(Color.rgb(45, 52, 62));
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private class Card {
        final TextView header, th, zh, en;
        Card(TextView h, TextView t, TextView z, TextView e) {
            header = h; th = t; zh = z; en = e;
        }
        void setLanguage(String code) {
            runOnUiThread(() ->
                    header.setText("Speaker • " + clock.format(new Date())));
        }
        void setTranslations(String thai, String chinese, String english) {
            th.setText("🇹🇭 " + thai);
            zh.setText("🇨🇳 " + chinese);
            en.setText("🇬🇧 " + english);
            handler.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
        void setError(String message) {
            runOnUiThread(() -> {
                th.setText("⚠️ " + message);
                zh.setText("");
                en.setText("");
            });
        }
    }

    private String safe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else if (requestCode == REQ_MIC) {
            Toast.makeText(this, "ต้องอนุญาตไมโครโฟนเพื่อใช้งาน", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        for (Translator t : translators.values()) t.close();
        translators.clear();
        if (languageIdentifier != null) languageIdentifier.close();
        super.onDestroy();
    }
}
