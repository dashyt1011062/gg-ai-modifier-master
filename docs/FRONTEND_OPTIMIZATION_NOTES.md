# Frontend Optimization Notes

本文件记录已经落地的前端优化，以及后续人工可继续处理的安全优化点。

## 已落地

### 1. 全局 Material 3 主题

文件：`lib/app.dart`

- 统一 ColorScheme。
- 统一 AppBar、Card、NavigationBar、Input、Button、Chip、Dialog、SnackBar 视觉。
- 新增全局渐变背景 `AppBackground`。
- 新增暖科技风主题色与状态色。

### 2. 现代 UI 组件层

文件：`lib/widgets/modern_ui.dart`

新增或增强：

- `ModernCard`
- `ModernHeroCard`
- `ModernIconButton`
- `StatusPill`
- `SectionHeader`
- `LoadingCard`
- `EmptyIllustration`

这些组件用于替代页面中的散落 Container/Card 样式，方便后续统一视觉。

### 3. 插件中心工作台化

文件：`lib/features/plugin/plugin_center_page.dart`

- 从占位页重写为插件工作台页面。
- 增加 Hero 卡片、状态卡、核心能力网格、动态扩展槽位。
- 接入现代卡片组件。

### 4. 进程选择器现代化

文件：`lib/widgets/game_selector.dart`

- 增加搜索清空按钮。
- 增加刷新按钮 tooltip。
- 增加当前选中状态卡。
- 增加系统进程标签。
- 增加空状态插画。
- 进程列表改为卡片化列表。

### 5. 脚本页现代化

文件：`lib/features/script/script_page.dart`

- 主脚本页增加 `ModernHeroCard`。
- 运行状态改为 `LoadingCard`。
- 空状态改为 `EmptyIllustration`。
- 脚本条目改为 `ModernCard`。
- 脚本类型、标签、执行次数改为 `StatusPill`。
- 日志页标题、空状态、日志卡片改为现代样式。

### 6. 搜索页现代化

文件：`lib/features/search/search_page.dart`

- 增加搜索控制台 `ModernHeroCard`。
- 顶部进程/类型控制栏改为 `ModernCard`。
- 搜索输入区域改为 `ModernCard`。
- 空状态改为 `EmptyIllustration`。
- 结果统计栏改为 `ModernCard` + `StatusPill`。
- 结果条目改为 `ModernCard`。

### 7. 设置页现代化

文件：`lib/features/settings/settings_page.dart`

- 增加配置中心 `ModernHeroCard`。
- 分区标题改为 `SectionHeader`。
- 预设选择改为 `ModernCard`。
- Root 状态、悬浮窗、AI 读取深度、关于信息改为 `ModernCard`。
- GitHub 地址和使用警告改为现代卡片。

### 8. LLM 服务稳定性

文件：`lib/core/llm/llm_service.dart`

- 修复编译级花括号风险。
- 统一请求超时常量。
- 非流式请求增加工具参数安全解析。
- 流式请求增加超时处理和 SSE 容错。
- API 错误日志脱敏，避免输出完整密钥。

### 9. 存储服务容错

文件：`lib/services/storage_service.dart`

- LLM 配置读取异常时返回 null。
- 旧版聊天历史读取异常时返回空列表。
- 单会话、单脚本、单书签读取异常时返回 null。
- 列表读取已跳过损坏记录。

## 尚未在当前环境验证

当前容器环境没有安装 Dart / Flutter，因此以下命令未能实际执行：

```bash
flutter pub get
dart format lib
flutter analyze
flutter build apk
```

## 后续建议

### 1. 本地运行格式化和分析

先执行：

```bash
cd /root/gg-ai-modifier-master/gg-ai-modifier-master
flutter pub get
dart format lib
flutter analyze
```

根据分析结果继续修复 import 顺序、const、lint、语法和兼容性问题。

### 2. 继续微调搜索页

- 结果列表可以进一步做分页控制。
- 搜索类型 chip 可按当前主题做更强状态区分。
- 编辑/冻结弹窗可继续卡片化。

### 3. 继续微调设置页

- API 表单可以整体包成一个大 `ModernCard`。
- 连接测试结果可以拆出专门状态组件。
- 自定义预设弹窗可继续现代化。

## 注意

本项目包含底层进程与内存相关代码。前端、存储、LLM、样式、稳定性可以继续优化；但不建议加入规避检测、反封禁、绕过安全机制、在线竞技作弊相关逻辑。
