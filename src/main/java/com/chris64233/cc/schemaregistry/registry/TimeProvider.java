package com.chris64233.cc.schemaregistry.registry;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 统一时间来源。生产环境使用系统 UTC 时钟；测试可替换为固定或可控时钟，
 * 以便确定性地验证租约到期、废弃生效与保留期。
 */
@Component
public class TimeProvider {

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return Instant.now(clock);
    }

    /** 仅供测试替换时钟。 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
