import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';
import 'package:zeroclaw_flutter_ui/src/core/service_manager.dart';
import 'package:zeroclaw_flutter_ui/src/core/zeroclaw_channel.dart';
import 'dart:convert';

class ChatPage extends StatefulWidget {
  const ChatPage({super.key});

  @override
  State<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends State<ChatPage> {
  static const String _fixedSessionId = 'tes13423671876997913';
  final TextEditingController _inputController = TextEditingController();
  final List<_ChatMessage> _messages = [
    const _ChatMessage(
      role: 'system',
      content: 'Loading webchat endpoint from config.toml...',
    ),
  ];
  int _webchatPort = 42617;
  String _webchatPath = '/response';
  bool _isLoadingConfig = false;
  bool _isSending = false;
  bool _streamResponse = true;
  String? _configError;

  @override
  void initState() {
    super.initState();
    _loadWebchatConfig();
  }

  @override
  void dispose() {
    _inputController.dispose();
    super.dispose();
  }

  Future<void> _loadWebchatConfig() async {
    if (_isLoadingConfig) {
      return;
    }

    setState(() {
      _isLoadingConfig = true;
      _configError = null;
    });

    try {
      final rawConfig = await ZeroClawChannel.getConfig();
      final config = _parseWebchatConfig(rawConfig);
      setState(() {
        _webchatPort = config.port;
        _webchatPath = config.listenPath;
        _messages
          ..clear()
          ..add(
            _ChatMessage(
              role: 'system',
              content:
                  'webchat endpoint ready: ${_buildEndpointUri().toString()}',
            ),
          );
      });
    } catch (e) {
      setState(() {
        _configError = 'Failed to load webchat config: $e';
        _messages
          ..clear()
          ..add(
            _ChatMessage(
              role: 'system',
              content:
                  'Failed to load [channels_config.webchat] from config.toml.',
            ),
          );
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoadingConfig = false;
        });
      }
    }
  }

  _WebChatConfig _parseWebchatConfig(String content) {
    final lines = content.split(RegExp(r'\r?\n'));
    var inWebchatSection = false;
    var port = 42617;
    var listenPath = '/response';

    for (final line in lines) {
      final trimmed = line.trim();
      if (trimmed.isEmpty || trimmed.startsWith('#')) {
        continue;
      }
      if (trimmed == '[channels_config.webchat]') {
        inWebchatSection = true;
        continue;
      }
      if (trimmed.startsWith('[')) {
        inWebchatSection = false;
      }
      if (!inWebchatSection) {
        continue;
      }

      final separatorIndex = trimmed.indexOf('=');
      if (separatorIndex <= 0) {
        continue;
      }
      final key = trimmed.substring(0, separatorIndex).trim();
      final rawValue = trimmed.substring(separatorIndex + 1).trim();
      final value = rawValue.replaceAll(RegExp(r'^"|"$'), '');

      if (key == 'port') {
        port = int.tryParse(value) ?? port;
      } else if (key == 'listen_path') {
        listenPath = value.isEmpty ? listenPath : value;
      }
    }

    final normalizedPath = listenPath.startsWith('/')
        ? listenPath
        : '/$listenPath';
    return _WebChatConfig(port: port, listenPath: normalizedPath);
  }

  Uri _buildEndpointUri() {
    return Uri.parse('http://127.0.0.1:$_webchatPort$_webchatPath');
  }

  Map<String, Object?> _buildRequestPayload(String text) {
    return {
      'session_id': _fixedSessionId,
      'messages': [
        {'role': 'user', 'content': text},
      ],
      'stream': _streamResponse,
    };
  }

