# Mobile VibeCoder 开发文档

## 项目概述

Mobile VibeCoder 是一个 Android SSH 终端管理应用，支持语音控制和服务器监控。

## 架构概览

```
app/src/main/java/com/vibecoder/
├── VibeCoderApp.kt              # Application类
├── data/                        # 数据层
│   ├── Models.kt                # 数据模型
│   └── PreferencesManager.kt    # SharedPreferences封装
├── ssh/                         # SSH核心功能
│   └── SSHManager.kt            # SSH连接管理 + 密钥生成
├── voice/                       # 语音功能
│   └── VoiceInputManager.kt     # 语音输入管理
└── ui/                          # UI层
    ├── MainActivity.kt          # 主Activity
    ├── ServerListFragment.kt    # 服务器列表
    ├── ServerAdapter.kt         # 服务器列表适配器
    ├── TerminalFragment.kt      # 终端界面
    ├── VoiceTerminalFragment.kt # 语音终端
    ├── MonitorFragment.kt       # 服务器监控
    ├── SettingsFragment.kt      # 设置页面
    └── widget/
        └── VirtualKeyboardView.kt # 虚拟键盘组件
```

## 文件统计

| 类别 | 数量 |
|------|------|
| Kotlin源文件 | 13 |
| XML布局 | 9 |
| Drawable资源 | 12 |
| 其他XML | 11 |
| **总计** | **45** |

## 核心模块说明

### 1. SSH模块 (ssh/)

**SSHManager.kt** - SSH核心（单例模式）
- 连接管理（密码/密钥认证）
- Shell会话
- 命令执行
- 密钥生成（Ed25519/RSA）

### 2. 数据层 (data/)

**Models.kt** - 数据模型
```kotlin
data class ServerConfig(...)    // 服务器配置
data class QuickCommand(...)    // 快捷命令
data class CommandHistory(...)   // 命令历史
```

**PreferencesManager.kt** - 持久化存储
- 服务器列表
- 快捷命令/快捷键
- 用户设置

### 3. UI模块 (ui/)

| 文件 | 功能 | 行数 |
|------|------|------|
| TerminalFragment.kt | 主终端界面 | 864 |
| ServerListFragment.kt | 服务器管理 | 321 |
| VoiceTerminalFragment.kt | 语音终端 | 467 |
| MonitorFragment.kt | 服务器监控 | 230 |
| SettingsFragment.kt | 设置页面 | 112 |
| ServerAdapter.kt | 列表适配器 | 74 |
| MainActivity.kt | 主入口 | 18 |

## 关键功能实现

### SSH连接持久化

SSHManager采用单例模式：
- 屏幕旋转不中断
- 页面切换复用连接
- 短时间息屏恢复

### 终端界面

- WebView + xterm.js渲染
- L型透明按键布局
- 自定义快捷命令（点击执行，长按编辑）
- 自定义快捷键（最多3键组合）

### 快捷命令

```kotlin
// 点击执行
val command = prefsManager.getQuickCommand()
if (!command.isNullOrBlank()) executeCommand(command)

// 长按编辑
prefsManager.saveQuickCommand(newCommand)
```

## 依赖库

```gradle
implementation 'com.jcraft:jsch:0.1.55'
implementation 'com.google.code.gson:gson:2.10.1'
implementation 'com.google.android.material:material:1.11.0'
```

## 构建

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```