/// LLM API 调用服务
///
/// 支持 OpenAI 兼容 API，包括流式响应和 Function Calling

import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'llm_config.dart';
import '../models/chat_message.dart';
import 'function_handler.dart';
import 'prompt_builder.dart';

/// LLM 服务类
class LlmService {
  static const Duration _requestTimeout = Duration(seconds: 60);

  LlmConfig _config;
  final FunctionHandler _functionHandler;
  final PromptBuilder _promptBuilder;

  http.Client? _httpClient;
  bool _isStreaming = false;

  LlmService({
    required LlmConfig config,
    required FunctionHandler functionHandler,
    required PromptBuilder promptBuilder,
  })  : _config = config,
        _functionHandler = functionHandler,
        _promptBuilder = promptBuilder {
    promptBuilder.setModelName(config.model);
  }

  /// 当前配置
  LlmConfig get config => _config;

  /// 是否正在流式响应
  bool get isStreaming => _isStreaming;

  /// 更新配置
  void updateConfig(LlmConfig newConfig) {
    _config = newConfig;
    _promptBuilder.setModelName(newConfig.model);
  }

  /// 发送聊天消息 (非流式)
  Future<ChatMessage> sendMessage(
    String userMessage, {
    List<ChatMessage> history = const [],
  }) async {
    if (!_config.isConfigured) {
      return ChatMessage.assistant('⚠️ 请先在设置中配置 LLM API');
    }

    try {
      final messages = _promptBuilder.buildMessages(
        userMessage: userMessage,
        history: history,
      );

      final response = await _makeRequest(messages, stream: false);

      if (response == null) {
        return ChatMessage.assistant('❌ 请求失败，请检查网络和 API 配置');
      }

      final choice = response['choices']?[0];
      if (choice == null) {
        return ChatMessage.assistant('❌ 响应格式错误');
      }

      final message = choice['message'];
      final content = message['content'] as String? ?? '';

      if (message['function_call'] != null) {
        final functionCall = message['function_call'];
        final funcName = functionCall['name'] as String;
        final funcArgs = _decodeFunctionArguments(functionCall['arguments']);
        if (funcArgs == null) {
          return ChatMessage.assistant('❌ 工具调用参数格式错误');
        }

        final result = await _functionHandler.executeFunction(funcName, funcArgs);
        return _sendFunctionResult(
          funcName: funcName,
          arguments: funcArgs,
          result: result,
          history: history,
        );
      }

      return ChatMessage.assistant(content);
    } catch (e) {
      return ChatMessage.assistant('❌ 发生错误: ${_sanitizeError(e)}');
    }
  }

  /// 发送聊天消息 (流式)
  Stream<String> sendMessageStream(
    String userMessage, {
    List<ChatMessage> history = const [],
  }) async* {
    if (!_config.isConfigured) {
      yield '⚠️ 请先在设置中配置 LLM API';
      return;
    }

    _isStreaming = true;

    try {
      final messages = _promptBuilder.buildMessages(
        userMessage: userMessage,
        history: history,
      );

      final request = http.Request('POST', Uri.parse(_config.chatEndpoint));
      request.headers.addAll({
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ${_config.apiKey}',
      });
      request.body = jsonEncode({
        'model': _config.model,
        'messages': messages,
        'temperature': _config.temperature,
        'max_tokens': _config.maxTokens,
        'stream': true,
        'tools': _functionHandler.getToolDefinitions(),
      });

      final streamedResponse = await _getClient()
          .send(request)
          .timeout(_requestTimeout);

      if (streamedResponse.statusCode != 200) {
        yield '❌ API 请求失败 (${streamedResponse.statusCode})';
        return;
      }

      var buffer = '';
      String? pendingFunctionName;
      final pendingFunctionArgs = StringBuffer();

      await for (final chunk in streamedResponse.stream.transform(utf8.decoder)) {
        buffer += chunk;
        final lines = buffer.split('\n');
        buffer = lines.last;

        for (var i = 0; i < lines.length - 1; i++) {
          final line = lines[i].trim();
          if (line.isEmpty || !line.startsWith('data: ')) continue;

          final data = line.substring(6);
          if (data == '[DONE]') continue;

          try {
            final json = jsonDecode(data);
            final delta = json['choices']?[0]?['delta'];
            if (delta == null) continue;

            final functionCall = delta['function_call'];
            if (functionCall != null) {
              final name = functionCall['name'] as String?;
              if (name != null && pendingFunctionName == null) {
                pendingFunctionName = name;
                yield '\n\n🔧 正在准备工具调用: $name...\n';
              }

              final arguments = functionCall['arguments'] as String?;
              if (arguments != null) {
                pendingFunctionArgs.write(arguments);
              }
            }

            final content = delta['content'] as String?;
            if (content != null) {
              yield content;
            }
          } catch (_) {
            // 忽略单行 SSE 解析错误，继续读取后续内容。
          }
        }
      }

      if (pendingFunctionName != null) {
        yield '\n\nℹ️ 已收到工具调用请求，请使用非流式模式执行需要工具结果总结的任务。';
      }
    } on TimeoutException {
      yield '\n\n❌ 流式响应超时，请稍后重试';
    } catch (e) {
      yield '\n\n❌ 流式响应错误: ${_sanitizeError(e)}';
    } finally {
      _isStreaming = false;
    }
  }

