# mobile-vibecoder 开发文档

## 项目信息

- **项目名称**: MobileVibeCoder
- **创建日期**: 2026-03-14
- **当前版本**: v1.0.0
- **包名**: com.vibecoder
- **最低SDK**: Android 8.0 (API 26)
- **目标SDK**: Android 14 (API 34)

## 项目概述

MobileVibeCoder 是一款 Android 平台的 SSH 服务器管理工具，提供终端模拟、服务器监控和远程命令执行功能。

### 核心功能

1. **SSH 连接管理**
   - 支持密码认证和密钥认证
   - 支持 Ed25519、RSA 2048、RSA 4096 密钥生成
   - 服务器配置的增删改查

2. **终端模拟**
   - 基于 xterm.js 的终端渲染
   - 支持方向键、Tab、Esc、Ctrl+C、Ctrl+D
   - 自定义快捷键 (F1-F3)
   - 命令历史记录

3. **服务器监控**
   - 实时 CPU、内存、磁盘使用率
   - 系统运行时间和负载
   - 快捷命令执行
   - 自动刷新 (5秒间隔)

4. **设置功能**
   - 终端字体大小调整
   - 保持屏幕常亮
   - API 密钥和端点配置

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| 构建工具 | Gradle 8.3.0 |
| 最小SDK | API 26 (Android 8.0) |
| 目标SDK | API 34 (Android 14) |
| SSH库 | JSch 0.2.18 (mwiede fork) |
| 加密库 | Bouncy Castle 1.77 |
| 协程 | Kotlinx Coroutines 1.7.3 |
| 网络 | OkHttp 4.12.0 |
| JSON | Gson 2.10.1 |
| 导航 | AndroidX Navigation 2.7.6 |
| UI组件 | Material Design 3 |

---

## 项目结构

```
app/src/main/java/com/vibecoder/
├── VibeCoderApp.kt              # Application 类，通知渠道初始化
├── data/
│   ├── Models.kt                # 数据模型 (ServerConfig, ServerStatus, QuickCommand, CommandHistory)
│   └── PreferencesManager.kt    # SharedPreferences 数据存储
├── ssh/
│   ├── SSHManager.kt            # SSH 连接和 Shell 管理
│   └── SSHKeyGenerator.kt       # SSH 密钥生成器
├── ui/
│   ├── MainActivity.kt          # 主 Activity
│   ├── ServerListFragment.kt    # 服务器列表页面
│   ├── ServerAdapter.kt         # 服务器列表适配器
│   ├── TerminalFragment.kt      # 终端页面
│   ├── MonitorFragment.kt       # 监控页面
│   └── SettingsFragment.kt      # 设置页面
└── voice/
    ├── VoiceInputManager.kt     # 语音输入管理
    └── AICommandInterpreter.kt  # AI 命令解释器

app/src/main/res/
├── layout/
│   ├── activity_main.xml        # 主布局
│   ├── fragment_server_list.xml # 服务器列表布局
│   ├── fragment_terminal.xml    # 终端布局
│   ├── fragment_terminal.xml (land) # 横屏终端布局
│   ├── fragment_monitor.xml     # 监控布局
│   ├── fragment_settings.xml    # 设置布局
│   ├── dialog_add_server.xml    # 添加服务器对话框
│   └── item_server.xml          # 服务器列表项
├── drawable/                    # 图标资源
├── values/
│   ├── strings.xml              # 字符串资源
│   ├── colors.xml               # 颜色资源
│   └── themes.xml               # 主题配置
└── navigation/
    └── nav_graph.xml            # 导航图

app/src/main/assets/
└── terminal.html                # xterm.js 终端 HTML
```

---

## 核心模块说明

### 1. SSHManager (`ssh/SSHManager.kt`)

SSH 连接管理核心类，提供：
- `connect(config)`: 建立 SSH 连接
- `executeCommand(command)`: 执行单条命令
- `openShell()`: 打开交互式 Shell
- `writeToShellAsync(command)`: 向 Shell 发送数据
- `sendCtrlC()`, `sendCtrlD()`: 发送控制序列
- `fetchServerStatus()`: 获取服务器状态信息
- `resizePty(cols, rows)`: 调整终端大小

### 2. SSHKeyGenerator (`ssh/SSHKeyGenerator.kt`)

SSH 密钥生成工具：
- 支持 Ed25519 (推荐)、RSA 2048、RSA 4096
- 自动生成 OpenSSH 格式公钥和私钥
- 计算 SHA256 指纹

### 3. PreferencesManager (`data/PreferencesManager.kt`)

数据持久化管理：
- 服务器列表存储
- 命令历史记录 (每服务器独立，最多100条)
- 用户设置 (字体大小、屏幕常亮等)
- 自定义快捷键配置

### 4. TerminalFragment (`ui/TerminalFragment.kt`)

终端界面核心功能：
- WebView + xterm.js 终端渲染
- 批量输出缓冲优化 (50ms 间隔)
- 横竖屏切换 PTY 大小调整
- 命令历史导航 (上下键)

---

## 开发日志

### 2026-03-14

- 项目初始化
- 实现 SSH 连接和终端功能
- 添加 Ed25519 密钥支持
- 实现 xterm.js 终端模拟
- 添加服务器监控功能
- 优化横屏布局
- 添加方向键和自定义快捷键

### 2026-03-14 (更新)

**功能优化：**
- 移除语音功能（国内手机兼容性问题）
- 移除顶部标题栏，最大化终端显示区域
- 添加方向键 (↑↓←→) 用于终端导航
- 添加3个可自定义快捷键 (长按编辑)
- 黑色沉浸式主题，状态栏和导航栏全黑

**布局优化：**
- 竖屏：快捷键栏 + 输入框(左) + 方向键(右)
- 横屏：输入框(左) + 所有控制按钮(右) 单行布局
- 整体高度压缩至 26-28dp
- 字体缩小至 9-10sp

**Bug修复：**
- 修复横竖屏切换导致SSH重连的问题
  - AndroidManifest 添加 configChanges 属性
  - Fragment 使用 retainInstance = true
  - 实现 PTY resize 动态调整

---

## 待办事项

- [ ] 终端复制/粘贴功能
- [ ] 多服务器同时连接
- [ ] SFTP 文件传输
- [ ] 端口转发功能
- [ ] 深色模式支持
- [ ] 生物识别锁定

---

## 问题记录

| 日期 | 问题 | 解决方案 |
|------|------|----------|
| 2026-03-14 | Ed25519 密钥在某些设备上生成失败 | 自动回退到 RSA 2048 |
| 2026-03-14 | 横竖屏切换导致终端输出错乱 | 实现 PTY resize 功能 |
| 2026-03-14 | 终端输出卡顿 | 批量缓冲输出，50ms 间隔刷新 |

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0.0 | 2026-03-14 | 初始发布版本 |

---

## 依赖库说明

### JSch (mwiede fork)
原版 JSch 已停止维护，使用社区维护的 mwiede 版本，支持 Ed25519 等现代加密算法。

```gradle
implementation 'com.github.mwiede:jsch:0.2.18'
```

### Bouncy Castle
用于加密操作，支持 Ed25519 密钥生成。

```gradle
implementation 'org.bouncycastle:bcprov-jdk18on:1.77'
```

---

## 构建说明

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 清理构建
./gradlew clean
```

---

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | SSH 网络连接 |
| ACCESS_NETWORK_STATE | 网络状态检测 |
| WAKE_LOCK | 长时间操作保持唤醒 |
| VIBRATE | 通知振动反馈 |