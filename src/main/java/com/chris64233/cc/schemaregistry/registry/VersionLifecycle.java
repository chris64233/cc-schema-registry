package com.chris64233.cc.schemaregistry.registry;

/**
 * 契约版本生命周期。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：正常可用。</li>
 *   <li>{@link #DEPRECATION_SCHEDULED}：已登记废弃请求并设置生效时间，等待生效与消费者迁移。</li>
 *   <li>{@link #DEPRECATED}：生效时间已到且不存在有效消费者依赖。</li>
 *   <li>{@link #TOMBSTONE}：受控删除已执行，可变载荷被移除，仅保留摘要、版本号与审计记录。</li>
 * </ul>
 */
public enum VersionLifecycle {
    ACTIVE,
    DEPRECATION_SCHEDULED,
    DEPRECATED,
    TOMBSTONE
}
