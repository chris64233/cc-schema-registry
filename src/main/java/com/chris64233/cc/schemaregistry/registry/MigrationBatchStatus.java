package com.chris64233.cc.schemaregistry.registry;

/**
 * 消费者迁移批次状态。
 *
 * <ul>
 *   <li>{@link #OPEN}：批次已创建并冻结消费者集合，等待各消费者确认迁移或租约过期。</li>
 *   <li>{@link #COMPLETED}：冻结集合已全部解决（确认迁移或租约过期/自行迁出），
 *       源版本已被推动进入原有废弃流程。</li>
 *   <li>{@link #CANCELLED}：完成前被取消；只停止后续迁移推进，已确认的消费者版本不回退。</li>
 * </ul>
 *
 * 批次只允许 OPEN → COMPLETED / OPEN → CANCELLED 两种单向流转，批次本身不可修改。
 */
public enum MigrationBatchStatus {
    OPEN,
    COMPLETED,
    CANCELLED
}
