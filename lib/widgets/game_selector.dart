/// 游戏选择器组件
/// 显示运行中的进程列表，允许用户选择目标游戏

import 'package:flutter/material.dart';
import '../app.dart';
import '../core/models/process_info.dart';
import 'modern_ui.dart';

/// 游戏选择器组件
class GameSelector extends StatefulWidget {
  /// 进程列表
  final List<ProcessInfo> processes;

  /// 选中回调
  final ValueChanged<ProcessInfo>? onSelected;

  /// 当前选中的进程
  final ProcessInfo? selectedProcess;

  /// 是否正在加载
  final bool isLoading;

  /// 刷新回调
  final VoidCallback? onRefresh;

  const GameSelector({
    super.key,
    required this.processes,
    this.onSelected,
    this.selectedProcess,
    this.isLoading = false,
    this.onRefresh,
  });

  @override
  State<GameSelector> createState() => _GameSelectorState();
}

class _GameSelectorState extends State<GameSelector> {
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<ProcessInfo> get _filteredProcesses {
    final query = _searchQuery.trim().toLowerCase();
    if (query.isEmpty) return widget.processes;
    return widget.processes.where((p) {
      return p.packageName.toLowerCase().contains(query) ||
          p.processName.toLowerCase().contains(query);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _filteredProcesses;

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: TextField(
            controller: _searchController,
            decoration: InputDecoration(
              hintText: '搜索进程或包名...',
              prefixIcon: const Icon(Icons.search_rounded, size: 18),
              suffixIcon: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (_searchQuery.isNotEmpty)
                    IconButton(
                      tooltip: '清空搜索',
                      icon: const Icon(Icons.close_rounded, size: 18),
                      onPressed: () {
                        _searchController.clear();
                        setState(() => _searchQuery = '');
                      },
                    ),
                  if (widget.onRefresh != null)
                    IconButton(
                      tooltip: '刷新列表',
                      icon: const Icon(Icons.refresh_rounded, size: 18),
                      onPressed: widget.onRefresh,
                    ),
                ],
              ),
            ),
            onChanged: (value) => setState(() => _searchQuery = value),
          ),
        ),
        if (widget.selectedProcess != null)
          ModernCard(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            padding: const EdgeInsets.all(14),
            selected: true,
            child: Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(
                    color: kAccentColor.withValues(alpha: 0.1),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Icon(
                    Icons.check_circle_rounded,
                    color: kAccentColor,
                    size: 21,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.selectedProcess!.packageName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: kTextPrimary,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        'PID: ${widget.selectedProcess!.pid}',
                        style: const TextStyle(
                          fontSize: 12,
                          color: kTextSecondary,
                        ),
                      ),
                    ],
                  ),
                ),
                const StatusPill(
                  icon: Icons.link_rounded,
                  label: '已选择',
                  color: kAccentColor,
                ),
              ],
            ),
          ),
        const SizedBox(height: 8),
        if (widget.isLoading)
          const Padding(
            padding: EdgeInsets.all(24),
            child: CircularProgressIndicator(),
          )
        else if (filtered.isEmpty)
          const Padding(
            padding: EdgeInsets.all(20),
            child: EmptyIllustration(
              icon: Icons.manage_search_rounded,
              title: '未找到进程',
              subtitle: '可以尝试刷新列表，或检查目标应用是否正在运行。',
            ),
          )
        else
          SizedBox(
            height: 320,
            child: ListView.separated(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              itemCount: filtered.length,
              separatorBuilder: (_, __) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                final process = filtered[index];
                final isSelected = widget.selectedProcess?.pid == process.pid;

                return ModernCard(
                  margin: EdgeInsets.zero,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  selected: isSelected,
                  onTap: () => widget.onSelected?.call(process),
                  child: Row(
                    children: [
                      Icon(
                        isSelected
                            ? Icons.check_circle_rounded
                            : Icons.apps_rounded,
                        color: isSelected ? kAccentColor : kTextSecondary,
                        size: 21,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              process.packageName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: kTextPrimary,
                                fontSize: 14,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              'PID: ${process.pid}',
                              style: const TextStyle(
                                fontSize: 12,
                                color: kTextSecondary,
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (process.isSystem)
                        const StatusPill(
                          icon: Icons.shield_rounded,
                          label: '系统',
                          color: kWarningColor,
                        ),
                    ],
                  ),
                );
              },
            ),
          ),
      ],
    );
  }
}
