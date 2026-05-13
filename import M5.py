import M5
import time
from M5 import Widgets
import math
from machine import I2C, Pin
import random  # 添加random模块导入

# ==================== MAX30102 配置（新增）====================
MAX30102_ADDR = 0x57
# M5Stick S3 官方心率底座
i2c = I2C(0, sda=Pin(8), scl=Pin(0), freq=400000)

# 心率全局变量
ir_buffer = []
red_buffer = []
last_peak = 0
bpm_values = []
current_bpm = 0
last_heartbeat_time = 0
heartbeat_visible = False
no_finger_count = 0
finger_detected = False

# 手指检测阈值
IR_THRESHOLD = 5000  # 低于这个值认为手指离开
RED_THRESHOLD = 5000
PEAK_THRESHOLD = 15000  # 提高峰值检测阈值

# ==================== 蓝牙 BLE（手机连接）新增 ====================
import bluetooth
from micropython import const

# BLE UART UUID（Nordic UART Service，手机最容易连接）
_UART_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX   = (
    bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
    bluetooth.FLAG_NOTIFY,
)
_UART_RX   = (
    bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
    bluetooth.FLAG_WRITE,
)
_UART_SERVICE = (_UART_UUID, (_UART_TX, _UART_RX))

# BLE事件
_IRQ_CENTRAL_CONNECT    = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE        = const(3)

ble = None
ble_tx = None
ble_rx = None
ble_conn = None


def ble_irq(event, data):
    global ble_conn

    if event == _IRQ_CENTRAL_CONNECT:
        ble_conn, _, _ = data
        print("📱 手机已连接")

    elif event == _IRQ_CENTRAL_DISCONNECT:
        print("📴 手机已断开")
        ble_conn = None
        ble_advertise()

    elif event == _IRQ_GATTS_WRITE:
        conn_handle, value_handle = data
        if value_handle == ble_rx:
            msg = ble.gatts_read(ble_rx).decode().strip()
            print("收到手机消息:", msg)

            # 支持手机发送指令
            if msg == "mode":
                switch_mode()
            elif msg == "reset":
                reset_count()
            elif msg == "hr":
                global current_mode
                current_mode = MODE_HEARTRATE


def ble_init(name="FitSense-M5StickS3"):
    global ble, ble_tx, ble_rx

    ble = bluetooth.BLE()
    ble.active(True)
    ble.irq(ble_irq)

    ((ble_tx, ble_rx),) = ble.gatts_register_services((_UART_SERVICE,))
    ble_advertise(name)

    print("✅ BLE 已启动")


def ble_advertise(name="FitSense-M5StickS3"):
    adv_data = bytearray()

    # Flags
    adv_data += b'\x02\x01\x06'

    # Complete Local Name
    name_bytes = name.encode()
    adv_data += bytes([len(name_bytes) + 1, 0x09]) + name_bytes

    ble.gap_advertise(100, adv_data)
    print("📡 蓝牙广播中:", name)


def ble_send(msg):
    """发送数据到手机"""
    if ble_conn is not None:
        try:
            ble.gatts_notify(ble_conn, ble_tx, str(msg) + "\n")
        except:
            pass

# ==================== MAX30102 函数（新增）====================
def init_max30102():
    try:
        # 软件复位
        i2c.writeto_mem(MAX30102_ADDR, 0x09, b'\x40')
        time.sleep_ms(200)

        # FIFO配置
        i2c.writeto_mem(MAX30102_ADDR, 0x08, b'\x4F')

        # 模式配置（心率模式）
        i2c.writeto_mem(MAX30102_ADDR, 0x09, b'\x02')

        # SpO2配置
        i2c.writeto_mem(MAX30102_ADDR, 0x0A, b'\x27')

        # LED电流
        i2c.writeto_mem(MAX30102_ADDR, 0x0C, b'\x24')
        i2c.writeto_mem(MAX30102_ADDR, 0x0D, b'\x24')

        print("✅ MAX30102 initialized")
        return True
    except Exception as e:
        print("❌ MAX30102 init failed:", e)
        return False

def read_max30102():
    """读取 RED 和 IR 值"""
    try:
        data = i2c.readfrom_mem(MAX30102_ADDR, 0x07, 6)
        red = ((data[0] << 16) | (data[1] << 8) | data[2]) & 0x3FFFF
        ir = ((data[3] << 16) | (data[4] << 8) | data[5]) & 0x3FFFF
        return red, ir
    except Exception as e:
        print("MAX30102 read error:", e)
        return 0, 0

# 尝试导入BMI2库
try:
    from m5stack import bmi2
    HAS_BMI2 = True
    print("BMI2 library found")
except:
    HAS_BMI2 = False
    print("BMI2 library not found, using fallback methods")

# --- Configuration ---

# --- 新增：重力分离滤波变量 ---
alpha = 0.8  # 滤波系数，0.8-0.95之间
gravity = [0.0, 0.0, 0.0]  # 估计的重力分量
linear_accel = [0.0, 0.0, 0.0]  # 分离后的运动加速度

