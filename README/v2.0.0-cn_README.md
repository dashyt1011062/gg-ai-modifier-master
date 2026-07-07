# GG-AI Modifier

<p align="center">
  <strong>🤖 AI 驱动的游戏内存修改器</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.0.0-blue" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-green" alt="Platform">
  <img src="https://img.shields.io/badge/flutter-3.x-blue" alt="Flutter">
  <img src="https://img.shields.io/badge/license-MIT-brightgreen" alt="License">
  <img src="https://img.shields.io/badge/ROOT-required-red" alt="ROOT Required">
</p>

---

## 📖 项目简介

GG-AI Modifier 是一个基于 **Flutter + Kotlin** 开发的 Android 游戏内存修改器，结合了传统 GG 修改器的内存操作能力和 AI 大语言模型的智能辅助功能。

用户可以通过自然语言与 AI 对话，AI 会自动理解用户意图并调用内存搜索、写入、冻结等操作，极大降低了内存修改的技术门槛。

> ⚠️ **免责声明**：本工具仅供学习研究和个人单机游戏使用，禁止用于在线竞技游戏。修改游戏数据可能违反游戏服务条款。

---

## ✨ 核心功能

### 🧠 AI 智能对话
- 集成 DeepSeek、OpenAI、小米 MiMo 等 LLM API
- 支持 Function Calling，AI 自动调用内存操作函数
- 多轮对话上下文，AI 引导用户逐步定位目标数据
- Markdown/LaTeX/Mermaid 渲染，AI 回复支持代码高亮和图表

### 🔍 内存搜索引擎
- **精确搜索**：搜索指定数值的内存地址
- **模糊搜索**：未知值搜索，支持 changed/increased/decreased 等 8 种对比操作
- **特征码搜索（AOB Scan）**：字节模式匹配，支持游戏重启后自动重定位
- **内存段智能筛选**：优先搜索 `[heap]`、`[anon:*]` 等高价值区域
- **分块读取**：2MB chunk 避免 OOM，支持 1 字节精细扫描

### 🎮 内存操作
- **内存读写**：支持 byte/word/dword/qword/float/double 数据类型
- **内存冻结**：后台线程每 100ms 持续写入，防止游戏自动修改
- **批量操作**：支持批量写入和批量冻结

### 📜 Lua 脚本引擎
- 集成 LuaJ (luaj-jse-3.0.2)，在 JVM 中执行 Lua 脚本
- 完整 GG API 桥接：`gg.choice()`、`gg.prompt()`、`gg.searchNumber()` 等
- 交互式对话框：脚本中的选择菜单会弹出真正的 Android 对话框
- 脚本运行日志自动保存

### 🪟 悬浮窗
- 游戏内悬浮球，点击展开功能面板
- 支持：进程选择、内存搜索、AI 对话、脚本库
- 记住上次打开的面板
- 每级窗口都有"关闭窗口"功能

### 💬 对话记录管理
- 单条/多条导出为 Markdown 文件
- 导出路径：`/storage/emulated/0/Documents/AI-gg/`
- 支持清空、删除、刷新等管理操作

---

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────────┐
│                    Flutter (Dart)                 │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────┐ │
│  │ AI 对话 │ │ 内存搜索│ │ 脚本库  │ │  设置 │ │
│  └────┬────┘ └────┬────┘ └────┬────┘ └───┬───┘ │
│       └───────────┴───────────┴───────────┘     │
│                      │                           │
│              MethodChannel                       │
│                      │                           │
├──────────────────────┼───────────────────────────┤
│                Kotlin (Android)                   │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐         │
│  │Memory   │ │LuaEngine │ │Overlay   │         │
│  │Engine   │ │(LuaJ)    │ │Service   │         │
│  └────┬────┘ └──────────┘ └──────────┘         │
│       │                                          │
│  ┌────┴────┐ ┌──────────┐ ┌──────────┐         │
│  │Root     │ │Process   │ │Memory    │         │
│  │Manager  │ │Manager   │ │Freezer   │         │
│  └─────────┘ └──────────┘ └──────────┘         │
│                      │                           │
│              /proc/pid/mem                       │
│                      │                           │
├──────────────────────┼───────────────────────────┤
│              Linux Kernel                        │
│              (Root Required)                     │
└─────────────────────────────────────────────────┘
```

### 目录结构

```
ai_gg666/
├── lib/                              # Flutter/Dart 代码
│   ├── main.dart                     # 入口（支持主应用和悬浮窗模式）
│   ├── app.dart                      # 主应用 MaterialApp
│   ├── overlay_app.dart              # 悬浮窗 Flutter 应用
│   ├── core/
│   │   ├── ffi/native_bridge.dart    # MethodChannel 桥接层
│   │   ├── llm/                      # LLM 服务（API 调用、Function Calling）
│   │   ├── lua/lua_service.dart      # Lua 脚本服务
│   │   └── models/                   # 数据模型
│   ├── features/
│   │   ├── chat/                     # AI 对话页面
│   │   ├── search/                   # 内存搜索页面
│   │   ├── script/                   # 脚本库页面
│   │   ├── settings/                 # 设置页面
│   │   ├── process/                  # 进程选择器
│   │   └── home/                     # 主页（底部导航）
│   ├── services/                     # 业务服务层
│   └── widgets/                      # 通用组件
├── android/app/src/main/kotlin/com/yl/aigg/ai_gg666/
│   ├── MainActivity.kt               # 主 Activity（MethodChannel 路由）
│   ├── MemoryEngine.kt               # 内存引擎（搜索/读写/冻结）
│   ├── LuaEngine.kt                  # LuaJ 引擎（GG API 桥接）
│   ├── RootManager.kt                # Root 权限管理（持久化 su shell）
│   ├── MemoryFreezer.kt              # 内存冻结器（后台线程）
│   ├── ProcessManager.kt             # 进程管理器
│   ├── OverlayService.kt             # 悬浮窗服务
│   └── OverlayFlutterActivity.kt     # 悬浮窗 Flutter Activity
├── android/app/src/main/assets/      # 本地 JS/CSS 资源
│   ├── css/katex.min.css             # KaTeX 样式
│   └── js/                           # marked/mermaid/katex
├── android/app/libs/                 # 第三方 JAR
│   └── luaj-jse-3.0.2.jar           # LuaJ 引擎
└── lua/                              # Lua 脚本资源
    ├── gg_api.lua
    ├── builtins/                     # 内置脚本
    └── templates/                    # 脚本模板
