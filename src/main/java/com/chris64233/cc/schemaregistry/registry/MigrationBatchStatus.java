package com.chris64233.cc.schemaregistry.registry;

/**
 * 消费者迁移批次状态。
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS}：已冻结消费者集合，等待逐个确认迁移；租约过期的成员会被扫描剔除。</li>
 *   <li>{@link #COMPLETED}：冻结集合全部解决（确认迁移、主动改登记到他版或租约过期），
 *       源版本已被推进原有废弃流程。</li>
 *   <li>{@link #CANCELLED}：完成前被取消，只停止后续迁移推进，不回退已确认的消费者版本。</li>
 * </ul>
 * 批次只在上述状态间单向流转，批次记录与确认历史不可修改。
 */
public enum MigrationBatchStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
