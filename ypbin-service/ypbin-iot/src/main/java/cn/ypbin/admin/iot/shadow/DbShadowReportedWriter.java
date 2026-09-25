/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.shadow;

import cn.ypbin.admin.iot.mapper.IotShadowMapper;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 影子 {@code reported} 的落库写入器（G2）。
 *
 * <p><b>为什么整批只发一条语句</b>：一次上报（access 侧微批）可能含多台设备、多台设备里又各有点位，
 * 逐条/逐设备发语句就是 N+1；这里把整批收敛成一条
 * {@code INSERT ... VALUES (...),(...) ON DUPLICATE KEY UPDATE}（见
 * {@link IotShadowMapper#mergeReported}），往返次数与批大小无关。</p>
 *
 * <p><b>为什么不用「先读 JSON、Java 里合并、再整份写回」</b>：那是 read-modify-write，多副本并发上报
 * 同一台设备的不同点位时会丢更新（后写的那份覆盖先写的）。本实现把合并交给数据库在**行锁内**完成
 * （{@code JSON_MERGE_PATCH} + 唯一键 {@code ON DUPLICATE KEY UPDATE}）——按 propertyId 合并是原子且幂等的，
 * 重放同一批读数结果不变。</p>
 *
 * <p><b>租户上下文</b>：调用点在事务提交后（{@code TenantContext} 的显式租户作用域此时已退出），
 * 而 {@code iot_shadow} 是租户表且插件 fail-closed ⇒ 这里用
 * {@link TenantContext#runIgnore(Runnable)} 显式忽略（与断档扫描跨租户读候选同一做法），租户 ID 由**服务端自己**
 * 从 {@code iot_device} 解析后随行携带，绝不来自请求体。语句里因此显式写 {@code tenant_id}，
 * 与 {@code DeviceLivenessMapper#selectByDeviceIncludingDeleted} 的既有做法一致。</p>
 *
 * @author wenbin
 * @since 2026-09-25
 */
public class DbShadowReportedWriter implements ShadowReportedWriter {

    private static final Logger log = LoggerFactory.getLogger(DbShadowReportedWriter.class);

    /** 写入失败计数（按批计）。 */
    public static final String METRIC_FAILED = "iot.ingest.shadow.failed";

    private final IotShadowMapper shadowMapper;
    private final ObjectMapper objectMapper;
    private final Counter failedCounter;

    public DbShadowReportedWriter(IotShadowMapper shadowMapper, ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.shadowMapper = shadowMapper;
        this.objectMapper = objectMapper;
        this.failedCounter = Counter.builder(METRIC_FAILED)
            .description("设备影子 reported 写入失败的批次数").register(meterRegistry);
    }

    @Override
    public void writeAll(List<ShadowReportedUpdate> updates) {
        if (updates.isEmpty()) {
            // 空集合短路：既省一次往返，也避免下游把「空批」当异常
            return;
        }
        try {
            List<ShadowReportedRow> rows = new ArrayList<>(updates.size());
            for (ShadowReportedUpdate update : updates) {
                rows.add(new ShadowReportedRow(IdWorker.getId(), update.tenantId(), update.deviceId(),
                    json(update.reported()), update.reportTs()));
            }
            TenantContext.runIgnore(() -> shadowMapper.mergeReported(rows));
        } catch (RuntimeException ex) {
            failedCounter.increment();
            log.error("[iot] 设备影子 reported 写入失败（已计数，不影响上报落库）：设备数={}",
                LogSanitizer.sanitize(updates.size()), ex);
        }
    }

    /** 增量的 JSON 文本（值统一为字符串，见 {@link ShadowReportedUpdate} 的类注释）。 */
    private String json(Map<String, String> reported) {
        return objectMapper.writeValueAsString(reported);
    }
}
