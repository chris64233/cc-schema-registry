package com.chris64233.cc.schemaregistry.registry;

/**
 * 冻结消费者在迁移批次中的解决状态。
 *
 * <ul>
 *   <li>{@link #PENDING}：尚未确认，仍需迁移推进。</li>
 *   <li>{@link #CONFIRMED}：已通过批次唯一迁移事件确认切换到目标版本。</li>
 *   <li>{@link #LEASE_EXPIRED}：租约自然过期后被批次剔除（记录保留用于审计）。</li>
 *   <li>{@link #RELOCATED}：未走批次确认、自行把依赖改登记到其它版本（含目标版本），
 *       不再是源版本的活跃使用者。</li>
 * </ul>
 * 进行中的批次按当前依赖实时归类；批次完成时每个成员的状态被冻结，此后不可修改。
 */
public enum MigrationMemberStatus {
    PENDING,
    CONFIRMED,
    LEASE_EXPIRED,
    RELOCATED
}