```

---

## 🛠️ 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| **前端框架** | Flutter 3.x | 跨平台 UI |
| **状态管理** | flutter_riverpod | 响应式状态管理 |
| **本地存储** | Hive | 轻量级 NoSQL 数据库 |
| **原生层** | Kotlin | Android 原生代码 |
| **Lua 引擎** | LuaJ 3.0.2 | JVM 中执行 Lua 脚本 |
| **Markdown 渲染** | flutter_markdown + marked.js | Flutter 端 + 悬浮窗 WebView |
| **LaTeX 渲染** | KaTeX | 数学公式渲染 |
| **图表渲染** | Mermaid.js | 流程图、时序图等 |
| **内存访问** | /proc/pid/mem + Root Shell | 通过 Root 权限读写进程内存 |
| **通信方式** | MethodChannel | Flutter ↔ Kotlin 双向通信 |

---

## 📋 系统要求

- **操作系统**：Android 5.0 (API 21) 及以上
- **Root 权限**：必须（需要读写 `/proc/pid/mem`）
- **推荐环境**：已安装 Magisk 的 Root 设备
- **网络**：AI 对话功能需要网络连接

---

## 🚀 快速开始

### 1. 克隆项目
```bash
git clone https://github.com/yl985211/gg-ai-modifier.git
cd gg-ai-modifier/ai_gg666
```

### 2. 安装依赖
```bash
flutter pub get
```

### 3. 运行调试
```bash
flutter run
```

### 4. 构建 Release
```bash
flutter build apk --release
```

---

## 📱 使用指南

### 首次启动
1. 应用会自动申请存储权限和悬浮窗权限
2. 在设置中配置 LLM API（支持 DeepSeek、OpenAI、小米 MiMo 等）
3. 检测 Root 权限

### 基本流程
1. **附加进程**：选择要修改的游戏进程
2. **AI 对话**：告诉 AI 你想修改什么（如"我想把金币改成 999999"）
3. **搜索数值**：AI 引导你搜索当前金币值
4. **缩小范围**：在游戏中改变数值后再次搜索
5. **修改/冻结**：找到目标地址后修改或冻结

### 悬浮窗使用
- 点击悬浮球打开功能菜单
- 在游戏中使用悬浮窗进行内存搜索和 AI 对话
- 切换进程后记得清空聊天记录

### 脚本库
- 内置测试脚本，支持自定义 Lua 脚本
- 脚本支持交互式菜单（`gg.choice()`）
- 运行日志自动保存

---

## 🔧 配置说明

### LLM API 配置
在设置页面配置：
- **API Base URL**：如 `https://api.deepseek.com`
- **API Key**：你的 API 密钥
- **模型名称**：如 `deepseek-chat`
- 支持添加自定义模型

### AI 读取深度
- 限制发送给 AI 的搜索结果数量
- 可选：20/30/50/100/全部
- 防止 Token 溢出，节省 API 费用

---

## 📄 开源协议

本项目采用 MIT 协议开源，详见 [LICENSE](LICENSE)。

---

## 🔗 相关链接

- **GitHub**：https://github.com/yl985211/gg-ai-modifier
- **问题反馈**：https://github.com/yl985211/gg-ai-modifier/issues

---

## 🙏 致谢

- [Flutter](https://flutter.dev/) - 跨平台 UI 框架
- [LuaJ](https://github.com/luaj/luaj) - Java Lua 解释器
- [KaTeX](https://katex.org/) - 数学公式渲染
- [Mermaid](https://mermaid.js.org/) - 图表渲染
- [marked](https://marked.js.org/) - Markdown 解析
- GG 修改器 - 功能灵感来源
- 联系反馈邮箱me985211@qq.com
---

<p align="center">
  <strong>⭐ 如果这个项目对你有帮助，请给个 Star！</strong>
</p>
