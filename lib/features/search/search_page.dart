/// 内存搜索页面 - 紧凑设计

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../app.dart';
import '../../core/models/memory_result.dart';
import '../../widgets/modern_ui.dart';
import '../process/process_selector.dart';

/// 搜索类型
enum SearchType { exact, fuzzy, range, aob }

/// 当前搜索类型 Provider
final searchTypeProvider = StateProvider<SearchType>((ref) => SearchType.exact);

/// 搜索结果 Provider
final searchResultsProvider = StateProvider<List<MemoryResult>>((ref) => []);

/// 数据类型 Provider
final dataTypeProvider = StateProvider<DataType>((ref) => DataType.dword);

/// 控制面板是否折叠
final controlsCollapsedProvider = StateProvider<bool>((ref) => false);

/// 内存搜索页面
class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key});

  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  final TextEditingController _valueController = TextEditingController();
  final TextEditingController _minController = TextEditingController();
  final TextEditingController _maxController = TextEditingController();
  String _selectedFuzzyComparison = 'changed';

  @override
  void dispose() {
    _valueController.dispose();
    _minController.dispose();
    _maxController.dispose();
    super.dispose();
  }

  Future<void> _performSearch() async {
    final attachedProcess = ref.read(attachedProcessProvider);
    if (attachedProcess == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请先附加游戏进程')),
      );
      return;
    }

    final searchType = ref.read(searchTypeProvider);
    final dataType = ref.read(dataTypeProvider);

    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      ref.read(searchResultsProvider.notifier).state = [];

      List<dynamic>? rawResults;

      switch (searchType) {
        case SearchType.range:
          final min = int.tryParse(_minController.text);
          final max = int.tryParse(_maxController.text);
          if (min == null || max == null) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请输入有效的范围值')),
            );
            return;
          }
          rawResults = await channel.invokeMethod('searchByRange', {
            'minValue': min,
            'maxValue': max,
            'type': dataType.name,
          }) as List<dynamic>?;

        case SearchType.fuzzy:
          rawResults = await channel.invokeMethod('searchFuzzy', {
            'comparison': _selectedFuzzyComparison,
            'type': dataType.name,
          }) as List<dynamic>?;

        case SearchType.aob:
          final pattern = _valueController.text.trim();
          if (pattern.isEmpty) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请输入特征码')),
            );
            return;
          }
          rawResults = await channel.invokeMethod('searchAob', {
            'pattern': pattern,
          }) as List<dynamic>?;

        case SearchType.exact:
          final value = _valueController.text.trim();
          if (value.isEmpty) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请输入搜索值')),
            );
            return;
          }

          dynamic searchValue;
          switch (dataType) {
            case DataType.float:
            case DataType.double:
              searchValue = double.tryParse(value);
              break;
            default:
              searchValue = int.tryParse(value);
              break;
          }

          if (searchValue == null) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('请输入有效的数值')),
            );
            return;
          }

          rawResults = await channel.invokeMethod('searchExact', {
            'value': searchValue,
            'type': dataType.name,
          }) as List<dynamic>?;
      }

      if (rawResults != null) {
        final results = rawResults.map((item) {
          return MemoryResult.fromJson(Map<String, dynamic>.from(item as Map));
        }).toList();

        ref.read(searchResultsProvider.notifier).state = results;
        // 有结果后自动折叠控制面板
        if (results.isNotEmpty) {
          ref.read(controlsCollapsedProvider.notifier).state = true;
        }
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('找到 ${results.length} 个结果'),
            duration: const Duration(seconds: 1),
          ),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('搜索失败: $e')),
      );
    }
  }

  Future<void> _writeMemory(MemoryResult result, dynamic newValue) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final success = await channel.invokeMethod('writeMemory', {
        'address': result.addressInt,
        'value': newValue,
        'type': result.type.name,
      });
      if (success == true) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已修改 ${result.address} = $newValue')),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('写入失败: $e')),
      );
    }
  }

  Future<void> _freezeMemory(MemoryResult result, dynamic value) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      final success = await channel.invokeMethod('freezeMemory', {
        'address': result.addressInt,
        'value': value,
        'type': result.type.name,
      });
      if (success == true) {
        final results = ref.read(searchResultsProvider);
        ref.read(searchResultsProvider.notifier).state = results.map((r) {
          if (r.addressInt == result.addressInt) {
            return r.copyWith(isFrozen: true, frozenValue: value);
          }
          return r;
        }).toList();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已冻结 ${result.address} = $value')),
        );
      }
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('冻结失败: $e')),
      );
    }
  }

  Future<void> _unfreezeMemory(MemoryResult result) async {
    try {
      const channel = MethodChannel('com.yl.aigg/bridge');
      await channel.invokeMethod('unfreezeMemory', {
        'address': result.addressInt,
      });
      final results = ref.read(searchResultsProvider);
      ref.read(searchResultsProvider.notifier).state = results.map((r) {
        if (r.addressInt == result.addressInt) {
          return r.copyWith(isFrozen: false);
        }
        return r;
      }).toList();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('已解冻 ${result.address}')),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('解冻失败: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final searchType = ref.watch(searchTypeProvider);
    final dataType = ref.watch(dataTypeProvider);
    final results = ref.watch(searchResultsProvider);
    final attachedProcess = ref.watch(attachedProcessProvider);
    final collapsed = ref.watch(controlsCollapsedProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.manage_search_rounded, color: kAccentColor),
            SizedBox(width: 8),
            Text('内存搜索'),
          ],
        ),
        actions: [
          // 折叠/展开按钮
          if (results.isNotEmpty)
            IconButton(
              icon: Icon(collapsed ? Icons.expand_more : Icons.expand_less),
              onPressed: () {
                ref.read(controlsCollapsedProvider.notifier).state = !collapsed;
              },
            ),
        ],
      ),
      body: Column(
        children: [
          ModernHeroCard(
            title: '内存搜索控制台',
            subtitle: attachedProcess != null
                ? '${attachedProcess.packageName} · PID:${attachedProcess.pid}'
                : '未附加进程，点击下方状态栏选择目标。',
            icon: Icons.manage_search_rounded,
          ),
          _buildTopBar(attachedProcess, searchType, dataType, collapsed),
          if (!collapsed) _buildSearchInput(searchType, dataType),
          if (results.isNotEmpty) _buildResultBar(results, collapsed, searchType, dataType),
          Expanded(
            child: results.isEmpty
                ? _buildEmptyState(attachedProcess)
                : _buildResultList(results),
          ),
        ],
      ),
    );
  }

  /// 顶部紧凑栏：进程状态 + 搜索类型切换 + 数据类型
  Widget _buildTopBar(
    dynamic attachedProcess,
    SearchType searchType,
    DataType dataType,
    bool collapsed,
  ) {
    return ModernCard(
      margin: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 第一行：进程状态
          GestureDetector(
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const ProcessSelectorPage()),
              );
            },
            child: Row(
              children: [
                Icon(
                  attachedProcess != null ? Icons.check_circle : Icons.circle,
                  size: 10,
                  color: attachedProcess != null ? Colors.green : Colors.red,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    attachedProcess != null
                        ? '${attachedProcess.packageName} (PID:${attachedProcess.pid})'
                        : '未附加进程 - 点击选择',
                    style: TextStyle(
                      color: attachedProcess != null ? Colors.green : Color(0xFFA1887F),
                      fontSize: 12,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                if (attachedProcess != null)
                  const Icon(Icons.arrow_forward_ios, size: 12, color: Color(0xFFA1887F)),
              ],
            ),
          ),
          const SizedBox(height: 6),
          // 第二行：搜索类型 + 数据类型 + 搜索/重置按钮
          Row(
            children: [
              // 搜索类型选择
              ...SearchType.values.map((type) {
                final isSelected = type == searchType;
                final labels = {
                  SearchType.exact: '精确',
                  SearchType.fuzzy: '模糊',
                  SearchType.range: '范围',
                  SearchType.aob: '特征码',
                };
                return Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: ChoiceChip(
                    label: Text(labels[type]!, style: const TextStyle(fontSize: 11)),
                    selected: isSelected,
                    onSelected: (_) {
                      ref.read(searchTypeProvider.notifier).state = type;
                    },
                    selectedColor: const Color(0xFF8D6E63),
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    labelPadding: const EdgeInsets.symmetric(horizontal: 4),
                  ),
                );
              }),
              const Spacer(),
              // 数据类型下拉
              Container(
                height: 32,
                padding: const EdgeInsets.symmetric(horizontal: 8),
                decoration: BoxDecoration(
                  color: const Color(0xFFFFF9F0),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<DataType>(
                    value: dataType,
                    isDense: true,
                    dropdownColor: const Color(0xFFFFF9F0),
                    style: const TextStyle(
                      color: Color(0xFF8D6E63),
                      fontSize: 12,
                    ),
                    items: DataType.values
                        .where((t) => t != DataType.string)
                        .map((type) => DropdownMenuItem(
                              value: type,
                              child: Text(type.displayName),
                            ))
                        .toList(),
                    onChanged: (type) {
                      if (type != null) {
                        ref.read(dataTypeProvider.notifier).state = type;
                      }
                    },
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// 搜索输入区域
  Widget _buildSearchInput(SearchType searchType, DataType dataType) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      child: ModernCard(
        margin: EdgeInsets.zero,
        padding: const EdgeInsets.all(12),
        child: _buildInputByType(searchType),
      ),
    );
  }

  Widget _buildInputByType(SearchType searchType) {
    switch (searchType) {
      case SearchType.range:
        return Row(
          children: [
            Expanded(
              child: TextField(
                controller: _minController,
                decoration: const InputDecoration(
                  hintText: '最小值',
                  prefixIcon: Icon(Icons.arrow_downward, size: 16),
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                keyboardType: TextInputType.number,
                style: const TextStyle(fontSize: 14),
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8),
              child: Text('~', style: TextStyle(color: Color(0xFFA1887F))),
            ),
            Expanded(
              child: TextField(
                controller: _maxController,
                decoration: const InputDecoration(
                  hintText: '最大值',
                  prefixIcon: Icon(Icons.arrow_upward, size: 16),
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                keyboardType: TextInputType.number,
                style: const TextStyle(fontSize: 14),
              ),
            ),
            const SizedBox(width: 8),
            _buildSearchResetButtons(),
          ],
        );

      case SearchType.fuzzy:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                _fuzzyChip('changed', '已改变'),
                _fuzzyChip('unchanged', '未改变'),
                _fuzzyChip('increased', '增大'),
                _fuzzyChip('decreased', '减小'),
                _fuzzyChip('greater', '大于'),
                _fuzzyChip('less', '小于'),
                _fuzzyChip('equal', '等于'),
                _fuzzyChip('not_equal', '不等于'),
              ],
            ),
            const SizedBox(height: 8),
            _buildSearchResetButtons(),
          ],
        );

      case SearchType.aob:
        return Row(
          children: [
            Expanded(
              child: TextField(
                controller: _valueController,
                decoration: const InputDecoration(
                  hintText: '特征码 (如: 48 89 5C 24 ? 48)',
                  prefixIcon: Icon(Icons.fingerprint, size: 16),
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                keyboardType: TextInputType.text,
                style: const TextStyle(fontSize: 14),
              ),
            ),
            const SizedBox(width: 8),
            _buildSearchResetButtons(),
          ],
        );

      case SearchType.exact:
        return Row(
          children: [
            Expanded(
              child: TextField(
                controller: _valueController,
                decoration: const InputDecoration(
                  hintText: '输入数值',
                  prefixIcon: Icon(Icons.numbers, size: 16),
                  isDense: true,
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                keyboardType: TextInputType.number,
                style: const TextStyle(fontSize: 14),
              ),
            ),
            const SizedBox(width: 8),
            _buildSearchResetButtons(),
          ],
        );
    }
  }

  /// 搜索 + 重置按钮
  Widget _buildSearchResetButtons() {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: 40,
          child: ElevatedButton(
            onPressed: _performSearch,
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF8D6E63),
              padding: const EdgeInsets.symmetric(horizontal: 16),
            ),
            child: const Icon(Icons.search, size: 20),
          ),
        ),
        const SizedBox(width: 4),
        SizedBox(
          height: 40,
          child: OutlinedButton(
            onPressed: () {
              ref.read(searchResultsProvider.notifier).state = [];
              _valueController.clear();
              _minController.clear();
              _maxController.clear();
              ref.read(controlsCollapsedProvider.notifier).state = false;
            },
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 12),
            ),
            child: const Icon(Icons.refresh, size: 18, color: Color(0xFFA1887F)),
          ),
        ),
      ],
    );
  }

  /// 模糊搜索 Chip
  Widget _fuzzyChip(String value, String label) {
    final isSelected = _selectedFuzzyComparison == value;
    return ChoiceChip(
      label: Text(label, style: const TextStyle(fontSize: 11)),
      selected: isSelected,
      onSelected: (_) {
        setState(() {
          _selectedFuzzyComparison = value;
        });
      },
      selectedColor: const Color(0xFF8D6E63),
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      labelPadding: const EdgeInsets.symmetric(horizontal: 4),
    );
  }

  /// 结果统计栏 (折叠时也显示快速搜索按钮)
  Widget _buildResultBar(
    List<MemoryResult> results,
    bool collapsed,
    SearchType searchType,
    DataType dataType,
  ) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
      child: ModernCard(
        margin: EdgeInsets.zero,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Row(
          children: [
            const StatusPill(
              icon: Icons.list_alt_rounded,
              label: '结果',
              color: kAccentColor,
            ),
            const SizedBox(width: 8),
            Text(
              '${results.length} 个地址',
              style: const TextStyle(
                color: kTextPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w900,
              ),
            ),
            if (results.length > 100)
              const Text(
                ' · 显示前100',
                style: TextStyle(color: kTextSecondary, fontSize: 11),
              ),
            const Spacer(),
            if (collapsed) ...[
              SizedBox(
                height: 32,
                child: ElevatedButton.icon(
                  onPressed: _performSearch,
                  icon: const Icon(Icons.search_rounded, size: 16),
                  label: const Text('搜索', style: TextStyle(fontSize: 12)),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                  ),
                ),
              ),
              const SizedBox(width: 6),
              SizedBox(
                height: 32,
                child: OutlinedButton(
                  onPressed: () {
                    ref.read(controlsCollapsedProvider.notifier).state = false;
                  },
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                  ),
                  child: const Icon(Icons.tune_rounded, size: 16),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// 空状态
  Widget _buildEmptyState(dynamic attachedProcess) {
    return EmptyIllustration(
      icon: attachedProcess == null
          ? Icons.link_off_rounded
          : Icons.search_off_rounded,
      title: attachedProcess == null ? '请先附加游戏进程' : '输入数值开始搜索',
      subtitle: attachedProcess == null
          ? '选择目标后即可使用精确、模糊、范围和特征码搜索。'
          : '设置搜索类型和数据类型后点击搜索按钮。',
    );
  }

  /// 结果列表
  Widget _buildResultList(List<MemoryResult> results) {
    final displayResults = results.length > 100
        ? results.sublist(0, 100)
        : results;

    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 96),
      itemCount: displayResults.length,
      itemBuilder: (context, index) {
        return _buildCompactResultItem(displayResults[index]);
      },
    );
  }

  /// 紧凑的结果项
  Widget _buildCompactResultItem(MemoryResult result) {
    return ModernCard(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      onTap: () => _showEditDialog(result),
      semanticLabel: result.address,
      child: Row(
            children: [
              // 状态图标
              Icon(
                result.isFrozen ? Icons.lock : Icons.memory,
                color: result.isFrozen
                    ? const Color(0xFF6D4C41)
                    : result.isFavorite
                        ? Colors.amber
                        : Color(0xFFA1887F),
                size: 18,
              ),
              const SizedBox(width: 10),
              // 地址
              Expanded(
                flex: 3,
                child: Text(
                  result.address,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: Color(0xFF8D6E63),
                  ),
                ),
              ),
              // 值
              Expanded(
                flex: 2,
                child: Text(
                  '${result.value}',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
                  textAlign: TextAlign.right,
                ),
              ),
              const SizedBox(width: 8),
              // 类型标签
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: const Color(0xFFFFF9F0),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  result.type.displayName,
                  style: const TextStyle(fontSize: 10, color: Color(0xFFA1887F)),
                ),
              ),
              const SizedBox(width: 4),
              // 操作按钮
              PopupMenuButton<String>(
                icon: const Icon(Icons.more_vert, size: 18),
                onSelected: (action) {
                  switch (action) {
                    case 'edit':
                      _showEditDialog(result);
                      break;
                    case 'freeze':
                      if (result.isFrozen) {
                        _unfreezeMemory(result);
                      } else {
                        _showFreezeDialog(result);
                      }
                      break;
                  }
                },
                itemBuilder: (context) => [
                  const PopupMenuItem(value: 'edit', child: Text('修改值')),
                  PopupMenuItem(
                    value: 'freeze',
                    child: Text(result.isFrozen ? '解冻' : '冻结'),
                  ),
                ],
              ),
            ],
          ),
    );
  }

  void _showEditDialog(MemoryResult result) {
    final controller = TextEditingController(text: result.value.toString());

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFFFFF9F0),
        title: const Text('修改内存值'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '地址: ${result.address}',
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Color(0xFF8D6E63),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              decoration: const InputDecoration(
                labelText: '新值',
                hintText: '输入要修改的值',
              ),
              keyboardType: TextInputType.number,
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () {
              final newValue = controller.text.trim();
              if (newValue.isNotEmpty) {
                dynamic value;
                switch (result.type) {
                  case DataType.float:
                  case DataType.double:
                    value = double.tryParse(newValue);
                    break;
                  default:
                    value = int.tryParse(newValue);
                    break;
                }
                if (value != null) {
                  _writeMemory(result, value);
                }
              }
              Navigator.pop(context);
            },
            child: const Text('修改'),
          ),
        ],
      ),
    );
  }

  void _showFreezeDialog(MemoryResult result) {
    final controller = TextEditingController(text: result.value.toString());

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFFFFF9F0),
        title: const Text('冻结内存值'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '地址: ${result.address}',
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Color(0xFF8D6E63),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: controller,
              decoration: const InputDecoration(
                labelText: '冻结值',
                hintText: '输入要冻结的值',
              ),
              keyboardType: TextInputType.number,
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () {
              final freezeValue = controller.text.trim();
              if (freezeValue.isNotEmpty) {
                dynamic value;
                switch (result.type) {
                  case DataType.float:
                  case DataType.double:
                    value = double.tryParse(freezeValue);
                    break;
                  default:
                    value = int.tryParse(freezeValue);
                    break;
                }
                if (value != null) {
                  _freezeMemory(result, value);
                }
              }
              Navigator.pop(context);
            },
            child: const Text('冻结'),
          ),
        ],
      ),
    );
  }
}
