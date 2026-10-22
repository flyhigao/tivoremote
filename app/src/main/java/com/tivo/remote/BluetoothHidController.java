package com.tivo.remote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
public class BluetoothHidController {
    private static final String TAG = "BtHidController";

    public interface StatusListener {
        void onHidReady(boolean isRegistered);
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected();
        void onError(String message);
    }

    // Standard Composite HID Report Descriptor
    // Report ID 1: Standard Keyboard
    // Report ID 2: Consumer Control (Home, Volume, etc.)
    private static final byte[] HID_REPORT_DESCRIPTOR = new byte[]{
            // Keyboard (Report ID 1)
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            (byte) 0xA1, 0x01, // Collection (Application)
            (byte) 0x85, 0x01, //   Report ID (1)
            0x05, 0x07,        //   Usage Page (Key Codes)
            0x19, (byte) 0xE0, //   Usage Minimum (224 - Left Control)
            0x29, (byte) 0xE7, //   Usage Maximum (231 - Right GUI)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte) 0x95, 0x08, //   Report Count (8)
            (byte) 0x81, 0x02, //   Input (Data, Variable, Absolute)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x95, 0x01, //   Report Count (1)
            (byte) 0x81, 0x01, //   Input (Constant)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, 0x65,        //   Usage Maximum (101)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x65,        //   Logical Maximum (101)
            0x75, 0x08,        //   Report Size (8)
            (byte) 0x95, 0x06, //   Report Count (6)
            (byte) 0x81, 0x00, //   Input (Data, Array)
            (byte) 0xC0,       // End Collection

            // Consumer Control (Report ID 2)
            0x05, 0x0C,        // Usage Page (Consumer)
            0x09, 0x01,        // Usage (Consumer Control)
            (byte) 0xA1, 0x01, // Collection (Application)
            (byte) 0x85, 0x02, //   Report ID (2)
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, (byte) 0xFF, 0x03, // Logical Maximum (0x03FF)
            0x19, 0x00,        //   Usage Minimum (0)
            0x2A, (byte) 0xFF, 0x03, // Usage Maximum (0x03FF)
            0x75, 0x10,        //   Report Size (16)
            (byte) 0x95, 0x01, //   Report Count (1)
            (byte) 0x81, 0x00, //   Input (Data, Array)
            (byte) 0xC0        // End Collection
    };

    // Keyboard Scancodes
    public static final byte KEY_UP = 0x52;
    public static final byte KEY_DOWN = 0x51;
    public static final byte KEY_LEFT = 0x50;
    public static final byte KEY_RIGHT = 0x4F;
    public static final byte KEY_ENTER = 0x28; // OK
    public static final byte KEY_ESCAPE = 0x29; // Back
    public static final byte KEY_BACKSPACE = 0x2A;
    public static final byte KEY_1 = 0x1E;
    public static final byte KEY_2 = 0x1F;
    public static final byte KEY_3 = 0x20;
    public static final byte KEY_4 = 0x21;
    public static final byte KEY_5 = 0x22;
    public static final byte KEY_6 = 0x23;
    public static final byte KEY_7 = 0x24;
    public static final byte KEY_8 = 0x25;
    public static final byte KEY_9 = 0x26;
    public static final byte KEY_0 = 0x27;

    // Consumer Usage Codes (16-bit)
    public static final int CONSUMER_HOME = 0x0223; // AC Home
    public static final int CONSUMER_BACK = 0x0224; // AC Back
    public static final int CONSUMER_VOL_UP = 0x00E9;
    public static final int CONSUMER_VOL_DOWN = 0x00EA;
    public static final int CONSUMER_MUTE = 0x00E2;

    private final Context context;
    private final StatusListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice hidDevice;
    private BluetoothDevice connectedDevice;
    private boolean isRegistered = false;

    public BluetoothHidController(Context context, StatusListener listener) {
        this.context = context;
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void init() {
        if (bluetoothAdapter == null) {
            listener.onError("设备不支持蓝牙");
            return;
        }

        bluetoothAdapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = (BluetoothHidDevice) proxy;
                    registerHidApp();
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null;
                    isRegistered = false;
                    connectedDevice = null;
                    mainHandler.post(() -> listener.onHidReady(false));
                }
            }
        }, BluetoothProfile.HID_DEVICE);
    }

    private void registerHidApp() {
        if (hidDevice == null) return;

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "TiVo Remote",
                "Android TV Bluetooth Remote",
                "TiVoRemote",
                BluetoothHidDevice.SUBCLASS2_REMOTE_CONTROL,
                HID_REPORT_DESCRIPTOR
        );

        BluetoothHidDeviceAppQosSettings qos = new BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        );

        hidDevice.registerApp(sdp, null, qos, Executors.newCachedThreadPool(), new BluetoothHidDevice.Callback() {
            @Override
            public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                isRegistered = registered;
                Log.d(TAG, "onAppStatusChanged registered=" + registered);
                mainHandler.post(() -> listener.onHidReady(registered));
            }

            @Override
            public void onConnectionStateChanged(BluetoothDevice device, int state) {
                Log.d(TAG, "onConnectionStateChanged state=" + state);
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = device;
                    mainHandler.post(() -> listener.onDeviceConnected(device));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevice = null;
                    mainHandler.post(listener::onDeviceDisconnected);
                }
            }
        });
    }

    public boolean isConnected() {
        return hidDevice != null && connectedDevice != null;
    }

    public void connect(BluetoothDevice device) {
        if (hidDevice != null && device != null) {
            hidDevice.connect(device);
        }
    }

    public void disconnect() {
        if (hidDevice != null && connectedDevice != null) {
            hidDevice.disconnect(connectedDevice);
        }
    }

    public void sendKeyboardKey(byte scancode) {
        if (!isConnected()) {
            Log.w(TAG, "HID not connected, cannot send key");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Key press
                byte[] pressReport = new byte[]{0x00, 0x00, scancode, 0, 0, 0, 0, 0};
                hidDevice.sendReport(connectedDevice, 1, pressReport);
                Thread.sleep(15);
                // Key release
                byte[] releaseReport = new byte[]{0x00, 0x00, 0, 0, 0, 0, 0, 0};
                hidDevice.sendReport(connectedDevice, 1, releaseReport);
            } catch (Exception e) {
                Log.e(TAG, "sendKeyboardKey failed", e);
            }
        });
    }

    public void sendConsumerKey(int usageCode) {
        if (!isConnected()) {
            Log.w(TAG, "HID not connected, cannot send consumer key");
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Key press
                byte[] pressReport = new byte[]{
                        (byte) (usageCode & 0xFF),
                        (byte) ((usageCode >> 8) & 0xFF)
                };
                hidDevice.sendReport(connectedDevice, 2, pressReport);
                Thread.sleep(15);
                // Key release
                byte[] releaseReport = new byte[]{0x00, 0x00};
                hidDevice.sendReport(connectedDevice, 2, releaseReport);
            } catch (Exception e) {
                Log.e(TAG, "sendConsumerKey failed", e);
            }
        });
    }

    public void cleanup() {
        if (hidDevice != null) {
            if (isRegistered) {
                hidDevice.unregisterApp();
            }
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        }
    }
}
