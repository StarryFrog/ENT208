# FitSense Demo

这是一个根据 `FitSense_README_中文版.md` 制作的可演示软件原型。当前包含两个版本：

- 安卓 App：`app/`，可用 Android Studio 打开并安装到安卓手机。
- 网页/Electron Demo：可直接打开 `index.html` 预览。

## 已实现功能

- BLE 扫描与连接流程演示，内置 M5StickS3 虚拟设备
- 实时运动数据面板：心率、跑步步数、距离估算、深蹲次数、卧推次数
- 心率历史曲线和当前 JSON 数据包展示
- Zone 1-4 心率区间识别
- 按心率区间推荐音乐歌单，并支持本地自定义
- 用户设置：步幅估算、目标心率上限、自动切换音乐

## 安卓手机运行

1. 用 Android Studio 打开当前文件夹：`/Users/yuantu.zhao/Documents/New project`
2. 等待 Gradle Sync 完成。
3. 手机开启开发者选项和 USB 调试。
4. 用 USB 连接手机，在 Android Studio 顶部设备列表选择你的手机。
5. 点击 Run。

当前安卓版本使用模拟数据代替真实 BLE。点击“扫描设备”，选择 `FitSense-M5StickS3` 后，App 会开始实时刷新心率、运动数据和音乐推荐。

## 网页预览

直接预览：

```bash
open index.html
```

## 桌面版运行

```bash
npm install
npm start
```

## 打包

```bash
npm run build:mac
npm run build:win
```

产物会输出到 `dist/` 目录。

## Demo 说明

当前版本使用模拟数据代替真实 BLE。点击“扫描设备”，选择 `FitSense-M5StickS3` 后，应用会持续生成与 README 示例一致的 JSON 数据：

```json
{
  "hr": 142,
  "squat": 18,
  "bench": 10,
  "run": 620
}
```

后续接入真实硬件时，可以把 `app.js` 中的模拟器替换为 Android BLE 或 Web Bluetooth 的通知数据源。
