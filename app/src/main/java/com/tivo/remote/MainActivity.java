package com.tivo.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String PREF_NAME = "tivo_remote_settings";
    private static final String PREF_IP = "tv_ip";
    private static final String DEFAULT_IP = "192.168.2.223";

    private boolean isWifiMode = true;
    private String tvIp = DEFAULT_IP;

    private TextView tabWifi;
    private TextView tabBluetooth;
    private View viewStatusDot;
    private TextView tvStatus;
    private Button btnAction;

    private AdbProtocol adbProtocol;
    private BluetoothHidController btController;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        tvIp = prefs.getString(PREF_IP, DEFAULT_IP);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initViews();
        initAdb();
        initBluetooth();

        checkPermissions();

        // Connect Wi-Fi by default
        switchMode(true);
    }

    private void initViews() {
        tabWifi = findViewById(R.id.tab_wifi);
        tabBluetooth = findViewById(R.id.tab_bluetooth);
        viewStatusDot = findViewById(R.id.view_status_dot);
        tvStatus = findViewById(R.id.tv_status);
        btnAction = findViewById(R.id.btn_action);

        tabWifi.setOnClickListener(v -> switchMode(true));
        tabBluetooth.setOnClickListener(v -> switchMode(false));

        btnAction.setOnClickListener(v -> {
            vibrate();
            if (isWifiMode) {
                showIpDialog();
            } else {
                openBluetoothSettings();
            }
        });

        // D-Pad
        setKeyButton(R.id.btn_up, 19, BluetoothHidController.KEY_UP, false);
        setKeyButton(R.id.btn_down, 20, BluetoothHidController.KEY_DOWN, false);
        setKeyButton(R.id.btn_left, 21, BluetoothHidController.KEY_LEFT, false);
        setKeyButton(R.id.btn_right, 22, BluetoothHidController.KEY_RIGHT, false);
        setKeyButton(R.id.btn_ok, 66, BluetoothHidController.KEY_ENTER, false);

        // Function Buttons
        setKeyButton(R.id.btn_back, 4, BluetoothHidController.KEY_ESCAPE, false);
        setKeyButton(R.id.btn_home, 3, BluetoothHidController.CONSUMER_HOME, true);
        setKeyButton(R.id.btn_menu, 82, BluetoothHidController.KEY_ESCAPE, false);

        // Volume Buttons
        setKeyButton(R.id.btn_vol_down, 25, BluetoothHidController.CONSUMER_VOL_DOWN, true);
        setKeyButton(R.id.btn_vol_up, 24, BluetoothHidController.CONSUMER_VOL_UP, true);

        // Numbers 0 - 9
        setKeyButton(R.id.btn_1, 8, BluetoothHidController.KEY_1, false);
        setKeyButton(R.id.btn_2, 9, BluetoothHidController.KEY_2, false);
        setKeyButton(R.id.btn_3, 10, BluetoothHidController.KEY_3, false);
        setKeyButton(R.id.btn_4, 11, BluetoothHidController.KEY_4, false);
        setKeyButton(R.id.btn_5, 12, BluetoothHidController.KEY_5, false);
        setKeyButton(R.id.btn_6, 13, BluetoothHidController.KEY_6, false);
        setKeyButton(R.id.btn_7, 14, BluetoothHidController.KEY_7, false);
        setKeyButton(R.id.btn_8, 15, BluetoothHidController.KEY_8, false);
        setKeyButton(R.id.btn_9, 16, BluetoothHidController.KEY_9, false);
        setKeyButton(R.id.btn_0, 7, BluetoothHidController.KEY_0, false);
        setKeyButton(R.id.btn_del, 67, BluetoothHidController.KEY_BACKSPACE, false);

        // TiVo specific button
        findViewById(R.id.btn_tivo).setOnClickListener(v -> {
            vibrate();
            if (isWifiMode) {
                // Send intent to open TiVo Stream or Home
                adbProtocol.sendKey(3);
            } else {
                btController.sendConsumerKey(BluetoothHidController.CONSUMER_HOME);
            }
        });
    }

    private void setKeyButton(int resId, int adbKeycode, int btCode, boolean isConsumer) {
        View btn = findViewById(resId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            vibrate();
            if (isWifiMode) {
                if (adbProtocol != null) {
                    adbProtocol.sendKey(adbKeycode);
                }
            } else {
                if (btController != null) {
                    if (isConsumer) {
                        btController.sendConsumerKey(btCode);
                    } else {
                        btController.sendKeyboardKey((byte) btCode);
                    }
                }
            }
        });
    }

    private void switchMode(boolean wifi) {
        isWifiMode = wifi;
        if (isWifiMode) {
            tabWifi.setBackgroundResource(R.drawable.bg_tab_active);
            tabWifi.setTextColor(getResources().getColor(R.color.text_primary));
            tabBluetooth.setBackgroundResource(R.drawable.bg_tab_inactive);
            tabBluetooth.setTextColor(getResources().getColor(R.color.text_secondary));

            btnAction.setText("设置IP");
            updateWifiStatus();
            if (adbProtocol != null && !adbProtocol.isConnected()) {
                adbProtocol.connect(tvIp, 5555);
            }
        } else {
            tabBluetooth.setBackgroundResource(R.drawable.bg_tab_active);
            tabBluetooth.setTextColor(getResources().getColor(R.color.text_primary));
            tabWifi.setBackgroundResource(R.drawable.bg_tab_inactive);
            tabWifi.setTextColor(getResources().getColor(R.color.text_secondary));

            btnAction.setText("配对设备");
            updateBluetoothStatus();
        }
    }

    private void initAdb() {
        AdbCrypto crypto = AdbCrypto.loadOrGenerate(this);
        adbProtocol = new AdbProtocol(crypto, new AdbProtocol.ConnectionListener() {
            @Override
            public void onConnecting() {
                if (isWifiMode) {
                    tvStatus.setText("正在连接 " + tvIp + "...");
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_yellow));
                }
            }

            @Override
            public void onConnected() {
                if (isWifiMode) {
                    tvStatus.setText("已连接 (Wi-Fi): " + tvIp);
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_green));
                    Toast.makeText(MainActivity.this, "Wi-Fi 模式已连接", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onAwaitingAuthorization() {
                if (isWifiMode) {
                    tvStatus.setText("请在电视屏幕上勾选【一律允许】并确认");
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_yellow));
                }
            }

            @Override
            public void onDisconnected(String reason) {
                if (isWifiMode) {
                    tvStatus.setText("Wi-Fi 未连接 (" + (reason != null ? reason : "已断开") + ")");
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_red));
                }
            }
        });
    }

    private void initBluetooth() {
        btController = new BluetoothHidController(this, new BluetoothHidController.StatusListener() {
            @Override
            public void onHidReady(boolean isRegistered) {
                if (!isWifiMode) {
                    updateBluetoothStatus();
                }
            }

            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                if (!isWifiMode) {
                    updateBluetoothStatus();
                    Toast.makeText(MainActivity.this, "蓝牙已连接: " + (device.getName() != null ? device.getName() : device.getAddress()), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isWifiMode) {
                    updateBluetoothStatus();
                }
            }

            @Override
            public void onError(String message) {
                if (!isWifiMode) {
                    tvStatus.setText("蓝牙错误: " + message);
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_red));
                }
            }
        });
        btController.init();
    }

    private void updateWifiStatus() {
        if (adbProtocol != null && adbProtocol.isConnected()) {
            tvStatus.setText("已连接 (Wi-Fi): " + tvIp);
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_green));
        } else {
            tvStatus.setText("Wi-Fi 未连接 (目标: " + tvIp + ")");
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_red));
        }
    }

    private void updateBluetoothStatus() {
        if (btController != null && btController.isConnected()) {
            tvStatus.setText("已连接 (蓝牙 HID)");
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_green));
        } else {
            tvStatus.setText("蓝牙广播就绪，请在电视【添加配件】配对");
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_yellow));
        }
    }

    private void showIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("配置电视 IP 地址");

        final EditText input = new EditText(this);
        input.setText(tvIp);
        input.setSelection(tvIp.length());
        builder.setView(input);

        builder.setPositiveButton("连接", (dialog, which) -> {
            String newIp = input.getText().toString().trim();
            if (!newIp.isEmpty()) {
                tvIp = newIp;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_IP, tvIp).apply();
                if (adbProtocol != null) {
                    adbProtocol.connect(tvIp, 5555);
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void openBluetoothSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "请在手机系统设置中打开蓝牙", Toast.LENGTH_SHORT).show();
        }
    }

    private void vibrate() {
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(25);
            }
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adbProtocol != null) {
            adbProtocol.disconnect();
        }
        if (btController != null) {
            btController.cleanup();
        }
    }
}
