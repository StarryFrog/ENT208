const devices = [
  { name: "FitSense-M5StickS3", rssi: -48, battery: 86 },
  { name: "M5-Wearable-02", rssi: -61, battery: 72 },
  { name: "GymBench-Sensor", rssi: -67, battery: 64 },
];

const zones = [
  { id: "zone1", label: "Zone 1", range: "<100", state: "放松", min: 0, max: 99, color: "#1d9a6c" },
  { id: "zone2", label: "Zone 2", range: "100-130", state: "热身", min: 100, max: 130, color: "#2f77d1" },
  { id: "zone3", label: "Zone 3", range: "131-160", state: "训练", min: 131, max: 160, color: "#f3b833" },
  { id: "zone4", label: "Zone 4", range: ">160", state: "高燃", min: 161, max: 240, color: "#e24c4c" },
];

const defaultPlaylists = {
  zone1: "Lo-fi Recovery / 慢速拉伸",
  zone2: "Warm Up Pop / 轻节奏热身",
  zone3: "Training Beat / 稳态训练",
  zone4: "Peak Mode EDM / 冲刺高燃",
};

const state = {
  connected: false,
  scanning: false,
  paused: false,
  selectedDevice: null,
  hr: 0,
  squat: 0,
  bench: 0,
  run: 0,
  tick: 0,
  history: [],
  stride: Number(localStorage.getItem("fitsense-stride") || 0.78),
  targetHr: Number(localStorage.getItem("fitsense-target-hr") || 160),
  autoMusic: localStorage.getItem("fitsense-auto-music") !== "false",
  playlists: JSON.parse(localStorage.getItem("fitsense-playlists") || JSON.stringify(defaultPlaylists)),
};

const el = {
  navTabs: document.querySelectorAll(".nav-tab"),
  views: document.querySelectorAll(".view"),
  connectionStatus: document.getElementById("connectionStatus"),
  scanBtn: document.getElementById("scanBtn"),
  deviceList: document.getElementById("deviceList"),
  pauseBtn: document.getElementById("pauseBtn"),
  resetBtn: document.getElementById("resetBtn"),
  heartRate: document.getElementById("heartRate"),
  zonePill: document.getElementById("zonePill"),
  runSteps: document.getElementById("runSteps"),
  distance: document.getElementById("distance"),
  squatCount: document.getElementById("squatCount"),
  benchCount: document.getElementById("benchCount"),
  squatTrend: document.getElementById("squatTrend"),
  benchTrend: document.getElementById("benchTrend"),
  packetTime: document.getElementById("packetTime"),
  connectedDevice: document.getElementById("connectedDevice"),
  signalStrength: document.getElementById("signalStrength"),
  watchHr: document.getElementById("watchHr"),
  chart: document.getElementById("heartChart"),
  musicZone: document.getElementById("musicZone"),
  albumArt: document.getElementById("albumArt"),
  trackName: document.getElementById("trackName"),
  trackReason: document.getElementById("trackReason"),
  playlistEditor: document.getElementById("playlistEditor"),
  strideInput: document.getElementById("strideInput"),
  targetHrInput: document.getElementById("targetHrInput"),
  autoMusicInput: document.getElementById("autoMusicInput"),
  jsonPacket: document.getElementById("jsonPacket"),
};

const chartContext = el.chart.getContext("2d");
let simulator = null;

init();

function init() {
  el.strideInput.value = state.stride.toFixed(2);
  el.targetHrInput.value = state.targetHr;
  el.autoMusicInput.checked = state.autoMusic;
  bindEvents();
  renderPlaylistEditor();
  renderAll();
  drawChart();
}

