package com.chris64233.cc.schemaregistry.registry;

/**
 * 迁移批次中单个冻结消费者的解析状态。
 *
 * <ul>
 *   <li>{@link #PENDING}：尚未确认，且当前仍是源版本的有效（租约未过期）使用者。</li>
 *   <li>{@link #CONFIRMED}：已用唯一迁移事件确认切换到批次目标版本。</li>
 *   <li>{@link #LEASE_EXPIRED}：未确认但消费者租约已自然过期，不再有效依赖源版本。</li>
 *   <li>{@link #DETACHED}：未通过本批次确认，但消费者已自行把依赖登记到其他版本，
 *       不再有效依赖源版本。</li>
 * </ul>
 */
public enum MigrationMemberStatus {
    PENDING,
    CONFIRMED,
    LEASE_EXPIRED,
    DETACHED
}