# Three detection modes + 新增心率模式（不改动原有模式）
MODE_PUSHUP = 0
MODE_BENCHPRESS = 1
MODE_RUNNING = 2
MODE_HEARTRATE = 3

current_mode = MODE_PUSHUP
AXIS_X = 0
AXIS_Y = 1
AXIS_Z = 2
current_axis = AXIS_Y

# Thresholds（原有阈值不变）
PUSHUP_DOWN_THRESHOLD = 0.3
PUSHUP_UP_THRESHOLD = 0.7
BENCHPRESS_DOWN_THRESHOLD = 0.3
BENCHPRESS_UP_THRESHOLD = 0.7
RUNNING_THRESHOLD = 1.2  # 可选改0.4更灵敏，不改动你的原始设置

# Global counters（原有计数器不变）
pushup_count = 0
benchpress_count = 0
step_count = 0
cadence = 0
is_down = False
last_step_time = 0
step_history = []

# 长按检测变量（原有不变）
btnA_press_time = 0
btnA_long_press_threshold = 1000  # 1秒
btnA_long_pressed = False

# IMU状态（原有不变）
imu_initialized = False
imu_data = [0, 0, 0]
last_read_time = 0
read_interval = 50  # 读取间隔(ms)

# Screen labels (调整位置适应Stick3的80x160屏幕)（原有不变）
lbl_mode = None
lbl_raw = None
lbl_val = None
lbl_count = None
lbl_status = None
lbl_axis = None
lbl_cadence = None
lbl_instruction = None
lbl_init = None  # 初始化状态

# ==================== 宠物弹窗新增变量 ====================
pet_popup_active = False
pet_expression_index = 0
pet_blinking = False
pet_last_blink_time = 0
pet_next_interval = 1500

# 按钮状态（原有不变）
btnA_pressed = False
btnB_pressed = False
btnA_handling_long_press = False

# 心率相关变量（新增）
bpm_values = []
current_bpm = 0
heartbeat_visible = True
last_beat_anim = 0
last_peak = 0
no_finger_count = 0
signal_quality = 0
finger_present = False

def hr_init():
    """初始化心率传感器"""
    global bpm_values, current_bpm, heartbeat_visible, last_beat_anim, last_peak, no_finger_count, signal_quality, finger_present
    global ir_buffer, red_buffer, last_heartbeat_time, finger_detected

    bpm_values = []
    current_bpm = 0
    heartbeat_visible = True
    last_beat_anim = time.ticks_ms()
    last_peak = time.ticks_ms()
    no_finger_count = 0
    signal_quality = 0
    finger_present = False

    # 心率算法缓冲区
    ir_buffer = []
    red_buffer = []
    last_heartbeat_time = 0
    finger_detected = False

    init_max30102()
    print("Heart rate sensor initialized")

def hr_reset():
    """重置心率测量"""
    global bpm_values, current_bpm, no_finger_count, signal_quality, finger_present
    global ir_buffer, red_buffer, last_peak, last_heartbeat_time, finger_detected

    bpm_values = []
    current_bpm = 0
    no_finger_count = 0
    signal_quality = 0
    finger_present = False

    # 清空心率算法缓冲区
    ir_buffer = []
    red_buffer = []
    last_peak = 0
    last_heartbeat_time = 0
    finger_detected = False

    print("Heart rate reset")

