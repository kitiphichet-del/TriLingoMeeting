package com.trilingo.interpreter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQ_MIC = 1001;
    private static final String PREFS = "trilingo_preferences";

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
    private TextView languageHint;
    private LinearLayout timeline;
    private ScrollView scroll;
    private Button start;
    private Button pause;
    private Button stop;
    private CheckBox thaiCheck;
    private CheckBox chineseCheck;
    private CheckBox englishCheck;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        languageIdentifier = LanguageIdentification.getClient();
        buildUi();
        restoreLanguageSelection();
        updateLanguageHint();
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
        subtitle.setText("AI Group Conversation Interpreter");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(90, 100, 115));
        subtitle.setPadding(0, dp(2), 0, dp(5));
        root.addView(subtitle, full());

        TextView selectorTitle = new TextView(this);
        selectorTitle.setText("เลือกภาษาที่ต้องการฟังและแปล");
        selectorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        selectorTitle.setTextSize(13);
        selectorTitle.setTextColor(Color.rgb(75, 85, 100));
        selectorTitle.setPadding(0, dp(4), 0, 0);
        root.addView(selectorTitle, full());

        LinearLayout languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.HORIZONTAL);
        languageRow.setGravity(Gravity.CENTER_VERTICAL);

        thaiCheck = languageCheckBox("🇹🇭 ไทย");
        chineseCheck = languageCheckBox("🇨🇳 中文(简体)");
        englishCheck = languageCheckBox("🇬🇧 English");

        languageRow.addView(thaiCheck, weighted());
        languageRow.addView(chineseCheck, weighted());
        languageRow.addView(englishCheck, weighted());
        root.addView(languageRow, full());

        View.OnClickListener languageClick = v -> {
            CheckBox changed = (CheckBox) v;
            if (selectedLanguageCount() < 2) {
                changed.setChecked(true);
                Toast.makeText(this, "กรุณาเลือกอย่างน้อย 2 ภาษา", Toast.LENGTH_SHORT).show();
                return;
            }
            saveLanguageSelection();
            speechModelsRequested = false;
            if (recognizer != null) requestSpeechModels();
            updateLanguageHint();
        };
        thaiCheck.setOnClickListener(languageClick);
        chineseCheck.setOnClickListener(languageClick);
        englishCheck.setOnClickListener(languageClick);

        languageHint = new TextView(this);
        languageHint.setTextSize(12);
        languageHint.setTextColor(Color.GRAY);
        languageHint.setPadding(dp(2), 0, dp(2), dp(4));
        root.addView(languageHint, full());

        status = new TextView(this);
        status.setTextSize(15);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.rgb(21, 101, 192));
        status.setPadding(dp(10), dp(5), dp(10), dp(6));
        root.addView(status, full());

        partial = new TextView(this);
        partial.setTextSize(20);
        partial.setTextColor(Color.DKGRAY);
        partial.setPadding(dp(10), dp(6), dp(10), dp(8));
        root.addView(partial, full());

        scroll = new ScrollView(this);
        timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(timeline, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("กดเริ่มครั้งเดียว แล้วสนทนาด้วยภาษาที่เลือกได้เลย\nระบบจะตรวจภาษาและแปลไปยังภาษาที่เลือกโดยอัตโนมัติ");
        hint.setTextSize(13);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(4), dp(8), dp(4), dp(12));
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

    private CheckBox languageCheckBox(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(12);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setButtonTintList(null);
        return c;
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
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restoreLanguageSelection() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        thaiCheck.setChecked(prefs.getBoolean("lang_th", true));
        chineseCheck.setChecked(prefs.getBoolean("lang_zh", true));
        englishCheck.setChecked(prefs.getBoolean("lang_en", true));

        if (selectedLanguageCount() < 2) {
            thaiCheck.setChecked(true);
            chineseCheck.setChecked(true);
        }
    }

    private void saveLanguageSelection() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean("lang_th", thaiCheck.isChecked())
                .putBoolean("lang_zh", chineseCheck.isChecked())
                .putBoolean("lang_en", englishCheck.isChecked())
                .apply();
    }

    private int selectedLanguageCount() {
        int count = 0;
        if (thaiCheck.isChecked()) count++;
        if (chineseCheck.isChecked()) count++;
        if (englishCheck.isChecked()) count++;
        return count;
    }

    private boolean isSelectedLanguage(String code) {
        if ("th".equals(code)) return thaiCheck.isChecked();
        if ("zh".equals(code)) return chineseCheck.isChecked();
        if ("en".equals(code)) return englishCheck.isChecked();
        return false;
    }

    private ArrayList<String> selectedLocales() {
        ArrayList<String> locales = new ArrayList<>();
        if (thaiCheck.isChecked()) locales.add("th-TH");
        if (chineseCheck.isChecked()) locales.add("zh-CN");
        if (englishCheck.isChecked()) locales.add("en-US");
        return locales;
    }

    private String firstSelectedLocale() {
        if (thaiCheck.isChecked()) return "th-TH";
        if (chineseCheck.isChecked()) return "zh-CN";
        return "en-US";
    }

    private void updateLanguageHint() {
        ArrayList<String> names = new ArrayList<>();
        if (thaiCheck.isChecked()) names.add("ไทย");
        if (chineseCheck.isChecked()) names.add("中文(简体)");
        if (englishCheck.isChecked()) names.add("English");
        languageHint.setText("ใช้งาน: " + android.text.TextUtils.join(" • ", names));
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private void refreshButtons() {
        start.setEnabled(!running);
        pause.setEnabled(running);
        stop.setEnabled(running);
        pause.setText(paused ? "▶ ทำต่อ" : "⏸ พัก");

        boolean canEditLanguages = !running;
        thaiCheck.setEnabled(canEditLanguages);
        chineseCheck.setEnabled(canEditLanguages);
        englishCheck.setEnabled(canEditLanguages);
    }

    private void startSession() {
        if (selectedLanguageCount() < 2) {
            Toast.makeText(this, "กรุณาเลือกอย่างน้อย 2 ภาษา", Toast.LENGTH_SHORT).show();
            return;
        }

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
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, firstSelectedLocale());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);

        if (Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> allowed = selectedLocales();

            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putStringArrayListExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                    allowed
            );

            if (allowed.size() > 1) {
                i.putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE
                );
                i.putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        allowed
                );
            }
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

        for (String locale : selectedLocales()) {
            Intent modelIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            modelIntent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            );
            modelIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
            try {
                recognizer.triggerModelDownload(modelIntent);
            } catch (Exception ignored) {
                // Recognition providers may handle models online instead.
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
            String normalized = normalize(tag);
            if (isSelectedLanguage(normalized)) detectedLanguage = normalized;
        }
    }

    private String firstText(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private void processUtterance(String original, String sourceHint) {
        Card card = addCard(original);
        setStatus("กำลังตรวจภาษาและแปล...");

        if (supported(sourceHint) && isSelectedLanguage(sourceHint)) {
            translateSelected(original, sourceHint, card);
            return;
        }

        languageIdentifier.identifyLanguage(original)
                .addOnSuccessListener(code -> {
                    String source = normalize(code);
                    if (!supported(source)) {
                        card.setError("ตรวจจับภาษาไม่ได้");
                        resumeListeningStatus();
                        return;
                    }
                    if (!isSelectedLanguage(source)) {
                        card.setError("ภาษานี้ไม่ได้ถูกเลือก");
                        resumeListeningStatus();
                        return;
                    }
                    translateSelected(original, source, card);
                })
                .addOnFailureListener(e -> {
                    card.setError("ตรวจจับภาษาไม่สำเร็จ");
                    resumeListeningStatus();
                });
    }

    private void translateSelected(String text, String source, Card card) {
        final int expected = selectedLanguageCount();
        final int[] done = {0};

        if (thaiCheck.isChecked()) {
            translateOne(text, source, "th", value -> {
                card.setLine("th", value);
                finishTranslation(++done[0], expected);
            });
        }

        if (chineseCheck.isChecked()) {
            translateOne(text, source, "zh", value -> {
                card.setLine("zh", value);
                finishTranslation(++done[0], expected);
            });
        }

        if (englishCheck.isChecked()) {
            translateOne(text, source, "en", value -> {
                card.setLine("en", value);
                finishTranslation(++done[0], expected);
            });
        }
    }

    private synchronized void finishTranslation(int done, int expected) {
        if (done >= expected) resumeListeningStatus();
    }

    private void resumeListeningStatus() {
        runOnUiThread(() -> {
            if (running && !paused) setStatus("🟢 กำลังฟัง...");
        });
    }

    private interface TextCallback {
        void onDone(String text);
    }

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
            case "th":
                return TranslateLanguage.THAI;
            case "zh":
                // Chinese in this app is paired with zh-CN speech recognition
                // and presented as Simplified Chinese.
                return TranslateLanguage.CHINESE;
            default:
                return TranslateLanguage.ENGLISH;
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

    private Card addCard(String original) {
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
        TextView zh = line("🇨🇳 正在翻译...");
        TextView en = line("🇬🇧 Translating...");

        if (thaiCheck.isChecked()) box.addView(th, full());
        if (chineseCheck.isChecked()) box.addView(zh, full());
        if (englishCheck.isChecked()) box.addView(en, full());

        handler.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return new Card(th, zh, en);
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
        final TextView th;
        final TextView zh;
        final TextView en;

        Card(TextView t, TextView z, TextView e) {
            th = t;
            zh = z;
            en = e;
        }

        void setLine(String code, String value) {
            runOnUiThread(() -> {
                if ("th".equals(code) && thaiCheck.isChecked()) {
                    th.setText("🇹🇭 " + value);
                } else if ("zh".equals(code) && chineseCheck.isChecked()) {
                    zh.setText("🇨🇳 " + value);
                } else if ("en".equals(code) && englishCheck.isChecked()) {
                    en.setText("🇬🇧 " + value);
                }
                handler.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        }

        void setError(String message) {
            runOnUiThread(() -> {
                if (thaiCheck.isChecked()) th.setText("⚠️ " + message);
                if (chineseCheck.isChecked()) zh.setText("");
                if (englishCheck.isChecked()) en.setText("");
            });
        }
    }

    private String safe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty())
                ? e.getClass().getSimpleName()
                : m;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_MIC &&
                grantResults.length > 0 &&
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
