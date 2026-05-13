package com.fitsense.demo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

class BleFitnessClient {
    private static final String TAG = "FitSenseBLE";
    static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    static final UUID DATA_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    static final UUID COMMAND_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NUS_TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NUS_RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener {
        void onScanResult(FitDevice device);
        void onConnectionStateChanged(boolean connected, String name, String message);
        void onPayloadReceived(String payload);
        void onError(String message);
        void onDebugLog(String message);
    }

    static class FitDevice {
      final BluetoothDevice device;
      final String name;
      final int rssi;
      final boolean likelyTarget;

      FitDevice(BluetoothDevice device, String name, int rssi, boolean likelyTarget) {
          this.device = device;
          this.name = name;
          this.rssi = rssi;
          this.likelyTarget = likelyTarget;
      }
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final Map<String, FitDevice> scanResults = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic dataCharacteristic;
    private FitDevice pendingDevice;
    private boolean scanning = false;
    private boolean connected = false;
    private boolean retryingConnection = false;
    private static boolean englishMode = false;

    private String bi(String zh, String en) {
        return englishMode ? en : zh;
    }

    static void setEnglishMode(boolean enabled) {
        englishMode = enabled;
    }

    BleFitnessClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager == null ? null : manager.getAdapter();
    }

    boolean isBluetoothReady() {
        return adapter != null && adapter.isEnabled();
    }

    boolean hasRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean isConnected() {
        return connected;
    }

    boolean isScanning() {
        return scanning;
    }

    @SuppressLint("MissingPermission")
    void startScan() {
        debug("startScan()");
        if (!isBluetoothReady()) {
            listener.onError(bi("蓝牙未开启。", "Bluetooth is off."));
            return;
        }
        if (!hasRuntimePermissions()) {
            listener.onError(bi("缺少蓝牙权限。", "Bluetooth permissions are missing."));
            return;
        }

        stopScan();
        scanResults.clear();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError(bi("无法启动 BLE 扫描。", "Unable to start BLE scan."));
            return;
        }

        scanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
        debug("scanner.startScan() called");
    }

    @SuppressLint("MissingPermission")
    void stopScan() {
        if (scanner != null && scanning) {
            scanner.stopScan(scanCallback);
            debug("scanner.stopScan()");
        }
        scanning = false;
    }

    @SuppressLint("MissingPermission")
    void connect(FitDevice fitDevice) {
        debug("connect() target=" + (fitDevice == null ? "null" : fitDevice.name + "/" + fitDevice.device.getAddress()));
        if (fitDevice == null) {
            listener.onError(bi("没有可连接的设备。", "No device is available to connect."));
            return;
        }
        if (!hasRuntimePermissions()) {
            listener.onError(bi("缺少蓝牙权限。", "Bluetooth permissions are missing."));
            return;
        }
        stopScan();
        disconnect();
        pendingDevice = fitDevice;
        retryingConnection = false;
        gatt = fitDevice.device.connectGatt(context, false, gattCallback);
        debug("connectGatt() called");
        listener.onConnectionStateChanged(false, fitDevice.name, bi("正在连接 ", "Connecting to ") + fitDevice.name);
    }

    @SuppressLint("MissingPermission")
    void disconnect() {
        debug("disconnect()");
        connected = false;
        commandCharacteristic = null;
        dataCharacteristic = null;
        retryingConnection = false;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }

    @SuppressLint("MissingPermission")
    void sendCommand(String command) {
        if (!connected || gatt == null || commandCharacteristic == null) {
            return;
        }
        commandCharacteristic.setValue(command.getBytes(StandardCharsets.UTF_8));
        gatt.writeCharacteristic(commandCharacteristic);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord scanRecord = result.getScanRecord();
            String recordName = scanRecord == null ? null : scanRecord.getDeviceName();
            String deviceName = device == null ? null : device.getName();
            String name = firstNonEmpty(recordName, deviceName);

            debug("raw scan result name=" + name + " recordName=" + recordName + " deviceName=" + deviceName);

            boolean looksLikeTarget = false;
            if (name != null) {
                looksLikeTarget = name.contains("FitSense") || name.contains("M5Stick");
            }

            if (!looksLikeTarget && scanRecord != null && scanRecord.getServiceUuids() != null) {
                for (android.os.ParcelUuid serviceUuid : scanRecord.getServiceUuids()) {
                    if (serviceUuid != null && NUS_SERVICE_UUID.equals(serviceUuid.getUuid())) {
                        looksLikeTarget = true;
                        if (name == null || name.trim().isEmpty()) {
                            name = "FitSense-M5StickS3";
                        }
                        break;
                    }
                }
            }

            if (name == null || name.trim().isEmpty()) {
                name = looksLikeTarget ? "FitSense-M5StickS3" : bi("未命名 BLE 设备", "Unnamed BLE Device");
            }
            FitDevice fitDevice = new FitDevice(device, name, result.getRssi(), looksLikeTarget);
            scanResults.put(device.getAddress(), fitDevice);
            debug("scan result=" + name + " rssi=" + result.getRssi() + " addr=" + device.getAddress() + " likelyTarget=" + looksLikeTarget);
            listener.onScanResult(fitDevice);
        }

        @Override
        public void onBatchScanResults(java.util.List<ScanResult> results) {
            debug("onBatchScanResults size=" + (results == null ? 0 : results.size()));
            if (results == null) return;
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            debug("onScanFailed errorCode=" + errorCode);
            listener.onError(bi("BLE 扫描失败，错误码：", "BLE scan failed, code: ") + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            debug("onConnectionStateChange status=" + status + " newState=" + newState);
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                connected = false;
                commandCharacteristic = null;
                dataCharacteristic = null;
                if (!retryingConnection && pendingDevice != null) {
                    retryingConnection = true;
                    debug("retry scheduled for status=" + status);
                    listener.onError(bi("连接失败，状态码：", "Connection failed, status: ") + status + bi("，正在重试...", ", retrying..."));
                    gatt.close();
                    handler.postDelayed(() -> {
                        if (pendingDevice != null) {
                            BleFitnessClient.this.gatt = pendingDevice.device.connectGatt(context, false, gattCallback);
                            debug("retry connectGatt() called");
                        }
                    }, 600);
                    return;
                }
                listener.onError(bi("连接失败，状态码：", "Connection failed, status: ") + status);
                gatt.close();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                retryingConnection = false;
                debug("connected, requesting mtu/services");
                listener.onConnectionStateChanged(true, safeName(gatt.getDevice()), bi("已连接，正在发现服务...", "Connected, discovering services..."));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    boolean requested = gatt.requestMtu(185);
                    debug("requestMtu(185)=" + requested);
                    if (!requested) {
                        handler.postDelayed(gatt::discoverServices, 300);
                    }
                } else {
                    handler.postDelayed(gatt::discoverServices, 300);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                commandCharacteristic = null;
                dataCharacteristic = null;
                debug("disconnected");
                listener.onConnectionStateChanged(false, safeName(gatt.getDevice()), bi("设备已断开连接", "Device disconnected"));
                gatt.close();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            debug("onMtuChanged mtu=" + mtu + " status=" + status);
            handler.postDelayed(gatt::discoverServices, 250);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            debug("onServicesDiscovered status=" + status + " services=" + gatt.getServices().size());
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError(bi("服务发现失败，状态码：", "Service discovery failed, status: ") + status);
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service != null) {
                dataCharacteristic = service.getCharacteristic(DATA_UUID);
                commandCharacteristic = service.getCharacteristic(COMMAND_UUID);
                debug("custom service found");
            }

            if (dataCharacteristic == null) {
                BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
                if (nusService != null) {
                    dataCharacteristic = nusService.getCharacteristic(NUS_TX_UUID);
                    commandCharacteristic = nusService.getCharacteristic(NUS_RX_UUID);
                    debug("NUS service found");
                }
            }

            if (dataCharacteristic == null) {
                dataCharacteristic = findNotifyCharacteristic(gatt);
                debug("fallback notify characteristic=" + (dataCharacteristic != null ? dataCharacteristic.getUuid() : "null"));
            }

            if (commandCharacteristic == null) {
                commandCharacteristic = findWritableCharacteristic(gatt);
                debug("fallback writable characteristic=" + (commandCharacteristic != null ? commandCharacteristic.getUuid() : "null"));
            }

            if (dataCharacteristic == null) {
                listener.onError(bi("已连接到设备，但没有找到可通知的数据特征值。", "Connected, but no notifiable data characteristic was found."));
                return;
            }

            enableNotifications(gatt, dataCharacteristic);
            listener.onConnectionStateChanged(true, safeName(gatt.getDevice()), bi("已连接 ", "Connected ") + safeName(gatt.getDevice()));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            debug("onCharacteristicChanged uuid=" + characteristic.getUuid());
            if (dataCharacteristic != null && characteristic.getUuid().equals(dataCharacteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value != null && value.length > 0) {
                    listener.onPayloadReceived(new String(value, StandardCharsets.UTF_8));
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        debug("enableNotifications uuid=" + characteristic.getUuid());
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
            debug("CCCD write requested");
        } else {
            debug("CCCD descriptor missing");
        }
    }

    private String safeName(BluetoothDevice device) {
        if (device == null || device.getName() == null || device.getName().trim().isEmpty()) {
            return "FitSense Device";
        }
        return device.getName();
    }

    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGatt gatt) {
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int properties = characteristic.getProperties();
                boolean canNotify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean canIndicate = (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (canNotify || canIndicate) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGatt gatt) {
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int properties = characteristic.getProperties();
                boolean canWrite = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                boolean canWriteNoRsp = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                if (canWrite || canWriteNoRsp) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    private void debug(String message) {
        Log.d(TAG, message);
        listener.onDebugLog(message);
    }

    private String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        return null;
    }
}