  /// 发送函数执行结果给 LLM
  Future<ChatMessage> _sendFunctionResult({
    required String funcName,
    required Map<String, dynamic> arguments,
    required dynamic result,
    required List<ChatMessage> history,
  }) async {
    try {
      final messages = _promptBuilder.buildFunctionResultMessages(
        funcName: funcName,
        arguments: arguments,
        result: result,
        history: history,
      );

      final response = await _makeRequest(messages, stream: false);

      if (response == null) {
        return ChatMessage.assistant('函数执行完成，但无法获取 AI 总结');
      }

      final content =
          response['choices']?[0]?['message']?['content'] as String? ?? '';
      return ChatMessage.assistant(content);
    } catch (_) {
      return ChatMessage.assistant('函数执行完成: $result');
    }
  }

  /// 发送 API 请求
  Future<Map<String, dynamic>?> _makeRequest(
    List<Map<String, dynamic>> messages, {
    bool stream = false,
  }) async {
    try {
      final response = await _getClient()
          .post(
            Uri.parse(_config.chatEndpoint),
            headers: {
              'Content-Type': 'application/json',
              'Authorization': 'Bearer ${_config.apiKey}',
            },
            body: jsonEncode({
              'model': _config.model,
              'messages': messages,
              'temperature': _config.temperature,
              'max_tokens': _config.maxTokens,
              'stream': stream,
              'tools': _functionHandler.getToolDefinitions(),
            }),
          )
          .timeout(_requestTimeout);

      if (response.statusCode == 200) {
        return jsonDecode(response.body) as Map<String, dynamic>;
      }

      _logApiFailure(response.statusCode, response.body);
      return null;
    } on TimeoutException {
      print('API 请求超时');
      return null;
    } catch (e) {
      print('API 请求异常: ${_sanitizeError(e)}');
      return null;
    }
  }

  Map<String, dynamic>? _decodeFunctionArguments(dynamic rawArguments) {
    if (rawArguments == null) return <String, dynamic>{};
    try {
      final decoded = rawArguments is String ? jsonDecode(rawArguments) : rawArguments;
      return Map<String, dynamic>.from(decoded as Map);
    } catch (_) {
      return null;
    }
  }

  /// 输出脱敏后的 API 错误日志，避免把完整响应或密钥写入日志。
  void _logApiFailure(int statusCode, String body) {
    final compactBody = body.length > 300 ? '${body.substring(0, 300)}...' : body;
    print('API 请求失败: $statusCode ${_sanitizeError(compactBody)}');
  }

  String _sanitizeError(Object error) {
    var text = error.toString();
    if (_config.apiKey.isNotEmpty) {
      text = text.replaceAll(_config.apiKey, '***');
    }
    return text.replaceAll(RegExp(r'Bearer\s+[A-Za-z0-9._\-]+'), 'Bearer ***');
  }

  /// 获取 HTTP 客户端
  http.Client _getClient() {
    _httpClient ??= http.Client();
    return _httpClient!;
  }

  /// 释放资源
  void dispose() {
    _httpClient?.close();
    _httpClient = null;
    _isStreaming = false;
  }
}
