/// 书签模型
/// 用户标记的确定基址（如"金币"、"等级"等）

class BookmarkModel {
  /// 书签 ID
  final String id;

  /// 书签名称（如"金币"、"等级"）
  final String name;

  /// 内存地址
  final String address;

  /// 地址的数值表示
  final int addressInt;

  /// 数据类型
  final String dataType;

  /// 当前值（可选）
  final dynamic currentValue;

  /// 冻结值（可选）
  final dynamic frozenValue;

  /// 是否冻结
  final bool isFrozen;

  /// 关联的游戏包名
  final String? packageName;

  /// 关联的进程 PID
  final int? pid;

  /// 创建时间
  final DateTime createdAt;

  /// 更新时间
  final DateTime updatedAt;

  /// 备注
  final String? note;

  const BookmarkModel({
    required this.id,
    required this.name,
    required this.address,
    required this.addressInt,
    required this.dataType,
    this.currentValue,
    this.frozenValue,
    this.isFrozen = false,
    this.packageName,
    this.pid,
    required this.createdAt,
    required this.updatedAt,
    this.note,
  });

  BookmarkModel copyWith({
    String? id,
    String? name,
    String? address,
    int? addressInt,
    String? dataType,
    dynamic currentValue,
    dynamic frozenValue,
    bool? isFrozen,
    String? packageName,
    int? pid,
    DateTime? createdAt,
    DateTime? updatedAt,
    String? note,
  }) {
    return BookmarkModel(
      id: id ?? this.id,
      name: name ?? this.name,
      address: address ?? this.address,
      addressInt: addressInt ?? this.addressInt,
      dataType: dataType ?? this.dataType,
      currentValue: currentValue ?? this.currentValue,
      frozenValue: frozenValue ?? this.frozenValue,
      isFrozen: isFrozen ?? this.isFrozen,
      packageName: packageName ?? this.packageName,
      pid: pid ?? this.pid,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      note: note ?? this.note,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'address': address,
      'addressInt': addressInt,
      'dataType': dataType,
      'currentValue': currentValue,
      'frozenValue': frozenValue,
      'isFrozen': isFrozen,
      'packageName': packageName,
      'pid': pid,
      'createdAt': createdAt.toIso8601String(),
      'updatedAt': updatedAt.toIso8601String(),
      'note': note,
    };
  }

  factory BookmarkModel.fromJson(Map<String, dynamic> json) {
    return BookmarkModel(
      id: json['id'] as String,
      name: json['name'] as String,
      address: json['address'] as String,
      addressInt: json['addressInt'] as int,
      dataType: json['dataType'] as String,
      currentValue: json['currentValue'],
      frozenValue: json['frozenValue'],
      isFrozen: json['isFrozen'] as bool? ?? false,
      packageName: json['packageName'] as String?,
      pid: json['pid'] as int?,
      createdAt: DateTime.parse(json['createdAt'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
      note: json['note'] as String?,
    );
  }

  @override
  String toString() {
    return 'BookmarkModel(name: $name, address: $address, type: $dataType)';
  }
}
