# Overlay Fullscreen Menu Notes

## 目标

将原来的悬浮窗小菜单优化为点击悬浮球后直接展开的全屏半透明控制台，适配横屏和竖屏，同时保持原有功能入口逻辑不变。

## 参考方向

- AppFlowy：工作台式大面板、卡片分区、大留白、清晰层级。
- Material 3 Adaptive Layout：根据横竖屏切换布局密度和分栏方式。

## 已落地文件

`android/app/src/main/kotlin/com/yl/aigg/ai_gg666/OverlayService.kt`

## 已完成优化

### 1. 点击悬浮球直接进入全屏菜单

原行为：点击悬浮球可能恢复上次面板或打开小菜单。

新行为：点击悬浮球统一打开全屏 Dashboard 菜单。

### 2. 新增全屏 Overlay 容器

新增 `showFullscreenPanel(view: View)`：

- 宽高为 `MATCH_PARENT`。
- 背景支持半透明遮罩。
- 使用硬件加速 flag。
- 进入时带轻量 alpha + scale 动画。
- 不使用 WebView / FlutterView，避免额外渲染开销。

### 3. 新增可输入全屏 Overlay 容器

新增 `showFocusableFullscreenPanel(view: View)`：

- 同样全屏显示。
- 支持 `EditText`、输入法和软键盘调整。
- 用于搜索、AI 对话、脚本、进程等子面板。

### 4. 新增横竖屏自适应主菜单

`showMainMenu()` 已重写为全屏 Dashboard：

- 竖屏：上下布局。
- 横屏：左右分栏布局。
- 顶部渐变 Hero 区。
- 中间核心功能卡片。
- 右侧/下方状态与快捷操作。

### 5. 搜索 / AI / 脚本 / 进程面板全屏化

公共面板包装方法 `makeDraggablePanel(...)` 已重写为全屏现代控制台外壳。

因此下列面板全部会套用新的全屏半透明 UI：

- `showProcessPanel()` 进程选择
- `showSearchPanel()` 内存搜索
- `showAIChatPanel()` AI 对话
- `showScriptPanel()` 脚本库

这些面板仍然调用原来的内容构建逻辑，所以业务逻辑不变，只升级外层视觉、尺寸、横竖屏适配和键盘适配。

### 6. 新增轻量 UI 工具方法

- `fullscreenSectionTitle(...)`
- `fullscreenFeatureCard(...)`
- `fullscreenStatusCard()`
- `fullscreenActionPill(...)`

用于减少重复 View 创建代码，并保持悬浮窗菜单样式统一。

## 视觉设计

- 背景：半透明深色遮罩。
- 主面板：奶白色大圆角卡片。
- 顶部：棕金渐变 Hero。
- 子面板：统一全屏 Hero 顶栏 + 奶白内容卡片。
- 功能入口：大圆角卡片 + 图标 + 状态胶囊。
- 状态区：当前附加 PID + 全屏菜单状态。
- 快捷操作：打开主界面 / 关闭悬浮窗 / 收起。

## 性能优化点

- 使用原生 Android View，不引入额外渲染容器。
- 菜单和子面板只在打开时创建，关闭时移除。
- 不使用实时模糊，避免 GPU 压力。
- 动画只做 160ms alpha/scale，成本较低。
- 横竖屏布局在打开时根据 displayMetrics 一次性计算。
- 子面板复用公共外壳，减少重复布局代码。

## 后续可继续优化

1. 将搜索、AI、脚本、进程面板内部的旧按钮和旧颜色继续替换成统一卡片/胶囊风格。
2. 增加点击遮罩边缘收起。
3. 支持系统返回键关闭全屏菜单。
4. 悬浮球收起时增加状态点：绿色已附加、橙色未附加。
5. 搜索结果列表增加虚拟化/分页显示，避免结果过多时 UI 卡顿。

## 本轮未验证项

当前环境未运行 Android 编译。建议本地执行：

```bash
cd /root/gg-ai-modifier-master/gg-ai-modifier-master
./gradlew :app:assembleDebug
```

如有 Kotlin 编译错误，优先检查 `OverlayService.kt` 中新增的全屏菜单和全屏子面板相关方法。
