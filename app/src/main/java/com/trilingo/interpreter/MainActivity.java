package com.trilingo.interpreter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.EditText;
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

    private static final long RESTART_AFTER_RESULT_MS = 1200L;
    private static final long RESTART_AFTER_TIMEOUT_MS = 1500L;
    private static final long RESTART_AFTER_ERROR_MS = 2200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Translator> translators = new HashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private SpeechRecognizer recognizer;
    private boolean running = false;
    private boolean paused = false;
    private boolean recognitionActive = false;
    private boolean changingLanguage = false;
    private boolean destroyed = false;

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
    private RadioGroup inputLanguageGroup;
    private RadioButton thaiInput;
    private RadioButton chineseInput;
    private RadioButton englishInput;
    private RadioGroup speakerGroup;
    private RadioButton speaker1Button;
    private RadioButton speaker2Button;
    private RadioButton speaker3Button;
    private RadioButton speaker4Button;
    private Button renameSpeakerButton;

    private int currentSpeakerId = 1;
    private int speakerForCurrentRecognition = 1;
    private String sourceForCurrentRecognition = "th";
    private final String[] speakerNames = {"", "Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4"};

    private final Runnable restartRunnable = () -> {
        if (!isUsable() || !running || paused || recognitionActive) return;
        beginRecognition();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        restoreLanguageSelection();
        restoreInputLanguageMode();
        restoreSpeakerSettings();
        updateInputLanguageAvailability();
        updateLanguageHint();
        refreshSpeakerButtons();
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

        TextView selectorTitle = smallTitle("เลือกภาษาที่ต้องการแสดงผล");
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
            updateInputLanguageAvailability();
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

        TextView speakerTitle = smallTitle("ผู้พูดตอนนี้");
        root.addView(speakerTitle, full());

        LinearLayout speakerRow = new LinearLayout(this);
        speakerRow.setOrientation(LinearLayout.HORIZONTAL);
        speakerRow.setGravity(Gravity.CENTER_VERTICAL);

        speakerGroup = new RadioGroup(this);
        speakerGroup.setOrientation(LinearLayout.HORIZONTAL);
        speakerGroup.setGravity(Gravity.CENTER_VERTICAL);

        speaker1Button = inputRadio("1");
        speaker2Button = inputRadio("2");
        speaker3Button = inputRadio("3");
        speaker4Button = inputRadio("4");

        speaker1Button.setId(View.generateViewId());
        speaker2Button.setId(View.generateViewId());
        speaker3Button.setId(View.generateViewId());
        speaker4Button.setId(View.generateViewId());

        speakerGroup.addView(speaker1Button, weighted());
        speakerGroup.addView(speaker2Button, weighted());
        speakerGroup.addView(speaker3Button, weighted());
        speakerGroup.addView(speaker4Button, weighted());

        speakerRow.addView(speakerGroup, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        renameSpeakerButton = makeButton("✏️ ชื่อ");
        renameSpeakerButton.setTextSize(12);
        renameSpeakerButton.setMinHeight(dp(44));
        speakerRow.addView(renameSpeakerButton, new LinearLayout.LayoutParams(
                dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(speakerRow, full());

        TextView speakerHelp = new TextView(this);
        speakerHelp.setText("เลือก 1–4 ก่อนพูด • แตะ ✏️ เพื่อเปลี่ยนชื่อ");
        speakerHelp.setTextSize(11);
        speakerHelp.setTextColor(Color.GRAY);
        speakerHelp.setPadding(dp(2), 0, dp(2), dp(3));
        root.addView(speakerHelp, full());

        speakerGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == speaker2Button.getId()) currentSpeakerId = 2;
            else if (checkedId == speaker3Button.getId()) currentSpeakerId = 3;
            else if (checkedId == speaker4Button.getId()) currentSpeakerId = 4;
            else currentSpeakerId = 1;

            saveSpeakerSettings();
            refreshSpeakerButtons();
        });

        renameSpeakerButton.setOnClickListener(v -> showRenameSpeakerDialog());

        TextView inputTitle = smallTitle("ภาษาผู้พูดตอนนี้");
        root.addView(inputTitle, full());

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
        root.addView(inputLanguageGroup, full());

        TextView inputHelp = new TextView(this);
        inputHelp.setText("โหมดเสถียร: เลือกภาษาของคนที่กำลังพูด แล้วระบบจะถอดเสียงและแปลให้");
        inputHelp.setTextSize(11);
        inputHelp.setTextColor(Color.GRAY);
        inputHelp.setPadding(dp(2), 0, dp(2), dp(3));
        root.addView(inputHelp, full());

        inputLanguageGroup.setOnCheckedChangeListener((group, checkedId) -> {
            saveInputLanguageMode();
            if (running && !paused) {
                requestLanguageChange();
            }
        });

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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("เลือกผู้พูด 1–4 ก่อนพูด • เปลี่ยนชื่อได้\nถ้าระบุผิด กด 👤 แก้ผู้พูด ในข้อความย้อนหลังได้");
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
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isUsable() {
        return !destroyed && !isFinishing() &&
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

    private void updateLanguageHint() {
        ArrayList<String> names = new ArrayList<>();
        if (thaiCheck.isChecked()) names.add("ไทย");
        if (chineseCheck.isChecked()) names.add("中文(简体)");
        if (englishCheck.isChecked()) names.add("English");
        languageHint.setText("แสดงผล: " + android.text.TextUtils.join(" • ", names));
    }

    private void restoreInputLanguageMode() {
        String mode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("input_language_mode", "th");

        if ("zh".equals(mode) && chineseCheck.isChecked()) {
            chineseInput.setChecked(true);
        } else if ("en".equals(mode) && englishCheck.isChecked()) {
            englishInput.setChecked(true);
        } else {
            thaiInput.setChecked(true);
        }
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

    private void restoreSpeakerSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        speakerNames[1] = prefs.getString("speaker_name_1", "Speaker 1");
        speakerNames[2] = prefs.getString("speaker_name_2", "Speaker 2");
        speakerNames[3] = prefs.getString("speaker_name_3", "Speaker 3");
        speakerNames[4] = prefs.getString("speaker_name_4", "Speaker 4");
        currentSpeakerId = prefs.getInt("current_speaker_id", 1);

        if (currentSpeakerId == 2) speaker2Button.setChecked(true);
        else if (currentSpeakerId == 3) speaker3Button.setChecked(true);
        else if (currentSpeakerId == 4) speaker4Button.setChecked(true);
        else speaker1Button.setChecked(true);
    }

    private void saveSpeakerSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt("current_speaker_id", currentSpeakerId)
                .putString("speaker_name_1", speakerNames[1])
                .putString("speaker_name_2", speakerNames[2])
                .putString("speaker_name_3", speakerNames[3])
                .putString("speaker_name_4", speakerNames[4])
                .apply();
    }

    private String speakerName(int id) {
        if (id < 1 || id > 4) return "Speaker";
        String name = speakerNames[id];
        if (name == null || name.trim().isEmpty()) return "Speaker " + id;
        return name.trim();
    }

    private void refreshSpeakerButtons() {
        if (speaker1Button == null) return;
        speaker1Button.setText(shortSpeakerLabel(1));
        speaker2Button.setText(shortSpeakerLabel(2));
        speaker3Button.setText(shortSpeakerLabel(3));
        speaker4Button.setText(shortSpeakerLabel(4));
    }

    private String shortSpeakerLabel(int id) {
        String name = speakerName(id);
        String defaultName = "Speaker " + id;
        if (defaultName.equals(name)) return String.valueOf(id);
        if (name.length() <= 7) return name;
        return String.valueOf(id);
    }

    private void showRenameSpeakerDialog() {
        if (!isUsable()) return;

        final int speakerId = currentSpeakerId;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(speakerName(speakerId));
        input.setSelection(input.getText().length());
        input.setHint("ชื่อผู้พูด");

        new AlertDialog.Builder(this)
                .setTitle("เปลี่ยนชื่อผู้พูด " + speakerId)
                .setView(input)
                .setPositiveButton("บันทึก", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    speakerNames[speakerId] = value.isEmpty()
                            ? "Speaker " + speakerId
                            : value;
                    saveSpeakerSettings();
                    refreshSpeakerButtons();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void showChangeSpeakerDialog(Card card) {
        if (!isUsable()) return;

        String[] names = {
                speakerName(1),
                speakerName(2),
                speakerName(3),
                speakerName(4)
        };

        int checked = Math.max(0, Math.min(3, card.speakerId - 1));

        new AlertDialog.Builder(this)
                .setTitle("เปลี่ยนผู้พูดของข้อความนี้")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    card.setSpeaker(which + 1);
                    dialog.dismiss();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void setStatus(String text) {
        if (status != null && isUsable()) status.setText(text);
    }

    private void refreshButtons() {
        if (!isUsable()) return;

        start.setEnabled(!running);
        pause.setEnabled(running);
        stop.setEnabled(running);
        pause.setText(paused ? "▶ ทำต่อ" : "⏸ พัก");

        boolean canEditOutputs = !running;
        thaiCheck.setEnabled(canEditOutputs);
        chineseCheck.setEnabled(canEditOutputs);
        englishCheck.setEnabled(canEditOutputs);
        updateInputLanguageAvailability();
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
        changingLanguage = false;
        recognitionActive = false;

        ensureRecognizer();
        refreshButtons();
        scheduleStart(400L);
    }

    private void ensureRecognizer() {
        if (recognizer != null || !isUsable()) return;

        recognizer = SpeechRecognizer.createSpeechRecognizer(getApplicationContext());
        recognizer.setRecognitionListener(this);
    }

    private Intent buildRecognizerIntent() {
        String locale = localeForCode(sourceForCurrentRecognition);

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        // Longer end-pointer windows: intended to reduce clipped sentences.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L);
        return i;
    }

    private void beginRecognition() {
        if (!isUsable() || !running || paused || recognitionActive) return;

        ensureRecognizer();
        if (recognizer == null) return;

        handler.removeCallbacks(restartRunnable);
        partial.setText("");
        speakerForCurrentRecognition = currentSpeakerId;
        sourceForCurrentRecognition = currentInputLanguage();
        setStatus("🟢 " + speakerName(speakerForCurrentRecognition) + " กำลังฟัง...");

        try {
            recognitionActive = true;
            recognizer.startListening(buildRecognizerIntent());
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

    private void requestLanguageChange() {
        if (!running || paused || recognizer == null) return;

        changingLanguage = true;
        handler.removeCallbacks(restartRunnable);
        setStatus("กำลังเปลี่ยนภาษาผู้พูด...");

        if (recognitionActive) {
            try {
                recognizer.cancel();
            } catch (Exception e) {
                recognitionActive = false;
                changingLanguage = false;
                scheduleStart(1200L);
            }
        } else {
            changingLanguage = false;
            scheduleStart(700L);
        }
    }

    private void togglePause() {
        if (!running) return;

        paused = !paused;
        handler.removeCallbacks(restartRunnable);

        if (paused) {
            setStatus("⏸ พักการฟัง");
            if (recognitionActive && recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) {}
            }
        } else {
            recognitionActive = false;
            scheduleStart(700L);
        }
        refreshButtons();
    }

    private void stopSession() {
        running = false;
        paused = false;
        changingLanguage = false;
        handler.removeCallbacks(restartRunnable);

        if (recognitionActive && recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        recognitionActive = false;

        if (isUsable()) {
            partial.setText("");
            setStatus("หยุดแล้ว");
            refreshButtons();
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        setStatus("🟢 " + speakerName(speakerForCurrentRecognition) + " กำลังฟัง...");
    }

    @Override
    public void onBeginningOfSpeech() {
        setStatus("🎙 " + speakerName(speakerForCurrentRecognition) + " กำลังพูด...");
    }

    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {
        setStatus("กำลังประมวลผล...");
        // IMPORTANT: do not call startListening here.
        // Wait for onResults/onError from this recognition cycle.
    }

    @Override
    public void onError(int error) {
        recognitionActive = false;

        if (!isUsable() || !running || paused) return;

        if (changingLanguage) {
            changingLanguage = false;
            scheduleStart(900L);
            return;
        }

        if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            setStatus("🟢 รอฟังประโยคถัดไป...");
            scheduleStart(RESTART_AFTER_TIMEOUT_MS);
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
            setStatus("ภาษานี้ไม่รองรับโดยบริการเสียงของเครื่อง");
            running = false;
            refreshButtons();
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            setStatus("ภาษานี้ยังไม่พร้อมใช้งาน กรุณาลองเชื่อมอินเทอร์เน็ต");
            running = false;
            refreshButtons();
            return;
        }

        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            setStatus("ไมค์ยังไม่พร้อม กำลังรอ...");
            scheduleStart(2600L);
            return;
        }

        setStatus("การฟังสะดุด กำลังเริ่มใหม่...");
        scheduleStart(RESTART_AFTER_ERROR_MS);
    }

    @Override
    public void onResults(Bundle results) {
        recognitionActive = false;

        if (!isUsable()) return;

        String text = firstText(results);
        partial.setText("");

        if (text != null && !text.trim().isEmpty()) {
            processUtterance(
                    text.trim(),
                    sourceForCurrentRecognition,
                    speakerForCurrentRecognition
            );
        }

        if (running && !paused) {
            scheduleStart(RESTART_AFTER_RESULT_MS);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        if (!isUsable()) return;

        String text = firstText(partialResults);
        if (text != null) partial.setText("“" + text + "”");
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    private String firstText(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private void processUtterance(String original, String source, int speakerId) {
        if (!isUsable()) return;

        Card card = addCard(original, speakerId);
        setStatus("กำลังแปล...");
        translateSelected(original, source, card);
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
        if (done >= expected && isUsable()) {
            runOnUiThread(() -> {
                if (isUsable() && running && !paused && !recognitionActive) {
                    setStatus("🟢 รอฟังประโยคถัดไป...");
                }
            });
        }
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
                .addOnSuccessListener(v -> {
                    if (destroyed) return;
                    translator.translate(text)
                            .addOnSuccessListener(value -> {
                                if (!destroyed) cb.onDone(value);
                            })
                            .addOnFailureListener(e -> {
                                if (!destroyed) cb.onDone("[แปลไม่สำเร็จ]");
                            });
                })
                .addOnFailureListener(e -> {
                    if (!destroyed) cb.onDone("[ดาวน์โหลดโมเดลไม่สำเร็จ]");
                });
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
        if ("th".equals(code)) return TranslateLanguage.THAI;
        if ("zh".equals(code)) return TranslateLanguage.CHINESE;
        return TranslateLanguage.ENGLISH;
    }

    private Card addCard(String original, int speakerId) {
        if (timeline.getChildCount() >= 30) {
            timeline.removeViewAt(0);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundColor(Color.WHITE);

        LinearLayout.LayoutParams bp = full();
        bp.setMargins(0, dp(5), 0, dp(7));
        timeline.addView(box, bp);

        TextView header = new TextView(this);
        header.setText(speakerName(speakerId) + " • " + clock.format(new Date()));
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

        Button changeSpeaker = new Button(this);
        changeSpeaker.setText("👤 แก้ผู้พูด");
        changeSpeaker.setAllCaps(false);
        changeSpeaker.setTextSize(12);
        changeSpeaker.setMinHeight(dp(40));
        LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        box.addView(changeSpeaker, changeParams);

        TextView th = line("🇹🇭 กำลังแปล...");
        TextView zh = line("🇨🇳 正在翻译...");
        TextView en = line("🇬🇧 Translating...");

        if (thaiCheck.isChecked()) box.addView(th, full());
        if (chineseCheck.isChecked()) box.addView(zh, full());
        if (englishCheck.isChecked()) box.addView(en, full());

        handler.post(() -> {
            if (isUsable()) scroll.fullScroll(View.FOCUS_DOWN);
        });

        Card card = new Card(header, th, zh, en, speakerId);
        changeSpeaker.setOnClickListener(v -> showChangeSpeakerDialog(card));
        return card;
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
        final TextView header;
        final TextView th;
        final TextView zh;
        final TextView en;
        int speakerId;

        Card(TextView h, TextView t, TextView z, TextView e, int speaker) {
            header = h;
            th = t;
            zh = z;
            en = e;
            speakerId = speaker;
        }

        void setSpeaker(int newSpeakerId) {
            speakerId = Math.max(1, Math.min(4, newSpeakerId));
            header.setText(speakerName(speakerId) + " • " + clock.format(new Date()));
        }

        void setLine(String code, String value) {
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
                    if (isUsable()) scroll.fullScroll(View.FOCUS_DOWN);
                });
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (running && !isChangingConfigurations()) {
            running = false;
            paused = false;
            changingLanguage = false;
            handler.removeCallbacks(restartRunnable);

            if (recognitionActive && recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) {}
            }
            recognitionActive = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!destroyed && status != null && !running) {
            setStatus("พร้อม");
            refreshButtons();
        }
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
        destroyed = true;
        running = false;
        paused = true;
        recognitionActive = false;
        changingLanguage = false;
        handler.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }

        for (Translator t : translators.values()) {
            try { t.close(); } catch (Exception ignored) {}
        }
        translators.clear();

        super.onDestroy();
    }
}