  Future<void> _submitMessage() async {
    final text = _inputController.text.trim();
    if (text.isEmpty || _isSending) {
      return;
    }

    final assistantIndex = _messages.length + 1;
    setState(() {
      _messages.add(_ChatMessage(role: 'user', content: text));
      _messages.add(
        _ChatMessage(
          role: 'assistant',
          content: _streamResponse ? 'Waiting for stream...' : 'Waiting for response...',
          meta:
              'POST ${_buildEndpointUri()} • session=$_fixedSessionId • stream=$_streamResponse',
        ),
      );
      _isSending = true;
    });
    _inputController.clear();

    final service = context.read<ServiceManager>();
    if (service.status != ServiceStatus.running) {
      setState(() {
        _messages.add(
          const _ChatMessage(
            role: 'system',
            content: 'Service is not running. Start ZeroClaw first.',
          ),
        );
        _isSending = false;
      });
      return;
    }

    try {
      final payload = _buildRequestPayload(text);
      final body = jsonEncode(payload);
      if (_streamResponse) {
        final request = http.Request('POST', _buildEndpointUri())
          ..headers['Content-Type'] = 'application/json; charset=utf-8'
          ..body = body;
        final streamedResponse = await request.send().timeout(
          const Duration(seconds: 30),
        );
        final buffer = StringBuffer();
        var receivedChunk = false;
        await for (final chunk in streamedResponse.stream.transform(utf8.decoder)) {
          if (chunk.isEmpty) {
            continue;
          }
          receivedChunk = true;
          buffer.write(chunk);
          if (!mounted) {
            continue;
          }
          setState(() {
            _messages[assistantIndex] = _messages[assistantIndex].copyWith(
              content: buffer.toString().trim().isEmpty
                  ? chunk
                  : buffer.toString().trim(),
              meta:
                  'POST ${_buildEndpointUri()} • HTTP ${streamedResponse.statusCode} • stream=true',
            );
          });
        }
        if (!mounted) {
          return;
        }
        setState(() {
          _messages[assistantIndex] = _messages[assistantIndex].copyWith(
            content: receivedChunk
                ? (buffer.toString().trim().isEmpty
                      ? 'HTTP ${streamedResponse.statusCode} with empty response body.'
                      : buffer.toString().trim())
                : 'HTTP ${streamedResponse.statusCode} with empty response body.',
            meta:
                'POST ${_buildEndpointUri()} • HTTP ${streamedResponse.statusCode} • stream=true',
          );
        });
      } else {
        final response = await http
            .post(
              _buildEndpointUri(),
              headers: const {'Content-Type': 'application/json; charset=utf-8'},
              body: body,
            )
            .timeout(const Duration(seconds: 30));

        final responseBody = response.body.trim();
        setState(() {
          _messages[assistantIndex] = _messages[assistantIndex].copyWith(
            content: responseBody.isEmpty
                ? 'HTTP ${response.statusCode} with empty response body.'
                : responseBody,
            meta:
                'POST ${_buildEndpointUri()} • HTTP ${response.statusCode} • stream=false',
          );
        });
      }
    } catch (e) {
      setState(() {
        _messages[assistantIndex] = _messages[assistantIndex].copyWith(
          role: 'system',
          content: 'Request failed: $e',
          meta: 'POST ${_buildEndpointUri()} • stream=$_streamResponse',
        );
      });
    } finally {
      if (mounted) {
        setState(() {
          _isSending = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final endpoint = _buildEndpointUri().toString();
    final service = context.watch<ServiceManager>();

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Chat',
              style: GoogleFonts.inter(
                fontSize: 28,
                fontWeight: FontWeight.w900,
                letterSpacing: -0.5,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Endpoint comes from [channels_config.webchat] in config.toml.',
              style: TextStyle(
                fontSize: 14,
                color: colorScheme.onSurface.withAlpha(
                  ((0.68).clamp(0.0, 1.0) * 255).round(),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colorScheme.surface.withAlpha(
                  ((0.55).clamp(0.0, 1.0) * 255).round(),
                ),
                borderRadius: BorderRadius.circular(18),
                border: Border.all(
                  color: colorScheme.onSurface.withAlpha(
                    ((0.08).clamp(0.0, 1.0) * 255).round(),
                  ),
                ),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'webchat',
                          style: GoogleFonts.inter(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const SizedBox(height: 6),
                        SelectableText(
                          endpoint,
                          style: TextStyle(
                            fontSize: 13,
                            color: colorScheme.onSurface.withAlpha(
                              ((0.72).clamp(0.0, 1.0) * 255).round(),
                            ),
                          ),
                        ),
                        if (_configError != null) ...[
                          const SizedBox(height: 6),
                          Text(
                            _configError!,
                            style: TextStyle(
                              fontSize: 12,
                              color: colorScheme.error,
                            ),
                          ),
                        ],
                        const SizedBox(height: 6),
                        Text(
                          service.status == ServiceStatus.running
                              ? 'Service running'
                              : 'Service not running',
                          style: TextStyle(
                            fontSize: 12,
                            color: service.status == ServiceStatus.running
                                ? colorScheme.tertiary
                                : colorScheme.error,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Row(
                          children: [
                            Switch(
                              value: _streamResponse,
                              onChanged: _isSending
                                  ? null
                                  : (value) {
                                      setState(() {
                                        _streamResponse = value;
                                      });
                                    },
                            ),
                            Text(
                              _streamResponse ? 'Stream on' : 'Stream off',
                              style: TextStyle(
                                fontSize: 12,
                                color: colorScheme.onSurface.withAlpha(
                                  ((0.72).clamp(0.0, 1.0) * 255).round(),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 12),
                  OutlinedButton(
                    onPressed: _isLoadingConfig ? null : _loadWebchatConfig,
                    child: Text(_isLoadingConfig ? 'Loading...' : 'Reload'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            Expanded(
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: colorScheme.surface.withAlpha(
                    ((0.5).clamp(0.0, 1.0) * 255).round(),
                  ),
                  borderRadius: BorderRadius.circular(24),
                  border: Border.all(
                    color: colorScheme.onSurface.withAlpha(
                      ((0.08).clamp(0.0, 1.0) * 255).round(),
                    ),
                  ),
                ),
                child: ListView.separated(
                  itemCount: _messages.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 14),
                  itemBuilder: (context, index) {
                    final message = _messages[index];
                    final isUser = message.role == 'user';
                    return Align(
                      alignment: isUser
                          ? Alignment.centerRight
                          : Alignment.centerLeft,
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 560),
                        child: Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: isUser
                                ? colorScheme.secondary.withAlpha(
                                    ((0.12).clamp(0.0, 1.0) * 255).round(),
                                  )
                                : colorScheme.onSurface.withAlpha(
                                    ((0.05).clamp(0.0, 1.0) * 255).round(),
                                  ),
                            borderRadius: BorderRadius.circular(18),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                message.role.toUpperCase(),
                                style: GoogleFonts.inter(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: 1.2,
                                  color: isUser
                                      ? colorScheme.secondary
                                      : colorScheme.tertiary,
                                ),
                              ),
                              if (message.meta != null) ...[
                                const SizedBox(height: 6),
                                Text(
                                  message.meta!,
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: colorScheme.onSurface.withAlpha(
                                      ((0.56).clamp(0.0, 1.0) * 255).round(),
                                    ),
                                  ),
                                ),
                              ],
                              const SizedBox(height: 8),
                              Text(
                                message.content,
                                style: const TextStyle(
                                  fontSize: 14,
                                  height: 1.5,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _inputController,
                    onSubmitted: (_) => _submitMessage(),
                    decoration: InputDecoration(
                      hintText: 'POST message to $_webchatPath',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton(
                  onPressed: _isSending ? null : _submitMessage,
                  child: Text(_isSending ? 'Sending...' : 'Send'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ChatMessage {
  const _ChatMessage({required this.role, required this.content, this.meta});

  final String role;
  final String content;
  final String? meta;

  _ChatMessage copyWith({
    String? role,
    String? content,
    String? meta,
  }) {
    return _ChatMessage(
      role: role ?? this.role,
      content: content ?? this.content,
      meta: meta ?? this.meta,
    );
  }
}

class _WebChatConfig {
  const _WebChatConfig({required this.port, required this.listenPath});

  final int port;
  final String listenPath;
}
