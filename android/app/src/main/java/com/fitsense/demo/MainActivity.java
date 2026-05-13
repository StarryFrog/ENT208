package com.fitsense.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.location.LocationManager;
import android.content.Context;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements BleFitnessClient.Listener {
    private static final int PICK_AUDIO_REQUEST = 42;
    private static final int BLE_PERMISSION_REQUEST = 1001;
    private static final int MAX_VISIBLE_SCAN_RESULTS = 10;
    private static final Pattern HEART_RATE_PATTERN = Pattern.compile("\"?(hr|HR|heartRate|heart_rate|current_bpm)\"?\\s*[:=]\\s*\"?(\\d{1,3})\"?");
    private static final Pattern REPS_PATTERN = Pattern.compile("\"?(reps|rep|count|rep_count|current_reps|squat|squat_count|counter|motion_count|reps_done|repDone)\"?\\s*[:=]\\s*\"?(\\d{1,4})\"?");
    private static final Pattern TARGET_PATTERN = Pattern.compile("\"?(target|target_reps|goal|total_reps)\"?\\s*[:=]\\s*\"?(\\d{1,4})\"?");
    private static final Pattern PUSHUP_PATTERN = Pattern.compile("PUSHUP\\s*[:=]\\s*(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRESS_PATTERN = Pattern.compile("PRESS\\s*[:=]\\s*(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_PATTERN = Pattern.compile("STEP\\s*[:=]\\s*(\\d{1,6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAD_PATTERN = Pattern.compile("CAD\\s*[:=]\\s*(\\d{1,4})", Pattern.CASE_INSENSITIVE);
    private static final int COLOR_BG = Color.rgb(246, 244, 236);
    private static final int COLOR_SURFACE = Color.rgb(255, 253, 247);
    private static final int COLOR_PANEL = Color.rgb(255, 255, 252);
    private static final int COLOR_TEXT = Color.rgb(25, 28, 24);
    private static final int COLOR_MUTED = Color.rgb(106, 110, 97);
    private static final int COLOR_LINE = Color.rgb(231, 229, 217);
    private static final int COLOR_ACCENT = Color.rgb(210, 238, 96);
    private static final int COLOR_ACCENT_DEEP = Color.rgb(62, 84, 16);
    private static final int COLOR_OLIVE = Color.rgb(60, 72, 35);
    private static final int COLOR_CHART = Color.rgb(148, 188, 41);
    private static boolean englishMode = false;

    private MediaPlayer mediaPlayer;
    private LinearLayout root;
    private LinearLayout pageContent;
    private LinearLayout bottomTabs;
    private LinearLayout miniPlayer;
    private LinearLayout modeTabs;
    private LinearLayout deviceList;
    private LinearLayout playlistRows;
    private TextView heroTitleText;
    private TextView heroSubtitleText;
    private TextView statusText;
    private TextView heroHrText;
    private TextView heroZoneText;
    private TextView modeTitleText;
    private TextView modeHintText;
    private TextView hrText;
    private TextView zoneText;
    private TextView primaryMetricText;
    private TextView secondaryMetricText;
    private TextView tertiaryMetricText;
    private TextView summaryText;
    private TextView musicText;
    private TextView nowPlayingText;
    private TextView miniSongText;
    private TextView jsonText;
    private TextView debugText;
    private TextView permissionStatusText;
    private TextView locationStatusText;
    private TextView bluetoothStatusText;
    private TextView resultsSummaryText;
    private HeartChartView heartChartView;
    private Button scanButton;
    private Button stopScanButton;
    private Button quickConnectButton;
    private Button pauseButton;
    private Button playButton;
    private Button startTrainingButton;
    private TextView previousSongButton;
    private TextView playerToggleButton;
    private TextView nextSongButton;
    private EditText strideInput;
    private EditText targetHrInput;
    private Switch autoMusicSwitch;
    private Switch languageSwitch;

    private BleFitnessClient bleClient;
    private final List<BleFitnessClient.FitDevice> scannedDevices = new ArrayList<>();

    private boolean connected = false;
    private boolean paused = false;
    private boolean autoMusic = true;
    private boolean isPlaying = false;
    private int heartRate = 0;
    private int runSteps = 0;
    private int pushupCount = 0;
    private int benchCount = 0;
    private int cadence = 0;
    private int signalQuality = 0;
    private int hardwareRepCount = 0;
    private int targetRepCount = 10;
    private int pendingZoneIndex = 0;
    private int playingZoneIndex = -1;
    private int pendingAutoZoneIndex = -1;
    private final int[] heartHistory = new int[36];
    private int heartHistoryCount = 0;
    private double stride = 0.78;
    private int targetHr = 160;
    private String activePage = "train";
    private String selectedMode = "run";
    private String connectedDevice = "未连接设备 / Disconnected";
    private String lastPayload = "{}";
    private String bleDebugStatus = "等待扫描 / Waiting to scan";
    private final List<String> bleDebugLines = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean deviceListRefreshPending = false;
    private boolean trainingActive = false;
    private boolean restActive = false;
    private boolean setCompleted = false;
    private int restSecondsRemaining = 0;
    private int completedSets = 0;
    private final Runnable restTicker = new Runnable() {
        @Override
        public void run() {
            if (!restActive) return;
            if (restSecondsRemaining > 0) {
                restSecondsRemaining -= 1;
                render();
                uiHandler.postDelayed(this, 1000);
                return;
            }
            restActive = false;
            trainingActive = false;
            setCompleted = false;
            hardwareRepCount = 0;
            pushupCount = 0;
            benchCount = 0;
            appendBleDebug(bi("休息结束，可以开始下一组", "Rest complete, ready for next set"));
            render();
        }
    };

    private final SongSlot[] zoneSongs = {
            new SongSlot("Zone 1", "<100 放松 / Relax"),
            new SongSlot("Zone 2", "100-130 热身 / Warm-up"),
            new SongSlot("Zone 3", "131-160 训练 / Train"),
            new SongSlot("Zone 4", ">160 高燃 / Peak")
    };

    private final WorkoutMode[] modes = {
            new WorkoutMode("run", "跑步 Run", "实时步数与步频 / Live steps & cadence"),
            new WorkoutMode("pushup", "俯卧撑 Push-up", "上肢推举计数 / Upper-body rep count"),
            new WorkoutMode("benchpress", "卧推 Bench", "胸肩训练计数 / Chest & shoulder reps")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bleClient = new BleFitnessClient(this, this);
        BleFitnessClient.setEnglishMode(englishMode);
        setContentView(buildContent());
        showTrainPage();
    }

    @Override
    protected void onDestroy() {
        stopAudio();
        if (bleClient != null) {
            bleClient.disconnect();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                zoneSongs[pendingZoneIndex].addTrack(uri, displayNameFor(uri));
                Toast.makeText(this, bi("已加入 ", "Added to ") + zoneSongs[pendingZoneIndex].zone + bi(" 歌单", " playlist"), Toast.LENGTH_SHORT).show();
                rebuildCurrentPage();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLE_PERMISSION_REQUEST) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                scanDevices();
            } else {
                Toast.makeText(this, bi("需要蓝牙权限才能扫描设备", "Bluetooth permission is required to scan devices"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private View buildContent() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setBackgroundColor(COLOR_BG);

        LinearLayout hero = compactPanel(COLOR_SURFACE, dp(24));
        LinearLayout heroTop = row();
        LinearLayout heroCopy = new LinearLayout(this);
        heroCopy.setOrientation(LinearLayout.VERTICAL);
        heroTitleText = text("FitSense", 23, COLOR_TEXT, true);
        heroSubtitleText = text("", 13, COLOR_MUTED, false);
        heroCopy.addView(heroTitleText);
        heroCopy.addView(heroSubtitleText);
        LinearLayout heroVitals = new LinearLayout(this);
        heroVitals.setOrientation(LinearLayout.VERTICAL);
        heroVitals.setGravity(Gravity.CENTER);
        heroVitals.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable heroBadge = new GradientDrawable();
        heroBadge.setShape(GradientDrawable.OVAL);
        heroBadge.setColor(COLOR_PANEL);
        heroBadge.setStroke(dp(2), COLOR_ACCENT);
        heroVitals.setBackground(heroBadge);
        heroHrText = text("--", 20, COLOR_TEXT, true);
        heroZoneText = text("等待设备 / Waiting", 11, COLOR_MUTED, false);
        heroVitals.addView(heroHrText);
        heroVitals.addView(heroZoneText);
        heroTop.addView(heroCopy, weightParams());
        heroTop.addView(heroVitals, new LinearLayout.LayoutParams(dp(92), dp(92)));
        statusText = text("", 12, COLOR_MUTED, false);
        statusText.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable statusBadge = new GradientDrawable();
        statusBadge.setCornerRadius(dp(999));
        statusBadge.setColor(Color.rgb(248, 247, 239));
        statusBadge.setStroke(dp(1), COLOR_LINE);
        statusText.setBackground(statusBadge);
        addTopMargin(statusText, 8);
        hero.addView(heroTop);
        hero.addView(statusText);
        root.addView(hero);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        pageContent = new LinearLayout(this);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(0, 0, 0, dp(8));
        scrollView.addView(pageContent);
        root.addView(scrollView, scrollParams);

        miniPlayer = compactPanel(COLOR_OLIVE, dp(22));
        miniPlayer.setPadding(dp(14), dp(10), dp(14), dp(8));
        miniSongText = text("未播放歌曲 / No song", 12, Color.rgb(241, 245, 231), true);
        miniPlayer.addView(miniSongText);
        LinearLayout playerControls = row();
        previousSongButton = playerButton("‹", false);
        playerToggleButton = playerButton("▶", true);
        nextSongButton = playerButton("›", false);
        playerControls.addView(previousSongButton, centerButtonParams());
        playerControls.addView(playerToggleButton, centerButtonParams());
        playerControls.addView(nextSongButton, centerButtonParams());
        miniPlayer.addView(playerControls);
        root.addView(miniPlayer);

        bottomTabs = row();
        bottomTabs.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable tabsShell = new GradientDrawable();
        tabsShell.setCornerRadius(dp(26));
        tabsShell.setColor(COLOR_PANEL);
        tabsShell.setStroke(dp(1), COLOR_LINE);
        bottomTabs.setBackground(tabsShell);
        root.addView(bottomTabs);
        buildBottomTabs();
        previousSongButton.setOnClickListener(v -> playAdjacentSong(-1));
        playerToggleButton.setOnClickListener(v -> togglePlayer());
        nextSongButton.setOnClickListener(v -> playAdjacentSong(1));
        return root;
    }

    private void showTrainPage() {
        activePage = "train";
        pageContent.removeAllViews();
        heroSubtitleText.setText(bi("训练仪表盘", "Training Dashboard"));

        modeTabs = row();
        addTopMargin(modeTabs, 2);
        pageContent.addView(modeTabs);
        buildModeTabs();

        LinearLayout actionRow = row();
        startTrainingButton = button(connected ? bi("开始训练", "Start Training") : bi("先连接设备", "Connect First"), true);
        pauseButton = button(connected ? bi("断开设备", "Disconnect") : bi("去设置页连接", "Go to Settings"), false);
        Button resetButton = button(bi("重置训练", "Reset Workout"), false);
        actionRow.addView(startTrainingButton, weightParams());
        actionRow.addView(pauseButton, weightParams());
        actionRow.addView(resetButton, weightParams());
        pageContent.addView(actionRow);

        LinearLayout modeCard = card();
        modeTitleText = text("", 20, COLOR_TEXT, true);
        modeHintText = text("", 13, COLOR_MUTED, false);
        modeCard.addView(modeTitleText);
        modeCard.addView(modeHintText);
        pageContent.addView(modeCard);

        hrText = heroHrText;
        zoneText = heroZoneText;

        LinearLayout chartCard = card();
        chartCard.addView(sectionTitle(bi("心率曲线", "Heart Rate Curve")));
        heartChartView = new HeartChartView(this);
        heartChartView.setConnected(connected);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, dp(144));
        chartParams.setMargins(0, dp(8), 0, 0);
        chartCard.addView(heartChartView, chartParams);
        pageContent.addView(chartCard);

        LinearLayout metricsRow = row();
        primaryMetricText = metricCard(metricsRow, bi("主要数据", "Primary"), "--");
        secondaryMetricText = metricCard(metricsRow, bi("辅助数据", "Secondary"), "--");
        tertiaryMetricText = metricCard(metricsRow, bi("训练状态", "Status"), "--");
        pageContent.addView(metricsRow);

        summaryText = text("", 15, Color.rgb(20, 33, 27), true);
        pageContent.addView(cardWithTitle(bi("训练摘要", "Workout Summary"), summaryText));

        LinearLayout musicCard = card();
        musicCard.addView(sectionTitle(bi("当前音乐", "Now Playing")));
        musicText = text("", 16, COLOR_TEXT, true);
        nowPlayingText = text("", 13, COLOR_MUTED, false);
        playButton = button(bi("播放当前区间歌曲", "Play Zone Song"), true);
        addTopMargin(musicText, 8);
        addTopMargin(nowPlayingText, 8);
        addTopMargin(playButton, 12);
        musicCard.addView(musicText);
        musicCard.addView(nowPlayingText);
        musicCard.addView(playButton);
        pageContent.addView(musicCard);

        LinearLayout jsonCard = roundedPanel(COLOR_OLIVE, dp(22));
        jsonCard.addView(text(bi("设备 JSON 数据", "Device JSON Payload"), 16, Color.WHITE, true));
        jsonText = text("", 13, Color.rgb(234, 239, 223), false);
        addTopMargin(jsonText, 8);
        jsonCard.addView(jsonText);
        pageContent.addView(jsonCard);

        startTrainingButton.setOnClickListener(v -> {
            if (!connected) {
                showSettingsPage();
                buildBottomTabs();
                return;
            }
            if (restActive) {
                Toast.makeText(this, bi("正在休息，请等待倒计时结束", "Rest timer is running, please wait"), Toast.LENGTH_SHORT).show();
                return;
            }
            if (effectiveRepCount() >= targetRepCount) {
                hardwareRepCount = 0;
                pushupCount = 0;
                benchCount = 0;
            }
            trainingActive = true;
            setCompleted = false;
            appendBleDebug(bi("开始训练", "Training started"));
            if (bleClient != null && bleClient.isConnected()) {
                bleClient.sendCommand("{\"action\":\"mode\",\"value\":\"" + selectedMode + "\"}");
                bleClient.sendCommand("{\"action\":\"start\"}");
            }
            render();
        });
        pauseButton.setOnClickListener(v -> {
            if (connected && bleClient != null) {
                bleClient.disconnect();
                connected = false;
                connectedDevice = bi("未连接设备", "Disconnected");
                trainingActive = false;
                stopRestTimer();
                render();
            } else {
                showSettingsPage();
                buildBottomTabs();
            }
        });
        resetButton.setOnClickListener(v -> {
            resetWorkout();
            if (bleClient != null && bleClient.isConnected()) {
                bleClient.sendCommand("{\"action\":\"reset\"}");
            }
            render();
        });
        playButton.setOnClickListener(v -> playCurrentZoneSong());
        render();
    }

    private void showPlaylistPage() {
        activePage = "playlist";
        pageContent.removeAllViews();
        heroSubtitleText.setText(bi("区间歌单管理", "Zone Playlist"));

        LinearLayout intro = card();
        intro.addView(sectionTitle(bi("按心率区间放歌", "Songs by Heart Zone")));
        TextView description = text(bi("给 Zone 1-4 各放一首歌。训练时 App 会根据当前心率区间推荐并播放对应歌曲。", "Add one song to each Zone 1-4. The app will recommend and play songs based on the current heart-rate zone."), 15, COLOR_MUTED, false);
        addTopMargin(description, 8);
        intro.addView(description);
        pageContent.addView(intro);

        playlistRows = new LinearLayout(this);
        playlistRows.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(playlistRows);
        buildPlaylistRows();
        render();
    }

    private void showSettingsPage() {
        activePage = "settings";
        pageContent.removeAllViews();
        heroSubtitleText.setText(bi("设备与训练设置", "Device & Settings"));

        LinearLayout statusCard = card();
        statusCard.addView(sectionTitle(bi("连接准备", "Connection Prep")));
        TextView statusHelp = text(bi("这里直接把蓝牙、权限、系统定位和扫描结果都摊开。先确认状态都正常，再从列表里手动点设备连接。", "Here you can inspect Bluetooth, permissions, location, and scan results before manually choosing a device."), 14, COLOR_MUTED, false);
        addTopMargin(statusHelp, 8);
        statusCard.addView(statusHelp);
        bluetoothStatusText = text("", 14, COLOR_TEXT, true);
        permissionStatusText = text("", 14, COLOR_TEXT, true);
        locationStatusText = text("", 14, COLOR_TEXT, true);
        addTopMargin(bluetoothStatusText, 12);
        addTopMargin(permissionStatusText, 8);
        addTopMargin(locationStatusText, 8);
        statusCard.addView(bluetoothStatusText);
        statusCard.addView(permissionStatusText);
        statusCard.addView(locationStatusText);
        LinearLayout settingsRow = row();
        Button grantButton = button(bi("请求蓝牙权限", "Request Permission"), true);
        Button systemButton = button(bi("打开系统定位", "Open Location"), false);
        settingsRow.addView(grantButton, weightParams());
        settingsRow.addView(systemButton, weightParams());
        addTopMargin(settingsRow, 12);
        statusCard.addView(settingsRow);
        Button appSettingsButton = button(bi("打开 App 权限设置", "Open App Settings"), false);
        addTopMargin(appSettingsButton, 10);
        statusCard.addView(appSettingsButton);
        pageContent.addView(statusCard);

        LinearLayout bleCard = roundedPanel(Color.rgb(250, 251, 240), dp(22));
        bleCard.addView(sectionTitle(bi("蓝牙扫描台", "BLE Scanner")));
        TextView bleHelp = text(bi("像 nRF 一样先把周围 BLE 广播尽量都列出来。哪怕名字不像 FitSense，也可以手动点进去试连。", "List nearby BLE broadcasts like nRF Connect. Even unnamed devices can be selected manually."), 14, COLOR_MUTED, false);
        addTopMargin(bleHelp, 8);
        bleCard.addView(bleHelp);
        TextView bleBadge = text(bi("设备扫描面板", "Scan Panel"), 12, COLOR_ACCENT_DEEP, true);
        bleBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bleBadgeBg = new GradientDrawable();
        bleBadgeBg.setCornerRadius(dp(999));
        bleBadgeBg.setColor(Color.rgb(237, 245, 191));
        bleBadgeBg.setStroke(dp(1), Color.rgb(221, 233, 160));
        bleBadge.setBackground(bleBadgeBg);
        addTopMargin(bleBadge, 10);
        bleCard.addView(bleBadge);
        quickConnectButton = button(bi("快速连接 FitSense-M5StickS3", "Quick Connect FitSense-M5StickS3"), true);
        addTopMargin(quickConnectButton, 12);
        bleCard.addView(quickConnectButton);
        LinearLayout scanRow = row();
        scanButton = button(bleClient != null && bleClient.isScanning() ? bi("扫描中...", "Scanning...") : bi("开始扫描", "Start Scan"), true);
        stopScanButton = button(bi("停止扫描", "Stop Scan"), false);
        scanRow.addView(scanButton, weightParams());
        scanRow.addView(stopScanButton, weightParams());
        addTopMargin(scanRow, 12);
        bleCard.addView(scanRow);
        pageContent.addView(bleCard);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        pageContent.addView(deviceList);
        refreshDeviceList();

        LinearLayout settings = card();
        settings.addView(sectionTitle(bi("训练参数", "Workout Settings")));
        settings.addView(label(bi("步幅估算（米）", "Stride Length (m)")));
        strideInput = input(String.format(Locale.US, "%.2f", stride));
        settings.addView(strideInput);
        settings.addView(label(bi("目标心率上限", "Target HR Upper Limit")));
        targetHrInput = input(String.valueOf(targetHr));
        settings.addView(targetHrInput);
        autoMusicSwitch = new Switch(this);
        autoMusicSwitch.setText(bi("按心率区间自动推荐音乐", "Auto music by heart zone"));
        autoMusicSwitch.setTextSize(15);
        autoMusicSwitch.setChecked(autoMusic);
        settings.addView(autoMusicSwitch);
        languageSwitch = new Switch(this);
        languageSwitch.setText(bi("英文界面", "English UI"));
        languageSwitch.setTextSize(15);
        languageSwitch.setChecked(englishMode);
        addTopMargin(languageSwitch, 10);
        settings.addView(languageSwitch);
        pageContent.addView(settings);

        grantButton.setOnClickListener(v -> requestBlePermissions());
        systemButton.setOnClickListener(v -> openSystemLocationSettings());
        appSettingsButton.setOnClickListener(v -> openAppSettings());
        quickConnectButton.setOnClickListener(v -> connectBestDevice());
        scanButton.setOnClickListener(v -> scanDevices());
        stopScanButton.setOnClickListener(v -> stopScan());
        strideInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                stride = readDouble(strideInput, 0.78);
                render();
            }
        });
        targetHrInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                targetHr = (int) readDouble(targetHrInput, 160);
                render();
            }
        });
        autoMusicSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            autoMusic = isChecked;
            render();
        });
        languageSwitch.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            englishMode = isChecked;
            BleFitnessClient.setEnglishMode(isChecked);
            rebuildCurrentPage();
        });
        render();
    }

    private void buildBottomTabs() {
        bottomTabs.removeAllViews();
        addBottomTab(bi("训练", "Train"), "train");
        addBottomTab(bi("歌单", "Playlist"), "playlist");
        addBottomTab(bi("设置", "Settings"), "settings");
    }

    private void addBottomTab(String label, String page) {
        Button tab = button(label, page.equals(activePage));
        tab.setTextSize(12);
        tab.setMinHeight(dp(54));
        tab.setMinimumHeight(dp(54));
        tab.setPadding(dp(10), dp(10), dp(10), dp(10));
        tab.setText(page.equals(activePage) ? iconForPage(page) + "\n" + label : iconForPage(page) + "\n" + label);
        tab.setSingleLine(false);
        tab.setLineSpacing(0f, 1.05f);
        tab.setOnClickListener(v -> {
            if ("train".equals(page)) showTrainPage();
            if ("playlist".equals(page)) showPlaylistPage();
            if ("settings".equals(page)) showSettingsPage();
            buildBottomTabs();
        });
        bottomTabs.addView(tab, weightParams());
    }

    private String iconForPage(String page) {
        if ("train".equals(page)) return "|||";
        if ("playlist".equals(page)) return "♪";
        return "⚙";
    }

    private void buildModeTabs() {
        modeTabs.removeAllViews();
        for (WorkoutMode mode : modes) {
            Button tab = button(mode.name, mode.id.equals(selectedMode));
            tab.setOnClickListener(v -> {
                selectedMode = mode.id;
                if (bleClient != null && bleClient.isConnected()) {
                    bleClient.sendCommand("{\"action\":\"mode\",\"value\":\"" + mode.id + "\"}");
                }
                buildModeTabs();
                render();
            });
            modeTabs.addView(tab, weightParams());
        }
    }

    private void buildPlaylistRows() {
        playlistRows.removeAllViews();
        for (int i = 0; i < zoneSongs.length; i += 1) {
            final int index = i;
            SongSlot slot = zoneSongs[index];
            LinearLayout card = card();
            if (index == 2) {
                GradientDrawable highlight = new GradientDrawable();
                highlight.setCornerRadius(dp(22));
                highlight.setColor(Color.rgb(244, 250, 212));
                highlight.setStroke(dp(1), Color.rgb(224, 236, 169));
                card.setBackground(highlight);
            }
            card.addView(sectionTitle(slot.zone + " · " + slot.range));
            TextView songName = text(slot.summary(), 14, COLOR_MUTED, false);
            addTopMargin(songName, 8);
            card.addView(songName);

            if (slot.hasTracks()) {
                for (int trackIndex = 0; trackIndex < slot.trackCount(); trackIndex += 1) {
                    final int selectedTrackIndex = trackIndex;
                    LinearLayout trackRow = roundedPanel(Color.rgb(252, 252, 247), dp(16));
                    TextView trackTitle = text(
                            (slot.isCurrentTrack(trackIndex) ? bi("当前播放位 · ", "Current slot · ") : "")
                                    + (trackIndex + 1) + ". " + slot.trackNameAt(trackIndex),
                            13,
                            COLOR_TEXT,
                            slot.isCurrentTrack(trackIndex)
                    );
                    trackRow.addView(trackTitle);
                    LinearLayout trackActions = row();
                    Button setCurrentTrack = button(bi("设为当前", "Set Current"), false);
                    Button previewTrack = button(bi("试听", "Preview"), false);
                    Button moveUpTrack = button(bi("上移", "Up"), false);
                    Button moveDownTrack = button(bi("下移", "Down"), false);
                    Button removeTrack = button(bi("删除", "Delete"), false);
                    trackActions.addView(setCurrentTrack, weightParams());
                    trackActions.addView(previewTrack, weightParams());
                    addTopMargin(trackActions, 8);
                    trackRow.addView(trackActions);

                    LinearLayout trackActions2 = row();
                    trackActions2.addView(moveUpTrack, weightParams());
                    trackActions2.addView(moveDownTrack, weightParams());
                    trackActions2.addView(removeTrack, weightParams());
                    addTopMargin(trackActions2, 8);
                    trackRow.addView(trackActions2);
                    addTopMargin(trackRow, 8);
                    card.addView(trackRow);

                    setCurrentTrack.setOnClickListener(v -> {
                        slot.setCurrentTrack(selectedTrackIndex);
                        Toast.makeText(this, bi("已设为当前歌曲", "Set as current track"), Toast.LENGTH_SHORT).show();
                        rebuildCurrentPage();
                        render();
                    });
                    previewTrack.setOnClickListener(v -> {
                        slot.setCurrentTrack(selectedTrackIndex);
                        playZoneSong(index, false, false);
                    });
                    moveUpTrack.setOnClickListener(v -> moveTrackInZone(index, selectedTrackIndex, -1));
                    moveDownTrack.setOnClickListener(v -> moveTrackInZone(index, selectedTrackIndex, 1));
                    removeTrack.setOnClickListener(v -> removeTrackFromZone(index, selectedTrackIndex));
                }
            }

            LinearLayout row = row();
            Button choose = button(bi("加入歌曲", "Add Song"), true);
            Button play = button(bi("播放本区", "Play Zone"), false);
            row.addView(choose, weightParams());
            row.addView(play, weightParams());
            card.addView(row);
            choose.setOnClickListener(v -> pickAudioForZone(index));
            play.setOnClickListener(v -> playZoneSong(index));
            playlistRows.addView(card);
        }
    }

    private void removeTrackFromZone(int zoneIndex, int trackIndex) {
        SongSlot slot = zoneSongs[zoneIndex];
        boolean removingCurrentPlayingTrack = zoneIndex == playingZoneIndex && slot.isCurrentTrack(trackIndex);
        slot.removeTrackAt(trackIndex);

        if (removingCurrentPlayingTrack) {
            stopAudio();
            pendingAutoZoneIndex = -1;
            if (slot.hasTracks()) {
                playZoneSong(zoneIndex, false, false);
            }
        }

        Toast.makeText(this, bi("已删除歌曲", "Song removed"), Toast.LENGTH_SHORT).show();
        rebuildCurrentPage();
        render();
    }

    private void moveTrackInZone(int zoneIndex, int trackIndex, int direction) {
        SongSlot slot = zoneSongs[zoneIndex];
        if (!slot.moveTrack(trackIndex, direction)) {
            return;
        }
        Toast.makeText(this, direction < 0 ? bi("已上移", "Moved up") : bi("已下移", "Moved down"), Toast.LENGTH_SHORT).show();
        rebuildCurrentPage();
        render();
    }

    private void scanDevices() {
        if (bleClient == null) return;
        if (!bleClient.hasRuntimePermissions()) {
            appendBleDebug(bi("缺少权限，准备请求权限", "Missing permissions, requesting now"));
            requestBlePermissions();
            return;
        }
        if (!bleClient.isBluetoothReady()) {
            appendBleDebug(bi("蓝牙未开启", "Bluetooth is off"));
            Toast.makeText(this, bi("请先打开手机蓝牙", "Please turn on Bluetooth first"), Toast.LENGTH_SHORT).show();
            return;
        }
        scannedDevices.clear();
        appendBleDebug(bi("开始扫描 BLE 广播", "Started BLE scan"));
        refreshDeviceList();
        if (scanButton != null) {
            scanButton.setText(bi("扫描中...", "Scanning..."));
        }
        bleClient.startScan();
    }

    private void stopScan() {
        if (bleClient == null) return;
        bleClient.stopScan();
        appendBleDebug(bi("已手动停止扫描", "Scan stopped manually"));
        render();
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, BLE_PERMISSION_REQUEST);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERMISSION_REQUEST);
        }
    }

    private void connectDevice(BleFitnessClient.FitDevice device) {
        if (bleClient == null) return;
        appendBleDebug(bi("准备连接 ", "Preparing to connect ") + device.name + " · " + device.device.getAddress());
        if (scanButton != null) {
            scanButton.setText(bi("准备连接...", "Preparing..."));
        }
        if (quickConnectButton != null) {
            quickConnectButton.setText(bi("连接中...", "Connecting..."));
            quickConnectButton.setEnabled(false);
        }
        bleClient.connect(device);
    }

    private void connectBestDevice() {
        if (scannedDevices.isEmpty()) {
            appendBleDebug(bi("还没有扫描到设备，先开始扫描", "No devices yet, start scanning first"));
            Toast.makeText(this, bi("请先扫描到设备", "Please scan for devices first"), Toast.LENGTH_SHORT).show();
            return;
        }
        BleFitnessClient.FitDevice best = null;
        for (BleFitnessClient.FitDevice device : scannedDevices) {
            if (device.likelyTarget) {
                if (best == null || device.rssi > best.rssi) {
                    best = device;
                }
            }
        }
        if (best == null) {
            best = scannedDevices.get(0);
        }
        connectDevice(best);
    }

    private void refreshDeviceList() {
        if (deviceList == null) return;
        deviceList.removeAllViews();

        Collections.sort(scannedDevices, Comparator
                .comparing((BleFitnessClient.FitDevice device) -> !device.likelyTarget)
                .thenComparingInt(device -> -device.rssi));

        updateBleStatusViews();
        updateQuickConnectButton();

        LinearLayout listCard = card();
        listCard.addView(sectionTitle(bi("扫描结果", "Scan Results")));
        TextView listHelp = text(bi("下面列出当前这次扫描里发现的设备。优先连接像 FitSense/M5Stick 的条目，但你也可以手动试其他广播。", "Devices discovered in this scan are listed below. Prioritize FitSense/M5Stick targets, but you can also try others manually."), 13, COLOR_MUTED, false);
        addTopMargin(listHelp, 8);
        listCard.addView(listHelp);

        if (scannedDevices.isEmpty()) {
            TextView empty = text(bi("还没有收到任何扫描结果。现在更像是系统没有把 BLE 广播返回给 App，而不是设备列表被过滤掉了。先检查上面的蓝牙、权限和系统定位状态。", "No scan results yet. It looks more like the system is not returning BLE broadcasts than a filter issue. Please check Bluetooth, permissions, and location."), 14, COLOR_MUTED, false);
            addTopMargin(empty, 8);
            listCard.addView(empty);
            deviceList.addView(listCard);
            return;
        }

        int visibleCount = Math.min(scannedDevices.size(), MAX_VISIBLE_SCAN_RESULTS);
        TextView count = text(
                bi("本次共发现 ", "Found ") + scannedDevices.size() + bi(" 个 BLE 广播，当前显示前 ", " BLE broadcasts, showing top ")
                        + visibleCount + bi(" 个", ""),
                13,
                COLOR_ACCENT_DEEP,
                true
        );
        addTopMargin(count, 8);
        listCard.addView(count);

        for (int i = 0; i < visibleCount; i += 1) {
            BleFitnessClient.FitDevice device = scannedDevices.get(i);
            LinearLayout card = roundedPanel(device.likelyTarget ? Color.rgb(247, 251, 222) : COLOR_PANEL, dp(18));
            TextView name = text(device.name, 16, COLOR_TEXT, true);
            TextView meta = text("MAC: " + device.device.getAddress() + "\nRSSI: " + device.rssi + " dBm", 12, COLOR_MUTED, false);
            TextView hint = text(device.likelyTarget ? bi("更像目标设备：名字或服务特征匹配 FitSense / M5Stick / Nordic UART。", "Likely target: name or service matches FitSense / M5Stick / Nordic UART.") : bi("普通 BLE 广播设备。连不上 FitSense 时，也可以手动试一下。", "Generic BLE broadcast. If FitSense fails, you can still try this manually."), 13, device.likelyTarget ? COLOR_ACCENT_DEEP : COLOR_MUTED, false);
            Button connectButton = button(device.likelyTarget ? bi("连接这个设备", "Connect This Device") : bi("手动尝试连接", "Try Connect Manually"), true);
            addTopMargin(meta, 6);
            addTopMargin(hint, 6);
            addTopMargin(connectButton, 10);
            connectButton.setOnClickListener(v -> connectDevice(device));
            card.addView(name);
            card.addView(meta);
            card.addView(hint);
            card.addView(connectButton);
            listCard.addView(card);
        }
        if (scannedDevices.size() > visibleCount) {
            TextView moreHint = text(
                    bi("其余设备已省略，避免列表过长影响操作。优先使用上方的目标设备或前 10 个结果连接。", "The remaining devices are hidden to keep the list manageable. Use the target device above or one of the top 10 results."),
                    12,
                    COLOR_MUTED,
                    false
            );
            addTopMargin(moreHint, 10);
            listCard.addView(moreHint);
        }
        deviceList.addView(listCard);
    }

    private void resetWorkout() {
        stopRestTimer();
        heartRate = 0;
        runSteps = 0;
        pushupCount = 0;
        benchCount = 0;
        cadence = 0;
        signalQuality = 0;
        hardwareRepCount = 0;
        heartHistoryCount = 0;
        trainingActive = false;
        setCompleted = false;
        completedSets = 0;
        lastPayload = "{}";
    }

    private void render() {
        Zone zone = zoneFor(heartRate);
        statusText.setText(connected ? bi("● 已连接 ", "● Connected ") + connectedDevice + " · " + zone.label : bi("● 未连接设备 · 到设置页扫描连接", "● No device connected · Scan in Settings"));
        heroHrText.setText(connected && heartRate > 0 ? String.valueOf(heartRate) : "--");
        heroZoneText.setText(connected && heartRate > 0 ? heartRate + " BPM" : bi("等待设备", "Waiting"));
        updateMiniPlayer();
        if ("train".equals(activePage)) renderTrainPage(zone);
        if ("settings".equals(activePage)) {
            refreshDeviceList();
            if (scanButton != null) {
                scanButton.setText(bleClient != null && bleClient.isScanning() ? bi("扫描中...", "Scanning...") : bi("开始扫描", "Start Scan"));
            }
            if (debugText != null) {
                debugText.setText(joinDebugLines());
            }
        }
    }

    private void renderTrainPage(Zone zone) {
        WorkoutMode mode = currentMode();
        modeTitleText.setText(mode.name + bi(" 模式", " Mode"));
        modeHintText.setText(restActive
                ? bi("本组完成，正在休息。", "Set complete, rest in progress.")
                : mode.subtitle + " · " + (trainingActive ? bi("训练进行中，正在等待实时数据。", "Training is active, waiting for live data.") : bi("连接后点开始训练，接收 M5Stick S3 实时数据。", "After connecting, tap Start Training to receive live data from M5Stick S3.")));
        hrText.setText(connected && heartRate > 0 ? String.valueOf(heartRate) : "--");
        zoneText.setText(connected ? zone.label + " · " + zone.state : bi("等待设备", "Waiting"));
        if (heartChartView != null) {
            heartChartView.setConnected(connected);
            heartChartView.setHistory(heartHistory, heartHistoryCount);
        }
        if (startTrainingButton != null) {
            startTrainingButton.setText(!connected
                    ? bi("先连接设备", "Connect First")
                    : (restActive
                    ? bi("休息中", "Resting")
                    : (trainingActive ? bi("训练进行中", "Training Active") : bi("开始训练", "Start Training"))));
            startTrainingButton.setEnabled(connected && !trainingActive && !restActive);
        }
        renderModeMetrics(mode);
        summaryText.setText(restActive
                ? restSummary()
                : (trainingActive ? summaryForMode(mode) : bi("设备已连接。点“开始训练”后进入当前模式，并开始等待实时心率、动作和步数数据。", "Device connected. Tap Start Training to enter the current mode and wait for live heart-rate, motion, and step data.")));
        SongSlot currentSong = zoneSongs[zone.index];
        musicText.setText(connected ? musicLine(zone, mode, currentSong) : bi("连接设备后，根据实时心率区间推荐 Zone 歌单。", "After connecting, the app recommends music based on the live heart-rate zone."));
        nowPlayingText.setText(bi("当前区间歌曲：", "Current zone song: ") + currentSong.currentTrackName());
        playButton.setText(isPlaying ? bi("停止播放", "Stop Playback") : bi("播放当前区间歌曲", "Play Zone Song"));
        jsonText.setText(lastPayload);
        pauseButton.setText(connected ? bi("断开设备", "Disconnect") : bi("去设置页连接", "Go to Settings"));
    }

    private void renderModeMetrics(WorkoutMode mode) {
        int liveRepCount = effectiveRepCount();
        if ("run".equals(mode.id)) {
            primaryMetricText.setText(runSteps + bi(" 步", " steps"));
            secondaryMetricText.setText(cadence + bi(" 步/分", " spm"));
            tertiaryMetricText.setText(String.format(Locale.CHINA, "%.2f km", runSteps * stride / 1000.0));
        } else if ("pushup".equals(mode.id)) {
            primaryMetricText.setText(liveRepCount + bi(" 次", " reps"));
            secondaryMetricText.setText((completedSets + (trainingActive || restActive ? 1 : 0)) + bi(" 组", " sets"));
            tertiaryMetricText.setText(restActive
                    ? bi("休息 ", "Rest ") + restSecondsRemaining + bi(" 秒", " s")
                    : (targetRepCount > 0 ? bi("目标 ", "Target ") + targetRepCount + bi(" 次", " reps") : (signalQuality > 0 ? bi("信号 ", "Signal ") + signalQuality + "%" : bi("等待动作", "Waiting motion"))));
        } else if ("benchpress".equals(mode.id)) {
            primaryMetricText.setText(liveRepCount + bi(" 次", " reps"));
            secondaryMetricText.setText((completedSets + (trainingActive || restActive ? 1 : 0)) + bi(" 组", " sets"));
            tertiaryMetricText.setText(restActive
                    ? bi("休息 ", "Rest ") + restSecondsRemaining + bi(" 秒", " s")
                    : (targetRepCount > 0 ? bi("目标 ", "Target ") + targetRepCount + bi(" 次", " reps") : (signalQuality > 0 ? bi("信号 ", "Signal ") + signalQuality + "%" : bi("等待动作", "Waiting motion"))));
        } else {
            primaryMetricText.setText(heartRate > 0 ? heartRate + " BPM" : "--");
            secondaryMetricText.setText(restActive ? bi("休息 ", "Rest ") + restSecondsRemaining + bi(" 秒", " s") : bi("信号 ", "Signal ") + signalQuality + "%");
            tertiaryMetricText.setText(liveRepCount > 0 ? bi("计数 ", "Reps ") + liveRepCount + " / " + targetRepCount : zoneFor(heartRate).label);
        }
    }

    private String summaryForMode(WorkoutMode mode) {
        if (!connected) return bi("进入设置页扫描设备，连接后开始接收 M5Stick S3 的实时数据。", "Go to Settings to scan devices. After connection, live data from M5Stick S3 will appear here.");
        if ("run".equals(mode.id)) return String.format(Locale.CHINA, "跑步距离 %.2f km，累计 %d 步，当前步频 %d。\nRun distance %.2f km, total %d steps, cadence %d.", runSteps * stride / 1000.0, runSteps, cadence, runSteps * stride / 1000.0, runSteps, cadence);
        if ("pushup".equals(mode.id)) return String.format(Locale.CHINA, "设备实时计数 %d 次，目标 %d 次。\nLive hardware reps %d, target %d.", effectiveRepCount(), targetRepCount, effectiveRepCount(), targetRepCount);
        if ("benchpress".equals(mode.id)) return String.format(Locale.CHINA, "设备实时计数 %d 次，目标 %d 次。\nLive hardware reps %d, target %d.", effectiveRepCount(), targetRepCount, effectiveRepCount(), targetRepCount);
        return String.format(Locale.CHINA, "当前心率 %d BPM，传感器信号质量 %d%%。\nCurrent heart rate %d BPM, signal quality %d%%.", heartRate, signalQuality, heartRate, signalQuality);
    }

    private String restSummary() {
        return String.format(
                Locale.CHINA,
                "本组完成。休息还剩 %d 秒，已完成 %d 组。\nNice set. %d-second rest remaining. %d set(s) completed.",
                restSecondsRemaining,
                completedSets,
                restSecondsRemaining,
                completedSets
        );
    }

    private String musicLine(Zone zone, WorkoutMode mode, SongSlot song) {
        String source = song.hasTracks() ? song.currentTrackName() : bi("还没有给这个区间放歌", "No song added to this zone yet");
        String target = heartRate >= targetHr ? bi("已接近目标上限", "Close to target upper limit") : bi("训练节奏正常", "Training pace is stable");
        return mode.name + " · " + zone.label + " " + zone.range + "\n" + source + "\n" + target;
    }

    private void pickAudioForZone(int zoneIndex) {
        pendingZoneIndex = zoneIndex;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_AUDIO_REQUEST);
    }

    private void playCurrentZoneSong() {
        Zone zone = zoneFor(heartRate);
        playZoneSong(zone.index, false, true);
    }

    private void playZoneSong(int zoneIndex) {
        playZoneSong(zoneIndex, false, true);
    }

    private void playZoneSong(int zoneIndex, boolean advanceTrack, boolean userInitiated) {
        SongSlot slot = zoneSongs[zoneIndex];
        if (!slot.hasTracks()) {
            Toast.makeText(this, bi("请先给 ", "Please add a song to ") + slot.zone, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isPlaying && playingZoneIndex == zoneIndex && !advanceTrack && userInitiated) {
            stopAudio();
            render();
            return;
        }
        try {
            if (advanceTrack) {
                slot.moveToNextTrack();
            }
            SongTrack track = slot.currentTrack();
            if (track == null || track.uri == null) {
                Toast.makeText(this, bi("当前区间还没有可播放歌曲", "No playable song in this zone"), Toast.LENGTH_SHORT).show();
                return;
            }
            stopAudio();
            mediaPlayer = MediaPlayer.create(this, track.uri);
            if (mediaPlayer == null) {
                Toast.makeText(this, bi("这首歌暂时无法播放", "This song cannot be played right now"), Toast.LENGTH_SHORT).show();
                return;
            }
            mediaPlayer.setOnCompletionListener(mp -> {
                stopAudio();
                int nextZone = pendingAutoZoneIndex;
                pendingAutoZoneIndex = -1;
                if (nextZone >= 0 && zoneSongs[nextZone].hasTracks()) {
                    playZoneSong(nextZone, false, false);
                    return;
                }
                if (zoneSongs[zoneIndex].hasTracks()) {
                    playZoneSong(zoneIndex, true, false);
                    return;
                }
                render();
            });
            mediaPlayer.start();
            isPlaying = true;
            playingZoneIndex = zoneIndex;
            Toast.makeText(this, bi("正在播放：", "Now playing: ") + track.name, Toast.LENGTH_SHORT).show();
            render();
        } catch (Exception exception) {
            stopAudio();
            Toast.makeText(this, bi("播放失败，请换一首本地音频", "Playback failed, please choose another local audio file"), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPlaying = false;
        playingZoneIndex = -1;
    }

    private void togglePlayer() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                isPlaying = false;
            } else {
                mediaPlayer.start();
                isPlaying = true;
            }
            render();
            return;
        }
        playCurrentZoneSong();
    }

    private void playAdjacentSong(int direction) {
        int start = playingZoneIndex >= 0 ? playingZoneIndex : zoneFor(heartRate).index;
        for (int step = 1; step <= zoneSongs.length; step += 1) {
            int next = (start + direction * step + zoneSongs.length * 2) % zoneSongs.length;
            if (zoneSongs[next].hasTracks()) {
                pendingAutoZoneIndex = -1;
                playZoneSong(next, false, false);
                return;
            }
        }
        Toast.makeText(this, bi("还没有可播放的区间歌曲", "There is no playable zone song yet"), Toast.LENGTH_SHORT).show();
    }

    private void updateMiniPlayer() {
        if (miniSongText == null || playerToggleButton == null) return;
        String song = playingZoneIndex >= 0 ? zoneSongs[playingZoneIndex].currentTrackName() : zoneSongs[zoneFor(heartRate).index].currentTrackName();
        miniSongText.setText((isPlaying ? bi("正在播放：", "Playing: ") : bi("当前歌曲：", "Current song: ")) + song);
        playerToggleButton.setText(isPlaying ? "Ⅱ" : "▶");
    }

    private void addHeartSample(int sample) {
        if (sample <= 0) return;
        if (heartHistoryCount == 0) {
            heartHistory[0] = sample;
            heartHistory[1] = sample;
            heartHistoryCount = 2;
            return;
        }
        if (heartHistoryCount < heartHistory.length) {
            heartHistory[heartHistoryCount] = sample;
            heartHistoryCount += 1;
        } else {
            System.arraycopy(heartHistory, 1, heartHistory, 0, heartHistory.length - 1);
            heartHistory[heartHistory.length - 1] = sample;
        }
    }

    private void rebuildCurrentPage() {
        if ("train".equals(activePage)) showTrainPage();
        if ("playlist".equals(activePage)) showPlaylistPage();
        if ("settings".equals(activePage)) showSettingsPage();
        buildBottomTabs();
    }

    private String displayNameFor(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
            return uri.getLastPathSegment();
        }
        return uri.getLastPathSegment() == null ? bi("已选择歌曲", "Selected song") : uri.getLastPathSegment();
    }

    private WorkoutMode currentMode() {
        for (WorkoutMode mode : modes) {
            if (mode.id.equals(selectedMode)) return mode;
        }
        return modes[0];
    }

    private Zone zoneFor(int hr) {
        if (hr < 100) return new Zone(0, "Zone 1", "<100", bi("放松", "Relax"));
        if (hr <= 130) return new Zone(1, "Zone 2", "100-130", bi("热身", "Warm-up"));
        if (hr <= 160) return new Zone(2, "Zone 3", "131-160", bi("训练", "Train"));
        return new Zone(3, "Zone 4", ">160", bi("高燃", "Peak"));
    }

    private void applyLivePayload(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            lastPayload = json.toString(2);
            heartRate = readIntDeep(json, heartRate, "hr", "HR", "heartRate", "heart_rate", "current_bpm");
            runSteps = readIntDeep(json, runSteps, "run", "steps", "step_count");
            pushupCount = readIntDeep(json, pushupCount, "pushup", "pushups", "pushup_count");
            benchCount = readIntDeep(json, benchCount, "bench", "benchpress", "benchpress_count");
            hardwareRepCount = readIntDeep(json, hardwareRepCount, "reps", "rep", "count", "rep_count", "current_reps", "squat", "squat_count", "counter", "motion_count", "reps_done", "repDone");
            targetRepCount = readIntDeep(json, targetRepCount, "target", "target_reps", "goal", "total_reps");
            cadence = readIntDeep(json, cadence, "cadence");
            signalQuality = readIntDeep(json, signalQuality, "signal", "signalQuality", "signal_quality");
            String mode = json.has("mode")
                    ? String.valueOf(json.opt("mode"))
                    : selectedMode;
            selectedMode = normalizeMode(mode);
        } catch (Exception exception) {
            lastPayload = payload;
        }
        heartRate = parseHeartRateFromText(payload, heartRate);
        hardwareRepCount = parsePatternInt(REPS_PATTERN, payload, hardwareRepCount);
        targetRepCount = parsePatternInt(TARGET_PATTERN, payload, targetRepCount);
        applyLegacyPayload(payload);
        updateAutoPlaybackForZone();
        addHeartSample(heartRate);
        maybeCompleteSet();
        render();
    }

    private void updateAutoPlaybackForZone() {
        if (!autoMusic || !connected || heartRate <= 0) return;
        int currentZoneIndex = zoneFor(heartRate).index;

        if (mediaPlayer == null || playingZoneIndex < 0 || !isPlaying) {
            if (zoneSongs[currentZoneIndex].hasTracks()) {
                pendingAutoZoneIndex = -1;
                playZoneSong(currentZoneIndex, false, false);
            }
            return;
        }

        if (currentZoneIndex != playingZoneIndex) {
            pendingAutoZoneIndex = currentZoneIndex;
        } else {
            pendingAutoZoneIndex = -1;
        }
    }

    private void applyLegacyPayload(String payload) {
        int pushup = parseSingleGroupInt(PUSHUP_PATTERN, payload, Integer.MIN_VALUE);
        int press = parseSingleGroupInt(PRESS_PATTERN, payload, Integer.MIN_VALUE);
        int step = parseSingleGroupInt(STEP_PATTERN, payload, Integer.MIN_VALUE);
        int cad = parseSingleGroupInt(CAD_PATTERN, payload, Integer.MIN_VALUE);

        if (pushup != Integer.MIN_VALUE) {
            pushupCount = pushup;
            hardwareRepCount = pushup;
            selectedMode = "pushup";
        }
        if (press != Integer.MIN_VALUE) {
            benchCount = press;
            hardwareRepCount = press;
            selectedMode = "benchpress";
        }
        if (step != Integer.MIN_VALUE) {
            runSteps = step;
            selectedMode = "run";
        }
        if (cad != Integer.MIN_VALUE) {
            cadence = cad;
            if (step == Integer.MIN_VALUE && pushup == Integer.MIN_VALUE && press == Integer.MIN_VALUE) {
                selectedMode = "run";
            }
        }
    }

    private int readInt(JSONObject json, int fallback, String... keys) {
        for (String key : keys) {
            if (!json.has(key)) continue;
            Object value = json.opt(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                try {
                    return Integer.parseInt(((String) value).trim());
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return fallback;
    }

    private int readIntDeep(JSONObject json, int fallback, String... keys) {
        int direct = readInt(json, Integer.MIN_VALUE, keys);
        if (direct != Integer.MIN_VALUE) {
            return direct;
        }
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = json.opt(key);
            if (value instanceof JSONObject) {
                int nested = readIntDeep((JSONObject) value, Integer.MIN_VALUE, keys);
                if (nested != Integer.MIN_VALUE) return nested;
            } else if (value instanceof JSONArray) {
                int nested = readIntFromArray((JSONArray) value, Integer.MIN_VALUE, keys);
                if (nested != Integer.MIN_VALUE) return nested;
            } else if (value instanceof String) {
                int parsed = parsePatternInt(REPS_PATTERN, (String) value, Integer.MIN_VALUE);
                if (parsed != Integer.MIN_VALUE) return parsed;
                parsed = parsePatternInt(TARGET_PATTERN, (String) value, Integer.MIN_VALUE);
                if (parsed != Integer.MIN_VALUE) return parsed;
                parsed = parseHeartRateFromText((String) value, Integer.MIN_VALUE);
                if (parsed != Integer.MIN_VALUE) return parsed;
            }
        }
        return fallback;
    }

    private int readIntFromArray(JSONArray array, int fallback, String... keys) {
        for (int i = 0; i < array.length(); i += 1) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                int nested = readIntDeep((JSONObject) value, Integer.MIN_VALUE, keys);
                if (nested != Integer.MIN_VALUE) return nested;
            } else if (value instanceof JSONArray) {
                int nested = readIntFromArray((JSONArray) value, Integer.MIN_VALUE, keys);
                if (nested != Integer.MIN_VALUE) return nested;
            } else if (value instanceof Number) {
                int n = ((Number) value).intValue();
                if (n >= 0) return n;
            } else if (value instanceof String) {
                int parsed = parsePatternInt(REPS_PATTERN, (String) value, Integer.MIN_VALUE);
                if (parsed != Integer.MIN_VALUE) return parsed;
            }
        }
        return fallback;
    }

    private int parseHeartRateFromText(String payload, int fallback) {
        Matcher matcher = HEART_RATE_PATTERN.matcher(payload);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int parsePatternInt(Pattern pattern, String payload, int fallback) {
        Matcher matcher = pattern.matcher(payload);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int parseSingleGroupInt(Pattern pattern, String payload, int fallback) {
        Matcher matcher = pattern.matcher(payload);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String normalizeMode(String mode) {
        if (mode == null) return selectedMode;
        String normalized = mode.trim().toLowerCase(Locale.US);
        if ("0".equals(normalized)) return "pushup";
        if ("1".equals(normalized)) return "benchpress";
        if ("2".equals(normalized)) return "run";
        if ("3".equals(normalized)) return fallbackWorkoutMode();
        if ("squat".equals(normalized) || "squats".equals(normalized)) return "pushup";
        if ("running".equals(normalized) || "run".equals(normalized)) return "run";
        if ("pushup".equals(normalized) || "pushups".equals(normalized)) return "pushup";
        if ("bench".equals(normalized) || "benchpress".equals(normalized)) return "benchpress";
        if ("heartrate".equals(normalized) || "heart".equals(normalized) || "hr".equals(normalized)) return fallbackWorkoutMode();
        return selectedMode;
    }

    private String fallbackWorkoutMode() {
        return "benchpress".equals(selectedMode) || "pushup".equals(selectedMode) || "run".equals(selectedMode)
                ? selectedMode
                : "run";
    }

    private int effectiveRepCount() {
        if (hardwareRepCount > 0) return hardwareRepCount;
        if ("benchpress".equals(selectedMode)) return benchCount;
        return pushupCount;
    }

    private void maybeCompleteSet() {
        if (!trainingActive || restActive || setCompleted) return;
        if ("run".equals(selectedMode)) return;
        if (targetRepCount <= 0) return;
        if (effectiveRepCount() < targetRepCount) return;

        setCompleted = true;
        trainingActive = false;
        completedSets += 1;
        appendBleDebug(bi("本组完成，开始 45 秒休息", "Set complete, starting 45-second rest"));
        startRestTimer(45);
    }

    private void startRestTimer(int seconds) {
        stopRestTimer();
        restActive = true;
        restSecondsRemaining = seconds;
        uiHandler.postDelayed(restTicker, 1000);
    }

    private void stopRestTimer() {
        restActive = false;
        restSecondsRemaining = 0;
        uiHandler.removeCallbacks(restTicker);
    }

    private TextView metricCard(LinearLayout parent, String title, String value) {
        boolean highlight = title.contains("Status") || title.contains("训练状态");
        LinearLayout card = roundedPanel(highlight ? Color.rgb(244, 250, 212) : COLOR_SURFACE, dp(20));
        card.setMinimumHeight(dp(112));
        TextView top = text(title, 12, COLOR_MUTED, false);
        card.addView(top);
        TextView metric = text(value, 28, highlight ? COLOR_ACCENT_DEEP : COLOR_TEXT, true);
        addTopMargin(metric, 6);
        card.addView(metric);
        TextView hint = text(highlight ? bi("实时准备度", "Readiness") : bi("实时更新", "Live update"), 12, COLOR_MUTED, false);
        addTopMargin(hint, 4);
        card.addView(hint);
        parent.addView(card, weightParams());
        return metric;
    }

    private LinearLayout cardWithTitle(String title, TextView body) {
        LinearLayout card = card();
        card.addView(sectionTitle(title));
        addTopMargin(body, 8);
        card.addView(body);
        return card;
    }

    private LinearLayout card() {
        return roundedPanel(COLOR_PANEL, dp(22));
    }

    private LinearLayout compactPanel(int color, int radius) {
        LinearLayout layout = roundedPanel(color, radius);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, dp(6));
        layout.setLayoutParams(params);
        return layout;
    }

    private LinearLayout roundedPanel(int color, int radius) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        background.setStroke(dp(1), COLOR_LINE);
        layout.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(4));
        layout.setLayoutParams(params);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, dp(2));
        return row;
    }

    private TextView sectionTitle(String value) {
        return text(value, 18, COLOR_TEXT, true);
    }

    private TextView label(String value) {
        TextView label = text(value, 12, COLOR_MUTED, false);
        addTopMargin(label, 6);
        return label;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.0f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setTextColor(primary ? COLOR_ACCENT_DEEP : COLOR_TEXT);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(primary ? COLOR_ACCENT : COLOR_PANEL);
        background.setStroke(dp(1), primary ? Color.rgb(196, 228, 98) : COLOR_LINE);
        button.setBackground(background);
        return button;
    }

    private TextView playerButton(String value, boolean primary) {
        TextView button = text(value, primary ? 24 : 28, primary ? COLOR_ACCENT_DEEP : Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(primary ? COLOR_ACCENT : Color.rgb(88, 101, 57));
        background.setStroke(dp(1), primary ? Color.rgb(196, 228, 98) : Color.rgb(104, 118, 72));
        button.setBackground(background);
        return button;
    }

    private EditText input(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextSize(16);
        input.setTextColor(COLOR_TEXT);
        input.setPadding(dp(4), dp(10), dp(4), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setStroke(dp(1), COLOR_LINE);
        background.setCornerRadius(dp(12));
        input.setBackground(background);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams centerButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
        params.setMargins(dp(10), dp(3), dp(10), dp(1));
        return params;
    }

    private void addTopMargin(View view, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(marginDp), 0, 0);
        view.setLayoutParams(params);
    }

    private double readDouble(EditText input, double fallback) {
        try {
            return Double.parseDouble(input.getText().toString().trim());
        } catch (NumberFormatException exception) {
            input.setText(String.valueOf(fallback));
            return fallback;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String bi(String zh, String en) {
        return englishMode ? en : zh;
    }

    @Override
    public void onScanResult(BleFitnessClient.FitDevice device) {
        runOnUiThread(() -> {
            boolean replaced = false;
            for (int i = 0; i < scannedDevices.size(); i += 1) {
                BleFitnessClient.FitDevice item = scannedDevices.get(i);
                if (item.device.getAddress().equals(device.device.getAddress())) {
                    scannedDevices.set(i, device);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                scannedDevices.add(device);
            }
            if (device.likelyTarget) {
                appendBleDebug(bi("发现目标设备 ", "Target device found: ") + device.name + " · " + device.rssi + " dBm");
            }
            scheduleDeviceListRefresh();
        });
    }

    @Override
    public void onConnectionStateChanged(boolean isConnected, String name, String message) {
        runOnUiThread(() -> {
            connected = isConnected;
            connectedDevice = name;
            appendBleDebug(message);
            if (isConnected) {
                stopRestTimer();
                trainingActive = false;
                setCompleted = false;
                if (heartRate <= 0) {
                    lastPayload = "{\n  \"status\": \"connected\",\n  \"device\": \"" + name + "\"\n}";
                }
                showTrainPage();
                buildBottomTabs();
            }
            if (!isConnected) {
                stopRestTimer();
                if (bleClient != null) {
                    scannedDevices.clear();
                    if (scanButton != null) {
                        scanButton.setText(bi("扫描设备", "Scan Device"));
                    }
                }
                trainingActive = false;
                setCompleted = false;
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            render();
        });
    }

    @Override
    public void onPayloadReceived(String payload) {
        runOnUiThread(() -> applyLivePayload(payload));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            appendBleDebug(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            render();
        });
    }

    @Override
    public void onDebugLog(String message) {
        runOnUiThread(() -> {
            appendBleDebug(message);
            if ("settings".equals(activePage) && debugText != null) {
                debugText.setText(joinDebugLines());
            }
            updateBleStatusViews();
        });
    }

    private void updateBleStatusViews() {
        if (bluetoothStatusText != null) {
            bluetoothStatusText.setText((bleClient != null && bleClient.isBluetoothReady() ? bi("蓝牙：已开启", "Bluetooth: On") : bi("蓝牙：未开启", "Bluetooth: Off")) +
                    (bleClient != null && bleClient.isScanning() ? bi(" · 正在扫描", " · Scanning") : bi(" · 当前未扫描", " · Idle")));
        }
        if (permissionStatusText != null) {
            permissionStatusText.setText((hasNearbyPermission() ? bi("权限：附近设备已允许", "Permission: Nearby allowed") : bi("权限：附近设备未允许", "Permission: Nearby denied")) +
                    " · " + (hasLocationPermission() ? bi("位置信息已允许", "Location allowed") : bi("位置信息未允许", "Location denied")));
        }
        if (locationStatusText != null) {
            locationStatusText.setText(isSystemLocationEnabled() ? bi("系统定位：已开启", "System location: On") : bi("系统定位：未开启（很多安卓机不开这里就扫不到 BLE）", "System location: Off (many Android phones cannot scan BLE without this)"));
        }
        if (resultsSummaryText != null) {
            resultsSummaryText.setText(bi("最近状态：", "Latest: ") + bleDebugStatus + "\n" + bi("当前设备数：", "Devices: ") + scannedDevices.size());
        }
    }

    private boolean hasNearbyPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemLocationEnabled() {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return false;
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openSystemLocationSettings() {
        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void appendBleDebug(String message) {
        bleDebugStatus = message;
        bleDebugLines.add(0, message);
        while (bleDebugLines.size() > 8) {
            bleDebugLines.remove(bleDebugLines.size() - 1);
        }
    }

    private BleFitnessClient.FitDevice bestLikelyTarget() {
        BleFitnessClient.FitDevice best = null;
        for (BleFitnessClient.FitDevice device : scannedDevices) {
            if (!device.likelyTarget) continue;
            if (best == null || device.rssi > best.rssi) {
                best = device;
            }
        }
        return best;
    }

    private void scheduleDeviceListRefresh() {
        if (deviceListRefreshPending) return;
        deviceListRefreshPending = true;
        uiHandler.postDelayed(() -> {
            deviceListRefreshPending = false;
            if ("settings".equals(activePage)) {
                refreshDeviceList();
                if (debugText != null) {
                    debugText.setText(joinDebugLines());
                }
            }
        }, 250);
    }

    private void updateQuickConnectButton() {
        if (quickConnectButton == null) return;
        BleFitnessClient.FitDevice best = bestLikelyTarget();
        if (bleClient != null && bleClient.isConnected()) {
            quickConnectButton.setText(bi("已连接 ", "Connected ") + connectedDevice);
            quickConnectButton.setEnabled(false);
            return;
        }
        if (best == null) {
            quickConnectButton.setText(bi("快速连接 FitSense-M5StickS3", "Quick Connect FitSense-M5StickS3"));
            quickConnectButton.setEnabled(false);
            return;
        }
        quickConnectButton.setText(bi("快速连接 ", "Quick Connect ") + best.name + " · " + best.rssi + " dBm");
        quickConnectButton.setEnabled(true);
    }

    private String joinDebugLines() {
        if (bleDebugLines.isEmpty()) {
            return bi("等待扫描", "Waiting to scan");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bleDebugLines.size(); i += 1) {
            if (i > 0) builder.append("\n");
            builder.append(i + 1).append(". ").append(bleDebugLines.get(i));
        }
        return builder.toString();
    }

    private static class WorkoutMode {
        final String id;
        final String name;
        final String subtitle;

        WorkoutMode(String id, String name, String subtitle) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
        }
    }

    private static class SongSlot {
        final String zone;
        final String range;
        final List<SongTrack> tracks = new ArrayList<>();
        int currentTrackIndex = 0;

        SongSlot(String zone, String range) {
            this.zone = zone;
            this.range = range;
        }

        void addTrack(Uri uri, String name) {
            tracks.add(new SongTrack(uri, name));
            if (tracks.size() == 1) {
                currentTrackIndex = 0;
            }
        }

        boolean hasTracks() {
            return !tracks.isEmpty();
        }

        int trackCount() {
            return tracks.size();
        }

        boolean isCurrentTrack(int trackIndex) {
            return hasTracks() && currentTrackIndex == trackIndex;
        }

        void setCurrentTrack(int trackIndex) {
            if (tracks.isEmpty()) return;
            if (trackIndex < 0) {
                currentTrackIndex = 0;
            } else if (trackIndex >= tracks.size()) {
                currentTrackIndex = tracks.size() - 1;
            } else {
                currentTrackIndex = trackIndex;
            }
        }

        SongTrack currentTrack() {
            if (tracks.isEmpty()) return null;
            if (currentTrackIndex < 0 || currentTrackIndex >= tracks.size()) {
                currentTrackIndex = 0;
            }
            return tracks.get(currentTrackIndex);
        }

        void moveToNextTrack() {
            if (tracks.isEmpty()) return;
            currentTrackIndex = (currentTrackIndex + 1) % tracks.size();
        }

        String currentTrackName() {
            SongTrack track = currentTrack();
            return track == null || track.name == null || track.name.isEmpty() ? bi("未放入歌曲", "No song added") : track.name;
        }

        String trackNameAt(int trackIndex) {
            if (trackIndex < 0 || trackIndex >= tracks.size()) {
                return bi("未知歌曲", "Unknown track");
            }
            String trackName = tracks.get(trackIndex).name;
            return trackName == null || trackName.isEmpty() ? bi("未命名歌曲", "Untitled track") : trackName;
        }

        void removeTrackAt(int trackIndex) {
            if (trackIndex < 0 || trackIndex >= tracks.size()) return;
            tracks.remove(trackIndex);
            if (tracks.isEmpty()) {
                currentTrackIndex = 0;
                return;
            }
            if (currentTrackIndex > trackIndex) {
                currentTrackIndex -= 1;
            } else if (currentTrackIndex >= tracks.size()) {
                currentTrackIndex = tracks.size() - 1;
            }
        }

        boolean moveTrack(int trackIndex, int direction) {
            int newIndex = trackIndex + direction;
            if (trackIndex < 0 || trackIndex >= tracks.size()) return false;
            if (newIndex < 0 || newIndex >= tracks.size()) return false;

            SongTrack track = tracks.remove(trackIndex);
            tracks.add(newIndex, track);

            if (currentTrackIndex == trackIndex) {
                currentTrackIndex = newIndex;
            } else if (direction < 0 && currentTrackIndex >= newIndex && currentTrackIndex < trackIndex) {
                currentTrackIndex += 1;
            } else if (direction > 0 && currentTrackIndex <= newIndex && currentTrackIndex > trackIndex) {
                currentTrackIndex -= 1;
            }
            return true;
        }

        String summary() {
            if (tracks.isEmpty()) {
                return bi("还没有歌曲", "No songs yet");
            }
            return currentTrackName() + "\n" + bi("共 ", "Total ") + tracks.size() + bi(" 首", " tracks");
        }
    }

    private static class SongTrack {
        final Uri uri;
        final String name;

        SongTrack(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static class Zone {
        final int index;
        final String label;
        final String range;
        final String state;

        Zone(int index, String label, String range, String state) {
            this.index = index;
            this.label = label;
            this.range = range;
            this.state = state;
        }
    }

    private static class HeartChartView extends View {
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path linePath = new Path();
        private final Path fillPath = new Path();
        private int[] values = new int[0];
        private int count = 0;
        private boolean connected = false;

        HeartChartView(Activity activity) {
            super(activity);
            gridPaint.setColor(COLOR_LINE);
            gridPaint.setStrokeWidth(1f);
            linePaint.setColor(COLOR_CHART);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(4f);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            fillPaint.setColor(Color.argb(46, 210, 238, 96));
            fillPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(COLOR_MUTED);
            textPaint.setTextSize(26f);
        }

        void setHistory(int[] history, int historyCount) {
            count = historyCount;
            values = new int[count];
            System.arraycopy(history, 0, values, 0, count);
            invalidate();
        }

        void setConnected(boolean connected) {
            this.connected = connected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            int pad = 24;
            for (int i = 0; i < 4; i += 1) {
                float y = pad + i * ((height - pad * 2) / 3f);
                canvas.drawLine(pad, y, width - pad, y, gridPaint);
            }
            if (count < 2) {
                float baseline = height / 2f;
                canvas.drawLine(pad, baseline, width - pad, baseline, gridPaint);
                canvas.drawText(connected ? bi("已连接，等待心率数据", "Connected, waiting for heart-rate data") : bi("连接设备后显示心率曲线", "Heart-rate curve appears after connection"), pad, height / 2f, textPaint);
                return;
            }
            linePath.reset();
            fillPath.reset();
            for (int i = 0; i < count; i += 1) {
                float x = pad + i * ((width - pad * 2) / Math.max(1f, count - 1f));
                float y = height - pad - ((values[i] - 70) / 120f) * (height - pad * 2);
                y = Math.max(pad, Math.min(height - pad, y));
                if (i == 0) {
                    linePath.moveTo(x, y);
                    fillPath.moveTo(x, height - pad);
                    fillPath.lineTo(x, y);
                } else {
                    linePath.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
                if (i == count - 1) {
                    fillPath.lineTo(x, height - pad);
                    fillPath.close();
                }
            }
            canvas.drawPath(fillPath, fillPaint);
            canvas.drawPath(linePath, linePaint);
        }
    }
}
