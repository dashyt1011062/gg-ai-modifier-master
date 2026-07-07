/// GG Modifier 应用入口

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'features/home/home_page.dart';

/// 应用主题色（Material 3 暖科技风）
const Color kPrimaryColor = Color(0xFF8D6E63);
const Color kSecondaryColor = Color(0xFFB97945);
const Color kBackgroundColor = Color(0xFFF8F2E9);
const Color kSurfaceColor = Color(0xFFFFFBF5);
const Color kSurfaceElevated = Color(0xFFFFFFFF);
const Color kErrorColor = Color(0xFFBA1A1A);
const Color kTextPrimary = Color(0xFF2B1B14);
const Color kTextSecondary = Color(0xFF7B6257);
const Color kAccentColor = Color(0xFF5D4037);
const Color kSplashColor = Color(0xFFFFE4C7);
const Color kSuccessColor = Color(0xFF2E7D5B);
const Color kWarningColor = Color(0xFFC47A16);

const LinearGradient kAppBackgroundGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment.bottomRight,
  colors: [
    Color(0xFFFFFBF5),
    Color(0xFFF8F2E9),
    Color(0xFFF1E2D0),
  ],
);

const LinearGradient kHeroGradient = LinearGradient(
  begin: Alignment.topLeft,
  end: Alignment.bottomRight,
  colors: [Color(0xFF6D4C41), Color(0xFF8D6E63), Color(0xFFB97945)],
);

/// GG Modifier 主应用
class GgModifierApp extends ConsumerWidget {
  const GgModifierApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp(
      title: 'GG-AI Modifier',
      debugShowCheckedModeBanner: false,
      theme: buildGgModifierTheme(),
      home: const AppBackground(child: HomePage()),
    );
  }
}

ThemeData buildGgModifierTheme() {
  const colorScheme = ColorScheme.light(
    primary: kAccentColor,
    onPrimary: Colors.white,
    primaryContainer: Color(0xFFFFE1CF),
    onPrimaryContainer: kTextPrimary,
    secondary: kSecondaryColor,
    onSecondary: Colors.white,
    secondaryContainer: Color(0xFFFFDCC4),
    onSecondaryContainer: Color(0xFF3A1B00),
    tertiary: Color(0xFF4F6759),
    onTertiary: Colors.white,
    tertiaryContainer: Color(0xFFD2EBD9),
    onTertiaryContainer: Color(0xFF0D2117),
    surface: kSurfaceColor,
    onSurface: kTextPrimary,
    surfaceContainerLowest: Color(0xFFFFFFFF),
    surfaceContainerLow: Color(0xFFFFF8EF),
    surfaceContainer: Color(0xFFF7EFE5),
    surfaceContainerHigh: Color(0xFFF0E6DA),
    outline: Color(0xFFD6C3B7),
    outlineVariant: Color(0xFFE7D7CB),
    error: kErrorColor,
    onError: Colors.white,
  );

  final base = ThemeData(
    useMaterial3: true,
    brightness: Brightness.light,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: Colors.transparent,
    splashColor: kSplashColor.withValues(alpha: 0.42),
    highlightColor: kSplashColor.withValues(alpha: 0.35),
    visualDensity: VisualDensity.adaptivePlatformDensity,
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(
      bodyColor: kTextPrimary,
      displayColor: kTextPrimary,
      fontFamilyFallback: const ['PingFang SC', 'Microsoft YaHei', 'Noto Sans CJK SC'],
    ),
    appBarTheme: const AppBarTheme(
      backgroundColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: true,
      foregroundColor: kTextPrimary,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: TextStyle(
        color: kTextPrimary,
        fontSize: 18,
        fontWeight: FontWeight.w800,
        letterSpacing: 0.2,
      ),
    ),
    cardTheme: CardThemeData(
      color: kSurfaceElevated,
      elevation: 0,
      shadowColor: const Color(0x1A5D4037),
      surfaceTintColor: Colors.transparent,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(22),
        side: const BorderSide(color: Color(0x33D6C3B7)),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 70,
      elevation: 0,
      backgroundColor: kSurfaceElevated.withValues(alpha: 0.88),
      indicatorColor: kSplashColor,
      labelTextStyle: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return TextStyle(
          color: selected ? kAccentColor : kTextSecondary,
          fontSize: 12,
          fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
        );
      }),
      iconTheme: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return IconThemeData(
          color: selected ? kAccentColor : kTextSecondary,
          size: selected ? 25 : 23,
        );
      }),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: kSurfaceElevated,
      hintStyle: const TextStyle(color: kTextSecondary),
      labelStyle: const TextStyle(color: kTextSecondary, fontWeight: FontWeight.w600),
      prefixIconColor: kTextSecondary,
      suffixIconColor: kTextSecondary,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: Color(0xFFE7D7CB)),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: Color(0xFFE7D7CB)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: kSecondaryColor, width: 1.7),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: const BorderSide(color: kErrorColor),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 15),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: kAccentColor,
        foregroundColor: Colors.white,
        elevation: 0,
        shadowColor: Colors.transparent,
        padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 13),
        textStyle: const TextStyle(fontWeight: FontWeight.w800, letterSpacing: 0.2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        foregroundColor: kAccentColor,
        side: const BorderSide(color: Color(0xFFD6C3B7)),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 13),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: kAccentColor,
        textStyle: const TextStyle(fontWeight: FontWeight.w700),
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      backgroundColor: kSurfaceElevated,
      selectedColor: kSplashColor,
      checkmarkColor: kAccentColor,
      labelStyle: const TextStyle(color: kTextPrimary, fontWeight: FontWeight.w700),
      secondaryLabelStyle: const TextStyle(color: kAccentColor, fontWeight: FontWeight.w800),
      side: const BorderSide(color: Color(0xFFE7D7CB)),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: const Color(0xFF3E2723),
      contentTextStyle: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: kSurfaceColor,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      titleTextStyle: const TextStyle(
        color: kTextPrimary,
        fontSize: 20,
        fontWeight: FontWeight.w800,
      ),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: kSurfaceElevated,
      surfaceTintColor: Colors.transparent,
      elevation: 8,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
    ),
    dividerTheme: const DividerThemeData(color: Color(0xFFE7D7CB), thickness: 1),
  );
}