# ==================== 心率检测函数（只修改这部分）====================
def detect_heart_rate():
    """稳定版 MAX30102 心率检测"""
    global bpm_values, current_bpm, last_heartbeat_time, heartbeat_visible
    global no_finger_count, finger_detected, ir_buffer, red_buffer, last_peak

    try:
        # 读取数据
        red, ir = read_max30102()

        # 检测手指是否放置
        is_finger_present = (ir > IR_THRESHOLD and red > RED_THRESHOLD)

        if is_finger_present:
            no_finger_count = 0
            finger_detected = True

            # 心率检测
            ir_buffer.append(ir)
            if len(ir_buffer) > 30:  # 增加缓冲区大小
                ir_buffer.pop(0)

            signal_quality = 0
            if len(ir_buffer) >= 10:
                # 计算信号质量 - 使用更精确的方法
                recent = ir_buffer[-10:]
                dynamic_range = max(recent) - min(recent)
                signal_quality = min(100, int(dynamic_range / 15))  # 调整除数

                # 峰值检测 - 更严格的条件
                if len(ir_buffer) >= 5:
                    # 使用更大的窗口检测峰值
                    center = ir_buffer[-3]
                    # 检查中心点是否比前后2个点都高
                    is_peak = True
                    for i in range(1, 3):
                        if not (center > ir_buffer[-3-i] and center > ir_buffer[-3+i]):
                            is_peak = False
                            break

                    if is_peak and center > PEAK_THRESHOLD:
                        current_time = time.ticks_ms()
                        if last_peak > 0:
                            interval = (current_time - last_peak) / 1000.0
                            if 0.3 < interval < 2.0:  # 30-200 BPM
                                bpm = 60.0 / interval
                                if 40 <= bpm <= 180:  # 更严格的范围
                                    bpm_values.append(bpm)
                                    if len(bpm_values) > 8:  # 增加缓冲区大小
                                        bpm_values.pop(0)

                                    if len(bpm_values) >= 3:  # 需要至少3个值
                                        # 使用中位数滤波减少噪声
                                        sorted_bpm = sorted(bpm_values)
                                        median_bpm = sorted_bpm[len(sorted_bpm)//2]
                                        current_bpm = int(median_bpm)

                                    # 检测到心跳时更新动画
                                    last_heartbeat_time = current_time
                                    heartbeat_visible = True

                        last_peak = current_time
        else:
            # 手指离开处理
            no_finger_count += 1
            signal_quality = 0

            # 连续5次检测不到手指，才认为手指真正离开
            if no_finger_count > 5 and finger_detected:
                # 重置所有检测状态
                current_bpm = 0
                ir_buffer = []
                bpm_values = []
                last_peak = 0
                finger_detected = False
                heartbeat_visible = False
                print("手指离开，已重置")

        # 心跳动画
        current_time = time.ticks_ms()
        if current_time - last_heartbeat_time > 300:  # 300ms后隐藏心跳
            heartbeat_visible = False

        # 确定状态消息
        if not is_finger_present and no_finger_count > 5:
            status_text = "请放置手指"
        elif is_finger_present and signal_quality < 20:
            status_text = "手指已放置，检测中..."
        elif is_finger_present and current_bpm == 0:
            status_text = "检测中，请保持稳定"
        elif is_finger_present and current_bpm > 0:
            status_text = "心率: " + str(current_bpm) + " BPM"
        else:
            status_text = "初始化中..."

        # 更新UI
        update_label(lbl_raw, f"IR:{ir} RED:{red}")
        update_label(lbl_val, f"Signal:{signal_quality}%")

        if current_bpm > 0:
            update_label(lbl_status, status_text, 0x00FF00)
            update_label(lbl_count, str(current_bpm), 0xFF0000)
        else:
            update_label(lbl_status, status_text, 0xFFFF00)
            update_label(lbl_count, "--", 0xFF0000)

        # 调试信息输出
        if time.ticks_ms() % 1000 < 20:  # 每秒输出一次
            print(f"IR: {ir}, Red: {red}, 手指: {is_finger_present}, 信号: {signal_quality}%, 心率: {current_bpm}")

    except Exception as e:
        print("Heart Error:", e)

def init_imu_bmi2():
    """使用BMI2库初始化IMU"""
    global imu_initialized

    try:
        print("Initializing BMI2 IMU...")

        # 方法1: 通过M5.Imu.Init
        if hasattr(M5, 'Imu') and hasattr(M5.Imu, 'Init'):
            try:
                M5.Imu.Init()
                print("IMU initialized via M5.Imu.Init()")
                imu_initialized = True
                return True
            except Exception as e:
                print(f"M5.Imu.Init failed: {e}")

        # 方法2: 通过bmi2库
        if HAS_BMI2:
            try:
                # 初始化BMI2传感器
                bmi2.init()
                print("BMI2 initialized via bmi2.init()")
                imu_initialized = True
                return True
            except Exception as e:
                print(f"bmi2.init failed: {e}")

        # 方法3: 尝试通过I2C初始化
        try:
            from machine import I2C, Pin

            # 尝试常见的I2C引脚
            i2c_pins = [
                (21, 22),  # M5Stack Core2
                (22, 21),  # M5StickC
                (32, 33),  # 其他M5设备
            ]

            for sda, scl in i2c_pins:
                try:
                    i2c_test = I2C(0, sda=Pin(sda), scl=Pin(scl), freq=400000)
                    devices = i2c_test.scan()
                    print(f"I2C devices found at {sda},{scl}: {[hex(d) for d in devices]}")

                    # BMI279的I2C地址通常是0x68或0x69
                    if 0x68 in devices or 0x69 in devices:
                        print("BMI2 IMU found on I2C bus")
                        imu_initialized = True
                        return True
                except:
                    continue

        except Exception as e:
            print(f"I2C scan failed: {e}")

        return False

    except Exception as e:
        print(f"IMU initialization error: {e}")
        return False

def init_imu_direct():
    """直接初始化IMU（不通过库）"""
    global imu_initialized

    try:
        print("Trying direct IMU initialization...")

        # 尝试多种初始化方法
        methods = [
            lambda: M5.Imu.begin() if hasattr(M5.Imu, 'begin') else None,
            lambda: M5.Imu.init() if hasattr(M5.Imu, 'init') else None,
            lambda: M5.Imu.Init() if hasattr(M5.Imu, 'Init') else None,
            lambda: M5.IMU.begin() if hasattr(M5, 'IMU') and hasattr(M5.IMU, 'begin') else None,
            lambda: M5.IMU.init() if hasattr(M5, 'IMU') and hasattr(M5.IMU, 'init') else None,
            lambda: M5.IMU.Init() if hasattr(M5, 'IMU') and hasattr(M5.IMU, 'Init') else None,
        ]

        for i, method in enumerate(methods):
            try:
                result = method()
                if result is not None:
                    print(f"IMU initialized with method {i}")
                    imu_initialized = True
                    return True
            except:
                continue

        # 尝试检查是否有加速度计属性
        if hasattr(M5, 'Imu'):
            attrs = dir(M5.Imu)
            accel_attrs = [attr for attr in attrs if 'acc' in attr.lower()]
            if accel_attrs:
                print(f"Found acceleration attributes: {accel_attrs}")
                imu_initialized = True
                return True

        return False

    except Exception as e:
        print(f"Direct IMU init error: {e}")
        return False

def setup():
    global lbl_mode, lbl_raw, lbl_val, lbl_count, lbl_status, lbl_axis, lbl_cadence, lbl_instruction, lbl_init
    hr_init()

    M5.begin()
    ble_init()

    # 显示初始化状态
    print("\n=== M5StickC Three-in-One Sports Detector ===")
    print("Device: M5Stick S3 135x240")
    print("Initializing...")

    # 尝试多种方法初始化IMU（原有不变）
    imu_success = False
    imu_success = init_imu_bmi2()
    if not imu_success:
        imu_success = init_imu_direct()
    if not imu_success:
        print("Trying to read IMU data anyway...")
        test_data = get_imu_data()
        if test_data != [0, 0, 0]:
            imu_success = True
            print(f"IMU data read successfully: {test_data}")

    print(f"\nIMU Status: {'Initialized' if imu_success else 'Failed'}")
    if not imu_success:
        print("WARNING: IMU not initialized")
    print("\nControls: A=Mode | B=Reset | Hold A=Calib")

    # 【无遮挡最终版UI】135×240 像素级精准布局（原有不变）
    try:
        # 分区严格留白，零遮挡、零重叠
        # 1. 顶部模式栏（独立区块，不碰下方）
        lbl_mode = Widgets.Label("Pushup", 5, 5, 1.8, 0xffffff, 0x000000, Widgets.FONTS.DejaVu18)
        status_color = 0x00ff00 if imu_success else 0xff0000

        # 2. 轴+加速度（拉大间距，与标题彻底分离）
        lbl_val = Widgets.Label("A:0.00g", 5, 45, 1.6, 0x00aaff, 0x000000, Widgets.FONTS.DejaVu12)

        # 3. 原始数据

        # 4. 核心计数【缩小字体+上下留白】无任何遮挡
        lbl_count = Widgets.Label("0", 10, 80, 2.7, 0x00ff00, 0x000000, Widgets.FONTS.DejaVu24)

        # 5. 状态文本（与计数拉开距离）
        lbl_status = Widgets.Label("Ready", 5, 150, 1.6, 0xffff00, 0x000000, Widgets.FONTS.DejaVu18)

        # 6. 橙色步频（独立区块，不碰上方）
        lbl_cadence = Widgets.Label("Cad: 0 /min", 5, 185, 1.5, 0xff8800, 0x000000, Widgets.FONTS.DejaVu12)

        # 7. 底部操作指令（贴底不溢出）
        lbl_instruction = Widgets.Label("A:Mode B:Reset", 5, 215, 1.1, 0xaaaaaa, 0x000000, Widgets.FONTS.DejaVu12)

    except Exception as e:
        print(f"UI creation error: {e}")
        # 极简备用布局（无遮挡）
        try:
            lbl_mode = Widgets.Label("Pushup", 5, 5, 1.8, 0xffffff, 0x000000)
            lbl_axis = Widgets.Label("Axis:Y", 5, 35, 1.2, 0x00aaff, 0x000000)
            lbl_count = Widgets.Label("0", 10, 85, 3.5, 0x00ff00, 0x000000)
            lbl_status = Widgets.Label("Ready", 5, 155, 1.8, 0xffff00, 0x000000)
            lbl_instruction = Widgets.Label("A:Mode B:Reset", 5, 215, 1.1, 0xaaaaaa, 0x000000)
        except Exception as e2:
            print(f"Default UI error: {e2}")

    # 初始化隐藏步频（原有不变）
    if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
        lbl_cadence.set_hidden(True)

    # 串口日志保持不变
    print(f"\nIMU Status: {'Initialized' if imu_success else 'Failed'}")
    if not imu_success:
        print("WARNING: IMU not initialized")
    print("\nControls: A=Mode | B=Reset | Hold A=Calib")

def get_imu_data():
    """获取IMU数据 - 支持多种方法（原有不变）"""
    global imu_data, imu_initialized, last_read_time

    current_time = time.ticks_ms()

    # 限制读取频率
    if current_time - last_read_time < read_interval:
        return imu_data

    last_read_time = current_time

    # 如果IMU未初始化，返回0
    if not imu_initialized:
        return [0, 0, 0]

    # 尝试多种读取方法
    try:
        # 方法1: 通过M5.Imu.acceleration
        if hasattr(M5, 'Imu') and hasattr(M5.Imu, 'acceleration'):
            data = M5.Imu.acceleration
            if data is not None:
                imu_data = [data.x, data.y, data.z]
                return imu_data

        # 方法2: 通过M5.Imu.accel
        if hasattr(M5, 'Imu') and hasattr(M5.Imu, 'accel'):
            data = M5.Imu.accel
            if data is not None:
                if isinstance(data, tuple) and len(data) >= 3:
                    imu_data = list(data)
                elif hasattr(data, 'x') and hasattr(data, 'y') and hasattr(data, 'z'):
                    imu_data = [data.x, data.y, data.z]
                return imu_data

        # 方法3: 通过M5.Imu.get_accel
        if hasattr(M5, 'Imu') and hasattr(M5.Imu, 'get_accel'):
            data = M5.Imu.get_accel()
            if data is not None:
                if isinstance(data, tuple) and len(data) >= 3:
                    imu_data = list(data)
                elif hasattr(data, 'x') and hasattr(data, 'y') and hasattr(data, 'z'):
                    imu_data = [data.x, data.y, data.z]
                return imu_data

        # 方法4: 通过M5.Imu.getAccel
        if hasattr(M5, 'Imu') and hasattr(M5.Imu, 'getAccel'):
            data = M5.Imu.getAccel()
            if data is not None:
                if isinstance(data, tuple) and len(data) >= 3:
                    imu_data = list(data)
                elif hasattr(data, 'x') and hasattr(data, 'y') and hasattr(data, 'z'):
                    imu_data = [data.x, data.y, data.z]
                return imu_data

        # 方法5: 通过BMI2库
        if HAS_BMI2:
            try:
                accel = bmi2.get_acceleration()
                if accel is not None:
                    imu_data = [accel[0], accel[1], accel[2]]
                    return imu_data
            except:
                pass

        # 方法6: 通过M5.IMU（大写）
        if hasattr(M5, 'IMU'):
            if hasattr(M5.IMU, 'acceleration'):
                data = M5.IMU.acceleration
                if data is not None:
                    imu_data = [data.x, data.y, data.z]
                    return imu_data
            elif hasattr(M5.IMU, 'accel'):
                data = M5.IMU.accel
                if data is not None:
                    if isinstance(data, tuple) and len(data) >= 3:
                        imu_data = list(data)
                    elif hasattr(data, 'x') and hasattr(data, 'y') and hasattr(data, 'z'):
                        imu_data = [data.x, data.y, data.z]
                    return imu_data

        # 方法7: 尝试读取模拟数据（用于测试）
        imu_data = [
            random.uniform(-0.1, 0.1),
            random.uniform(-0.1, 0.1),
            random.uniform(9.7, 9.9)  # 重力加速度
        ]

        return imu_data

    except Exception as e:
        print(f"IMU read error: {e}")

    return imu_data

def get_linear_accel():
    """
    获取分离重力后的线性加速度（真正的运动加速度）
    返回: [linear_x, linear_y, linear_z]（原有不变）
    """
    global gravity, linear_accel

    # 1. 获取原始数据
    raw = get_imu_data()

    # 2. 初始化重力（第一次运行时）
    if gravity[0] == 0 and gravity[1] == 0 and gravity[2] == 0:
        gravity = list(raw) 

    # 3. 使用互补滤波估计重力方向（低通滤波）
    gravity[0] = alpha * gravity[0] + (1 - alpha) * raw[0]
    gravity[1] = alpha * gravity[1] + (1 - alpha) * raw[1]
    gravity[2] = alpha * gravity[2] + (1 - alpha) * raw[2]

    # 4. 原始数据 减去 重力分量 = 线性加速度
    linear_accel[0] = raw[0] - gravity[0]
    linear_accel[1] = raw[1] - gravity[1]
    linear_accel[2] = raw[2] - gravity[2]

    return linear_accel

# ==================== 宠物显示动画代码（完整融合）====================
def rgb(r, g, b):
    return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)

