# MobileVibeCoder

一款 Android 平台的 SSH 服务器管理工具，提供终端模拟、服务器监控和语音控制功能。

## 功能特性

### SSH 连接管理
- 支持密码认证和密钥认证
- 支持 Ed25519、RSA 2048、RSA 4096 密钥生成
- 服务器配置的增删改查
- **连接持久化**：屏幕旋转、切屏、短时间息屏不中断连接

### 终端模拟
- 基于 xterm.js 的终端渲染
- **L型透明按键布局**：方向键、查看键
- **自定义快捷命令**：点击执行，长按编辑保存
- **自定义快捷键**：支持多键组合（最多3键，如 Ctrl+Alt+Delete）
- Esc 键、回车键
- 命令历史记录

### 语音终端
- 语音输入命令
- 语音输出反馈
- 自动过滤 Claude Code 工具调用，只朗读文字内容
- 工作状态检测和指示

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
```

### 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | SSH 网络连接 |
| ACCESS_NETWORK_STATE | 网络状态检测 |
| WAKE_LOCK | 长时间操作保持唤醒 |
| RECORD_AUDIO | 语音输入 |

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

- **方向键**：↑↓←→ 用于导航和光标移动
- **查看键**：快速查看终端内容
- **快捷命令**：点击执行已保存的命令，长按编辑
- **自定义快捷键**：长按配置组合键，点击发送
- **Esc/回车**：发送 Esc 键和回车键

### 语音终端

1. 从服务器列表选择服务器，点击进入语音终端
2. 按住麦克风按钮说话，松开后自动识别
3. 开启语音输出后，终端内容会被朗读

### 服务器监控

1. 在服务器列表中点击服务器卡片右侧的 **监控** 按钮
2. 查看实时状态：
   - CPU 使用率
   - 内存使用情况
   - 磁盘使用情况
   - 系统运行时间
   - 系统负载

### 密钥管理

1. 添加服务器时点击 **生成密钥对**
2. 选择密钥类型：Ed25519（推荐）、RSA 2048、RSA 4096
3. 生成的公钥可复制到服务器的 `~/.ssh/authorized_keys`

## 项目结构

```
app/src/main/java/com/vibecoder/
├── VibeCoderApp.kt              # Application 类
├── data/
│   ├── Models.kt                # 数据模型
│   └── PreferencesManager.kt    # 数据存储
├── ssh/
│   └── SSHManager.kt            # SSH 连接管理 + 密钥生成
├── ui/
│   ├── MainActivity.kt          # 主 Activity
│   ├── ServerListFragment.kt    # 服务器列表
│   ├── ServerAdapter.kt         # 列表适配器
│   ├── TerminalFragment.kt      # 终端页面
│   ├── VoiceTerminalFragment.kt # 语音终端
│   ├── MonitorFragment.kt       # 监控页面
│   ├── SettingsFragment.kt      # 设置页面
│   └── widget/
│       └── VirtualKeyboardView.kt # 虚拟键盘
└── voice/
    └── VoiceInputManager.kt     # 语音输入管理
```

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.1.0 | 2026-03-16 | 语音终端、连接持久化、UI重构、代码精简 |
| v1.0.0 | 2026-03-14 | 初始发布版本 |

### v1.1.0 更新内容

**新功能**
- 语音终端：语音输入命令，语音输出反馈
- SSH连接持久化：屏幕旋转、切屏不中断
- L型透明按键布局：方向键 + 查看键
- 自定义快捷命令：保存后点击直接执行
- 自定义快捷键：支持多键组合

**优化**
- 合并 SSHKeyGenerator 到 SSHManager，减少文件数
- 移除冗余代码和资源
- 源文件从 46 个精简到 45 个

## 开发计划

- [ ] 终端复制/粘贴功能
- [ ] 多服务器同时连接
- [ ] SFTP 文件传输
- [ ] 端口转发功能
- [ ] 生物识别锁定

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request。