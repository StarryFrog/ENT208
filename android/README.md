# FitSense Android

This is a pure Android Studio project containing the FitSense mobile demo application for Android devices.

---

## Run

1. Open the project in Android Studio.

2. Open the following folder:

/Users/yuantu.zhao/Documents/New project/FitSenseAndroid

3. Wait for Gradle Sync to finish.

4. Connect an Android phone and enable USB Debugging.

5. Click Run.

---

The current version uses simulated BLE data.

In the “Settings” page, tap “Scan Devices” and select `FitSense-M5StickS3`. The app will then update heart rate, step count, squat count, bench press count, JSON data, and music recommendations in real time.

---

# New Demo Features

- The top menu supports switching between Running, Squat, Bench Press, and HIIT modes.

- Different modes simulate different heart rates, step counts, exercise counts, and workout summaries.

- The bottom navigation includes Training, Playlist, and Settings pages.

- The bottom navigation bar remains fixed while scrolling.

- A fixed mini music player is placed above the navigation bar, supporting Previous, Play/Pause, and Next controls.

- The Playlist page allows users to assign local audio files to Zone 1–4 and preview them.

- The Training page automatically displays and plays music based on the current heart-rate zone.

- The Training page includes a real-time heart-rate graph.

- The Settings page includes Bluetooth scanning, device connection, stride length, target heart rate, and automatic recommendation settings.

---

# Project Notes

- This project is the ENT208 Demo Day showcase version.

- BLE data is currently simulated for UI and interaction demonstration purposes.

- Please refer to the Python hardware code in this repository for the M5StickS3 firmware implementation.

---

# Authors

- YuanTu Zhao
- ENT208 Project Team

==================================================

# FitSense Android

这是一个纯 Android Studio 项目，仅包含安卓手机可运行的 FitSense Demo。

---

## 运行方式

1. 在 Android Studio 中选择 Open 打开项目。

2. 打开以下文件夹：

/Users/yuantu.zhao/Documents/New project/FitSenseAndroid

3. 等待 Gradle Sync 完成。

4. 连接安卓手机并开启 USB 调试。

5. 点击 Run 运行项目。

---

当前版本使用模拟 BLE 数据。

在“设置”页面点击“扫描设备”，选择 `FitSense-M5StickS3` 后，App 会实时刷新心率、步数、深蹲、卧推、JSON 数据和音乐推荐。

---

# 新版演示功能

- 顶部支持切换 Running、Squat、Bench Press 和 HIIT 四种健身模式。

- 不同模式会模拟不同的心率、步数、动作次数和训练摘要。

- 底部导航包含 Training、Playlist 和 Settings 三个页面。

- 底部导航栏在滚动时会保持固定，不会消失。

- 导航栏上方固定有一个迷你播放器，支持上一首、播放/暂停和下一首控制。

- Playlist 页面支持为 Zone 1–4 添加本地音乐并进行试听。

- Training 页面会根据当前心率区间自动显示并播放对应音乐。

- Training 页面包含实时心率曲线图。

- Settings 页面集中管理蓝牙扫描、设备连接、步幅、目标心率以及自动推荐设置。

---

# 项目说明

- 本项目为 ENT208 Demo Day 展示版本。

- 当前 BLE 数据为模拟数据，用于 UI 与交互演示。

- M5StickS3 的硬件代码请参考仓库中的 Python 文件。

---

# 作者

- 赵远图（YuanTu Zhao）
- ENT208 Project Team
