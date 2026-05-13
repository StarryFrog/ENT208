# FitSense Android

这是一个纯 Android Studio 项目，只包含安卓手机可运行的 FitSense Demo。

## 运行

1. 在 Android Studio 选择 `Open`。
2. 打开这个文件夹：

```text
/Users/yuantu.zhao/Documents/New project/FitSenseAndroid
```

3. 等 Gradle Sync 完成。
4. 连接安卓手机并开启 USB 调试。
5. 点击 Run。

当前版本使用模拟 BLE 数据。在“设置”页点击“扫描设备”，选择 `FitSense-M5StickS3` 后，App 会实时刷新心率、步数、深蹲、卧推、JSON 数据和音乐推荐。

## 新版演示点

- 顶部可切换跑步、深蹲、卧推、HIIT 四种健身模式。
- 不同模式会改变模拟心率、步数、动作次数和训练摘要。
- 底部包含“训练 / 歌单 / 设置”三页。
- 三页导航固定在屏幕底部，滚动内容时不会消失。
- 底部导航上方固定迷你播放器，支持上一首、播放/暂停、下一首。
- “歌单”页可给 Zone 1-4 分别加入手机本地音频，并可试听。
- “训练”页会根据当前心率区间显示对应 Zone 歌曲，并可直接播放。
- “训练”页包含实时心率曲线图。
- “设置”页集中放蓝牙扫描、设备连接、步幅、目标心率和自动推荐开关。
