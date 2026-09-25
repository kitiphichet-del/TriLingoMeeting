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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
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

    private static final long RESTART_AFTER_RESULT_MS = 320L;
    private static final long RESTART_AFTER_TIMEOUT_MS = 550L;
    private static final long RESTART_AFTER_ERROR_MS = 1400L;

    // Our own silence watchdog. Some Android speech providers ignore the
    // end-pointer extras and otherwise keep a partial result alive too long.
    private static final long SILENCE_COMMIT_MS = 1450L;
    private static final long MAX_UTTERANCE_MS = 12000L;
    private static final long RESULT_WATCHDOG_MS = 3500L;
    private static final int RECYCLE_AFTER_CYCLES = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Translator> translators = new HashMap<>();
    private final Map<String, Boolean> translatorReady = new HashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private SpeechRecognizer recognizer;
    private boolean running = false;
    private boolean paused = false;
    private boolean recognitionActive = false;
    private boolean destroyed = false;
    private boolean preparingTranslation = false;
    private boolean speechStarted = false;
    private int completedRecognitionCycles = 0;

    private TextView status;
    private TextView partial;
    private TextView settingsSummary;
    private LinearLayout settingsPanel;
    private Button settingsToggle;
    private boolean settingsExpanded = false;

    private LinearLayout timeline;
    private ScrollView scroll;
    private Button start;
    private Button pause;
    private Button stop;

    private CheckBox thaiCheck;
    private CheckBox chineseCheck;
    private CheckBox englishCheck;

    private RadioGroup inputLanguageGroup;
    private RadioButton thaiInput;
    private RadioButton chineseInput;
    private RadioButton englishInput;

    private String sourceForCurrentRecognition = "th";
    private String lastResultText = "";
    private long lastResultAt = 0L;

    private final Runnable restartRunnable = () -> {
        if (!isUsable() || !running || paused || recognitionActive) return;
        beginRecognition();
    };

    private final Runnable silenceCommitRunnable = () -> {
        if (!isUsable() || !running || paused || !recognitionActive || !speechStarted) return;
        try {
            setStatus("กำลังปิดประโยคและแปล...");
            recognizer.stopListening();
            armResultWatchdog();
        } catch (Exception ignored) {
        }
    };

    private final Runnable maxUtteranceRunnable = () -> {
        if (!isUsable() || !running || paused || !recognitionActive) return;
        try {
            recognizer.stopListening();
            armResultWatchdog();
        } catch (Exception ignored) {
            recycleRecognizerAndRestart(650L);
        }
    };

    private final Runnable resultWatchdogRunnable = () -> {
        if (!isUsable() || !running || paused || !recognitionActive) return;
        setStatus("ไมค์ไม่ส่งผลลัพธ์ • กำลังรีเซ็ตอัตโนมัติ...");
        recycleRecognizerAndRestart(650L);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        restoreLanguageSelection();
        restoreInputLanguageMode();
        updateInputLanguageAvailability();
        updateSettingsSummary();
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
        subtitle.setText("Continuous Conversation Interpreter");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(90, 100, 115));
        subtitle.setPadding(0, dp(2), 0, dp(6));
        root.addView(subtitle, full());

        settingsToggle = new Button(this);
        settingsToggle.setAllCaps(false);
        settingsToggle.setText("⚙ การตั้งค่า ▾");
        settingsToggle.setTextSize(14);
        settingsToggle.setMinHeight(dp(44));
        root.addView(settingsToggle, full());

        settingsSummary = new TextView(this);
        settingsSummary.setTextSize(12);
        settingsSummary.setTextColor(Color.rgb(85, 95, 110));
        settingsSummary.setPadding(dp(4), dp(1), dp(4), dp(5));
        root.addView(settingsSummary, full());

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(8), dp(5), dp(8), dp(8));
        settingsPanel.setBackgroundColor(Color.WHITE);
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, full());

        buildSettingsPanel();

        settingsToggle.setOnClickListener(v -> toggleSettings());
        settingsSummary.setOnClickListener(v -> toggleSettings());

        status = new TextView(this);
        status.setTextSize(15);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.rgb(21, 101, 192));
        status.setPadding(dp(10), dp(7), dp(10), dp(5));
        root.addView(status, full());

        partial = new TextView(this);
        partial.setTextSize(19);
        partial.setTextColor(Color.DKGRAY);
        partial.setPadding(dp(10), dp(5), dp(10), dp(7));
        root.addView(partial, full());

        scroll = new ScrollView(this);
        timeline = new LinearLayout(this);
        timeline.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(
                timeline,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );
        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        TextView hint = new TextView(this);
        hint.setText(
                "เปิดครั้งเดียวแล้วสนทนาได้ต่อเนื่อง • เว้นเงียบสั้น ๆ หลังแต่ละประโยค\n" +
                "ระบบจะแปลและกลับมาฟังต่ออัตโนมัติ • ถ้าไมค์ค้างจะรีเซ็ตตัวเอง"
        );
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(4), dp(7), dp(4), dp(9));
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

    private void buildSettingsPanel() {
        TextView outputTitle = smallTitle("ภาษาที่ต้องการแสดงผล");
        settingsPanel.addView(outputTitle, full());

        LinearLayout languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.HORIZONTAL);
        languageRow.setGravity(Gravity.CENTER_VERTICAL);

        thaiCheck = languageCheckBox("🇹🇭 ไทย");
        chineseCheck = languageCheckBox("🇨🇳 中文(简体)");
        englishCheck = languageCheckBox("🇬🇧 English");

        languageRow.addView(thaiCheck, weighted());
        languageRow.addView(chineseCheck, weighted());
        languageRow.addView(englishCheck, weighted());
        settingsPanel.addView(languageRow, full());

        View.OnClickListener outputClick = v -> {
            CheckBox changed = (CheckBox) v;

            if (selectedLanguageCount() < 2) {
                changed.setChecked(true);
                Toast.makeText(
                        this,
                        "กรุณาเลือกอย่างน้อย 2 ภาษา",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            saveLanguageSelection();
            updateInputLanguageAvailability();
            updateSettingsSummary();
        };

        thaiCheck.setOnClickListener(outputClick);
        chineseCheck.setOnClickListener(outputClick);
        englishCheck.setOnClickListener(outputClick);

        TextView inputTitle = smallTitle("ภาษาที่กำลังสนทนา");
        settingsPanel.addView(inputTitle, full());

        inputLanguageGroup = new RadioGroup(this);
        inputLanguageGroup.setOrientation(LinearLayout.HORIZONTAL);
        inputLanguageGroup.setGravity(Gravity.CENTER_VERTICAL);

        thaiInput = inputRadio("🇹🇭 ไทย");
        chineseInput = inputRadio("🇨🇳 中文");
        englishInput = inputRadio("🇬🇧 EN");

        thaiInput.setId(View.generateViewId());
        chineseInput.setId(View.generateViewId());
        englishInput.setId(View.generateViewId());

        inputLanguageGroup.addView(thaiInput, weighted());
        inputLanguageGroup.addView(chineseInput, weighted());
        inputLanguageGroup.addView(englishInput, weighted());
        settingsPanel.addView(inputLanguageGroup, full());

        inputLanguageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String previous = sourceForCurrentRecognition;
            saveInputLanguageMode();
            updateSettingsSummary();

            if (running && !paused) {
                String next = currentInputLanguage();

                if (!next.equals(previous)) {
                    handler.removeCallbacks(silenceCommitRunnable);
                    handler.removeCallbacks(maxUtteranceRunnable);

                    if (recognitionActive && recognizer != null) {
                        try {
                            recognizer.cancel();
                        } catch (Exception ignored) {
                        }
                    }

                    recognitionActive = false;
                    speechStarted = false;
                    completedRecognitionCycles = 0;
                    setStatus("กำลังเปลี่ยนภาษา...");

                    prepareRequiredModels(
                            next,
                            () -> scheduleStart(450L),
                            () -> {
                                setStatus("เตรียมโมเดลแปลไม่สำเร็จ");
                                scheduleStart(900L);
                            }
                    );
                }
            }
        });

        TextView help = new TextView(this);
        help.setText(
                "ไม่จำผู้พูดแล้ว • เลือกเฉพาะภาษาที่กำลังพูด และพับเมนูได้"
        );
        help.setTextSize(11);
        help.setTextColor(Color.GRAY);
        help.setPadding(dp(2), dp(2), dp(2), dp(2));
        settingsPanel.addView(help, full());
    }

    private void toggleSettings() {
        settingsExpanded = !settingsExpanded;
        settingsPanel.setVisibility(settingsExpanded ? View.VISIBLE : View.GONE);
        settingsToggle.setText(settingsExpanded ? "⚙ การตั้งค่า ▴" : "⚙ การตั้งค่า ▾");
    }

    private TextView smallTitle(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(13);
        v.setTextColor(Color.rgb(75, 85, 100));
        v.setPadding(0, dp(4), 0, 0);
        return v;
    }

    private CheckBox languageCheckBox(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(12);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setButtonTintList(null);
        return c;
    }

    private RadioButton inputRadio(String text) {
        RadioButton r = new RadioButton(this);
        r.setText(text);
        r.setTextSize(12);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isUsable() {
        return !destroyed &&
                !isFinishing() &&
                (Build.VERSION.SDK_INT < 17 || !isDestroyed());
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

    private void restoreInputLanguageMode() {
        String mode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("input_language_mode", "th");

        if ("zh".equals(mode)) chineseInput.setChecked(true);
        else if ("en".equals(mode)) englishInput.setChecked(true);
        else thaiInput.setChecked(true);

        sourceForCurrentRecognition = currentInputLanguage();
    }

    private void saveInputLanguageMode() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("input_language_mode", currentInputLanguage())
                .apply();
    }

    private String currentInputLanguage() {
        if (chineseInput != null && chineseInput.isChecked()) return "zh";
        if (englishInput != null && englishInput.isChecked()) return "en";
        return "th";
    }

    private String localeForCode(String code) {
        if ("zh".equals(code)) return "zh-CN";
        if ("en".equals(code)) return "en-US";
        return "th-TH";
    }

    private String languageLabel(String code) {
        if ("zh".equals(code)) return "🇨🇳 中文";
        if ("en".equals(code)) return "🇬🇧 EN";
        return "🇹🇭 ไทย";
    }

    private void updateInputLanguageAvailability() {
        if (thaiInput == null) return;

        thaiInput.setEnabled(thaiCheck.isChecked());
        chineseInput.setEnabled(chineseCheck.isChecked());
        englishInput.setEnabled(englishCheck.isChecked());

        String mode = currentInputLanguage();

        if (("th".equals(mode) && !thaiCheck.isChecked()) ||
                ("zh".equals(mode) && !chineseCheck.isChecked()) ||
                ("en".equals(mode) && !englishCheck.isChecked())) {

            if (thaiCheck.isChecked()) thaiInput.setChecked(true);
            else if (chineseCheck.isChecked()) chineseInput.setChecked(true);
            else englishInput.setChecked(true);
        }
    }

    private void updateSettingsSummary() {
        if (settingsSummary == null) return;

        ArrayList<String> outputs = new ArrayList<>();
        if (thaiCheck.isChecked()) outputs.add("TH");
        if (chineseCheck.isChecked()) outputs.add("中文");
        if (englishCheck.isChecked()) outputs.add("EN");

        settingsSummary.setText(
                languageLabel(currentInputLanguage()) +
                "   •   แสดง " +
                android.text.TextUtils.join("/", outputs)
        );
    }

    private void setStatus(String text) {
        if (status != null && isUsable()) {
            status.setText(text);
        }
    }

    private void refreshButtons() {
        if (!isUsable()) return;

        start.setEnabled(!running && !preparingTranslation);
        pause.setEnabled(running);
        stop.setEnabled(running);
        pause.setText(paused ? "▶ ทำต่อ" : "⏸ พัก");

        boolean canEditOutputs = !running;
        thaiCheck.setEnabled(canEditOutputs);
        chineseCheck.setEnabled(canEditOutputs);
        englishCheck.setEnabled(canEditOutputs);

        // Source language can still be changed during the conversation.
        updateInputLanguageAvailability();
    }

    private void startSession() {
        if (selectedLanguageCount() < 2) {
            Toast.makeText(
                    this,
                    "กรุณาเลือกอย่างน้อย 2 ภาษา",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_MIC
            );
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                    this,
                    "อุปกรณ์นี้ไม่มีบริการ Speech Recognition",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (preparingTranslation) return;

        preparingTranslation = true;
        setStatus("กำลังเตรียมการแปล...");
        refreshButtons();

        String source = currentInputLanguage();

        prepareRequiredModels(
                source,
                () -> {
                    if (!isUsable()) return;

                    preparingTranslation = false;
                    running = true;
                    paused = false;
                    recognitionActive = false;
                    speechStarted = false;

                    settingsExpanded = false;
                    settingsPanel.setVisibility(View.GONE);
                    settingsToggle.setText("⚙ การตั้งค่า ▾");

                    ensureRecognizer();
                    refreshButtons();
                    setStatus("🟢 กำลังฟัง...");
                    scheduleStart(250L);
                },
                () -> {
                    if (!isUsable()) return;

                    preparingTranslation = false;
                    running = false;
                    setStatus("เตรียมโมเดลแปลไม่สำเร็จ • ตรวจอินเทอร์เน็ตแล้วลองอีกครั้ง");
                    refreshButtons();
                }
        );
    }

    private void prepareRequiredModels(
            String source,
            Runnable onReady,
            Runnable onFailure
    ) {
        ArrayList<String[]> pairs = requiredTranslationPairs(source);

        if (pairs.isEmpty()) {
            onReady.run();
            return;
        }

        final int total = pairs.size();
        final int[] finished = {0};
        final boolean[] failed = {false};
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        for (String[] pair : pairs) {
            String from = pair[0];
            String to = pair[1];
            String key = from + ">" + to;

            if (Boolean.TRUE.equals(translatorReady.get(key))) {
                finished[0]++;
                if (finished[0] == total) {
                    if (failed[0]) onFailure.run();
                    else onReady.run();
                }
                continue;
            }

            Translator t = translator(from, to);

            t.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(v -> {
                        translatorReady.put(key, true);
                        finished[0]++;

                        if (finished[0] == total) {
                            if (failed[0]) onFailure.run();
                            else onReady.run();
                        }
                    })
                    .addOnFailureListener(e -> {
                        translatorReady.put(key, false);
                        failed[0] = true;
                        finished[0]++;

                        if (finished[0] == total) {
                            onFailure.run();
                        }
                    });
        }
    }

    private ArrayList<String[]> requiredTranslationPairs(String source) {
        ArrayList<String[]> pairs = new ArrayList<>();

        if ("zh".equals(source)) {
            if (englishCheck.isChecked() || thaiCheck.isChecked()) {
                addPairIfMissing(pairs, "zh", "en");
            }
            if (thaiCheck.isChecked()) {
                addPairIfMissing(pairs, "en", "th");
            }
        } else if ("th".equals(source)) {
            if (englishCheck.isChecked() || chineseCheck.isChecked()) {
                addPairIfMissing(pairs, "th", "en");
            }
            if (chineseCheck.isChecked()) {
                addPairIfMissing(pairs, "en", "zh");
            }
        } else {
            if (thaiCheck.isChecked()) {
                addPairIfMissing(pairs, "en", "th");
            }
            if (chineseCheck.isChecked()) {
                addPairIfMissing(pairs, "en", "zh");
            }
        }

        return pairs;
    }

    private void addPairIfMissing(
            ArrayList<String[]> pairs,
            String source,
            String target
    ) {
        for (String[] pair : pairs) {
            if (source.equals(pair[0]) && target.equals(pair[1])) return;
        }
        pairs.add(new String[]{source, target});
    }

    private void ensureRecognizer() {
        if (recognizer != null || !isUsable()) return;

        recognizer = SpeechRecognizer.createSpeechRecognizer(getApplicationContext());
        recognizer.setRecognitionListener(this);
    }

    private Intent buildRecognizerIntent() {
        String locale = localeForCode(sourceForCurrentRecognition);

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        // Helpful when supported; our watchdog below does not depend on them.
        i.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                450L
        );
        i.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1400L
        );
        i.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                900L
        );

        return i;
    }

    private void beginRecognition() {
        if (!isUsable() || !running || paused || recognitionActive) return;

        ensureRecognizer();
        if (recognizer == null) return;

        handler.removeCallbacks(restartRunnable);
        handler.removeCallbacks(silenceCommitRunnable);
        handler.removeCallbacks(maxUtteranceRunnable);

        sourceForCurrentRecognition = currentInputLanguage();
        recognitionActive = true;
        speechStarted = false;
        partial.setText("");
        setStatus("🟢 กำลังฟัง...");

        try {
            recognizer.startListening(buildRecognizerIntent());
            handler.postDelayed(maxUtteranceRunnable, MAX_UTTERANCE_MS);
        } catch (Exception e) {
            recognitionActive = false;
            setStatus("เริ่มฟังไม่สำเร็จ กำลังลองใหม่...");
            scheduleStart(RESTART_AFTER_ERROR_MS);
        }
    }

    private void scheduleStart(long delayMs) {
        if (!isUsable() || !running || paused) return;

        handler.removeCallbacks(restartRunnable);
        handler.postDelayed(restartRunnable, delayMs);
    }

    private void armSilenceCommit() {
        if (!speechStarted || !recognitionActive) return;

        handler.removeCallbacks(silenceCommitRunnable);
        handler.postDelayed(silenceCommitRunnable, SILENCE_COMMIT_MS);
    }

    private void clearSpeechTimers() {
        handler.removeCallbacks(silenceCommitRunnable);
        handler.removeCallbacks(maxUtteranceRunnable);
        handler.removeCallbacks(resultWatchdogRunnable);
    }

    private void armResultWatchdog() {
        handler.removeCallbacks(resultWatchdogRunnable);
        if (running && !paused && recognitionActive) {
            handler.postDelayed(resultWatchdogRunnable, RESULT_WATCHDOG_MS);
        }
    }

    private void clearResultWatchdog() {
        handler.removeCallbacks(resultWatchdogRunnable);
    }

    private void recycleRecognizerAndRestart(long delayMs) {
        handler.removeCallbacks(restartRunnable);
        handler.removeCallbacks(silenceCommitRunnable);
        handler.removeCallbacks(maxUtteranceRunnable);
        handler.removeCallbacks(resultWatchdogRunnable);

        recognitionActive = false;
        speechStarted = false;

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }

        if (!isUsable() || !running || paused) return;

        handler.postDelayed(() -> {
            if (!isUsable() || !running || paused) return;
            ensureRecognizer();
            setStatus("🟢 กลับมาฟังต่อแล้ว");
            scheduleStart(delayMs);
        }, 250L);
    }

    private void togglePause() {
        if (!running) return;

        paused = !paused;
        handler.removeCallbacks(restartRunnable);
        clearSpeechTimers();

        if (paused) {
            setStatus("⏸ พักการฟัง");

            if (recognitionActive && recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                }
            }

            recognitionActive = false;
            speechStarted = false;
        } else {
            setStatus("🟢 กำลังกลับมาฟัง...");
            scheduleStart(400L);
        }

        refreshButtons();
    }

    private void stopSession() {
        running = false;
        paused = false;
        recognitionActive = false;
        speechStarted = false;
        completedRecognitionCycles = 0;

        handler.removeCallbacks(restartRunnable);
        clearSpeechTimers();

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        if (isUsable()) {
            partial.setText("");
            setStatus("หยุดแล้ว");
            refreshButtons();
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        setStatus("🟢 กำลังฟัง...");
    }

    @Override
    public void onBeginningOfSpeech() {
        speechStarted = true;
        setStatus("🎙 กำลังพูด...");
        armSilenceCommit();
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        handler.removeCallbacks(silenceCommitRunnable);
        handler.removeCallbacks(maxUtteranceRunnable);
        setStatus("กำลังปิดประโยคและแปล...");
        armResultWatchdog();
    }

    @Override
    public void onSegmentResults(Bundle segmentResults) {
        // Not used. Standard recognition is more reliable on this device.
    }

    @Override
    public void onEndOfSegmentedSession() {
        // Not used.
    }

    @Override
    public void onError(int error) {
        recognitionActive = false;
        speechStarted = false;
        clearSpeechTimers();
        clearResultWatchdog();

        if (!isUsable() || !running || paused) return;

        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            setStatus("🟢 รอฟังประโยคถัดไป...");
            scheduleStart(RESTART_AFTER_TIMEOUT_MS);
            return;
        }

        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            setStatus("ไมค์ค้าง • กำลังรีเซ็ต...");
            recycleRecognizerAndRestart(700L);
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
            setStatus("ภาษานี้ไม่รองรับโดยบริการเสียงของเครื่อง");
            running = false;
            refreshButtons();
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            setStatus("ภาษานี้ยังไม่พร้อมใช้งาน กรุณาเชื่อมอินเทอร์เน็ต");
            running = false;
            refreshButtons();
            return;
        }

        setStatus("การฟังสะดุด กำลังเริ่มใหม่...");
        scheduleStart(RESTART_AFTER_ERROR_MS);
    }

    @Override
    public void onResults(Bundle results) {
        recognitionActive = false;
        speechStarted = false;
        clearSpeechTimers();
        clearResultWatchdog();

        if (!isUsable()) return;

        String text = firstText(results);
        partial.setText("");

        if (text != null && !text.trim().isEmpty()) {
            acceptRecognizedText(text.trim(), sourceForCurrentRecognition);
        }

        if (running && !paused) {
            completedRecognitionCycles++;
            if (completedRecognitionCycles >= RECYCLE_AFTER_CYCLES) {
                completedRecognitionCycles = 0;
                setStatus("กำลังรีเฟรชไมค์เพื่อฟังต่อ...");
                recycleRecognizerAndRestart(420L);
            } else {
                setStatus("🟢 กำลังฟังต่อ...");
                scheduleStart(RESTART_AFTER_RESULT_MS);
            }
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (!isUsable()) return;

        String text = firstText(partialResults);

        if (text != null && !text.trim().isEmpty()) {
            speechStarted = true;
            partial.setText("“" + text + "”");
            armSilenceCommit();
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    private String firstText(Bundle b) {
        if (b == null) return null;

        ArrayList<String> list = b.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
        );

        return (list == null || list.isEmpty())
                ? null
                : list.get(0);
    }

    private void acceptRecognizedText(
            String text,
            String source
    ) {
        long now = System.currentTimeMillis();

        if (text.equals(lastResultText) &&
                now - lastResultAt < 1100L) {
            return;
        }

        lastResultText = text;
        lastResultAt = now;

        ArrayList<String> units = splitIntoSentenceUnits(text);

        for (String unit : units) {
            String clean = unit.trim();
            if (!clean.isEmpty()) {
                processUtterance(clean, source);
            }
        }
    }

    private ArrayList<String> splitIntoSentenceUnits(String text) {
        ArrayList<String> units = new ArrayList<>();

        String[] pieces = text.split(
                "(?<=[.!?。！？；;])\\s*|\\n+"
        );

        for (String piece : pieces) {
            if (piece != null && !piece.trim().isEmpty()) {
                units.add(piece.trim());
            }
        }

        if (units.isEmpty()) {
            units.add(text);
        }

        return units;
    }

    private void processUtterance(
            String original,
            String source
    ) {
        if (!isUsable()) return;

        Card card = addCard(original);
        translateSelected(original, source, card);
    }

    private void translateSelected(
            String text,
            String source,
            Card card
    ) {
        if ("zh".equals(source)) {
            if (chineseCheck.isChecked()) {
                card.setLine("zh", text);
            }

            if (englishCheck.isChecked() || thaiCheck.isChecked()) {
                translateDirect(text, "zh", "en", english -> {
                    if (isFailure(english)) {
                        if (englishCheck.isChecked()) card.setLine("en", "[แปลไม่สำเร็จ]");
                        if (thaiCheck.isChecked()) card.setLine("th", "[แปลไม่สำเร็จ]");
                        return;
                    }

                    if (englishCheck.isChecked()) {
                        card.setLine("en", english);
                    }

                    if (thaiCheck.isChecked()) {
                        translateDirect(english, "en", "th", thai -> {
                            card.setLine(
                                    "th",
                                    isFailure(thai) ? "[แปลไม่สำเร็จ]" : thai
                            );
                        });
                    }
                });
            }
            return;
        }

        if ("th".equals(source)) {
            if (thaiCheck.isChecked()) {
                card.setLine("th", text);
            }

            if (englishCheck.isChecked() || chineseCheck.isChecked()) {
                translateDirect(text, "th", "en", english -> {
                    if (isFailure(english)) {
                        if (englishCheck.isChecked()) card.setLine("en", "[แปลไม่สำเร็จ]");
                        if (chineseCheck.isChecked()) card.setLine("zh", "[แปลไม่สำเร็จ]");
                        return;
                    }

                    if (englishCheck.isChecked()) {
                        card.setLine("en", english);
                    }

                    if (chineseCheck.isChecked()) {
                        translateDirect(english, "en", "zh", chinese -> {
                            card.setLine(
                                    "zh",
                                    isFailure(chinese) ? "[แปลไม่สำเร็จ]" : chinese
                            );
                        });
                    }
                });
            }
            return;
        }

        // English source.
        if (englishCheck.isChecked()) {
            card.setLine("en", text);
        }

        if (thaiCheck.isChecked()) {
            translateDirect(text, "en", "th", thai -> {
                card.setLine(
                        "th",
                        isFailure(thai) ? "[แปลไม่สำเร็จ]" : thai
                );
            });
        }

        if (chineseCheck.isChecked()) {
            translateDirect(text, "en", "zh", chinese -> {
                card.setLine(
                        "zh",
                        isFailure(chinese) ? "[แปลไม่สำเร็จ]" : chinese
                );
            });
        }
    }

    private boolean isFailure(String value) {
        return "[TIMEOUT]".equals(value) ||
                "[FAILED]".equals(value);
    }

    private interface TextCallback {
        void onDone(String text);
    }

    private void translateDirect(
            String text,
            String source,
            String target,
            TextCallback cb
    ) {
        String key = source + ">" + target;
        Translator t = translator(source, target);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        final boolean[] completed = {false};

        Runnable timeout = () -> {
            if (!completed[0] && !destroyed) {
                completed[0] = true;
                cb.onDone("[TIMEOUT]");
            }
        };

        handler.postDelayed(timeout, 8000L);

        Runnable doTranslate = () -> t.translate(text)
                .addOnSuccessListener(value -> {
                    if (completed[0] || destroyed) return;

                    completed[0] = true;
                    handler.removeCallbacks(timeout);
                    cb.onDone(value);
                })
                .addOnFailureListener(e -> {
                    if (completed[0] || destroyed) return;

                    completed[0] = true;
                    handler.removeCallbacks(timeout);
                    cb.onDone("[FAILED]");
                });

        if (Boolean.TRUE.equals(translatorReady.get(key))) {
            doTranslate.run();
            return;
        }

        t.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> {
                    translatorReady.put(key, true);

                    if (!completed[0] && !destroyed) {
                        doTranslate.run();
                    }
                })
                .addOnFailureListener(e -> {
                    translatorReady.put(key, false);

                    if (completed[0] || destroyed) return;

                    completed[0] = true;
                    handler.removeCallbacks(timeout);
                    cb.onDone("[FAILED]");
                });
    }

    private Translator translator(
            String source,
            String target
    ) {
        String key = source + ">" + target;
        Translator t = translators.get(key);

        if (t != null) return t;

        TranslatorOptions options =
                new TranslatorOptions.Builder()
                        .setSourceLanguage(ml(source))
                        .setTargetLanguage(ml(target))
                        .build();

        t = Translation.getClient(options);
        translators.put(key, t);
        return t;
    }

    private String ml(String code) {
        if ("th".equals(code)) return TranslateLanguage.THAI;
        if ("zh".equals(code)) return TranslateLanguage.CHINESE;
        return TranslateLanguage.ENGLISH;
    }

    private Card addCard(String original) {
        while (timeline.getChildCount() >= 100) {
            timeline.removeViewAt(0);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(11));
        box.setBackgroundColor(Color.WHITE);

        LinearLayout.LayoutParams bp = full();
        bp.setMargins(0, dp(4), 0, dp(6));
        timeline.addView(box, bp);

        TextView header = new TextView(this);
        header.setText(clock.format(new Date()));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(80, 90, 105));
        box.addView(header, full());

        TextView originalView = new TextView(this);
        originalView.setText(original);
        originalView.setTextSize(20);
        originalView.setTypeface(Typeface.DEFAULT_BOLD);
        originalView.setTextColor(Color.rgb(20, 25, 32));
        originalView.setPadding(0, dp(7), 0, dp(6));
        box.addView(originalView, full());

        TextView th = line("🇹🇭 กำลังแปล...");
        TextView zh = line("🇨🇳 正在翻译...");
        TextView en = line("🇬🇧 Translating...");

        if (thaiCheck.isChecked()) box.addView(th, full());
        if (chineseCheck.isChecked()) box.addView(zh, full());
        if (englishCheck.isChecked()) box.addView(en, full());

        Card card = new Card(th, zh, en);

        handler.post(() -> {
            if (isUsable()) {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });

        return card;
    }

    private TextView line(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(17);
        v.setTextColor(Color.rgb(45, 52, 62));
        v.setPadding(0, dp(5), 0, dp(5));
        return v;
    }

    private class Card {
        final TextView th;
        final TextView zh;
        final TextView en;

        Card(
                TextView t,
                TextView z,
                TextView e
        ) {
            th = t;
            zh = z;
            en = e;
        }

        void setLine(
                String code,
                String value
        ) {
            runOnUiThread(() -> {
                if (!isUsable()) return;

                if ("th".equals(code) && thaiCheck.isChecked()) {
                    th.setText("🇹🇭 " + value);
                } else if ("zh".equals(code) && chineseCheck.isChecked()) {
                    zh.setText("🇨🇳 " + value);
                } else if ("en".equals(code) && englishCheck.isChecked()) {
                    en.setText("🇬🇧 " + value);
                }

                handler.post(() -> {
                    if (isUsable()) {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            });
        }
    }

    private void closeTranslationClients() {
        for (Translator t : translators.values()) {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }

        translators.clear();
        translatorReady.clear();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (running && !isChangingConfigurations()) {
            running = false;
            paused = false;
            recognitionActive = false;
            speechStarted = false;

            handler.removeCallbacks(restartRunnable);
            clearSpeechTimers();

            if (recognizer != null) {
                try {
                    recognizer.cancel();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!destroyed &&
                status != null &&
                !running) {
            setStatus("พร้อม");
            refreshButtons();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQ_MIC &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else if (requestCode == REQ_MIC) {
            Toast.makeText(
                    this,
                    "ต้องอนุญาตไมโครโฟนเพื่อใช้งาน",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        running = false;
        paused = true;
        recognitionActive = false;
        speechStarted = false;

        handler.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }

            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }

            recognizer = null;
        }

        closeTranslationClients();
        super.onDestroy();
    }
}
