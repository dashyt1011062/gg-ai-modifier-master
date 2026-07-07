# GG-AI Modifier

<p align="center">
  <strong>🤖 AI-Powered Game Memory Modifier</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.0.0-blue" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-green" alt="Platform">
  <img src="https://img.shields.io/badge/flutter-3.x-blue" alt="Flutter">
  <img src="https://img.shields.io/badge/license-MIT-brightgreen" alt="License">
  <img src="https://img.shields.io/badge/ROOT-required-red" alt="ROOT Required">
</p>

---

## 📖 Project Overview

GG-AI Modifier is an Android game memory modifier developed with **Flutter + Kotlin**, combining the memory operation capabilities of traditional GG modifiers with the intelligent assistance of AI large language models.

Users can interact with AI through natural language, and the AI automatically understands user intent and performs memory search, writing, freezing, and other operations, greatly lowering the technical barrier for memory modification.

> ⚠️ **Disclaimer**: This tool is intended only for learning, research, and personal single-player game use. It is prohibited for use in online competitive games. Modifying game data may violate game terms of service.

---

## ✨ Core Features

### 🧠 AI Intelligent Conversation
- Integrated with DeepSeek, OpenAI, Xiaomi MiMo, and other LLM APIs
- Supports Function Calling, allowing AI to automatically invoke memory operation functions
- Multi-turn contextual conversation, with AI guiding users step by step to locate target data
- Markdown/LaTeX/Mermaid rendering, with AI replies supporting code highlighting and charts

### 🔍 Memory Search Engine
- **Exact Search**: Search memory addresses for specified values
- **Fuzzy Search**: Unknown value search supporting 8 comparison operations such as changed/increased/decreased
- **AOB Scan (Array of Bytes Search)**: Byte pattern matching with automatic relocation after game restart
- **Intelligent Memory Region Filtering**: Prioritizes high-value regions such as `[heap]` and `[anon:*]`
- **Chunked Reading**: 2MB chunks to avoid OOM, supports precise 1-byte scanning

### 🎮 Memory Operations
- **Memory Read/Write**: Supports byte/word/dword/qword/float/double data types
- **Memory Freeze**: Background thread continuously writes every 100ms to prevent automatic in-game changes
- **Batch Operations**: Supports batch writing and batch freezing

### 📜 Lua Script Engine
- Integrated with LuaJ (luaj-jse-3.0.2) to execute Lua scripts in the JVM
- Full GG API bridge: `gg.choice()`, `gg.prompt()`, `gg.searchNumber()`, etc.
- Interactive dialogs: Script selection menus trigger real Android dialog boxes
- Script execution logs are automatically saved

### 🪟 Floating Window
- In-game floating ball with expandable function panel
- Supports: Process selection, memory search, AI chat, script library
- Remembers the last opened panel
- Every window level includes a "Close Window" function

### 💬 Conversation History Management
- Export single or multiple conversations as Markdown files
- Export path: `/storage/emulated/0/Documents/AI-gg/`
- Supports clear, delete, refresh, and other management functions

---

## 🏗️ Technical Architecture

```text
┌─────────────────────────────────────────────────┐
│                    Flutter (Dart)               │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────┐ │
│  │ AI Chat │ │ Memory  │ │ Script  │ │Settings│ │
│  │         │ │ Search  │ │ Library │ │       │ │
│  └────┬────┘ └────┬────┘ └────┬────┘ └───┬───┘ │
│       └───────────┴───────────┴───────────┘     │
│                      │                           │
│              MethodChannel                       │
│                      │                           │
├──────────────────────┼───────────────────────────┤
│                Kotlin (Android)                  │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐         │
│  │Memory   │ │LuaEngine │ │Overlay   │         │
│  │Engine   │ │(LuaJ)    │ │Service   │         │
│  └────┬────┘ └──────────┘ └──────────┘         │
│       │                                         │
│  ┌────┴────┐ ┌──────────┐ ┌──────────┐         │
│  │Root     │ │Process   │ │Memory    │         │
│  │Manager  │ │Manager   │ │Freezer   │         │
│  └─────────┘ └──────────┘ └──────────┘         │
│                      │                           │
│              /proc/pid/mem                      │
│                      │                           │
├──────────────────────┼───────────────────────────┤
│              Linux Kernel                       │
│              (Root Required)                    │
└─────────────────────────────────────────────────┘
```

### Directory Structure

