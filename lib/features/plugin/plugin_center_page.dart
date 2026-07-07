/// 插件中心页面
///
/// 采用现代工作台布局：顶部能力概览 + 插件能力卡 + 动态扩展槽位。

import 'package:flutter/material.dart';
import '../../app.dart';
import '../../widgets/modern_ui.dart';

class PluginCenterPage extends StatelessWidget {
  const PluginCenterPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.extension_rounded, color: kAccentColor),
            SizedBox(width: 8),
            Text('插件中心'),
          ],
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 96),
        children: const [
          ModernHeroCard(
            title: 'AI-GG 插件工作台',
            subtitle: '搜索、脚本、悬浮窗和 AI 能力已统一沉淀为可扩展插件能力。',
            icon: Icons.hub_rounded,
          ),
          SizedBox(height: 4),
          _PluginStatusCard(),
          SizedBox(height: 10),
          SectionHeader(title: '核心能力', subtitle: '当前版本内置能力概览'),
          _PluginFeatureGrid(),
          SizedBox(height: 18),
          SectionHeader(title: '动态扩展槽位', subtitle: '后续可接入远程插件、模板与脚本市场'),
          _ExtensionSlotGrid(),
        ],
      ),
    );
  }
}

class _PluginStatusCard extends StatelessWidget {
  const _PluginStatusCard();

  @override
  Widget build(BuildContext context) {
    return ModernCard(
      semanticLabel: '插件系统状态',
      padding: const EdgeInsets.all(18),
      child: Row(
        children: [
          Container(
            width: 52,
            height: 52,
            decoration: BoxDecoration(
              color: kSuccessColor.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(18),
            ),
            child: const Icon(Icons.verified_rounded, color: kSuccessColor, size: 28),
          ),
          const SizedBox(width: 14),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '增强悬浮窗已就绪',
                  style: TextStyle(
                    color: kTextPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                SizedBox(height: 5),
                Text(
                  '高级数值逻辑集中到悬浮窗面板，主界面保留插件调度与扩展入口。',
                  style: TextStyle(color: kTextSecondary, fontSize: 12.5, height: 1.35),
                ),
              ],
            ),
          ),
          const StatusPill(
            icon: Icons.bolt_rounded,
            label: 'READY',
            color: kSuccessColor,
          ),
        ],
      ),
    );
  }
}

class _PluginFeatureGrid extends StatelessWidget {
  const _PluginFeatureGrid();

  @override
  Widget build(BuildContext context) {
    const items = [
      _FeatureItem(Icons.search_rounded, '数值搜索', '精确 / 模糊 / AOB'),
      _FeatureItem(Icons.terminal_rounded, '脚本执行', 'Lua 模板与日志'),
      _FeatureItem(Icons.smart_toy_rounded, 'AI 助手', '函数调用与上下文'),
      _FeatureItem(Icons.picture_in_picture_alt_rounded, '悬浮窗', '游戏内快捷面板'),
    ];

    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: items.length,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 1.25,
      ),
      itemBuilder: (context, index) => _FeatureTile(item: items[index]),
    );
  }
}

class _ExtensionSlotGrid extends StatelessWidget {
  const _ExtensionSlotGrid();

  @override
  Widget build(BuildContext context) {
    return GridView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: 4,
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 1.18,
      ),
      itemBuilder: (context, index) {
        return ModernCard(
          semanticLabel: '扩展槽 ${index + 1}',
          margin: EdgeInsets.zero,
          padding: const EdgeInsets.all(14),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  color: kSplashColor.withValues(alpha: 0.55),
                  borderRadius: BorderRadius.circular(17),
                ),
                child: const Icon(Icons.add_circle_outline_rounded, color: kAccentColor),
              ),
              const SizedBox(height: 10),
              Text(
                '扩展槽 ${index + 1}',
                style: const TextStyle(
                  color: kTextPrimary,
                  fontSize: 13,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 4),
              const Text(
                '等待插件下发',
                style: TextStyle(color: kTextSecondary, fontSize: 11.5),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _FeatureTile extends StatelessWidget {
  final _FeatureItem item;

  const _FeatureTile({required this.item});

  @override
  Widget build(BuildContext context) {
    return ModernCard(
      semanticLabel: item.title,
      margin: EdgeInsets.zero,
      padding: const EdgeInsets.all(15),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(
              color: kAccentColor.withValues(alpha: 0.09),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(item.icon, color: kAccentColor, size: 23),
          ),
          const Spacer(),
          Text(
            item.title,
            style: const TextStyle(
              color: kTextPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            item.subtitle,
            style: const TextStyle(color: kTextSecondary, fontSize: 11.5),
          ),
        ],
      ),
    );
  }
}

class _FeatureItem {
  final IconData icon;
  final String title;
  final String subtitle;

  const _FeatureItem(this.icon, this.title, this.subtitle);
}
