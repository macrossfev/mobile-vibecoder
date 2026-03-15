# MobileVibeCoder

一款 Android 平台的 SSH 服务器管理工具，提供终端模拟、服务器监控和远程命令执行功能。

## 功能特性

### SSH 连接管理
- 支持密码认证和密钥认证
- 支持 Ed25519、RSA 2048、RSA 4096 密钥生成
- 服务器配置的增删改查

### 终端模拟
- 基于 xterm.js 的终端渲染
- 支持方向键、Tab、Esc、Ctrl+C、Ctrl+D
- 自定义快捷键 (F1-F8)
- 命令历史记录
- 滑动球手势滚动（顺时针/逆时针画圈）
- 快速输入按钮

### 服务器监控
- 实时 CPU、内存、磁盘使用率
- 系统运行时间和负载
- 快捷命令执行
- 自动刷新 (5秒间隔)

### 其他功能
- 终端字体大小调整
- 保持屏幕常亮
- 横竖屏自适应
- 深色主题

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| 构建工具 | Gradle 8.3.0 |
| 最低SDK | Android 8.0 (API 26) |
| 目标SDK | Android 14 (API 34) |
| SSH库 | JSch 0.2.18 (mwiede fork) |
| 加密库 | Bouncy Castle 1.77 |
| 协程 | Kotlinx Coroutines 1.7.3 |
| 网络 | OkHttp 4.12.0 |
| JSON | Gson 2.10.1 |
| 导航 | AndroidX Navigation 2.7.6 |
| UI组件 | Material Design 3 |

## 安装指南

### 环境要求
- Android Studio Hedgehog 或更高版本
- JDK 17+
- Android SDK (API 26+)

### 构建步骤

```bash
# 克隆项目
git clone <repository-url>
cd mobile-vibecoder

# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 清理构建
./gradlew clean
```

### 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | SSH 网络连接 |
| ACCESS_NETWORK_STATE | 网络状态检测 |
| WAKE_LOCK | 长时间操作保持唤醒 |
| VIBRATE | 通知振动反馈 |

## 使用说明

### 添加服务器

1. 点击主界面右下角的 **+** 按钮
2. 填写服务器信息：
   - 名称：服务器别名
   - 主机：服务器IP或域名
   - 端口：SSH端口（默认22）
   - 用户名：登录用户名
   - 认证方式：密码或密钥
3. 点击保存

### 终端操作

- **方向键**：用于命令历史导航和光标移动
- **快捷键**：F1-F3，长按可自定义功能（F1-F8, Ctrl+C/D/Z, Tab）
- **快速输入**：点击快1/快2/快3按钮插入预设命令，长按可编辑
- **滑动球**：拖动定位，顺时针画圈向下滚动，逆时针向上滚动，长按调整透明度
- **Esc/Del**：发送 Esc 键和删除键

### 服务器监控

1. 在服务器列表中点击服务器卡片右侧的 **监控** 按钮
2. 查看实时状态：
   - CPU 使用率
   - 内存使用情况
   - 磁盘使用情况
   - 系统运行时间
   - 系统负载

### 密钥管理

1. 进入 **设置** 页面
2. 点击 **生成密钥对**
3. 选择密钥类型：Ed25519（推荐）、RSA 2048、RSA 4096
4. 生成的公钥可复制到服务器的 `~/.ssh/authorized_keys`

## 项目结构

```
app/src/main/java/com/vibecoder/
├── VibeCoderApp.kt              # Application 类
├── data/
│   ├── Models.kt                # 数据模型
│   └── PreferencesManager.kt    # 数据存储
├── ssh/
│   ├── SSHManager.kt            # SSH 连接管理
│   └── SSHKeyGenerator.kt       # 密钥生成器
├── ui/
│   ├── MainActivity.kt          # 主 Activity
│   ├── ServerListFragment.kt    # 服务器列表
│   ├── ServerAdapter.kt         # 列表适配器
│   ├── TerminalFragment.kt      # 终端页面
│   ├── MonitorFragment.kt       # 监控页面
│   └── SettingsFragment.kt      # 设置页面
└── voice/
    ├── VoiceInputManager.kt     # 语音输入管理
    └── AICommandInterpreter.kt  # AI 命令解释器
```

## 依赖库

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

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0.0 | 2026-03-14 | 初始发布版本 |

## 开发计划

- [ ] 终端复制/粘贴功能
- [ ] 多服务器同时连接
- [ ] SFTP 文件传输
- [ ] 端口转发功能
- [ ] 深色模式支持
- [ ] 生物识别锁定

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request。