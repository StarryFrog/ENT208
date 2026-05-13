import M5
import time
import math
import random
import json
from M5 import Widgets
from machine import I2C, Pin

try:
    import bluetooth
    HAS_BLE = True
    print("BLE module OK")
except Exception as ble_import_error:
    HAS_BLE = False
    print("BLE module NOT found:", ble_import_error)

# ==================== BLE 配置 ====================
BLE_DEVICE_NAME = "FitSense-M5StickS3"
ble = None
ble_notify_handle = None
ble_write_handle = None
ble_connected = False
last_ble_send = 0

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
            ble.gap_advertise(100, adv_data=advertising_payload(BLE_DEVICE_NAME))


def init_ble():
    global ble, ble_notify_handle, ble_write_handle

    if not HAS_BLE:
        print("BLE unavailable in current UIFlow firmware")
        return False

    try:
        ble = bluetooth.BLE()
        ble.active(True)
        ble.irq(ble_irq)
        handles = ble.gatts_register_services((BLE_SERVICE,))
        ble_notify_handle = handles[0][0]
        ble_write_handle = handles[0][1]
        ble.gap_advertise(100, adv_data=advertising_payload(BLE_DEVICE_NAME))
        print("BLE advertising started:", BLE_DEVICE_NAME)
        return True
    except Exception as e:
        print("BLE init failed:", e)
        return False


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
    payload = {
        "hr": current_bpm if current_bpm > 0 else 0,
        "run": step_count,
        "pushup": pushup_count,
        "bench": benchpress_count,
        "cadence": cadence,
        "signal_quality": signal_quality,
        "mode": current_mode_name(),
    }
    return json.dumps(payload)


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


# ==================== MAX30102 配置 ====================
MAX30102_ADDR = 0x57
i2c = I2C(0, sda=Pin(8), scl=Pin(0), freq=400000)

ir_buffer = []
red_buffer = []
last_peak = 0
bpm_values = []
current_bpm = 0
last_heartbeat_time = 0
heartbeat_visible = False
no_finger_count = 0
finger_detected = False
signal_quality = 0
finger_present = False

IR_THRESHOLD = 5000
RED_THRESHOLD = 5000
PEAK_THRESHOLD = 15000


def init_max30102():
    try:
        i2c.writeto_mem(MAX30102_ADDR, 0x09, b"\x40")
        time.sleep_ms(200)
        i2c.writeto_mem(MAX30102_ADDR, 0x08, b"\x4F")
        i2c.writeto_mem(MAX30102_ADDR, 0x09, b"\x02")
        i2c.writeto_mem(MAX30102_ADDR, 0x0A, b"\x27")
        i2c.writeto_mem(MAX30102_ADDR, 0x0C, b"\x24")
        i2c.writeto_mem(MAX30102_ADDR, 0x0D, b"\x24")
        print("MAX30102 initialized")
        return True
    except Exception as e:
        print("MAX30102 init failed:", e)
        return False


def read_max30102():
    try:
        data = i2c.readfrom_mem(MAX30102_ADDR, 0x07, 6)
        red = ((data[0] << 16) | (data[1] << 8) | data[2]) & 0x3FFFF
        ir = ((data[3] << 16) | (data[4] << 8) | data[5]) & 0x3FFFF
        return red, ir
    except Exception as e:
        print("MAX30102 read error:", e)
        return 0, 0


try:
    from m5stack import bmi2
    HAS_BMI2 = True
    print("BMI2 library found")
except Exception:
    HAS_BMI2 = False
    print("BMI2 library not found, using fallback methods")


alpha = 0.8
gravity = [0.0, 0.0, 0.0]
linear_accel = [0.0, 0.0, 0.0]

MODE_PUSHUP = 0
MODE_BENCHPRESS = 1
MODE_RUNNING = 2
MODE_HEARTRATE = 3

current_mode = MODE_PUSHUP
AXIS_X = 0
AXIS_Y = 1
AXIS_Z = 2
current_axis = AXIS_Y

PUSHUP_DOWN_THRESHOLD = 0.3
PUSHUP_UP_THRESHOLD = 0.7
BENCHPRESS_DOWN_THRESHOLD = 0.3
BENCHPRESS_UP_THRESHOLD = 0.7
RUNNING_THRESHOLD = 1.2

pushup_count = 0
benchpress_count = 0
step_count = 0
cadence = 0
is_down = False
last_step_time = 0
step_history = []