```text
ai_gg666/
├── lib/                              # Flutter/Dart code
│   ├── main.dart                     # Entry point (supports main app and floating window mode)
│   ├── app.dart                      # Main application MaterialApp
│   ├── overlay_app.dart              # Floating window Flutter app
│   ├── core/
│   │   ├── ffi/native_bridge.dart    # MethodChannel bridge layer
│   │   ├── llm/                      # LLM services (API calls, Function Calling)
│   │   ├── lua/lua_service.dart      # Lua script service
│   │   └── models/                   # Data models
│   ├── features/
│   │   ├── chat/                     # AI chat page
│   │   ├── search/                   # Memory search page
│   │   ├── script/                   # Script library page
│   │   ├── settings/                 # Settings page
│   │   ├── process/                  # Process selector
│   │   └── home/                     # Home page (bottom navigation)
│   ├── services/                     # Business service layer
│   └── widgets/                      # Common components
├── android/app/src/main/kotlin/com/yl/aigg/ai_gg666/
│   ├── MainActivity.kt               # Main Activity (MethodChannel routing)
│   ├── MemoryEngine.kt               # Memory engine (search/read/write/freeze)
│   ├── LuaEngine.kt                  # LuaJ engine (GG API bridge)
│   ├── RootManager.kt                # Root permission management (persistent su shell)
│   ├── MemoryFreezer.kt              # Memory freezer (background thread)
│   ├── ProcessManager.kt             # Process manager
│   ├── OverlayService.kt             # Floating window service
│   └── OverlayFlutterActivity.kt     # Floating window Flutter Activity
├── android/app/src/main/assets/      # Local JS/CSS resources
│   ├── css/katex.min.css             # KaTeX styles
│   └── js/                           # marked/mermaid/katex
├── android/app/libs/                 # Third-party JARs
│   └── luaj-jse-3.0.2.jar            # LuaJ engine
└── lua/                              # Lua script resources
    ├── gg_api.lua
    ├── builtins/                     # Built-in scripts
    └── templates/                    # Script templates
```

---

## 🛠️ Tech Stack

| Layer                    | Technology                   | Description                                  |
| ------------------------ | ---------------------------- | -------------------------------------------- |
| **Frontend Framework**   | Flutter 3.x                  | Cross-platform UI                            |
| **State Management**     | flutter_riverpod             | Reactive state management                    |
| **Local Storage**        | Hive                         | Lightweight NoSQL database                   |
| **Native Layer**         | Kotlin                       | Android native code                          |
| **Lua Engine**           | LuaJ 3.0.2                   | Executes Lua scripts in JVM                  |
| **Markdown Rendering**   | flutter_markdown + marked.js | Flutter side + floating window WebView       |
| **LaTeX Rendering**      | KaTeX                        | Mathematical formula rendering               |
| **Chart Rendering**      | Mermaid.js                   | Flowcharts, sequence diagrams, etc.          |
| **Memory Access**        | /proc/pid/mem + Root Shell   | Read/write process memory through Root       |
| **Communication Method** | MethodChannel                | Flutter ↔ Kotlin bidirectional communication |

---

## 📋 System Requirements

- **Operating System**: Android 5.0 (API 21) or above
- **Root Permission**: Required (for reading/writing `/proc/pid/mem`)
- **Recommended Environment**: Rooted device with Magisk installed
- **Network**: Internet connection required for AI chat functionality

---

## 🚀 Quick Start

### 1. Clone the Project
```bash
git clone https://github.com/yl985211/gg-ai-modifier.git
cd gg-ai-modifier/ai_gg666
```

### 2. Install Dependencies
```bash
flutter pub get
```

### 3. Run Debug
```bash
flutter run
```

### 4. Build Release
```bash
flutter build apk --release
```

---

## 📱 User Guide

### First Launch
1. The app automatically requests storage permission and floating window permission
2. Configure the LLM API in Settings (supports DeepSeek, OpenAI, Xiaomi MiMo, etc.)
3. Detect Root permission

### Basic Workflow
1. **Attach Process**: Select the game process you want to modify
2. **AI Chat**: Tell AI what you want to modify (e.g., "I want to change my gold to 999999")
3. **Search Value**: AI guides you to search the current gold value
4. **Narrow Results**: Change the value in-game and search again
5. **Modify/Freeze**: Once the target address is found, modify or freeze it

### Floating Window Usage
- Tap the floating ball to open the function menu
- Use the floating window for memory search and AI chat in-game
- Clear chat history after switching processes

### Script Library
- Built-in test scripts with support for custom Lua scripts
- Scripts support interactive menus (`gg.choice()`)
- Execution logs are automatically saved

---

## 🔧 Configuration Guide

### LLM API Configuration
Configure the following in the Settings page:
- **API Base URL**: e.g., `https://api.deepseek.com`
- **API Key**: Your API key
- **Model Name**: e.g., `deepseek-chat`
- Supports adding custom models

### AI Read Depth
- Limits the number of search results sent to AI
- Options: 20/30/50/100/All
- Prevents token overflow and reduces API costs

---

## 📄 Open Source License

This project is open-sourced under the MIT License. See [LICENSE](LICENSE) for details.

---

## 🔗 Related Links

- **GitHub**: https://github.com/yl985211/gg-ai-modifier
- **Issue Reporting**: https://github.com/yl985211/gg-ai-modifier/issues

---

## 🙏 Acknowledgements

- [Flutter](https://flutter.dev/) - Cross-platform UI framework
- [LuaJ](https://github.com/luaj/luaj) - Java Lua interpreter
- [KaTeX](https://katex.org/) - Mathematical formula rendering
- [Mermaid](https://mermaid.js.org/) - Chart rendering
- [marked](https://marked.js.org/) - Markdown parsing
- GG Modifier - Functional inspiration
- Contact email: me985211@qq.com

---

<p align="center">
  <strong>⭐ If this project helps you, please give it a Star!</strong>
</p>