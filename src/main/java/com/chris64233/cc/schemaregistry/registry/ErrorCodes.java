package com.chris64233.cc.schemaregistry.registry;

public final class ErrorCodes {

    public static final String SUBJECT_NOT_FOUND = "SUBJECT_NOT_FOUND";
    public static final String SUBJECT_EXISTS = "SUBJECT_EXISTS";
    public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
    public static final String INVALID_CONTRACT = "INVALID_CONTRACT";
    public static final String CONTRACT_INCOMPATIBLE = "CONTRACT_INCOMPATIBLE";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";

    /** 版本当前生命周期不允许该操作（如对已废弃版本发布废弃、对墓碑登记依赖）。 */
    public static final String LIFECYCLE_CONFLICT = "LIFECYCLE_CONFLICT";
    /** 消费者更新的版本条件与当前登记不符。 */
    public static final String VERSION_CONDITION_MISMATCH = "VERSION_CONDITION_MISMATCH";
    /** 迟到的更新（更新号不大于已见更新号）。 */
    public static final String STALE_UPDATE = "STALE_UPDATE";
    /** 版本不满足受控删除条件（仍被兼容性检查依赖、有活跃消费者或保留期未满）。 */
    public static final String DELETE_NOT_ELIGIBLE = "DELETE_NOT_ELIGIBLE";
    /** 迁移批次不存在。 */
    public static final String MIGRATION_BATCH_NOT_FOUND = "MIGRATION_BATCH_NOT_FOUND";
    /** 批次当前状态不允许该操作（如对已完成/已取消批次确认或再次取消）。 */
    public static final String MIGRATION_BATCH_CLOSED = "MIGRATION_BATCH_CLOSED";
    /** 确认事件与批次不匹配：消费者不在冻结集合，或目标版本与批次目标版本不一致。 */
    public static final String MIGRATION_MISMATCH = "MIGRATION_MISMATCH";
    /** 消费者当前登记版本与批次源版本不一致（迟到的旧批次确认不能覆盖后来登记的版本）。 */
    public static final String MIGRATION_VERSION_CONFLICT = "MIGRATION_VERSION_CONFLICT";
    /** 该消费者在此批次上已有确认迁移事件（重复事件按幂等重放，不同事件号则冲突）。 */
    public static final String MIGRATION_ALREADY_CONFIRMED = "MIGRATION_ALREADY_CONFIRMED";
    /** 创建批次时源版本已无有效消费者，冻结集合为空。 */
    public static final String MIGRATION_NO_ACTIVE_CONSUMERS = "MIGRATION_NO_ACTIVE_CONSUMERS";

    private ErrorCodes() {
    }
}