C = {
    '1': rgb(0, 0, 0), '2': rgb(255, 255, 255), '3': rgb(255, 180, 200),
    '4': rgb(255, 74, 74), '5': rgb(255, 230, 0), '6': 0x7E07,
    '7': 0x5D04, '8': rgb(40, 40, 40), 'W': rgb(255, 255, 255), ' ': None
}

# 0: 呆萌 (保持身体起伏)
CAT_0_A = [
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122112222211221 ",
    "122111222211121 ", "122188222218821 ", "123222222222321 ", "122222122122221 ",
    " 12222211122221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]
CAT_0_B = [
    "                ", "  11       11   ", " 1221111111221  ", "123222222222321 ",
    "122112222211221 ", "122222222222221 ", "123222222222321 ", "122222122122221 ",
    " 12222211122221 ", "  112222222211  ", "   1222222221   ", "  122112211221  ",
    "  111  11  111  ", "                "
]

# 1: 星星眼 (保持身体跳动)
CAT_1_A = [
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122222222222221 ",
    "122252222252221 ", "122555222555221 ", "123252222252321 ", "122228282822221 ",
    " 12228888822221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]
CAT_1_B = [
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122255222255221 ",
    "122555522555521 ", "123255222255321 ", "122228282822221 ", " 12228888822221 ",
    "  112222222211  ", "   1222222221   ", "   1222222221   ", "  122112211221  ",
    "                ", "                " 
]

# 2: 委屈 (身体不动，仅闭眼)
CAT_2_A = [
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122222222222221 ",
    "122111222111221 ", "122211222211221 ", "123222222222321 ", "122222111122221 ",
    " 12222122212221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]
CAT_2_B = [ # 身体完全对齐A，只把眼睛像素去掉
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122222222222221 ",
    "122222222222221 ", "122111222111221 ", "123222222222321 ", "122222111122221 ",
    " 12222122212221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]

# 3: 酷炫 (身体不动，仅墨镜反光)
CAT_3_A = [
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122888282888221 ",
    "122888828888221 ", "122888222888221 ", "123222222222321 ", "122222211222221 ",
    " 12222221222221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]
CAT_3_B = [ # 身体完全对齐A，仅反光特效
    "  11       11   ", " 1221111111221  ", "123222222222321 ", "122WWWWWWWWW21 ",
    "122W88W88W8821 ", "122WWWWWWWWW21 ", "123222222222321 ", "122222211222221 ",
    " 12222221222221 ", "  112222222211  ", "   1222222221   ", "   1222222221   ",
    "  122112211221  ", "  111  11  111  "
]

EXPRESSIONS = [(CAT_0_A, CAT_0_B), (CAT_1_A, CAT_1_B), (CAT_2_A, CAT_2_B), (CAT_3_A, CAT_3_B)]

def draw_pet_scenery():
    M5.Lcd.fillScreen(0x87FF)
    M5.Lcd.fillRect(0, 160, 135, 80, C['7'])
    M5.Lcd.fillRect(0, 155, 135, 5, C['6'])
    M5.Lcd.fillCircle(20, 25, 12, 0xFFE0)

def render_pet(matrix):
    scale = 7
    offset_x = 11
    offset_y = 65
    M5.Lcd.fillRect(0, 60, 135, 100, 0x87FF)
    for y, row in enumerate(matrix):
        for x, char in enumerate(row):
            color = C.get(char)
            if color is not None:
                M5.Lcd.fillRect(offset_x + x*scale, offset_y + y*scale, scale, scale, color)

def show_pet_popup():
    """Pushup / Press 计数到达 5 的倍数时弹出宠物界面。"""
    global pet_popup_active, pet_expression_index
    global pet_blinking, pet_last_blink_time, pet_next_interval

    pet_popup_active = True
    pet_blinking = False
    pet_last_blink_time = time.ticks_ms()
    pet_next_interval = 1500

    draw_pet_scenery()
    render_pet(EXPRESSIONS[pet_expression_index][0])

    pet_expression_index = (pet_expression_index + 1) % len(EXPRESSIONS)

def update_pet_popup():
    """宠物弹窗循环。按 BtnA / 中键返回原运动界面。"""
    global pet_popup_active, pet_blinking
    global pet_last_blink_time, pet_next_interval

    now = time.ticks_ms()

    if hasattr(M5, 'BtnA') and M5.BtnA.wasPressed():
        pet_popup_active = False
        restore_sports_ui()
        return

    current_idx = (pet_expression_index - 1) % len(EXPRESSIONS)

    if not pet_blinking:
        if time.ticks_diff(now, pet_last_blink_time) > pet_next_interval:
            pet_blinking = True
            render_pet(EXPRESSIONS[current_idx][1])
            pet_last_blink_time = now
    else:
        if time.ticks_diff(now, pet_last_blink_time) > 150:
            pet_blinking = False
            render_pet(EXPRESSIONS[current_idx][0])
            pet_last_blink_time = now
            pet_next_interval = random.randint(1000, 2000)

def restore_sports_ui():
    """
    退出宠物界面后恢复原来的运动界面。
    不重置任何计数，不重启蓝牙，不重置心率。
    """
    M5.Lcd.fillScreen(0x000000)

    if current_mode == MODE_PUSHUP:
        update_label(lbl_mode, "Pushup", 0xffffff)
        update_label(lbl_axis, "Axis:Y", 0x00aaff)
        update_label(lbl_count, str(pushup_count), 0x00ff00)
        update_label(lbl_status, "Ready", 0xffff00)
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)

    elif current_mode == MODE_BENCHPRESS:
        update_label(lbl_mode, "Press", 0xffffff)
        update_label(lbl_axis, "Axis:Z", 0x00aaff)
        update_label(lbl_count, str(benchpress_count), 0x00ff00)
        update_label(lbl_status, "Ready", 0xffff00)
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)

    elif current_mode == MODE_RUNNING:
        update_label(lbl_mode, "Run", 0xffffff)
        update_label(lbl_axis, "Axis:Y", 0x00aaff)
        update_label(lbl_count, str(step_count), 0x00ff00)
        update_label(lbl_cadence, f"Cad:{cadence}/min", 0xff8800)
        update_label(lbl_status, "Ready", 0xffff00)
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(False)

    elif current_mode == MODE_HEARTRATE:
        update_label(lbl_mode, 'Heart', 0xFF0000)
        update_label(lbl_axis, 'MAX30102', 0x00aaff)
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)
        if current_bpm > 0:
            update_label(lbl_count, str(current_bpm), 0xFF0000)
        else:
            update_label(lbl_count, '--', 0xFF0000)
        update_label(lbl_status, 'Place Finger', 0xFFFF00)

    update_label(lbl_instruction, "A:Mode B:Reset", 0xaaaaaa)

def update_label(label, text, text_color=None):
    """更新标签文本（原有不变）"""
    try:
        if label is None:
            return False

        if hasattr(label, 'set_text'):
            label.set_text(str(text))
        elif hasattr(label, 'setText'):
            label.setText(str(text))
        elif hasattr(label, 'text'):
            label.text = str(text)
        else:
            return False

        if text_color is not None:
            if hasattr(label, 'set_color'):
                label.set_color(text_color)
            elif hasattr(label, 'setColor'):
                label.setColor(text_color)
        return True
    except Exception as e:
        return False

def switch_mode():
    global current_mode, current_axis

    current_mode = (current_mode + 1) % 4

    if current_mode == MODE_PUSHUP:
        # 原有俯卧撑代码（完全不变）
        current_axis = AXIS_Y
        update_label(lbl_mode, "Pushup", 0xffffff)
        update_label(lbl_axis, "Axis:Y")
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)
        update_label(lbl_count, str(pushup_count), 0x00ff00)

    elif current_mode == MODE_BENCHPRESS:
        # 原有卧推代码（完全不变）
        current_axis = AXIS_Z
        update_label(lbl_mode, "Press", 0xffffff)
        update_label(lbl_axis, "Axis:Z")
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)
        update_label(lbl_count, str(benchpress_count), 0x00ff00)

    elif current_mode == MODE_RUNNING:
        # 原有跑步代码（完全不变）
        current_axis = AXIS_Y
        update_label(lbl_mode, "Run", 0xffffff)
        update_label(lbl_axis, "Axis:Y")
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(False)
        update_label(lbl_count, str(step_count), 0x00ff00)
        update_label(lbl_cadence, f"Cad:{cadence}/min", 0xff8800)

    elif current_mode == MODE_HEARTRATE:
        update_label(lbl_mode, 'Heart', 0xFF0000)
        update_label(lbl_axis, 'MAX30102')
        if lbl_cadence and hasattr(lbl_cadence, 'set_hidden'):
            lbl_cadence.set_hidden(True)
        hr_reset()
        update_label(lbl_count, '--', 0xFF0000)
        update_label(lbl_status, 'Place Finger', 0xFFFF00)

    update_label(lbl_status, "Ready", 0xffff00)
    print(f"Switched to mode: {current_mode}")