function bindEvents() {
  el.navTabs.forEach((button) => {
    button.addEventListener("click", () => switchView(button.dataset.view));
  });

  el.scanBtn.addEventListener("click", scanDevices);
  el.pauseBtn.addEventListener("click", togglePause);
  el.resetBtn.addEventListener("click", resetWorkout);

  el.strideInput.addEventListener("input", () => {
    state.stride = Number(el.strideInput.value) || 0.78;
    localStorage.setItem("fitsense-stride", String(state.stride));
    renderMetrics();
  });

  el.targetHrInput.addEventListener("input", () => {
    state.targetHr = Number(el.targetHrInput.value) || 160;
    localStorage.setItem("fitsense-target-hr", String(state.targetHr));
    renderMusic();
  });

  el.autoMusicInput.addEventListener("change", () => {
    state.autoMusic = el.autoMusicInput.checked;
    localStorage.setItem("fitsense-auto-music", String(state.autoMusic));
    renderMusic();
  });
}

function switchView(viewName) {
  el.navTabs.forEach((button) => button.classList.toggle("active", button.dataset.view === viewName));
  el.views.forEach((view) => view.classList.toggle("active", view.id === `${viewName}View`));
}

function scanDevices() {
  state.scanning = true;
  el.scanBtn.textContent = "扫描中...";
  el.deviceList.innerHTML = "";
  devices.forEach((device, index) => {
    window.setTimeout(() => {
      const button = document.createElement("button");
      button.className = "device-button";
      button.type = "button";
      button.innerHTML = `<span>${device.name}</span><strong>${device.rssi} dBm</strong>`;
      button.addEventListener("click", () => connectDevice(device, button));
      el.deviceList.appendChild(button);

      if (index === devices.length - 1) {
        state.scanning = false;
        el.scanBtn.textContent = "重新扫描";
      }
    }, 360 * (index + 1));
  });
}

function connectDevice(device, button) {
  state.connected = true;
  state.selectedDevice = device;
  state.paused = false;
  document.querySelectorAll(".device-button").forEach((item) => item.classList.remove("active"));
  button.classList.add("active");
  el.connectionStatus.textContent = "已连接";
  el.connectedDevice.textContent = device.name;
  el.signalStrength.textContent = `${device.rssi} dBm`;
  el.pauseBtn.textContent = "暂停模拟";
  seedWorkout();
  startSimulator();
  renderAll();
}

function startSimulator() {
  window.clearInterval(simulator);
  simulator = window.setInterval(() => {
    if (!state.connected || state.paused) {
      return;
    }
    generatePacket();
    renderAll();
  }, 900);
}

function generatePacket() {
  state.tick += 1;
  const base = 94 + Math.sin(state.tick / 5) * 23 + Math.sin(state.tick / 13) * 42;
  const jitter = random(-7, 8);
  state.hr = clamp(Math.round(base + jitter), 82, 176);
  state.run += Math.round(random(12, state.hr > 145 ? 34 : 24));

  if (state.tick % 3 === 0 && state.hr > 108) {
    state.squat += 1;
  }

  if (state.tick % 5 === 0 && state.hr > 122) {
    state.bench += 1;
  }

  state.history.push({ hr: state.hr, time: new Date() });
  if (state.history.length > 48) {
    state.history.shift();
  }
}

function seedWorkout() {
  state.tick = 0;
  state.hr = 104;
  state.squat = 0;
  state.bench = 0;
  state.run = 0;
  state.history = Array.from({ length: 16 }, (_, index) => ({
    hr: 92 + Math.round(Math.sin(index / 2) * 8),
    time: new Date(Date.now() - (16 - index) * 900),
  }));
}

function resetWorkout() {
  seedWorkout();
  renderAll();
}

function togglePause() {
  state.paused = !state.paused;
  el.pauseBtn.textContent = state.paused ? "继续模拟" : "暂停模拟";
}

function renderAll() {
  renderMetrics();
  renderMusic();
  drawChart();
  renderPacket();
}

function renderMetrics() {
  const zone = getZone(state.hr);
  const distance = (state.run * state.stride) / 1000;
  el.heartRate.textContent = state.connected ? state.hr : "--";
  el.watchHr.textContent = state.connected ? state.hr : "--";
  el.runSteps.textContent = state.run.toLocaleString("zh-CN");
  el.distance.textContent = `${distance.toFixed(2)} km`;
  el.squatCount.textContent = state.squat;
  el.benchCount.textContent = state.bench;
  el.zonePill.textContent = state.connected ? `${zone.label} · ${zone.state}` : "等待设备";
  el.zonePill.style.color = zone.color;
  el.squatTrend.textContent = state.connected ? "加速度计识别稳定" : "动作识别待启动";
  el.benchTrend.textContent = state.connected ? "上肢推举节奏正常" : "动作识别待启动";
  el.packetTime.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
  el.connectionStatus.classList.toggle("is-alert", !state.connected);
}

