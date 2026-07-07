/// Prompt 构建器
///
/// 构建发送给 LLM 的系统提示和消息上下文

import '../models/chat_message.dart';

/// AI 读取深度选项
enum AiReadDepth {
  sample20(20, '前 20 条'),
  sample30(30, '前 30 条'),
  sample50(50, '前 50 条'),
  sample100(100, '前 100 条'),
  full(-1, '全部结果');

  final int value;
  final String label;
  const AiReadDepth(this.value, this.label);
}

/// Prompt 构建器
class PromptBuilder {
  /// 当前 AI 读取深度
  AiReadDepth _readDepth = AiReadDepth.sample50;
  String _modelName = '';

  /// 设置 AI 读取深度
  void setReadDepth(AiReadDepth depth) {
    _readDepth = depth;
  }

  /// 设置当前模型名称
  void setModelName(String name) {
    _modelName = name;
  }

  /// 获取当前读取深度
  AiReadDepth get readDepth => _readDepth;

  /// 构建系统提示（动态注入当前模型信息）
  static String buildSystemPrompt(String modelName) {
    final modelInfo = _getModelInfo(modelName);
    return '''
你是 GG-AI 游戏内存修改助手。你当前使用的底层大模型是：$modelInfo。
当用户问你是什么模型时，你必须回答「$modelInfo」，这是你真实运行的底层模型。GG-AI 只是这个应用的名称，不是你的模型名称。不要编造其他模型名称。

你的能力：

1. **数值修改**: 用户描述想要修改的游戏数据，你引导他们通过搜索定位内存地址
2. **内存分析**: 分析搜索结果，帮助用户识别哪个地址对应目标数据
3. **脚本生成**: 根据用户需求自动生成 Lua 修改脚本
4. **教学引导**: 解释内存修改的原理，帮助用户学习

工作流程:
- 用户说"我想改金币为99999"
- 你引导用户: 先搜索当前金币值 → 消费金币 → 再搜索新值 → 缩小范围 → 确认地址 → 写入新值
- 每一步返回结构化指令，由 App 执行

当搜索结果过多时，App 会只发送前 N 条样本和统计数据。你需要：
- 根据样本特征引导用户进行"数值变化"过滤
- 建议用户在游戏中改变数值后再次搜索以缩小范围
- 不要盲目修改，而是引导精确定位

安全规则:
- 不提供绕过在线验证的方法
- 不修改服务器端数据
- 提醒用户修改可能违反游戏服务条款
- 仅限单机游戏或学习研究使用

渲染支持：当前客户端支持 Markdown 渲染、代码块高亮、LaTeX 数学公式和 Mermaid 图表。
当需要展示流程图、架构图、思维导图、时序图、甘特图等可视化内容时，请使用 Mermaid 语法（用 ```mermaid 代码块包裹）。
示例：
```mermaid
graph TD
    A[搜索金币值] --> B[消费金币]
    B --> C[再次搜索]
    C --> D[确认地址]
    D --> E[修改值]
```

回复格式:
- 使用简洁友好的中文
- 操作步骤用编号列出
- 执行结果用 ✅ 或 ❌ 标记
- 地址和数值用代码格式显示
- 适当使用 Mermaid 图表辅助说明复杂流程
''';
  }

  static String _getModelInfo(String modelName) {
    final m = modelName.toLowerCase();
    if (m.contains('deepseek-reasoner') || m.contains('deepseek-r1')) return 'DeepSeek-R1（深度求索公司，推理增强模型）';
    if (m.contains('deepseek')) return 'DeepSeek-V3（深度求索公司，通用对话模型）';
    if (m.contains('mimo')) return 'MiMo-v2.5-Pro（小米公司，大语言模型）';
    if (m.contains('gpt-4o')) return 'GPT-4o（OpenAI 公司，多模态模型）';
    if (m.contains('gpt-4')) return 'GPT-4（OpenAI 公司，大语言模型）';
    if (m.contains('gpt-3.5')) return 'GPT-3.5-Turbo（OpenAI 公司，大语言模型）';
    if (m.contains('claude')) return 'Claude（Anthropic 公司，大语言模型）';
    if (m.contains('qwen') || m.contains('tongyi')) return '通义千问（阿里巴巴公司，大语言模型）';
    if (m.contains('glm') || m.contains('chatglm')) return 'ChatGLM（智谱AI公司，大语言模型）';
    return modelName;
  }

  /// 构建消息列表
  List<Map<String, dynamic>> buildMessages({
    required String userMessage,
    List<ChatMessage> history = const [],
  }) {
    final messages = <Map<String, dynamic>>[];

    // 系统提示
    messages.add({'role': 'system', 'content': buildSystemPrompt(_modelName)});

    // 历史消息 (最近 20 条)
    final recentHistory = history.length > 20
        ? history.sublist(history.length - 20)
        : history;

    for (final msg in recentHistory) {
      if (msg.isSystem) continue;

      messages.add({'role': msg.role.value, 'content': msg.content});
    }

    // 当前用户消息
    messages.add({'role': 'user', 'content': userMessage});

    return messages;
  }

  /// 构建函数执行结果消息
  List<Map<String, dynamic>> buildFunctionResultMessages({
    required String funcName,
    required Map<String, dynamic> arguments,
    required dynamic result,
    required List<ChatMessage> history,
  }) {
    final messages = <Map<String, dynamic>>[];

    // 系统提示
    messages.add({'role': 'system', 'content': buildSystemPrompt(_modelName)});

    // 历史消息
    final recentHistory = history.length > 20
        ? history.sublist(history.length - 20)
        : history;

    for (final msg in recentHistory) {
      if (msg.isSystem) continue;

      messages.add({'role': msg.role.value, 'content': msg.content});
    }

    // 函数调用消息
    messages.add({
      'role': 'assistant',
      'content': null,
      'function_call': {'name': funcName, 'arguments': arguments},
    });

    // 函数结果
    messages.add({'role': 'tool', 'content': result.toString()});

    return messages;
  }
}