btnA_press_time = 0
btnA_long_press_threshold = 1000
btnA_long_pressed = False

imu_initialized = False
imu_data = [0, 0, 0]
last_read_time = 0
read_interval = 50

lbl_mode = None
lbl_raw = None
lbl_val = None
lbl_count = None
lbl_status = None
lbl_axis = None
lbl_cadence = None
lbl_instruction = None
lbl_init = None

btnA_pressed = False
btnB_pressed = False
btnA_handling_long_press = False


def hr_init():
    global bpm_values, current_bpm, heartbeat_visible, last_peak, no_finger_count
    global signal_quality, finger_present, ir_buffer, red_buffer
    global last_heartbeat_time, finger_detected

    bpm_values = []
    current_bpm = 0
    heartbeat_visible = True
    last_peak = 0
    no_finger_count = 0
    signal_quality = 0
    finger_present = False
    ir_buffer = []
    red_buffer = []
    last_heartbeat_time = 0
    finger_detected = False
    init_max30102()
    print("Heart rate sensor initialized")


def hr_reset():
    global bpm_values, current_bpm, no_finger_count, signal_quality, finger_present
    global ir_buffer, red_buffer, last_peak, last_heartbeat_time, finger_detected

    bpm_values = []
    current_bpm = 0
    no_finger_count = 0
    signal_quality = 0
    finger_present = False
    ir_buffer = []
    red_buffer = []
    last_peak = 0
    last_heartbeat_time = 0
    finger_detected = False
    print("Heart rate reset")


