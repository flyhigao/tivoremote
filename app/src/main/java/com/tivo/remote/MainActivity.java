package com.tivo.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import java.util.List;

@SuppressLint("MissingPermission")
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
                showBluetoothDeviceDialog();
            }
        });

        // D-Pad: OK is DPAD_CENTER (23) for standard TV interaction and soft keyboard clicking
        setKeyButton(R.id.btn_up, 19, BluetoothHidController.KEY_UP, false);
        setKeyButton(R.id.btn_down, 20, BluetoothHidController.KEY_DOWN, false);
        setKeyButton(R.id.btn_left, 21, BluetoothHidController.KEY_LEFT, false);
        setKeyButton(R.id.btn_right, 22, BluetoothHidController.KEY_RIGHT, false);
        setKeyButton(R.id.btn_ok, 23, BluetoothHidController.KEY_ENTER, false);

        // Function Buttons
        setKeyButton(R.id.btn_back, 4, BluetoothHidController.KEY_ESCAPE, false);
        setKeyButton(R.id.btn_home, 3, BluetoothHidController.CONSUMER_HOME, true);
        setKeyButton(R.id.btn_menu, 82, BluetoothHidController.KEY_ESCAPE, false);

        // Volume Buttons
        setKeyButton(R.id.btn_vol_down, 25, BluetoothHidController.CONSUMER_VOL_DOWN, true);
        setKeyButton(R.id.btn_vol_up, 24, BluetoothHidController.CONSUMER_VOL_UP, true);

        // Text Input Dialog
        View btnInput = findViewById(R.id.btn_input_text);
        if (btnInput != null) {
            btnInput.setOnClickListener(v -> {
                vibrate();
                showTextInputDialog();
            });
        }

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
                if (adbProtocol != null) {
                    adbProtocol.sendKey(3);
                }
            } else {
                if (btController != null) {
                    btController.sendConsumerKey(BluetoothHidController.CONSUMER_HOME);
                }
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
                    if (!adbProtocol.isConnected()) {
                        Toast.makeText(this, "正在重新连接 Wi-Fi: " + tvIp, Toast.LENGTH_SHORT).show();
                        adbProtocol.connect(tvIp, 5555);
                    }
                    adbProtocol.sendKey(adbKeycode);
                }
            } else {
                if (btController != null) {
                    if (!btController.isConnected()) {
                        Toast.makeText(this, "蓝牙未连接，请先点击【连接电视】", Toast.LENGTH_SHORT).show();
                        showBluetoothDeviceDialog();
                        return;
                    }
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

            btnAction.setText("连接电视");
            updateBluetoothStatus();
            tryAutoConnectBluetooth();
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
                    Toast.makeText(MainActivity.this, "Wi-Fi 模式已成功连接电视！", Toast.LENGTH_SHORT).show();
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
                    if (isRegistered) {
                        tryAutoConnectBluetooth();
                    }
                }
            }

            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                if (!isWifiMode) {
                    updateBluetoothStatus();
                    String devName = device.getName() != null ? device.getName() : device.getAddress();
                    Toast.makeText(MainActivity.this, "蓝牙已连接: " + devName, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isWifiMode) {
                    updateBluetoothStatus();
                    Toast.makeText(MainActivity.this, "蓝牙已断开", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String message) {
                if (!isWifiMode) {
                    tvStatus.setText(message);
                    viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_red));
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
        btController.init();
    }

    private void tryAutoConnectBluetooth() {
        if (btController == null || btController.isConnected()) return;
        List<BluetoothDevice> bonded = btController.getBondedDevices();
        for (BluetoothDevice d : bonded) {
            String name = d.getName();
            if (name != null && (name.contains("TiVo") || name.contains("Stream") || name.contains("TV"))) {
                btController.connect(d);
                break;
            }
        }
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
            BluetoothDevice dev = btController.getConnectedDevice();
            String name = (dev != null && dev.getName() != null) ? dev.getName() : "TiVo";
            tvStatus.setText("已连接 (蓝牙 HID): " + name);
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_green));
        } else {
            tvStatus.setText("蓝牙未连接，请点击【连接电视】");
            viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_yellow));
        }
    }

    private void showTextInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("手机打字输入到电视");
        builder.setMessage("支持英文 / 数字 / 符号（订阅链接、拼音搜索、Wi-Fi 密码等）\n"
                + "可直接长按粘贴，或点【粘贴】填入手机剪贴板内容\n"
                + "发送时会自动规避电视端中文输入法与首字母自动补全\n"
                + "注：ADB 注入无法输入中文汉字，中文内容会被电视忽略");

        final EditText input = new EditText(this);
        input.setHint("输入英文、拼音搜索词 (如 qqmusic)");
        input.setSingleLine(true);
        builder.setView(input);

        // 打开即自动填入手机剪贴板内容，方便直接复制订阅链接/搜索词后一键发送
        String clip = getClipboardText();
        if (!clip.isEmpty()) {
            input.setText(clip);
            input.setSelection(input.getText().length());
        }

        builder.setPositiveButton("发送", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            if (!isWifiMode || adbProtocol == null) {
                Toast.makeText(this, "当前模式不支持打字推送，请切换到 Wi-Fi 模式", Toast.LENGTH_SHORT).show();
                return;
            }
            int sent = adbProtocol.sendText(text);
            if (sent < text.length()) {
                Toast.makeText(this, "文本过长，仅发送前 " + sent + " 个字符", Toast.LENGTH_LONG).show();
            } else if (!isAscii(text)) {
                Toast.makeText(this, "已发送（中文汉字无法注入电视，建议用拼音）", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已发送: " + text, Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.setNeutralButton("粘贴", null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String t = getClipboardText();
            if (t.isEmpty()) {
                Toast.makeText(this, "手机剪贴板为空", Toast.LENGTH_SHORT).show();
                return;
            }
            input.setText(t);
            input.setSelection(input.getText().length());
        }));
        dialog.show();
    }

    /** 读取手机剪贴板文本（弹窗在前台，Android 10+ 允许读取） */
    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return "";
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return "";
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            return cs == null ? "" : cs.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
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

    private void showBluetoothDeviceDialog() {
        if (btController == null) return;
        List<BluetoothDevice> bondedDevices = btController.getBondedDevices();

        if (bondedDevices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("未发现已配对设备")
                    .setMessage("请先在电视系统的【设置 -> 遥控器与配件 -> 添加配件】中，搜索并连接你的手机。")
                    .setPositiveButton("打开手机蓝牙设置", (d, w) -> openBluetoothSettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        String[] names = new String[bondedDevices.size() + 1];
        int defaultIndex = -1;
        for (int i = 0; i < bondedDevices.size(); i++) {
            BluetoothDevice d = bondedDevices.get(i);
            String name = d.getName() != null ? d.getName() : d.getAddress();
            names[i] = name;
            if (name.contains("TiVo") || name.contains("TV") || name.contains("Stream")) {
                defaultIndex = i;
            }
        }
        names[bondedDevices.size()] = "➕ 配对新设备 (进入系统蓝牙设置)";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择要连接的电视蓝牙");
        builder.setItems(names, (dialog, which) -> {
            if (which < bondedDevices.size()) {
                BluetoothDevice selected = bondedDevices.get(which);
                tvStatus.setText("正在连接蓝牙: " + names[which] + "...");
                viewStatusDot.setBackgroundColor(getResources().getColor(R.color.status_yellow));
                boolean initiated = btController.connect(selected);
                if (initiated) {
                    Toast.makeText(this, "正在建立蓝牙 HID 遥控连接...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "连接请求已发送", Toast.LENGTH_SHORT).show();
                }
            } else {
                openBluetoothSettings();
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