def reset_count():
    """重置计数器（原有不变）"""
    global pushup_count, benchpress_count, step_count, cadence, step_history, is_down

    if current_mode == MODE_PUSHUP:
        pushup_count = 0
        update_label(lbl_count, "0", 0x00ff00)
    elif current_mode == MODE_BENCHPRESS:
        benchpress_count = 0
        update_label(lbl_count, "0", 0x00ff00)
    elif current_mode == MODE_RUNNING:
        step_count = 0
        cadence = 0
        step_history = []
        update_label(lbl_count, "0", 0x00ff00)
        update_label(lbl_cadence, f"Cad:{cadence}/min", 0xff8800)
    elif current_mode == MODE_HEARTRATE:
        hr_reset()
        update_label(lbl_count, '--', 0xFF0000)

    is_down = False
    update_label(lbl_status, "Reset", 0x00ffff)
    time.sleep(0.5)
    update_label(lbl_status, "Ready", 0xffff00)

def calibrate_sensor():
    """校准传感器（原有不变）"""
    update_label(lbl_status, "Calib...", 0xffff00)
    print("Calibrating sensor. Keep device still on flat surface.")
    time.sleep(2)

    # 读取10次取平均值
    readings = []
    for i in range(10):
        accel = get_imu_data()
        readings.append(accel[current_axis])
        time.sleep(0.1)

    if readings:
        avg = sum(readings) / len(readings)
        print(f"Calibration complete. Baseline: {avg:.2f}g")
        update_label(lbl_status, f"Cal:{avg:.2f}", 0x00ff00)

    time.sleep(1)
    update_label(lbl_status, "Ready", 0xffff00)

