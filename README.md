# TiVoRemote (电视遥控器)

专为 **TiVo Stream 4K** 及通用 Android TV / 电视盒子设计的轻量级手机遥控器 APP。

内置 **Wi-Fi (局域网 ADB)** 与 **蓝牙 (Bluetooth HID)** 双通信模式，纯原生 Java 开发，体积仅约 35 KB，界面简洁无广告，支持换台必备的完整 0-9 数字小键盘。

---

## ✨ 功能特性

- **双通信模式无缝切换**：
  - **Wi-Fi 局域网模式**：自动或手动指定电视 IP（如 `192.168.2.223:5555`），免蓝牙配对，开机即控。
  - **蓝牙 HID 模式**：基于 Android 官方 `BluetoothHidDevice` API，将手机伪装成实体蓝牙键盘/遥控器，断网可用，超低延迟。
- **全功能遥控按键**：
  - **D-Pad 导航盘**：上、下、左、右、居中 OK 确认。
  - **系统常用控制**：主页 (Home)、返回 (Back)、菜单 (Menu)、音量增加 / 减小。
  - **经典 3x4 数字小键盘**：1 ~ 9、0、删除键，专为电视家、IPTV、影视换台设计。
  - **TiVo 专属键**：支持一键唤起 TiVo Stream 官方主界面。
- **触感反馈**：每个按键操作均配有清脆的微弱触感震动反馈（Haptic Feedback）。
- **极致纯净**：无第三方 SDK、无任何商业广告、无后台自启驻留，安装包仅 ~35 KB。

---

## 📱 界面与按键映射

| 功能区域 | 按键 | Wi-Fi 模式键值 | 蓝牙 HID 模式 |
| :--- | :--- | :--- | :--- |
| **导航** | 上 / 下 / 左 / 右 | `KEYCODE_DPAD_*` (19~22) | Keyboard `0x52 / 0x51 / 0x50 / 0x4F` |
| **确认** | OK | `KEYCODE_ENTER` (66) | Keyboard `0x28` (Enter) |
| **系统** | 返回 (Back) | `KEYCODE_BACK` (4) | Keyboard `0x29` (Escape) |
| **系统** | 主页 (Home) | `KEYCODE_HOME` (3) | Consumer `0x0223` (AC Home) |
| **系统** | 菜单 (Menu) | `KEYCODE_MENU` (82) | Keyboard `0x29` |
| **音量** | 音量+ / 音量- | `KEYCODE_VOLUME_*` (24, 25) | Consumer `0x00E9 / 0x00EA` |
| **数字** | 1 ~ 9, 0 | `KEYCODE_0 ~ KEYCODE_9` (7~16) | Keyboard `0x1E ~ 0x27` |
| **编辑** | 删除 (Del) | `KEYCODE_DEL` (67) | Keyboard `0x2A` (Backspace) |

---

## 🛠️ 构建与编译

本项目支持两种编译方式：

### 方式 1：独立脚本编译（无需 Gradle / Android Studio）
确保本地安装有 Android SDK（平台版本 28+）和 JDK 17+：
```bash
bash build.sh
```
执行完毕后将在根目录下生成已签名的 `TiVoRemote.apk`。

### 方式 2：Android Studio / Gradle
直接用 Android Studio 打开本目录，点击 `Build -> Build APK(s)` 即可。

---

## 📖 使用指南

### 1. Wi-Fi 局域网模式
1. 确保手机与电视连接在同一个路由器 Wi-Fi 下。
2. 打开 App，顶部选中 **Wi-Fi 局域网**。
3. 点击顶部状态栏的 **设置IP**，填入你的电视盒子局域网 IP（例如 `192.168.2.223`）。
4. 初次连接时，电视屏幕上会弹出“是否允许 USB 调试？”，使用原装遥控器勾选“一律允许”并点击确认即可。

### 2. 蓝牙 HID 模式
1. 手机打开蓝牙，进入 App 选中 **蓝牙 HID** 模式。
2. 在电视上打开 `设置 -> 遥控器与配件 -> 添加配件`。
3. 搜索到手机名称后点击配对，配对成功后手机即成为标准实体蓝牙遥控器。

---

## 📄 许可证

MIT License
