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

    private ErrorCodes() {
    }
}