/// 应用统一渐变背景
class AppBackground extends StatelessWidget {
  final Widget child;
  final bool withDecorations;

  const AppBackground({super.key, required this.child, this.withDecorations = true});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(gradient: kAppBackgroundGradient),
      child: Stack(
        children: [
          if (withDecorations) ...const [
            Positioned(
              top: -120,
              right: -80,
              child: _BlurCircle(size: 260, color: Color(0x33B97945)),
            ),
            Positioned(
              left: -90,
              bottom: 70,
              child: _BlurCircle(size: 220, color: Color(0x2A6D4C41)),
            ),
          ],
          child,
        ],
      ),
    );
  }
}

class _BlurCircle extends StatelessWidget {
  final double size;
  final Color color;

  const _BlurCircle({required this.size, required this.color});

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(shape: BoxShape.circle, color: color),
      ),
    );
  }
}

/// 奶油感点击包裹组件
/// 按下时轻微缩放 + 背景色过渡，替代原生水波纹
class MilkClickWrapper extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final BorderRadius borderRadius;
  final Color? backgroundColor;

  const MilkClickWrapper({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.borderRadius = const BorderRadius.all(Radius.circular(16)),
    this.backgroundColor,
  });

  @override
  State<MilkClickWrapper> createState() => _MilkClickWrapperState();
}

class _MilkClickWrapperState extends State<MilkClickWrapper>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  late final Animation<double> _scaleAnim;
  late final Animation<Color?> _colorAnim;

  static const _duration = Duration(milliseconds: 150);
  static const _pressedScale = 0.97;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: _duration);
    _scaleAnim = Tween<double>(begin: 1.0, end: _pressedScale).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final base = widget.backgroundColor ?? kSurfaceElevated;
    _colorAnim = ColorTween(begin: base, end: kSplashColor).animate(
      CurvedAnimation(parent: _controller, curve: Curves.easeOutCubic),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onTapDown(TapDownDetails _) => _controller.forward();

  void _onTapUp(TapUpDetails _) {
    _controller.reverse();
    widget.onTap?.call();
  }

  void _onTapCancel() => _controller.reverse();

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: _onTapDown,
      onTapUp: _onTapUp,
      onTapCancel: _onTapCancel,
      onLongPressStart: (_) {
        _controller.forward();
        widget.onLongPress?.call();
      },
      onLongPressEnd: (_) => _controller.reverse(),
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return Transform.scale(
            scale: _scaleAnim.value,
            child: Container(
              decoration: BoxDecoration(
                color: _colorAnim.value,
                borderRadius: widget.borderRadius,
              ),
              child: child,
            ),
          );
        },
        child: widget.child,
      ),
    );
  }
}