def detect_pushup(accel, axis_acc):
    """检测俯卧撑（原有逻辑保留，只新增5次宠物弹窗）"""
    global pushup_count, is_down

    if not is_down and axis_acc < PUSHUP_DOWN_THRESHOLD:
        is_down = True
        update_label(lbl_status, "Down", 0xff0000)
    elif is_down and axis_acc > PUSHUP_UP_THRESHOLD:
        pushup_count += 1
        is_down = False
        update_label(lbl_status, "Up", 0x00ff00)
        update_label(lbl_count, str(pushup_count), 0x00ff00)

        # 新增：每到5的倍数自动弹出宠物界面
        if pushup_count > 0 and pushup_count % 5 == 0:
            show_pet_popup()

        return True

    return False

def detect_benchpress(accel, axis_acc):
    """检测卧推（原有逻辑保留，只新增5次宠物弹窗）"""
    global benchpress_count, is_down

    if not is_down and axis_acc < BENCHPRESS_DOWN_THRESHOLD:
        is_down = True
        update_label(lbl_status, "Down", 0xff0000)
    elif is_down and axis_acc > BENCHPRESS_UP_THRESHOLD:
        benchpress_count += 1
        is_down = False
        update_label(lbl_status, "Up", 0x00ff00)
        update_label(lbl_count, str(benchpress_count), 0x00ff00)

        # 新增：每到5的倍数自动弹出宠物界面
        if benchpress_count > 0 and benchpress_count % 5 == 0:
            show_pet_popup()

        return True

    return False

