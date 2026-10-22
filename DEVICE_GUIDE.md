# TiVo Stream 4K 系统优化与故障排除完全指南

本文档记录了针对 **TiVo Stream 4K**（代号 `SEI400TV`，系统 Android 12）的存储深度优化记录，以及彻底解决**开机设置向导卡死（“设备向导无法启动，由 SHELL 来管理”）**的技术根治方案。

---

## 一、系统瘦身与存储深度优化

### 1. 优化背景
- 内部数据分区容量仅 **4.1 GB**。
- 长期使用后可用空间仅剩 **726 MB（占用率 78%）**，每次开机都会频繁弹出“存储空间不足，请清理空间”系统警告。

### 2. 优化措施与收益
| 优化项目 | 占用空间 | 优化措施 | 影响评估 |
| :--- | :--- | :--- | :--- |
| **腾讯系 WNS 调试日志** (`/sdcard/tencent/wns`) | ~577 MB | 彻底删除并部署**文件占位免疫** | 无影响（防日志重生） |
| **QQ音乐 MV 视频缓存** (`/sdcard/qqmusictv`) | ~77 MB | 清空历史离线视频缓存 | 无影响（随时在线加载） |
| **沙发管家残留文件** (`/sdcard/shafa`) | ~59 MB | 删除遗留的安装包与缓存 | 无影响（软件早已卸载） |
| **Google TTS 语音包与更新** (`com.google.android.tts`) | ~414 MB | 清除 320MB 离线发音包，卸载 94MB 更新并挂起 | 无障碍朗读停用，电视观影无影响 |
| **国外预装流媒体** (Prime Video、Play 游戏) | ~40 MB | 清除缓存数据并系统级挂起 (`pm suspend`) | 减少后台常驻与内存占用 |
| **TiVo 广告与遥测服务** (`skywayclient`, `tivoplusplayer`, `sageservice`, `tvbugtracker`) | ~70 MB | 清除数据并挂起后台服务 | 去除开机广告和后台偷跑流量 |

> **优化成效**：可用空间从 **726 MB 提升至 1.8 GB**（可用空间提升 2.5 倍），开机报警彻底消除。

### 3. 特殊防护技术：腾讯 SDK 垃圾日志防再生（文件占位免疫法）
QQ音乐与腾讯系 SDK 会每天在 `/sdcard/tencent/wns/Logs/...` 递归创建日期文件夹并写入大量纯文本网络日志。为防止其在重新安装应用后死灰复燃，采用 Linux 文件系统占位机制：
```bash
# 建立同名 0 字节只读普通文件，阻断创建子目录
mkdir -p /sdcard/tencent
touch /sdcard/tencent/wns
touch /sdcard/tencent/TPush
chmod 444 /sdcard/tencent/wns /sdcard/tencent/TPush
```
**原理**：Linux 文件系统规则中，若路径上已存在同名普通文件，任何 `mkdir` 创建子目录的操作都会被底层强行拒绝（报错 `Not a directory`）。由于 SDK 内部捕获了 I/O 异常，因此应用依然正常运行，但本地日志写入被完全阻断。

---

## 二、“设备向导无法启动，由 SHELL 来管理” 故障根治

### 1. 故障现象
设备重启后，屏幕弹窗提示：
> **应用无法使用**  
> 设备向导无法启动，由 SHELL 所管理。 [确定]

- **问题表现**：电视遥控器无论按方向键、确认键还是返回键，焦点都无法选定该弹窗按钮，导致电视停滞在黑屏弹窗画面；按 Home 键也会被向导拦截。

### 2. 根本原因深度分析
1. **HOME 键被向导劫持**：Google TV 设置向导（`com.google.android.tungsten.setupwraith`）在 `AndroidManifest.xml` 中以 `priority="4"` 注册了 `CATEGORY_HOME`。在初始配置未全部完成前，安卓系统会将向导视为最高优先级桌面。
2. **向导死锁 Bug**：
   - 向导在末尾阶段调用厂商定制组件 `com.google.android.tvsetup.partnercustomizer` 的 `LoadingActivity`。
   - `LoadingActivity` 在初始化时试图连接厂商后台服务器：
     `https://www.evodevicemanager.com/application/skyway-cms-application/app/recommend/getFeatureAppByContentId`
   - **由于 SEI 厂商该远程服务器已经下线停机（HTTP 返回 503）**，导致 `LoadingActivity` 无限挂死在 `Please wait...`，无法正常返回 `RESULT_OK`。
   - 向导未收到成功信号便无法触发自身的退出与自毁逻辑，陷入无限重试死循环。

### 3. 完美根治方案（无需刷机/无需恢复出厂）

通过逆向分析 `LoadingActivity` 的 Dalvik 字节码，发现其在 `updateStatus()` 中监听了全局属性 `one_build_status`，且在值为 `100` 时判定为流程已完毕，自动调用 `setResult(RESULT_OK)` 并 `finish()`。

执行以下 ADB 序列即可彻底闭环该流程并永久禁用向导：

```bash
# 1. 向全局设置注入完成标记（触发向导自动跳过网络死等）
adb shell settings put global one_build_status 100

# 2. 补齐系统级初始化完毕标志
adb shell settings put secure user_setup_complete 1
adb shell settings put secure tv_user_setup_complete 1
adb shell settings put secure pai_selection_page_complete 1
adb shell settings put global device_provisioned 1

# 3. 锁定默认主桌面为系统 Android TV 启动器
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

### 4. 修复后底层状态验证
执行完毕后，向导官方退出流程跑通，系统会将向导主程序自动注销：
```text
adb shell dumpsys package com.google.android.tungsten.setupwraith | grep disabledComponents
  disabledComponents:
    com.google.android.tungsten.setupwraith.MainActivity
```
`MainActivity` 被系统标记为 `disabledComponents` 后，`priority=4` 的拦截彻底消失，后续**无论断电冷启还是热重启，系统都会直达正常 TV 桌面，永不复发**。