function renderMusic() {
  const zone = getZone(state.hr);
  const playlist = state.playlists[zone.id] || defaultPlaylists[zone.id];
  const overTarget = state.hr >= state.targetHr;
  el.musicZone.textContent = state.connected ? `${zone.label} ${zone.range} · ${zone.state}` : "等待心率";
  el.albumArt.style.background = `linear-gradient(135deg, ${zone.color}, #14211b)`;
  el.trackName.textContent = state.connected ? playlist : "连接设备后自动推荐";
  el.trackReason.textContent = state.connected
    ? `${state.autoMusic ? "自动" : "手动"}模式：心率 ${state.hr} BPM${overTarget ? "，已达到目标上限附近。" : "，维持当前训练节奏。"}`
    : "根据 Zone 1-4 心率区间切换歌单。";
}

function renderPacket() {
  const packet = {
    hr: state.hr || 142,
    squat: state.squat,
    bench: state.bench,
    run: state.run,
  };
  el.jsonPacket.textContent = JSON.stringify(packet, null, 2);
}

function renderPlaylistEditor() {
  el.playlistEditor.innerHTML = "";
  zones.forEach((zone) => {
    const row = document.createElement("label");
    row.className = "playlist-row";
    row.innerHTML = `<span>${zone.label}<br>${zone.state}</span><input value="${state.playlists[zone.id] || ""}" />`;
    const input = row.querySelector("input");
    input.addEventListener("input", () => {
      state.playlists[zone.id] = input.value;
      localStorage.setItem("fitsense-playlists", JSON.stringify(state.playlists));
      renderMusic();
    });
    el.playlistEditor.appendChild(row);
  });
}

function drawChart() {
  const width = el.chart.width;
  const height = el.chart.height;
  const padding = 28;
  chartContext.clearRect(0, 0, width, height);
  chartContext.fillStyle = "#f7fbf8";
  chartContext.fillRect(0, 0, width, height);

  chartContext.strokeStyle = "#dce6df";
  chartContext.lineWidth = 1;
  for (let y = 40; y < height; y += 44) {
    chartContext.beginPath();
    chartContext.moveTo(0, y);
    chartContext.lineTo(width, y);
    chartContext.stroke();
  }

  if (state.history.length < 2) {
    chartContext.fillStyle = "#66766f";
    chartContext.font = "18px PingFang SC, sans-serif";
    chartContext.fillText("连接设备后显示心率曲线", padding, height / 2);
    return;
  }

  const points = state.history.map((item, index) => {
    const x = padding + (index / (state.history.length - 1)) * (width - padding * 2);
    const y = height - padding - ((item.hr - 70) / 120) * (height - padding * 2);
    return { x, y, hr: item.hr };
  });

  const gradient = chartContext.createLinearGradient(0, 0, width, 0);
  gradient.addColorStop(0, "#1d9a6c");
  gradient.addColorStop(0.5, "#f3b833");
  gradient.addColorStop(1, "#e24c4c");
  chartContext.strokeStyle = gradient;
  chartContext.lineWidth = 4;
  chartContext.lineJoin = "round";
  chartContext.beginPath();
  points.forEach((point, index) => {
    if (index === 0) {
      chartContext.moveTo(point.x, point.y);
    } else {
      chartContext.lineTo(point.x, point.y);
    }
  });
  chartContext.stroke();

  const latest = points[points.length - 1];
  chartContext.fillStyle = getZone(state.hr).color;
  chartContext.beginPath();
  chartContext.arc(latest.x, latest.y, 7, 0, Math.PI * 2);
  chartContext.fill();
}

function getZone(hr) {
  return zones.find((zone) => hr >= zone.min && hr <= zone.max) || zones[0];
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function random(min, max) {
  return Math.random() * (max - min) + min;
}