def detect_heart_rate():
    global bpm_values, current_bpm, last_heartbeat_time, heartbeat_visible
    global no_finger_count, finger_detected, ir_buffer, red_buffer
    global last_peak, signal_quality

    try:
        red, ir = read_max30102()
        is_finger_present = ir > IR_THRESHOLD and red > RED_THRESHOLD

        if is_finger_present:
            no_finger_count = 0
            finger_detected = True
            ir_buffer.append(ir)
            if len(ir_buffer) > 30:
                ir_buffer.pop(0)

            signal_quality = 0
            if len(ir_buffer) >= 10:
                recent = ir_buffer[-10:]
                dynamic_range = max(recent) - min(recent)
                signal_quality = min(100, int(dynamic_range / 15))

                if len(ir_buffer) >= 5:
                    center = ir_buffer[-3]
                    is_peak = True
                    for i in range(1, 3):
                        if not (center > ir_buffer[-3 - i] and center > ir_buffer[-3 + i]):
                            is_peak = False
                            break

                    if is_peak and center > PEAK_THRESHOLD:
                        current_time = time.ticks_ms()
                        if last_peak > 0:
                            interval = (current_time - last_peak) / 1000.0
                            if 0.3 < interval < 2.0:
                                bpm = 60.0 / interval
                                if 40 <= bpm <= 180:
                                    bpm_values.append(bpm)
                                    if len(bpm_values) > 8:
                                        bpm_values.pop(0)
                                    if len(bpm_values) >= 3:
                                        sorted_bpm = sorted(bpm_values)
                                        median_bpm = sorted_bpm[len(sorted_bpm) // 2]
                                        current_bpm = int(median_bpm)
                                    last_heartbeat_time = current_time
                                    heartbeat_visible = True
                        last_peak = current_time
        else:
            no_finger_count += 1
            signal_quality = 0
            if no_finger_count > 5 and finger_detected:
                current_bpm = 0
                ir_buffer = []
                bpm_values = []
                last_peak = 0
                finger_detected = False
                heartbeat_visible = False
                print("Finger removed, heart rate reset")

        current_time = time.ticks_ms()
        if current_time - last_heartbeat_time > 300:
            heartbeat_visible = False

        if not is_finger_present and no_finger_count > 5:
            status_text = "Place finger"
        elif is_finger_present and signal_quality < 20:
            status_text = "Detecting..."
        elif is_finger_present and current_bpm == 0:
            status_text = "Hold still"
        elif is_finger_present and current_bpm > 0:
            status_text = "Heart: " + str(current_bpm)
        else:
            status_text = "Starting..."

        update_label(lbl_raw, "IR:{} RED:{}".format(ir, red))
        update_label(lbl_val, "Signal:{}%".format(signal_quality))

        if current_bpm > 0:
            update_label(lbl_status, status_text, 0x00FF00)
            update_label(lbl_count, str(current_bpm), 0xFF0000)
        else:
            update_label(lbl_status, status_text, 0xFFFF00)
            update_label(lbl_count, "--", 0xFF0000)

    except Exception as e:
        print("Heart Error:", e)


def init_imu_bmi2():
    global imu_initialized
    try:
        print("Initializing BMI2 IMU...")
        if hasattr(M5, "Imu") and hasattr(M5.Imu, "Init"):
            try:
                M5.Imu.Init()
                imu_initialized = True
                print("IMU initialized via M5.Imu.Init()")
                return True
            except Exception as e:
                print("M5.Imu.Init failed:", e)

        if HAS_BMI2:
            try:
                bmi2.init()
                imu_initialized = True
                print("BMI2 initialized via bmi2.init()")
                return True
            except Exception as e:
                print("bmi2.init failed:", e)
        return False
    except Exception as e:
        print("IMU initialization error:", e)
        return False


def init_imu_direct():
    global imu_initialized
    try:
        methods = [
            lambda: M5.Imu.begin() if hasattr(M5.Imu, "begin") else None,
            lambda: M5.Imu.init() if hasattr(M5.Imu, "init") else None,
            lambda: M5.Imu.Init() if hasattr(M5.Imu, "Init") else None,
        ]
        for method in methods:
            try:
                result = method()
                if result is not None:
                    imu_initialized = True
                    return True
            except Exception:
                pass
        return False
    except Exception as e:
        print("Direct IMU init error:", e)
        return False


def setup():
    global lbl_mode, lbl_raw, lbl_val, lbl_count, lbl_status, lbl_axis, lbl_cadence, lbl_instruction, lbl_init

    hr_init()
    M5.begin()
    init_ble()

    print("\n=== M5StickS3 FitSense BLE ===")

    imu_success = init_imu_bmi2()
    if not imu_success:
        imu_success = init_imu_direct()
    if not imu_success:
        test_data = get_imu_data()
        if test_data != [0, 0, 0]:
            imu_success = True

    try:
        lbl_mode = Widgets.Label("Pushup", 5, 5, 1.5, 0xFFFFFF, 0x000000, Widgets.FONTS.DejaVu18)
        status_color = 0x00FF00 if imu_success else 0xFF0000
        status_text = "OK" if imu_success else "ERR"
        lbl_init = Widgets.Label(status_text, 110, 7, 1.5, status_color, 0x000000, Widgets.FONTS.DejaVu12)
        lbl_axis = Widgets.Label("Axis:Y", 5, 35, 1.4, 0x00AAFF, 0x000000, Widgets.FONTS.DejaVu12)
        lbl_val = Widgets.Label("A:0.00g", 65, 35, 1.4, 0x00AAFF, 0x000000, Widgets.FONTS.DejaVu12)
        lbl_raw = Widgets.Label("X:0.0 Y:0.0 Z:0.0", 5, 60, 1.5, 0x888888, 0x000000, Widgets.FONTS.DejaVu9)
        lbl_count = Widgets.Label("0", 10, 90, 2.5, 0x00FF00, 0x000000, Widgets.FONTS.DejaVu24)
        lbl_status = Widgets.Label("Ready", 5, 150, 1.6, 0xFFFF00, 0x000000, Widgets.FONTS.DejaVu18)
        lbl_cadence = Widgets.Label("Cad: 0 /min", 5, 185, 1.5, 0xFF8800, 0x000000, Widgets.FONTS.DejaVu12)
        lbl_instruction = Widgets.Label("A:Mode B:Reset", 5, 215, 1.1, 0xAAAAAA, 0x000000, Widgets.FONTS.DejaVu12)
    except Exception as e:
        print("UI creation error:", e)

    if lbl_cadence and hasattr(lbl_cadence, "set_hidden"):
        lbl_cadence.set_hidden(True)


def get_imu_data():
    global imu_data, imu_initialized, last_read_time

    current_time = time.ticks_ms()
    if current_time - last_read_time < read_interval:
        return imu_data
    last_read_time = current_time

    if not imu_initialized:
        return [0, 0, 0]

    try:
        if hasattr(M5, "Imu") and hasattr(M5.Imu, "acceleration"):
            data = M5.Imu.acceleration
            if data is not None:
                imu_data = [data.x, data.y, data.z]
                return imu_data

        if hasattr(M5, "Imu") and hasattr(M5.Imu, "accel"):
            data = M5.Imu.accel
            if data is not None:
                if isinstance(data, tuple) and len(data) >= 3:
                    imu_data = list(data)
                elif hasattr(data, "x"):
                    imu_data = [data.x, data.y, data.z]
                return imu_data

        if HAS_BMI2:
            accel = bmi2.get_acceleration()
            if accel is not None:
                imu_data = [accel[0], accel[1], accel[2]]
                return imu_data
    except Exception as e:
        print("IMU read error:", e)

    imu_data = [
        random.uniform(-0.1, 0.1),
        random.uniform(-0.1, 0.1),
        random.uniform(9.7, 9.9),
    ]
    return imu_data


def get_linear_accel():
    global gravity, linear_accel

    raw = get_imu_data()
    if gravity[0] == 0 and gravity[1] == 0 and gravity[2] == 0:
        gravity = list(raw)

    gravity[0] = alpha * gravity[0] + (1 - alpha) * raw[0]
    gravity[1] = alpha * gravity[1] + (1 - alpha) * raw[1]
    gravity[2] = alpha * gravity[2] + (1 - alpha) * raw[2]

    linear_accel[0] = raw[0] - gravity[0]
    linear_accel[1] = raw[1] - gravity[1]
    linear_accel[2] = raw[2] - gravity[2]
    return linear_accel


def update_label(label, text, text_color=None):
    try:
        if label is None:
            return False
        if hasattr(label, "set_text"):
            label.set_text(str(text))
        elif hasattr(label, "text"):
            label.text = str(text)
        if text_color is not None:
            if hasattr(label, "set_color"):
                label.set_color(text_color)
        return True
    except Exception:
        return False


def switch_mode():
    global current_mode, current_axis
    current_mode = (current_mode + 1) % 4

    if current_mode == MODE_PUSHUP:
        current_axis = AXIS_Y
        update_label(lbl_mode, "Pushup", 0xFFFFFF)
        update_label(lbl_axis, "Axis:Y")
        if lbl_cadence and hasattr(lbl_cadence, "set_hidden"):
            lbl_cadence.set_hidden(True)
        update_label(lbl_count, str(pushup_count), 0x00FF00)

    elif current_mode == MODE_BENCHPRESS:
        current_axis = AXIS_Z
        update_label(lbl_mode, "Press", 0xFFFFFF)
        update_label(lbl_axis, "Axis:Z")
        if lbl_cadence and hasattr(lbl_cadence, "set_hidden"):
            lbl_cadence.set_hidden(True)
        update_label(lbl_count, str(benchpress_count), 0x00FF00)

    elif current_mode == MODE_RUNNING:
        current_axis = AXIS_Y
        update_label(lbl_mode, "Run", 0xFFFFFF)
        update_label(lbl_axis, "Axis:Y")
        if lbl_cadence and hasattr(lbl_cadence, "set_hidden"):
            lbl_cadence.set_hidden(False)
        update_label(lbl_count, str(step_count), 0x00FF00)
        update_label(lbl_cadence, "Cad:{}/min".format(cadence), 0xFF8800)

    elif current_mode == MODE_HEARTRATE:
        update_label(lbl_mode, "Heart", 0xFF0000)
        update_label(lbl_axis, "MAX30102")
        if lbl_cadence and hasattr(lbl_cadence, "set_hidden"):
            lbl_cadence.set_hidden(True)
        hr_reset()
        update_label(lbl_count, "--", 0xFF0000)
        update_label(lbl_status, "Place Finger", 0xFFFF00)

    update_label(lbl_status, "Ready", 0xFFFF00)


def reset_count():
    global pushup_count, benchpress_count, step_count, cadence, step_history, is_down

    if current_mode == MODE_PUSHUP:
        pushup_count = 0
        update_label(lbl_count, "0", 0x00FF00)
    elif current_mode == MODE_BENCHPRESS:
        benchpress_count = 0
        update_label(lbl_count, "0", 0x00FF00)
    elif current_mode == MODE_RUNNING:
        step_count = 0
        cadence = 0
        step_history = []
        update_label(lbl_count, "0", 0x00FF00)
        update_label(lbl_cadence, "Cad:{}/min".format(cadence), 0xFF8800)
    elif current_mode == MODE_HEARTRATE:
        hr_reset()
        update_label(lbl_count, "--", 0xFF0000)

    is_down = False
    update_label(lbl_status, "Reset", 0x00FFFF)
    time.sleep(0.5)
    update_label(lbl_status, "Ready", 0xFFFF00)


def calibrate_sensor():
    update_label(lbl_status, "Calib...", 0xFFFF00)
    time.sleep(2)
    readings = []
    for _ in range(10):
        accel = get_imu_data()
        readings.append(accel[current_axis])
        time.sleep(0.1)
    if readings:
        avg = sum(readings) / len(readings)
        update_label(lbl_status, "Cal:{:.2f}".format(avg), 0x00FF00)
    time.sleep(1)
    update_label(lbl_status, "Ready", 0xFFFF00)


def detect_pushup(accel, axis_acc):
    global pushup_count, is_down

    if not is_down and axis_acc < PUSHUP_DOWN_THRESHOLD:
        is_down = True
        update_label(lbl_status, "Down", 0xFF0000)
    elif is_down and axis_acc > PUSHUP_UP_THRESHOLD:
        pushup_count += 1
        is_down = False
        update_label(lbl_status, "Up", 0x00FF00)
        update_label(lbl_count, str(pushup_count), 0x00FF00)
        return True
    return False


def detect_benchpress(accel, axis_acc):
    global benchpress_count, is_down

    if not is_down and axis_acc < BENCHPRESS_DOWN_THRESHOLD:
        is_down = True
        update_label(lbl_status, "Down", 0xFF0000)
    elif is_down and axis_acc > BENCHPRESS_UP_THRESHOLD:
        benchpress_count += 1
        is_down = False
        update_label(lbl_status, "Up", 0x00FF00)
        update_label(lbl_count, str(benchpress_count), 0x00FF00)
        return True
    return False


def detect_running(accel, axis_acc):
    global step_count, last_step_time, cadence, step_history

    current_time = time.ticks_ms() / 1000.0
    if abs(axis_acc) > RUNNING_THRESHOLD and (current_time - last_step_time) > 0.3:
        step_count += 1
        step_history.append(current_time)
        if len(step_history) > 2:
            step_history.pop(0)
        if len(step_history) >= 2:
            step_interval = step_history[-1] - step_history[-2]
            if step_interval > 0:
                cadence = int(60.0 / step_interval)
        last_step_time = current_time
        update_label(lbl_count, str(step_count), 0x00FF00)
        update_label(lbl_cadence, "Cad:{}/min".format(cadence), 0xFF8800)
        update_label(lbl_status, "Step", 0x00FF00)
        return True

    if current_time - last_step_time > 3:
        cadence = 0
        update_label(lbl_cadence, "Cad:{}/min".format(cadence), 0xFF8800)
    return False


def loop():
    global btnA_pressed, btnB_pressed, btnA_press_time, btnA_long_pressed, btnA_handling_long_press

    M5.update()

    raw_accel = get_imu_data()
    motion_accel = get_linear_accel()
    axis_acc = motion_accel[current_axis]

    update_label(lbl_raw, "G:{:.1f} M:{:.1f}".format(raw_accel[current_axis], motion_accel[current_axis]))
    update_label(lbl_val, "Acc:{:.2f}".format(abs(axis_acc)))

    if hasattr(M5, "BtnA"):
        if M5.BtnA.isPressed():
            if not btnA_pressed and not btnA_handling_long_press:
                btnA_pressed = True
                btnA_press_time = time.ticks_ms()
                btnA_long_pressed = False
        elif M5.BtnA.wasReleased():
            if btnA_pressed and not btnA_long_pressed and not btnA_handling_long_press:
                switch_mode()
            btnA_pressed = False
            btnA_handling_long_press = False
        elif btnA_pressed and not btnA_long_pressed and not btnA_handling_long_press:
            if time.ticks_ms() - btnA_press_time > btnA_long_press_threshold:
                btnA_long_pressed = True
                btnA_handling_long_press = True
                calibrate_sensor()

    if hasattr(M5, "BtnB"):
        if M5.BtnB.wasPressed():
            if not btnB_pressed:
                reset_count()
                btnB_pressed = True
        else:
            btnB_pressed = False

    if current_mode == MODE_PUSHUP:
        detect_pushup(motion_accel, axis_acc)
    elif current_mode == MODE_BENCHPRESS:
        detect_benchpress(motion_accel, axis_acc)
    elif current_mode == MODE_RUNNING:
        detect_running(motion_accel, axis_acc)
    elif current_mode == MODE_HEARTRATE:
        detect_heart_rate()

    ble_notify_data()
    time.sleep_ms(20)


if __name__ == "__main__":
    try:
        setup()
        while True:
            loop()
    except Exception as e:
        print("Program error:", e)
        import sys
        sys.print_exception(e)