def detect_running(accel, axis_acc):
    """检测跑步（修复步频卡死，原有已优化，不变）"""
    global step_count, last_step_time, cadence, step_history

    current_time = time.ticks_ms() / 1000.0

    # 使用加速度变化检测步伐
    if abs(axis_acc) > RUNNING_THRESHOLD and (current_time - last_step_time) > 0.3:
        step_count += 1
        # 只保留最近2步，算实时步频（永不卡死）
        step_history.append(current_time)
        if len(step_history) > 2:
            step_history.pop(0)

        # 实时步频计算（相邻两步间隔，最稳定）
        if len(step_history) >= 2:
            step_interval = step_history[-1] - step_history[-2]
            if step_interval > 0:
                cadence = int(60.0 / step_interval)

        last_step_time = current_time
        update_label(lbl_count, str(step_count), 0x00ff00)
        update_label(lbl_cadence, f"Cad:{cadence}/min", 0xff8800)
        update_label(lbl_status, "Step", 0x00ff00)
        return True

    # 静止3秒自动清零步频（不锁死旧值）
    if current_time - last_step_time > 3:
        cadence = 0
        update_label(lbl_cadence, f"Cad:{cadence}/min", 0xff8800)

    return False

def loop():
    global btnA_pressed, btnB_pressed, btnA_press_time, btnA_long_pressed, btnA_handling_long_press

    M5.update()

    # 新增：宠物界面显示时，只处理宠物动画和返回，不刷新运动UI
    if pet_popup_active:
        update_pet_popup()
        time.sleep_ms(20)
        return

    # 读取数据（原有不变）
    raw_accel = get_imu_data()
    motion_accel = get_linear_accel() 
    axis_acc = motion_accel[current_axis]

    # 更新显示（原有不变，仅心率模式会覆盖显示）

    update_label(lbl_val, f"Acc:{abs(axis_acc):.2f}")

    # 每5秒打印一次数据到串口（用于调试，原有不变）
    if time.ticks_ms() % 2000 < 50:
        print(f"Accel: X={raw_accel[0]:.2f}, Y={raw_accel[1]:.2f}, Z={raw_accel[2]:.2f}")

    # 处理按钮A - 短按切换模式，长按校准（原有不变）
    if hasattr(M5, 'BtnA'):
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

    # 处理按钮B - 重置计数器（原有不变）
    if hasattr(M5, 'BtnB'):
        if M5.BtnB.wasPressed():
            if not btnB_pressed:
                reset_count()
                btnB_pressed = True     
        else:
            btnB_pressed = False

    # 根据模式进行检测（原有不变，仅新增心率模式判断）
    if current_mode == MODE_PUSHUP:
        detect_pushup(motion_accel, axis_acc)
    elif current_mode == MODE_BENCHPRESS:
        detect_benchpress(motion_accel, axis_acc)
    elif current_mode == MODE_RUNNING:
        detect_running(motion_accel, axis_acc)
    elif current_mode == MODE_HEARTRATE:
        detect_heart_rate()

    # 每秒发送一次数据给手机
    if time.ticks_ms() % 1000 < 30:
        if current_mode == MODE_HEARTRATE:
            ble_send("HR:{}".format(current_bpm))
        elif current_mode == MODE_RUNNING:
            ble_send("STEP:{} CAD:{}".format(step_count, cadence))
        elif current_mode == MODE_PUSHUP:
            ble_send("PUSHUP:{}".format(pushup_count))
        elif current_mode == MODE_BENCHPRESS:
            ble_send("PRESS:{}".format(benchpress_count))

    time.sleep_ms(20)

if __name__ == '__main__':
    try:
        setup()
        while True:
            loop()
    except Exception as e:
        print("Program error:", e)
        import sys
        sys.print_exception(e)
