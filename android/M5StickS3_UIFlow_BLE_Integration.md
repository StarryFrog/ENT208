# M5StickS3 UIFlow BLE Integration

目标：让 M5StickS3 使用 UIFlow Python 作为 BLE 外设，向安卓 App 广播并推送实时 JSON。

安卓 App 已按下面这套协议配置：

- 设备名建议：`FitSense-M5StickS3`
- Service UUID：`12345678-1234-1234-1234-1234567890ab`
- Notify UUID：`12345678-1234-1234-1234-1234567890ac`
- 可选 Write UUID：`12345678-1234-1234-1234-1234567890ad`

## 1. 先确认 UIFlow 固件支持 bluetooth

把下面代码加到最前面测试：

```python
try:
    import bluetooth
    HAS_BLE = True
    print("BLE module OK")
except Exception as e:
    HAS_BLE = False
    print("BLE module NOT found:", e)
```

如果没有 `bluetooth` 模块，当前固件不能直接走这套 Python BLE，需要改用支持 BLE 的 UIFlow 固件，或者切 Arduino。

## 2. 加 BLE 常量和全局变量

```python
if HAS_BLE:
    SERVICE_UUID = bluetooth.UUID("12345678-1234-1234-1234-1234567890ab")
    NOTIFY_UUID = bluetooth.UUID("12345678-1234-1234-1234-1234567890ac")
    WRITE_UUID = bluetooth.UUID("12345678-1234-1234-1234-1234567890ad")

    BLE_SERVICE = (
        SERVICE_UUID,
        (
            (NOTIFY_UUID, bluetooth.FLAG_NOTIFY | bluetooth.FLAG_READ),
            (WRITE_UUID, bluetooth.FLAG_WRITE),
        ),
    )

ble = None
ble_notify_handle = None
ble_write_handle = None
ble_connected = False
last_ble_send = 0
```

## 3. 加 BLE 初始化

```python
def advertising_payload(name):
    name_bytes = name.encode()
    payload = bytearray()
    payload += bytes((2, 0x01, 0x06))
    payload += bytes((len(name_bytes) + 1, 0x09)) + name_bytes
    return payload

def ble_irq(event, data):
    global ble_connected
    if event == 1:
        ble_connected = True
        print("BLE connected")
    elif event == 2:
        ble_connected = False
        print("BLE disconnected")
        if ble:
            ble.gap_advertise(100, adv_data=advertising_payload("FitSense-M5StickS3"))

def init_ble():
    global ble, ble_notify_handle, ble_write_handle
    if not HAS_BLE:
        print("BLE unavailable")
        return False
    try:
        ble = bluetooth.BLE()
        ble.active(True)
        ble.irq(ble_irq)
        handles = ble.gatts_register_services((BLE_SERVICE,))
        ble_notify_handle = handles[0][0]
        ble_write_handle = handles[0][1]
        ble.gap_advertise(100, adv_data=advertising_payload("FitSense-M5StickS3"))
        print("BLE advertising started")
        return True
    except Exception as e:
        print("BLE init failed:", e)
        return False
```

## 4. 加 JSON 打包函数

建议直接复用你现有变量：

```python
def current_mode_name():
    if current_mode == MODE_PUSHUP:
        return "pushup"
    if current_mode == MODE_BENCHPRESS:
        return "benchpress"
    if current_mode == MODE_RUNNING:
        return "run"
    if current_mode == MODE_HEARTRATE:
        return "heartrate"
    return "pushup"

def build_ble_payload():
    return '{{"hr":{},"run":{},"pushup":{},"bench":{},"cadence":{},"signal_quality":{},"mode":"{}"}}'.format(
        current_bpm if current_bpm > 0 else 0,
        step_count,
        pushup_count,
        benchpress_count,
        cadence,
        signal_quality,
        current_mode_name()
    )
```

## 5. 加 notify 发送函数

```python
def ble_notify_data():
    global last_ble_send
    if not HAS_BLE or ble is None or ble_notify_handle is None:
        return

    now = time.ticks_ms()
    if time.ticks_diff(now, last_ble_send) < 1000:
        return
    last_ble_send = now

    try:
        payload = build_ble_payload()
        ble.gatts_write(ble_notify_handle, payload.encode())
        if ble_connected:
            ble.gatts_notify(0, ble_notify_handle)
        print("BLE send:", payload)
    except Exception as e:
        print("BLE notify error:", e)
```

## 6. 在 setup 里初始化 BLE

在 `M5.begin()` 后面加：

```python
init_ble()
```

## 7. 在 loop 里持续发送

在 `time.sleep_ms(20)` 前面加：

```python
ble_notify_data()
```

## 8. 发给安卓 App 的 JSON 示例

```json
{
  "hr": 96,
  "run": 120,
  "pushup": 14,
  "bench": 8,
  "cadence": 92,
  "signal_quality": 68,
  "mode": "heartrate"
}
```

## 9. 安卓端当前已兼容的字段

安卓 App 现在支持解析这些字段：

- `hr`
- `run` / `step_count`
- `pushup` / `pushup_count`
- `bench` / `benchpress_count`
- `cadence`
- `signal_quality`
- `mode`

只要 Stick 端广播成功，安卓 App 设置页扫描到设备后，点击设备即可连接并实时更新。
