package com.chris64233.cc.schemaregistry.registry;

/**
 * 版本生命周期：ACTIVE（使用中）→ DEPRECATING（已登记废弃请求，等待生效或消费者迁移）
 * → DEPRECATED（已废弃）。删除只是清除载荷的附加状态（deletedAt 非空），不改变生命周期枚举。
 */
public enum VersionLifecycle {
    ACTIVE,
    DEPRECATING,
    DEPRECATED
}
