# FitSense

FitSense is an intelligent fitness assistant developed using M5StickS3 and Android.

The system provides real-time exercise monitoring, heart rate detection, Bluetooth communication, and personalized music recommendation based on heart rate zones.

---

## Features

### Exercise Detection
- Push-up counting
- Bench press counting
- Running step counting
- Running cadence (steps per minute)
- Running distance estimation

### Heart Rate Monitoring
- Real-time BPM detection using MAX30102
- Stable signal processing and filtering

### Bluetooth Connectivity
- BLE communication between M5StickS3 and Android
- Quick connection function
- Real-time data transmission

### Smart Music Recommendation
- Automatically switches playlists based on heart rate zones
- Personalized workout music experience

### Buddy Reminder
- Virtual pet appears after completing a preset number of repetitions
- Suggests rest between sets

---

## Hardware
- M5StickS3
- MAX30102 Heart Rate Sensor
- Built-in BMI270/BMI2 IMU

---

## Mobile Application
The Android application provides:
- Bluetooth connection management
- Training dashboard
- Heart rate visualization
- Playlist configuration
- Real-time workout monitoring

---

## Repository Structure

- `FitSenseAndroid_github.zip` — Final Android Studio project
- `M5_hardware_code.py` — Final M5StickS3 firmware
- `FitSense_PRD.docx` — Product Requirements Document
- `archive/` — Historical files and development artifacts

---

## Final Deliverables

The main deliverables of this project are:

1. Android Application (`FitSenseAndroid_github.zip`)
2. M5StickS3 Firmware (`M5_hardware_code.py`)
3. Product Requirements Document (`FitSense_PRD.docx`)

---

## Archive

The `archive` folder contains historical development files, prototypes, and intermediate versions.

These files are preserved to document the development process and demonstrate project evolution.

---

## Release

The latest release can be found in the GitHub Releases section.

Current version: **v1.0.0**

---

## Authors

- ENT208 Project Team

---

## License

This project is developed for academic demonstration purposes only.
# FitSense 智能健身助手

FitSense 是一个基于 M5StickS3 和 Android 平台开发的智能健身辅助系统。

本系统能够实现实时运动监测、心率检测、蓝牙通信，以及基于心率区间的个性化音乐推荐，为用户提供更加智能、有趣和高效的健身体验。

---

## 项目功能

### 🏋️ 运动识别与计数
- 俯卧撑（Push-up）自动计数
- 卧推（Bench Press）自动计数
- 跑步步数统计
- 实时步频（Cadence，步/分钟）
- 跑步距离估算

### ❤️ 心率监测
- 基于 MAX30102 的实时心率检测
- BPM（Beats Per Minute）实时显示
- 信号滤波与稳定算法处理

### 📶 蓝牙连接
- M5StickS3 与 Android App 的 BLE 通信
- 设备扫描功能
- 一键快速连接
- 实时数据传输

### 🎵 智能音乐推荐
- 根据不同心率区间自动切换歌单
- 用户可自定义各心率区间对应的本地音乐
- 提供更加沉浸式的运动体验

### 🐾 Buddy 智能提醒
- 完成预设训练组数后弹出虚拟宠物
- 提醒用户休息并准备下一组训练

---

## 硬件组成

- M5StickS3
- MAX30102 心率传感器
- 内置 BMI270 / BMI2 惯性测量单元（IMU）

---

## 手机应用功能

Android 应用提供以下功能：

- 蓝牙连接管理
- 实时训练仪表盘
- 心率数据展示
- 音乐歌单配置
- 实时运动监测

---

## 仓库结构

- `FitSenseAndroid_github.zip` —— Android Studio 最终源码
- `M5_hardware_code.py` —— M5StickS3 最终固件代码
- `FitSense_PRD.docx` —— 产品需求文档（PRD）
- `archive/` —— 开发过程中的历史文件和中间版本

---

## 最终交付文件

本项目的核心交付成果包括：

1. Android 应用源码（`FitSenseAndroid_github.zip`）
2. M5StickS3 固件代码（`M5_hardware_code.py`）
3. 产品需求文档（`FitSense_PRD.docx`）

---

## 开发过程留痕

`archive` 文件夹中保存了项目开发过程中的历史文件、早期原型和中间版本。

这些文件被保留下来，用于记录项目的开发过程，并展示系统从概念到最终实现的完整演进。

---

## Release 版本

最新版本可在 GitHub Releases 页面中下载。

当前版本：**v1.0.0**

---

## 作者

- ENT208 Project Team

---

## 许可说明

本项目仅用于课程学习、学术展示和教学演示用途。
