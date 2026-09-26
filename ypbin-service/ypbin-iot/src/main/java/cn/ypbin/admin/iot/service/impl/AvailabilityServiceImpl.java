/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.service.impl;

import cn.ypbin.admin.iot.availability.AvailabilityCalculator;
import cn.ypbin.admin.iot.availability.AvailabilityProperties;
import cn.ypbin.admin.iot.availability.AvailabilityResp;
import cn.ypbin.admin.iot.availability.MaintenanceWindowDto;
import cn.ypbin.admin.iot.availability.AvailabilityRules;
import cn.ypbin.admin.iot.availability.OutageDetector;
import cn.ypbin.admin.iot.availability.OutageEventResp;
import cn.ypbin.admin.iot.availability.OutageReason;
import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.availability.ReadingObservationDto;
import cn.ypbin.admin.iot.entity.DeviceLiveness;
import cn.ypbin.admin.iot.timeseries.PropertyIdRules;
import cn.ypbin.admin.iot.timeseries.TimeSeriesPoint;
import cn.ypbin.admin.iot.timeseries.TimeSeriesProperties;
import cn.ypbin.admin.iot.timeseries.TimeSeriesWriter;
import cn.ypbin.admin.iot.values.LatestValue;
import cn.ypbin.admin.iot.values.LatestValueWriter;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.MaintenanceWindow;
import cn.ypbin.admin.iot.entity.OutageEvent;
import cn.ypbin.admin.iot.mapper.DeviceLivenessMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.MaintenanceWindowMapper;
import cn.ypbin.admin.iot.mapper.OutageEventMapper;
import cn.ypbin.admin.iot.mapping.PointMappingIndex;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.admin.iot.shadow.ShadowReportedUpdate;
import cn.ypbin.admin.iot.shadow.ShadowReportedWriter;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.core.util.LogSanitizer;
import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.starter.tenant.core.TenantContext;
import cn.ypbin.starter.tenant.core.TenantProvider;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.annotation.Transactional;

/**
 * 断档与可用率服务实现（M-2，口径见 {@link AvailabilityRules}）。
 *
 * <p><b>静默设备是这条链路存在的理由</b>：设备彻底不再上报时没有任何请求进来，所以断档不能只在
 * 「收到读数」时判定——必须由周期扫描按「最近有效数据 + K × 采集周期」打开断档。
 * 上报只负责两件事：刷新活性、以及有效数据到达时闭合断档。</p>
 *
 * <p><b>租户上下文</b>：内部上报端点只有 {@code X-Internal-Token}、没有租户身份，而
 * {@code iot_device} / {@code device_liveness} 都是租户表（插件 fail-closed）⇒ 先用
 * {@link TenantContext#executeIgnore} 按设备解析出租户，再进入该租户执行写入。扫描同理：
 * 跨租户读候选，逐条进入各自租户写事件——**绝不**手写 tenant_id 过滤（见租户隔离门禁）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@Service
public class AvailabilityServiceImpl implements AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityServiceImpl.class);

    /** 入站点位标识非法被丢弃的条数（§6.5 / P0-6c 的格式子项的可观测出口）。 */
    public static final String METRIC_INVALID_PROPERTY_ID = "iot.ingest.propertyid.rejected";

    /** 入站点位未映射到该设备被丢弃的条数（P0-6c 的成员子项；与格式非法分开计数）。 */
    public static final String METRIC_UNMAPPED_PROPERTY_ID = "iot.ingest.propertyid.unmapped";

    /**
     * 入站点位**归属孤儿映射**（{@code iot_point_mapping} 行在、其 {@code iot_property} 行已缺失）
     * 被丢弃的条数（2026-09-26 孤儿映射收紧）。
     *
     * <p>与 {@value #METRIC_UNMAPPED_PROPERTY_ID} **分开计数**：两者成因不同，处置也不同——「未映射」
     * 是设备没配这条点位（该去配映射），「孤儿」是物模型被重导入后映射成了悬空引用（该清理/重建映射）。
     * 混在一个计数里会让运维无法判断到底该动哪一边。</p>
     */
    public static final String METRIC_ORPHAN_PROPERTY_ID = "iot.ingest.propertyid.orphan";

    /**
     * 交给时序出口的**待写点数**（累计）。
     *
     * <p>为什么要有它：时序写入此前**只有失败计数**（见 {@code IotDbTimeSeriesWriter.METRIC_FAILED}），
     * 于是「压根没收集到点」与「收集了却没写进去」在生产上无法区分——2026-09-26 实测到
     * 「`write.failed=0` 而 IoTDB 当天 0 行」正是被这个盲区藏住的。本计数与写入器的
     * {@code iot.timeseries.write.attempted}/{@code iot.timeseries.write.rows} 配对后，
     * 三者一比对即可定位断点在哪一段。</p>
     */
    public static final String METRIC_SERIES_COLLECTED = "iot.timeseries.points.collected";

    private final DeviceLivenessMapper livenessMapper;
    private final OutageEventMapper outageMapper;

    private final MaintenanceWindowMapper maintenanceWindowMapper;

    /** 租户来源：与 MP 租户插件**完全一致**（ThreadLocal 优先，其次 TenantProvider/IdentityContext）。 */
    private final TenantProvider tenantProvider;

    /**
     * 最新值写入器（Q8/D0.7：Redis Hash）。放在**事务提交后**写，避免"库回滚了但最新值已生效"的不一致；
     * 写失败只计数+日志，绝不让上报事务回滚（见 {@link cn.ypbin.admin.iot.values.LatestValueWriter}）。
     */
    private final LatestValueWriter latestValueWriter;

    /** 时序写入器（IoTDB 表模型，§5.2.1）：与最新值同一时机写；未启用时是「WARN 一次并丢弃」的实现。 */
    private final TimeSeriesWriter timeSeriesWriter;

    /** 时序配置：只在启用时才收集点位（默认关闭时不产生额外分配）。 */
    private final TimeSeriesProperties timeSeriesProperties;
    private final IotDeviceMapper deviceMapper;
    private final AvailabilityProperties properties;

    /**
     * 影子 reported 写入器（G2）：读数上报驱动「设备当前状态」，与 desired 合成真正的 merged。
     * 与最新值同一时机（事务提交后）写，失败只计数 + 日志，绝不让上报事务回滚。
     */
    private final ShadowReportedWriter shadowReportedWriter;

    /**
     * 入站点位标识非法的丢弃计数（§6.5 / P0-6c）。
     *
     * <p>点位标识是**外部输入**，且会被拼进 IoTDB 查询字面量与 Redis field 名 ⇒ 必须在入站就挡。
     * 丢弃必须可观测：只 warn 日志会在压测/攻击下被刷掉，指标才能进告警。</p>
     */
    private final Counter invalidPropertyIdCounter;

    /**
     * 入站点位**未映射**的丢弃计数（P0-6c 的**成员**子项；与「格式非法」分开计数，两类原因必须可分辨）。
     */
    private final Counter unmappedPropertyIdCounter;

    /** 入站点位**归属孤儿映射**的丢弃计数（与「未映射」分开；见 {@value #METRIC_ORPHAN_PROPERTY_ID}）。 */
    private final Counter orphanPropertyIdCounter;

    /** 交给时序出口的待写点数（成功侧观测的一半，见 {@link #METRIC_SERIES_COLLECTED}）。 */
    private final Counter seriesCollectedCounter;

    /** 设备 → 点位坐标集合（P0-6c 成员校验用；一次批量查映射，见 {@link PointMappingIndex}）。 */
    private final PointMappingIndex pointMappingIndex;

    public AvailabilityServiceImpl(DeviceLivenessMapper livenessMapper, OutageEventMapper outageMapper,
                                   MaintenanceWindowMapper maintenanceWindowMapper,
                                   IotDeviceMapper deviceMapper, AvailabilityProperties properties,
                                   TenantProvider tenantProvider, LatestValueWriter latestValueWriter,
                                   TimeSeriesWriter timeSeriesWriter, TimeSeriesProperties timeSeriesProperties,
                                   ShadowReportedWriter shadowReportedWriter, PointMappingIndex pointMappingIndex,
                                   MeterRegistry meterRegistry) {
        this.livenessMapper = livenessMapper;
        this.outageMapper = outageMapper;
        this.maintenanceWindowMapper = maintenanceWindowMapper;
        this.deviceMapper = deviceMapper;
        this.properties = properties;
        this.tenantProvider = tenantProvider;
        this.latestValueWriter = latestValueWriter;
        this.timeSeriesWriter = timeSeriesWriter;
        this.timeSeriesProperties = timeSeriesProperties;
        this.shadowReportedWriter = shadowReportedWriter;
        this.pointMappingIndex = pointMappingIndex;
        this.invalidPropertyIdCounter = Counter.builder(METRIC_INVALID_PROPERTY_ID)
            .description("入站读数因点位标识不合法被丢弃的条数").register(meterRegistry);
        this.unmappedPropertyIdCounter = Counter.builder(METRIC_UNMAPPED_PROPERTY_ID)
            .description("入站读数因点位未映射到该设备被丢弃的条数").register(meterRegistry);
        this.orphanPropertyIdCounter = Counter.builder(METRIC_ORPHAN_PROPERTY_ID)
            .description("入站读数因点位归属孤儿映射（属性行已缺失）被丢弃的条数")
            .register(meterRegistry);
        this.seriesCollectedCounter = Counter.builder(METRIC_SERIES_COLLECTED)
            .description("交给时序出口的待写点数（与写入器的 attempted/rows 配对定位断点）")
            .register(meterRegistry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int ingest(ReadingIngestReq req) {
        // 第①道：点位标识**格式/长度**（纯内存判定，早于任何库访问）
        List<ReadingObservationDto> items = dropInvalidPropertyIds(req.getItems());
        if (items.isEmpty()) {
            return 0;
        }
        // 先解析设备 → 租户：既拿到写活性所需的租户，也顺带把「设备不存在」的条目留给既有分支
        // warn + 丢弃（不应把它们报成「点位未映射」——两种原因必须可分辨）
        Map<Long, Long> tenantByDevice = resolveTenants(deviceIds(items));
        // 第②道：点位**成员/存在性**校验 + **坐标归一**——读数点位必须是该设备已配置且属性行仍存在的
        // 映射点位；通过后一律改写成规范坐标（属性标识）再落库（P0-6c 后半 + 2026-09-26 坐标统一）
        items = canonicalizePropertyIds(items, tenantByDevice.keySet());
        if (items.isEmpty()) {
            return 0;
        }
        Map<Long, DeviceReadingBatch> batches = aggregate(items);
        // 最新值（点位级）与可用率（设备级）是两条独立的关注点：这里只挑「带了点位与值」的读数，
        // 且在**事务提交后**才写（见 writeLatestAfterCommit 的注释）
        List<LatestValue> latestValues = collectLatestValues(items, tenantByDevice);
        // 影子 reported（设备级）：与最新值同一判据（带点位与值的读数），也在提交后写。
        // 它让 merged 真正反映「期望 vs 实际」——此前只有 desired 被写过，merged ≡ desired（G2 缺口）。
        List<ShadowReportedUpdate> shadowUpdates = collectShadowReported(items, tenantByDevice);
        int processed = 0;
        for (Map.Entry<Long, DeviceReadingBatch> entry : batches.entrySet()) {
            Long tenantId = tenantByDevice.get(entry.getKey());
            if (tenantId == null) {
                // 设备不存在（或不在任何租户）：丢弃并暴露，不静默当成有效上报
                log.warn("[iot] 读数上报的设备不存在，已丢弃：deviceId={} 条数={}",
                    LogSanitizer.sanitize(entry.getKey()), entry.getValue().count());
                continue;
            }
            Long deviceId = entry.getKey();
            DeviceReadingBatch batch = entry.getValue();
            processed += TenantContext.executeWithTenant(tenantId, () -> applyBatch(tenantId, deviceId, batch));
        }
        // 时序（历史曲线）与最新值同一时机写：只有启用时才收集，避免默认关闭时的无谓分配
        List<TimeSeriesPoint> seriesPoints = timeSeriesProperties.isEnabled()
            ? collectSeriesPoints(items, tenantByDevice) : List.of();
        writeDerivedAfterCommit(latestValues, seriesPoints, shadowUpdates);
        return processed;
    }

    /**
     * 剔除点位标识不合法的读数（P0-6c 的**格式/长度**子项，见设计 §6.5 的 {@code propertyId} 白名单说明）。
     *
     * <p><b>范围（P0-6c 的两半都在，但仍有明确未做项）</b>：本条只做「字符集白名单 + 长度上限 1~128」，
     * 即**格式**校验；「未映射（物模型属性 × 该设备点位映射）被拒且计数」与「孤儿映射被拒且计数」那一半
     * 由紧随其后的 {@link #canonicalizePropertyIds} 承担（2026-09-26 落地并收紧）。<b>仍未做</b>（不要夸大）：
     * ① 不做「该属性是否属于该产品/服务」的二次校验（映射行本身就是那个声明）；
     * ② {@code enabled=0}（停采）与 {@code ref_type=command} 的映射也会让属性读数通过。
     * ② 已登记在 {@code docs/IOT-ROADMAP.md} 四点十七的补充段（“坐标形态未统一”与“孤儿映射”两项
     * 已于 2026-09-26 闭环，见 {@link PointMappingIndex} 的类注释）。</p>
     *
     * <p><b>落点与设计原文的差异（已由用户决策，如实登记）</b>：设计 §6.5 建议把校验放在**入站适配层**，
     * 并写明「不改 {@code AvailabilityService.ingest} 的语义、HTTP 通道同防护属独立决策（登记为 P2-7）」。
     * 本轮按用户任务书**提前吃掉 P2-7** 并做进服务层——代价是既有 HTTP 通道也开始拒绝非法点位（行为变更），
     * 收益是将来 MQTT 薄适配端点复用同一个 {@code ingest} 时不会绕过校验。设计文档（已复核、冻结）
     * **未改**，差异记在此处与 PR 回执里。</p>
     *
     * <p><b>为什么逐条丢弃而不是整批拒绝</b>：本方法服务的是 HTTP 内部通道，一批最多 500 条、可能跨多个
     * 设备与点位；整批拒绝会把同批的**合法数据一起丢掉**，放大一次脏输入的影响面。整批拒绝（原始 4xx）
     * 留给将来「一条 MQTT 消息 = 一个设备的一小批」的薄适配端点（设计 §6.5 方案 A）。</p>
     *
     * <p><b>为什么在服务层而不是只在控制器</b>：新 MQTT 端点会复用同一个 {@code ingest}（设计 §6.5：
     * 「复用服务方法，不复用它的 HTTP 信封语义」）——校验必须与落库在同一条必经路径上，否则新端点一接
     * 就绕过。</p>
     *
     * <p><b>为什么必须早于任何库访问</b>：非法输入不得进入「解析租户 → 写活性 → 写派生数据」任何一步；
     * 本方法只做内存里的形态判断，不查库、不建连接（有单测用 mock 断言零交互）。</p>
     *
     * <p><b>与租户解析的先后（如实说明，勿编因果）</b>：成员校验需要「已解析出租户的设备集合」当
     * {@code knownDevices}（设备不存在时不在这里判「未映射」，避免两种原因互相污染），所以
     * {@code resolveTenants} 必须排在本方法之前。租户解析的入参用 {@link #deviceIds} （**全部**读数涉及的
     * 设备，含只报时刻+质量的）——与本轮之前的 {@code batches.keySet()} 语义等价（{@code aggregate} 本来就
     * 覆盖这些设备）；本轮开发中被写成「只取带点位的设备」并在单测上导致纯时刻+质量批次被误判为
     * 「设备不存在」，已修正。</p>
     *
     * <p><b>边界（如实说明）</b>：{@code propertyId} 为 {@code null} 或空白仍按既有语义处理——
     * 「只做断档判定的采集器」不带点位，是合法上报形态（见 {@link ReadingObservationDto#getPropertyId()}），
     * 因此**不拒绝**、照旧只参与可用率。本方法只拒绝「带了点位但形态不合法」的条目。
     * ⇒ 入站判据与查询侧 {@code propertyIdLiteral} 对**空白**的结论**故意不一致**（查询侧拒绝空白）；
     * 这不构成「写进去却查不出来」的缺口：空白点位从不会被写入存储（三个 {@code collect*} 都要求非空白），
     * 因此查询侧的空白拒绝对存量数据不可达。</p>
     *
     * <p><b>连带影响（如实登记）</b>：被丢弃的是**整条**读数 ⇒ 它既不写最新值/时序/影子，也**不刷新活性**
     * （持续只报非法点位的设备会被判成断档）；{@code ingest} 的返回值 {@code processed} 也相应少算这些条。
     * 若改成「只丢点位、保留活性」，把过滤下移到三个 {@code collect*} 即可。</p>
     *
     * @param items 原始上报项（可能为空、可能含 null 元素）
     * @return 通过校验的上报项（保持原顺序）
     */
    private List<ReadingObservationDto> dropInvalidPropertyIds(List<ReadingObservationDto> items) {
        List<ReadingObservationDto> accepted = new ArrayList<>(items.size());
        for (ReadingObservationDto item : items) {
            if (item == null) {
                // null 元素本来就由下游各处跳过；这里原样放行，不把「上游 bug」伪装成「点位非法」
                accepted.add(null);
                continue;
            }
            String propertyId = item.getPropertyId();
            if (propertyId == null || propertyId.isBlank() || PropertyIdRules.isValid(propertyId)) {
                accepted.add(item);
                continue;
            }
            invalidPropertyIdCounter.increment();
            // 非法值本身是不可信输入（换行/控制字符可伪造日志行）⇒ 必须脱敏后再记，且只记长度辅助定位
            log.warn("[iot] 读数上报的点位标识不合法，已丢弃该条（同批其它读数不受影响）："
                    + "deviceId={} 长度={} 点位={}",
                LogSanitizer.sanitize(item.getDeviceId()), propertyId.length(),
                LogSanitizer.sanitize(propertyId));
        }
        return accepted;
    }

    /**
     * **坐标归一 + 成员/存在性校验**（P0-6c 后半；2026-09-26 起同时承担坐标统一与孤儿映射收紧）。
     *
     * <p><b>规范坐标 = 属性标识</b>：本方法把通过校验的读数一律改写成
     * {@code iot_property.identifier} 再交给下游（最新值 / 时序 / 影子 reported）。
     * 于是**写入侧只剩一种形态**，不再出现「同一个 {@code iot:latest} 里两种 field、按标识查不到
     * access 来源数据」的读侧后果（见 {@code docs/IOT-ROADMAP.md} 四点十七补充段）。</p>
     *
     * <p><b>过渡期仍接受历史的主键字符串形态</b>：滚动升级期间旧版 access 还在发主键字符串，
     * 若入站只认标识会把它们**整批误杀**（表现为升级过程中数据缺口）。因此本方法按
     * {@link PointMappingIndex.DeviceCoordinates#canonicalize(String)} 接受两种形态，**但落库一律是标识**。
     * 过渡期结束后把索引收缩为只放标识即可（届时本方法无需改动）。</p>
     *
     * <p><b>与第①道（格式/长度）并列，成因三类分开计数</b>：格式非法是「这个字符串根本不能当点位标识」
     * （{@value #METRIC_INVALID_PROPERTY_ID}）；未映射是「形态没问题，但这个点位不属于这台设备」
     * （{@value #METRIC_UNMAPPED_PROPERTY_ID}）；**孤儿映射**是「映射行还在，但它引用的属性行已被删除」
     * （{@value #METRIC_ORPHAN_PROPERTY_ID}，物模型重导入会物理删属性 ⇒ 这是真实存在的一类悬空引用）。
     * 三类必须可分辨，否则运维不知道该去配映射、还是该去清理悬空映射。</p>
     *
     * <p><b>查库形态（铁律）</b>：坐标索引由 {@link PointMappingIndex} **一次批量**取回（最多两条 SQL），
     * 与设备数/点位数无关，**绝不在循环里查库**；入参为空或映射为空都先判空短路。
     * 本方法自身只做内存 Map 查表与字段改写。</p>
     *
     * <p><b>为什么只对「已解析出租户的设备」判定</b>：设备不存在（或不属于任何租户）时，条目本就由既有分支
     * warn + 丢弃；若在这里按「未映射」再判一次，同一件事会被报成两种原因、且指标语义被污染。
     * 传入的 {@code knownDevices} 就是刚从 {@code iot_device} 解析出的设备集合。</p>
     *
     * <p><b>边界</b>：{@code propertyId} 为 {@code null}/空白仍按「只报时刻+质量」的合法形态放行（不带点位）；
     * 设备无任何映射 ⇒ 该设备的读数**全部**判为未映射（丢弃 + 计数），且不会因此多发一次查询。</p>
     *
     * @param items        已通过格式校验的上报项
     * @param knownDevices 已解析出租户的设备 ID
     * @return 通过校验且坐标已归一为属性标识的上报项（保持原顺序）
     */
    private List<ReadingObservationDto> canonicalizePropertyIds(List<ReadingObservationDto> items,
                                                                Set<Long> knownDevices) {
        Set<Long> devicesToCheck = deviceIdsWithPointReadings(items).stream()
            .filter(knownDevices::contains)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (devicesToCheck.isEmpty()) {
            // 没有带点位的读数（纯「只报时刻+质量」批次）⇒ 一次映射查询都不发
            return new ArrayList<>(items);
        }
        Map<Long, PointMappingIndex.DeviceCoordinates> coordinatesByDevice =
            pointMappingIndex.loadCoordinates(devicesToCheck);
        // 配置级异常（形态撞名 / 坐标级撞名）**每批每设备告警一次**，不放在下面的逐条循环里——
        // 500 条一批、每秒一批时逐条 WARN 会把日志打爆，而这两类问题都是**配置**问题，一条就够定位。
        for (Map.Entry<Long, PointMappingIndex.DeviceCoordinates> entry : coordinatesByDevice.entrySet()) {
            warnCoordinateConflicts(entry.getKey(), entry.getValue());
        }
        List<ReadingObservationDto> accepted = new ArrayList<>(items.size());
        for (ReadingObservationDto item : items) {
            if (item == null) {
                accepted.add(null);
                continue;
            }
            String propertyId = item.getPropertyId();
            Long deviceId = item.getDeviceId();
            if (propertyId == null || propertyId.isBlank() || deviceId == null
                || !knownDevices.contains(deviceId)) {
                // 无点位（只报时刻+质量）或设备不存在：交给既有分支处理，不在这里判「未映射」
                accepted.add(item);
                continue;
            }
            PointMappingIndex.DeviceCoordinates coordinates =
                coordinatesByDevice.getOrDefault(deviceId, PointMappingIndex.DeviceCoordinates.empty());
            String canonical = coordinates.canonicalize(propertyId);
            if (canonical != null) {
                // 归一到规范坐标：下游（最新值/时序/影子）因此只见一种形态
                item.setPropertyId(canonical);
                accepted.add(item);
                continue;
            }
            if (coordinates.orphanForms().contains(propertyId)) {
                // 孤儿映射：映射行在、属性行已缺失 ⇒ 与「未映射」分开计数
                orphanPropertyIdCounter.increment();
                log.warn("[iot] 读数上报的点位归属**孤儿映射**（物模型属性行已缺失），已丢弃该条"
                        + "（同批其它读数不受影响）：deviceId={} 点位={}（请清理或重建该点位映射）",
                    LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(propertyId));
                continue;
            }
            unmappedPropertyIdCounter.increment();
            log.warn("[iot] 读数上报的点位未映射到该设备，已丢弃该条（同批其它读数不受影响）："
                    + "deviceId={} 点位={}",
                LogSanitizer.sanitize(deviceId), LogSanitizer.sanitize(propertyId));
        }
        return accepted;
    }

    /**
     * 坐标配置冲突告警（每批每设备一次）。
     *
     * <p>两类都必须留痕，且都**不改判**（改判会让「数据落到隔壁点位」变成查不出来的错误）：</p>
     * <ul>
     *   <li><b>形态级撞名</b>（{@link PointMappingIndex.DeviceCoordinates#ambiguousForms()}）：
     *       A 点的历史主键字符串等于 B 点的属性标识 ⇒ 该形态判给标识（确定性规则）。</li>
     *   <li><b>坐标级撞名</b>（{@link PointMappingIndex.DeviceCoordinates#duplicateIdentifiers()}）：
     *       同设备两条映射的属性标识相同（物模型只在 service 内保证唯一）⇒ 两个点位真实共享一个规范坐标，
     *       本平台无法分开。此处只告警；彻底解法（拒绝同设备同名标识的映射）属产品策略，
     *       登记在 {@code docs/IOT-ROADMAP.md} 四点十七，本轮不做。</li>
     * </ul>
     *
     * @param deviceId    设备 ID
     * @param coordinates 该设备的坐标索引
     */
    private static void warnCoordinateConflicts(Long deviceId,
                                                PointMappingIndex.DeviceCoordinates coordinates) {
        if (!coordinates.ambiguousForms().isEmpty()) {
            log.warn("[iot] 点位坐标形态撞名（该形态按**属性标识**判定，请修正物模型编码）：deviceId={} 形态数={} 形态={}",
                LogSanitizer.sanitize(deviceId), coordinates.ambiguousForms().size(),
                LogSanitizer.sanitize(String.join(",", coordinates.ambiguousForms())));
        }
        if (!coordinates.duplicateIdentifiers().isEmpty()) {
            log.warn("[iot] 同一设备上**多个点位共用同一个规范坐标**（属性标识在 service 内唯一、跨 service 可重名）："
                    + "deviceId={} 坐标数={} 坐标={} ⇒ 这些点位的读数会落进同一个坐标，"
                    + "请在点位映射侧避免同设备映射同名标识（详见 iot.pointmapping.coordinate_collision）",
                LogSanitizer.sanitize(deviceId), coordinates.duplicateIdentifiers().size(),
                LogSanitizer.sanitize(String.join(",", coordinates.duplicateIdentifiers())));
        }
    }

    /**
     * 本批读数涉及的**全部**设备 ID（含只报时刻+质量的读数）。
     *
     * <p>租户解析必须用它而不是「带点位的那些设备」：只做断档判定的采集器也刷新活性，漏掉它们的设备
     * 会被后续当成「设备不存在」丢弃（本机单测实测过这个回归）。</p>
     *
     * @param items 上报项
     * @return 设备 ID 集合（去重、保持顺序、不含 null）
     */
    private static Set<Long> deviceIds(List<ReadingObservationDto> items) {
        return items.stream()
            .filter(Objects::nonNull)
            .map(ReadingObservationDto::getDeviceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 本批中「带了点位」的读数所涉及的设备 ID（用于**一次批量**取映射；空集合即不查库）。
     *
     * <p>只取带点位（非空白 {@code propertyId}）的条目：没有点位的读数不需要映射校验，
     * 纯「只报时刻+质量」的批次因此**不会**产生任何映射查询。</p>
     *
     * @param items 上报项
     * @return 设备 ID 集合（去重、保持顺序、不含 null）
     */
    private static Set<Long> deviceIdsWithPointReadings(List<ReadingObservationDto> items) {
        return items.stream()
            .filter(Objects::nonNull)
            .filter(item -> item.getDeviceId() != null)
            .filter(item -> {
                String propertyId = item.getPropertyId();
                return propertyId != null && !propertyId.isBlank();
            })
            .map(ReadingObservationDto::getDeviceId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 挑选「可写最新值」的读数（纯计算，无外部调用）：必须带点位与值，且设备已解析出租户。
     *
     * @param items          原始上报项
     * @param tenantByDevice 设备 → 租户
     * @return 待写最新值（可能为空）
     */
    private static List<LatestValue> collectLatestValues(List<ReadingObservationDto> items,
                                                         Map<Long, Long> tenantByDevice) {
        List<LatestValue> values = new ArrayList<>();
        for (ReadingObservationDto item : items) {
            if (item == null || item.getDeviceId() == null || item.getTs() == null) {
                continue;
            }
            String propertyId = item.getPropertyId();
            String value = item.getValue();
            if (propertyId == null || propertyId.isBlank() || value == null) {
                // 只做断档判定的采集器（只报时刻+质量）不带点位/值：合法，跳过最新值即可
                continue;
            }
            Long tenantId = tenantByDevice.get(item.getDeviceId());
            if (tenantId == null) {
                // 设备不存在：上一段循环会 warn 并丢弃，这里不重复告警
                continue;
            }
            values.add(new LatestValue(tenantId, item.getDeviceId(), propertyId, value,
                item.getQuality(), item.getTs()));
        }
        return values;
    }

    /**
     * 挑选「可写时序」的读数（纯计算）：与最新值同一判据（必须有设备/点位/值 + 已解析出租户）。
     *
     * @param items          原始上报项
     * @param tenantByDevice 设备 → 租户
     * @return 待写时序点（可能为空）
     */
    private static List<TimeSeriesPoint> collectSeriesPoints(List<ReadingObservationDto> items,
                                                             Map<Long, Long> tenantByDevice) {
        List<TimeSeriesPoint> points = new ArrayList<>();
        for (ReadingObservationDto item : items) {
            if (item == null || item.getDeviceId() == null || item.getTs() == null) {
                continue;
            }
            String propertyId = item.getPropertyId();
            String value = item.getValue();
            if (propertyId == null || propertyId.isBlank() || value == null) {
                continue;
            }
            Long tenantId = tenantByDevice.get(item.getDeviceId());
            if (tenantId == null) {
                continue;
            }
            points.add(new TimeSeriesPoint(tenantId, item.getDeviceId(), propertyId, value,
                item.getQuality(), item.getTs()));
        }
        return points;
    }

    /**
     * 挑选「可合并进影子 reported」的读数（纯计算，无外部调用，G2）。
     *
     * <p>判据与最新值一致（必须带点位与值 + 设备已解析出租户）：影子 reported 的语义就是
     * 「上报链路上点位的最新值」，判据若不同会出现「最新值里有、影子里没有」的割裂。</p>
     *
     * <p><b>按设备收敛成增量</b>：同一批里同一设备的多个点位合并进**一条**增量（一台设备一条语句，
     * 而不是每个点位一条）；同一点位重复出现时按读数时刻取最新（与 {@code LatestValueWriter} 的批内规则一致）。</p>
     *
     * @param items          原始上报项
     * @param tenantByDevice 设备 → 租户
     * @return 待合并增量（每个设备至多一条；可能为空）
     */
    private static List<ShadowReportedUpdate> collectShadowReported(List<ReadingObservationDto> items,
                                                                    Map<Long, Long> tenantByDevice) {
        Map<Long, Map<String, String>> patchByDevice = new LinkedHashMap<>();
        Map<Long, Long> tsByDevice = new LinkedHashMap<>();
        Map<DevicePropertyKey, Long> tsByProperty = new LinkedHashMap<>();
        for (ReadingObservationDto item : items) {
            if (item == null || item.getDeviceId() == null || item.getTs() == null) {
                continue;
            }
            String propertyId = item.getPropertyId();
            String value = item.getValue();
            if (propertyId == null || propertyId.isBlank() || value == null) {
                continue;
            }
            Long deviceId = item.getDeviceId();
            if (tenantByDevice.get(deviceId) == null) {
                // 设备不存在：本批不会写它的任何派生数据（上面循环已 warn 并丢弃）
                continue;
            }
            DevicePropertyKey key = new DevicePropertyKey(deviceId, propertyId);
            Long known = tsByProperty.get(key);
            if (known != null && known >= item.getTs()) {
                // 同一批里同一点位多次上报：只保留读数时刻最新的那条
                continue;
            }
            tsByProperty.put(key, item.getTs());
            patchByDevice.computeIfAbsent(deviceId, ignored -> new LinkedHashMap<>()).put(propertyId, value);
            Long knownTs = tsByDevice.get(deviceId);
            if (knownTs == null || item.getTs() > knownTs) {
                tsByDevice.put(deviceId, item.getTs());
            }
        }
        List<ShadowReportedUpdate> updates = new ArrayList<>(patchByDevice.size());
        for (Map.Entry<Long, Map<String, String>> entry : patchByDevice.entrySet()) {
            Long tenantId = tenantByDevice.get(entry.getKey());
            LocalDateTime reportTs = AvailabilityRules.toLocalDateTime(tsByDevice.get(entry.getKey()));
            if (tenantId == null || reportTs == null) {
                continue;
            }
            updates.add(new ShadowReportedUpdate(tenantId, entry.getKey(), Map.copyOf(entry.getValue()),
                reportTs));
        }
        return updates;
    }

    /**
     * 事务**提交后**再写「派生数据」（最新值 + 时序 + 影子 reported）。
     *
     * <p>为什么不能直接写在事务里：Redis 不参与数据库事务，若本批因任何原因回滚，写在事务里的最新值
     * 却已经生效 ⇒ 出现"库里有断档记录、最新值却说设备正常"的不一致。注册 afterCommit 回调可以保证
     * 只有库侧真的成功才写；没有事务（如纯单测）时直接写——没有事务就没有回滚语义。</p>
     *
     * @param values       待写最新值
     * @param seriesPoints 待写时序点
     * @param shadows      待合并的影子上报增量
     */
    private void writeDerivedAfterCommit(List<LatestValue> values, List<TimeSeriesPoint> seriesPoints,
                                         List<ShadowReportedUpdate> shadows) {
        if (values.isEmpty() && seriesPoints.isEmpty() && shadows.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            writeDerived(values, seriesPoints, shadows);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                writeDerived(values, seriesPoints, shadows);
            }
        });
    }

    /**
     * 写派生数据（最新值 + 时序 + 影子 reported）：**各自兜底**——在 afterCommit 里抛异常会逃逸到
     * 调用方（库已提交、接口却报错，调用方会误判整批失败），因此任一路失败都只记录，
     * 不改变「上报已成功落库」的事实。
     *
     * @param values       最新值
     * @param seriesPoints 时序点
     * @param shadows      影子上报增量
     */
    private void writeDerived(List<LatestValue> values, List<TimeSeriesPoint> seriesPoints,
                              List<ShadowReportedUpdate> shadows) {
        if (!values.isEmpty()) {
            try {
                latestValueWriter.writeAll(values);
            } catch (RuntimeException ex) {
                log.error("[iot] 最新值写入器抛出异常（已忽略，不影响本批上报结果）：条数={}",
                    LogSanitizer.sanitize(values.size()), ex);
            }
        }
        if (!seriesPoints.isEmpty()) {
            // 成功侧观测：先记「打算写多少点」。它与写入器的 attempted/rows 三者比对，
            // 即可判定断点是在「没收集」还是「收集了没写」——不要再让这条路径静默。
            seriesCollectedCounter.increment(seriesPoints.size());
            if (log.isDebugEnabled()) {
                log.debug("[iot] 时序出口收到待写点数：{}（启用={}）", seriesPoints.size(),
                    timeSeriesProperties.isEnabled());
            }
            try {
                timeSeriesWriter.writeAll(seriesPoints);
            } catch (RuntimeException ex) {
                log.error("[iot] 时序写入器抛出异常（已忽略，历史曲线缺失但上报已落库）：条数={}",
                    LogSanitizer.sanitize(seriesPoints.size()), ex);
            }
        }
        if (!shadows.isEmpty()) {
            try {
                shadowReportedWriter.writeAll(shadows);
            } catch (RuntimeException ex) {
                // 写入器实现本身已是「计数 + 日志」，这里是第二道防线：任何实现都不得把上报拖崩
                log.error("[iot] 影子上报值写入器抛出异常（已忽略，merged 缺失但上报已落库）：设备数={}",
                    LogSanitizer.sanitize(shadows.size()), ex);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanAndOpenOutages() {
        if (!properties.isEnabled()) {
            return 0;
        }
        // 数据库时钟：候选 SQL 与下面的纯逻辑复核必须用**同一个基准**，否则两处会得出不同结论
        LocalDateTime now = livenessMapper.selectNow();
        // 跨租户读候选（扫描没有租户身份），逐条进入各自租户写 —— 见类注释
        List<DeviceLiveness> candidates = TenantContext.executeIgnore(() -> livenessMapper.selectList(
            Wrappers.<DeviceLiveness>lambdaQuery()
                .isNull(DeviceLiveness::getOpenOutageId)
                .apply("COALESCE(last_good_at, first_observed_at) IS NOT NULL")
                // 生效周期口径必须与 OutageDetector.effectiveIntervalMs 完全一致：
                // 「上报了正周期就用它，否则用兜底」——不用 GREATEST（那会把 1s 周期的设备抬到 5s，与 spec 的 K×周期不符）
                .apply("TIMESTAMPADD(MICROSECOND, CAST((CASE WHEN COALESCE(poll_interval_ms, 0) > 0"
                    + " THEN poll_interval_ms ELSE {0} END) AS SIGNED) * {1} * 1000,"
                    + " COALESCE(last_good_at, first_observed_at)) < NOW()",
                    properties.getFallbackIntervalMs(), properties.getKFactor())
                .orderByAsc(DeviceLiveness::getId)
                .last("LIMIT " + properties.getScanBatchSize())));
        List<DeviceLiveness> collectible = filterCollectible(candidates);
        int opened = 0;
        for (DeviceLiveness candidate : collectible) {
            opened += openOutage(candidate, now);
        }
        if (opened > 0) {
            log.warn("[iot] 断档扫描：新开断档 {} 个（候选 {} 个，其中可采集 {} 个；K={} 兜底周期={}ms）",
                opened, candidates.size(), collectible.size(), properties.getKFactor(),
                properties.getFallbackIntervalMs());
        }
        return opened;
    }

    /**
     * 只保留「设备仍存在且启用」的候选。
     *
     * <p><b>为什么必须筛</b>：设备被删除或停用后，活性行不会自己消失——若不过滤，它们会**永远**被判成断档，
     * 报表上出现一堆假断档（可用率看起来像坏了一样）。这里一次批量查设备（不是循环查，避免 N+1），
     * 只保留 {@code status=启用} 且未被逻辑删除的设备。</p>
     *
     * @param candidates 扫描候选
     * @return 可采集的候选
     */
    private List<DeviceLiveness> filterCollectible(List<DeviceLiveness> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> deviceIds = candidates.stream()
            .map(DeviceLiveness::getDeviceId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        Set<Long> enabled = TenantContext.executeIgnore(() -> deviceMapper.selectList(
            Wrappers.<IotDevice>lambdaQuery()
                .select(IotDevice::getId)
                .in(IotDevice::getId, deviceIds)
                .eq(IotDevice::getStatus, EntityStatus.ENABLED.getCode())))
            .stream()
            .map(IotDevice::getId)
            .collect(Collectors.toSet());
        List<DeviceLiveness> collectible = new ArrayList<>(candidates.size());
        // 设备已删除/停用的活性行不会自己消失，**必须清掉**而不是只跳过——候选查询按 id 升序 +
        // LIMIT 批次上限，这类行永远满足条件、永远占住前段，累积 ≥ 批次上限后**其它设备的断档再也
        // 不会被发现**（是饥饿，不是延迟）。清理走**一次批量删**（循环里逐条删是 N+1 写，被架构门禁拦）。
        List<Long> orphanIds = new ArrayList<>();
        for (DeviceLiveness candidate : candidates) {
            if (candidate.getDeviceId() != null && enabled.contains(candidate.getDeviceId())) {
                collectible.add(candidate);
            } else {
                orphanIds.add(candidate.getId());
            }
        }
        if (!orphanIds.isEmpty()) {
            // 平台级维护删除：**必须显式 ignore 租户**（扫描本来就在无租户上下文下跨租户读候选），
            // 否则租户插件会以「缺少租户上下文」直接拒绝（fail-closed），把整轮扫描打断——
            // 这是 CI 真库用例实测出来的：只跳过不清理会饥饿，清理写法不对会整轮失败。
            // 安全性来自显式 id 列表（取自本轮刚读到的候选行），且只删「设备已不存在/停用」的那些。
            TenantContext.executeIgnore(() -> livenessMapper.deleteBatchIds(orphanIds));
            log.info("[iot] 断档扫描清理了 {} 条设备已删除/停用的活性行（它们不再参与断档判定）",
                orphanIds.size());
        }
        return collectible;
    }

    @Override
    public AvailabilityResp query(Long deviceId, LocalDateTime from, LocalDateTime to) {
        if (deviceId == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备 ID 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            // 不存在（含跨租户访问）按「查不到」处理，不泄露存在性
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        LocalDateTime now = livenessMapper.selectNow();
        LocalDateTime windowTo = to != null ? to : now;
        LocalDateTime windowFrom = from != null
            ? from : windowTo.minusHours(properties.getDefaultWindowHours());
        if (windowFrom.isAfter(windowTo)) {
            // 参数给反了就报错，不静默交换：否则「用错参数」会得到一份看起来正常的报表
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "统计窗口起点必须早于终点：" + windowFrom + " > " + windowTo);
        }
        DeviceLiveness liveness = livenessMapper.selectOne(Wrappers.<DeviceLiveness>lambdaQuery()
            .eq(DeviceLiveness::getDeviceId, deviceId));
        long intervalMs = OutageDetector.effectiveIntervalMs(
            liveness == null ? null : liveness.getPollIntervalMs(), properties.getFallbackIntervalMs());
        // 汇总走**精确聚合**（与明细条数无关）：明细有返回上限，够不到时求和会低估断档 ⇒ 可用率偏高
        Long tenantId = currentTenantId();
        if (tenantId == null) {
            // 查询路径必然有租户身份（网关注入）；缺失时不猜、不查全表，直接暴露
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "缺少租户上下文，无法统计可用率");
        }
        Map<String, Object> aggregate = outageMapper.summarizeInWindow(tenantId, deviceId, windowFrom, windowTo,
            now);
        long rawOutageSeconds = toLong(aggregate == null ? null : aggregate.get("outageSeconds"));
        long rawLongestOutageSeconds = toLong(aggregate == null ? null : aggregate.get("longestOutageSeconds"));
        int outageCount = (int) toLong(aggregate == null ? null : aggregate.get("outageCount"));
        long windowSeconds = Math.max(0L, Duration.between(windowFrom, windowTo).getSeconds());
        // 维护窗口（spec §12.5）：统计总时长要排除计划停机；断档落在窗口内的部分也一并剔除（否则计划停机仍拉低可用率）。
        // 剔除在 Java 侧做：两段 SQL 保持简单可解析（复杂形状会被 MP 的 JSqlParser 与 MySQL 拒绝，CI 真实测过）。
        Long maintenanceRaw = maintenanceWindowMapper.sumMaintenanceSecondsInWindow(tenantId, deviceId,
            windowFrom, windowTo, now);
        long maintenanceSeconds = Math.max(0L, maintenanceRaw == null ? 0L : maintenanceRaw);
        Long outageInMaintenanceRaw = maintenanceWindowMapper.sumOutageInMaintenanceSeconds(tenantId, deviceId,
            windowFrom, windowTo, now);
        long outageInMaintenanceSeconds = Math.max(0L,
            Math.min(outageInMaintenanceRaw == null ? 0L : outageInMaintenanceRaw, rawOutageSeconds));
        long outageSeconds = Math.max(0L, rawOutageSeconds - outageInMaintenanceSeconds);
        // 计入的「最长单次断档」：原始最长断档减去「维护内部分」只能按下限近似（要精确得到
        // 「排除维护后的单次最长」需要逐行求交，那正是被 CI 拒绝的复杂 SQL）⇒ 取 min(原始最长, 计入断档合计)。
        // 方向是**偏严**（可能把「一半落在维护里的最长断档」算得更长 ⇒ 达标更困难），已在 ROADMAP 登记。
        long longestOutageSeconds = Math.max(0L, Math.min(rawLongestOutageSeconds, outageSeconds));
        boolean truncated = outageCount > AvailabilityRules.MAX_OUTAGE_ROWS;
        if (truncated) {
            log.warn("[iot] 窗口内断档 {} 条超过明细上限 {}：明细按**最新优先**截断展示，"
                + "可用率/最长断档仍由精确聚合给出（不因截断偏高）：deviceId={} from={} to={}",
                outageCount, AvailabilityRules.MAX_OUTAGE_ROWS, LogSanitizer.sanitize(deviceId), windowFrom,
                windowTo);
        }
        AvailabilityCalculator.Summary summary = AvailabilityCalculator.summarize(windowSeconds,
            maintenanceSeconds, outageSeconds, longestOutageSeconds, outageInMaintenanceSeconds, outageCount,
            intervalMs, truncated);
        if (summary.maintenanceSeconds() > 0L) {
            log.info("[iot] 可用率统计已排除维护窗口：deviceId={} 窗口={}秒 维护={}秒 统计总时长={}秒 "
                + "（其中断档落在维护内被剔除 {} 秒）",
                LogSanitizer.sanitize(deviceId), windowSeconds, summary.maintenanceSeconds(),
                summary.effectiveWindowSeconds(), summary.outageInMaintenanceSeconds());
        }
        // 明细：**最新优先**（截断时保留最近的断档，比丢最新更有用）
        List<OutageEvent> limited = outageMapper.selectList(Wrappers.<OutageEvent>lambdaQuery()
            .eq(OutageEvent::getDeviceId, deviceId)
            .lt(OutageEvent::getStartTs, windowTo)
            .and(wrapper -> wrapper.isNull(OutageEvent::getEndTs)
                .or().gt(OutageEvent::getEndTs, windowFrom))
            .orderByDesc(OutageEvent::getStartTs)
            .last("LIMIT " + AvailabilityRules.MAX_OUTAGE_ROWS));
        AvailabilityResp resp = new AvailabilityResp();
        resp.setDeviceId(deviceId);
        resp.setFrom(windowFrom);
        resp.setTo(windowTo);
        resp.setWindowSeconds(summary.windowSeconds());
        resp.setEffectiveWindowSeconds(summary.effectiveWindowSeconds());
        resp.setMaintenanceSeconds(summary.maintenanceSeconds());
        resp.setOutageInMaintenanceSeconds(summary.outageInMaintenanceSeconds());
        resp.setOutageSeconds(summary.outageSeconds());
        resp.setLongestOutageSeconds(summary.longestOutageSeconds());
        resp.setOutageCount(summary.outageCount());
        resp.setAvailability(summary.availability());
        resp.setMeetsTarget(summary.meetsTarget());
        resp.setTargetAvailability(AvailabilityRules.TARGET_AVAILABILITY);
        resp.setMaxAllowedOutageSeconds(summary.maxAllowedOutageSeconds());
        resp.setTruncated(summary.truncated());
        for (OutageEvent row : limited) {
            resp.getOutages().add(toResp(row, now));
        }
        // 回显维护窗口（最多 MAX_MAINTENANCE_ROWS 条）：让「这段时间为什么不算断档」在响应里自解释
        List<MaintenanceWindow> windows = maintenanceWindowMapper.listOverlappingInWindow(tenantId, deviceId,
            windowFrom, windowTo, AvailabilityRules.MAX_MAINTENANCE_ROWS);
        for (MaintenanceWindow window : windows) {
            MaintenanceWindowDto dto = new MaintenanceWindowDto();
            dto.setId(window.getId());
            dto.setDeviceId(window.getDeviceId());
            dto.setStartTs(window.getStartTs());
            dto.setEndTs(window.getEndTs());
            dto.setSource(window.getSource());
            dto.setReason(window.getReason());
            resp.getMaintenanceWindows().add(dto);
        }
        return resp;
    }

    /**
     * 落地一批（同一设备）观察：刷新活性；有效数据到达则闭合进行中的断档。
     *
     * @param tenantId 设备所属租户（已进入该租户上下文）
     * @param deviceId 设备 ID
     * @param batch    聚合后的批次
     * @return 处理的观察条数
     */
    private int applyBatch(Long tenantId, Long deviceId, DeviceReadingBatch batch) {
        DeviceLiveness row = livenessMapper.selectByDeviceIncludingDeleted(tenantId, deviceId);
        boolean created = row == null;
        if (created) {
            row = new DeviceLiveness();
            row.setId(IdWorker.getId());
            row.setDeviceId(deviceId);
            row.setPollIntervalMs(0);
        }
        // 闭合断档必须用**更新前**的 lastGoodAt/firstObservedAt 当起点（起点不能等于恢复时刻）
        if (batch.lastGoodAt() != null && row.getOpenOutageId() != null) {
            LocalDateTime start = OutageDetector.outageStart(row.getLastGoodAt(), row.getFirstObservedAt());
            if (start == null || !batch.lastGoodAt().isAfter(start)) {
                // 乱序/重放：这条有效数据**早于**断档起点，不可能是「恢复」⇒ 不闭合。
                // 否则会写出一条 start > end、duration=0 的假恢复，并把进行中的断档标记清掉。
                log.warn("[access→iot] 有效数据早于断档起点，忽略本次「恢复」（乱序/重放上报）：deviceId={} ts={} 起点={}",
                    LogSanitizer.sanitize(deviceId), batch.lastGoodAt(), start);
            } else {
                closeOutage(row.getOpenOutageId(), start, batch.lastGoodAt());
                int cleared = livenessMapper.clearOpenOutage(row.getId(), row.getOpenOutageId());
                if (cleared == 0) {
                    // 清空期间标记已被别的路径改掉（扫描开了新断档）：保留新标记，不覆盖
                    log.warn("[access→iot] 断档标记在闭合期间已被改动，保留当前标记：deviceId={} 期望清空={}",
                        LogSanitizer.sanitize(deviceId), row.getOpenOutageId());
                }
            }
        }
        if (batch.pollIntervalMs() != null && batch.pollIntervalMs() > 0) {
            row.setPollIntervalMs(batch.pollIntervalMs());
        }
        row.setFirstObservedAt(earliest(row.getFirstObservedAt(), batch.firstObservedAt()));
        row.setLastObservedAt(latest(row.getLastObservedAt(), batch.lastObservedAt()));
        row.setLastGoodAt(latest(row.getLastGoodAt(), batch.lastGoodAt()));
        if (created) {
            livenessMapper.insert(row);
        } else {
            livenessMapper.reviveAndUpdate(row);
        }
        return batch.count();
    }

    /**
     * 打开一条断档（多副本安全：只有把 {@code open_outage_id} 从 NULL 改成新值的那一方算开成功）。
     *
     * @param candidate 候选活性行（来自跨租户扫描）
     * @return 实际打开返回 1，被其它副本抢先返回 0
     */
    private int openOutage(DeviceLiveness candidate, LocalDateTime now) {
        Long tenantId = candidate.getTenantId();
        LocalDateTime start = OutageDetector.outageStart(candidate.getLastGoodAt(),
            candidate.getFirstObservedAt());
        long intervalMs = OutageDetector.effectiveIntervalMs(candidate.getPollIntervalMs(),
            properties.getFallbackIntervalMs());
        if (!OutageDetector.isOutage(start, now, intervalMs, properties.getKFactor())) {
            // 用与 SQL 同一套纯逻辑复核一遍：两处口径（SQL 与 Java）必须一致，不一致时这里会先暴露
            log.warn("[iot] 断档候选未通过纯逻辑复核（SQL 与 Java 口径可能漂移）：deviceId={} 起点={} 周期={}",
                LogSanitizer.sanitize(candidate.getDeviceId()), start, intervalMs);
            return 0;
        }
        return TenantContext.executeWithTenant(tenantId, () -> {
            OutageEvent event = new OutageEvent();
            event.setId(IdWorker.getId());
            event.setDeviceId(candidate.getDeviceId());
            event.setStartTs(start);
            event.setReason(OutageReason.NO_GOOD_DATA.getCode());
            outageMapper.insert(event);
            int marked = livenessMapper.markOpenOutage(candidate.getId(), event.getId());
            if (marked == 0) {
                // 另一个副本已开断档：撤销本次插入，避免同一段断档被记两次（可用率会被双计）
                outageMapper.deleteById(event.getId());
                log.debug("[iot] 断档已被其它副本打开，撤销本次插入：deviceId={}",
                    LogSanitizer.sanitize(candidate.getDeviceId()));
                return 0;
            }
            log.warn("[iot] 发现断档：deviceId={} start={}（连续超过 {}×采集周期无有效数据）",
                LogSanitizer.sanitize(candidate.getDeviceId()), start, properties.getKFactor());
            return 1;
        });
    }

    /**
     * 闭合断档并计算时长。
     *
     * @param outageId 断档事件 ID
     * @param start    断档起点（可空：起点缺失时只闭合、不算时长）
     * @param end      恢复时刻
     */
    private void closeOutage(Long outageId, LocalDateTime start, LocalDateTime end) {
        Long durationSeconds = start == null ? null : Math.max(0L, Duration.between(start, end).getSeconds());
        outageMapper.closeOutage(outageId, end, durationSeconds);
        log.info("[iot] 断档恢复：outageId={} 时长={}秒", LogSanitizer.sanitize(outageId), durationSeconds);
    }

    /** 按设备聚合一批观察（同一批里同一设备可能多条）。 */
    private static Map<Long, DeviceReadingBatch> aggregate(List<ReadingObservationDto> items) {
        Map<Long, DeviceReadingBatch> byDevice = new LinkedHashMap<>();
        for (ReadingObservationDto item : items) {
            if (item == null || item.getDeviceId() == null || item.getTs() == null) {
                continue;
            }
            LocalDateTime observedAt = AvailabilityRules.toLocalDateTime(item.getTs());
            if (observedAt == null) {
                continue;
            }
            boolean good = AvailabilityRules.QUALITY_GOOD.equals(item.getQuality());
            byDevice.merge(item.getDeviceId(), new DeviceReadingBatch(item.getDeviceId(),
                    item.getPollIntervalMs(), observedAt, observedAt, good ? observedAt : null, 1),
                (left, right) -> new DeviceReadingBatch(left.deviceId(),
                    right.pollIntervalMs() != null ? right.pollIntervalMs() : left.pollIntervalMs(),
                    earliest(left.firstObservedAt(), right.firstObservedAt()),
                    latest(left.lastObservedAt(), right.lastObservedAt()),
                    latest(left.lastGoodAt(), right.lastGoodAt()),
                    left.count() + right.count()));
        }
        return byDevice;
    }

    /** 解析设备 → 租户（内部端点没有租户身份，必须显式忽略租户条件再回到各租户）。 */
    private Map<Long, Long> resolveTenants(Set<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        List<IotDevice> devices = TenantContext.executeIgnore(
            () -> deviceMapper.selectBatchIds(deviceIds));
        Map<Long, Long> tenantByDevice = new LinkedHashMap<>(devices.size());
        for (IotDevice device : devices) {
            if (device.getTenantId() != null) {
                tenantByDevice.put(device.getId(), device.getTenantId());
            }
        }
        return tenantByDevice;
    }

    /** 实体 → 视图。 */
    private static OutageEventResp toResp(OutageEvent row, LocalDateTime now) {
        OutageEventResp resp = new OutageEventResp();
        resp.setId(row.getId());
        resp.setDeviceId(row.getDeviceId());
        resp.setStartTs(row.getStartTs());
        resp.setEndTs(row.getEndTs());
        resp.setReason(row.getReason());
        boolean ongoing = row.getEndTs() == null;
        resp.setOngoing(ongoing);
        if (row.getDurationSec() != null) {
            resp.setDurationSec(row.getDurationSec());
        } else if (ongoing && row.getStartTs() != null) {
            resp.setDurationSec(Math.max(0L, Duration.between(row.getStartTs(), now).getSeconds()));
        }
        return resp;
    }

    /**
     * 当前请求的租户：**与 MP 租户插件同源**（{@code TenantContext} 优先，其次 {@code TenantProvider}）。
     *
     * <p>只读 {@code TenantContext} 是错的：真实请求链路上绑定的是 {@code IdentityContext}（网关身份头 →
     * {@code IdentityHeaderFilter}），`TenantContext` 只有显式 executeWithTenant 才非空 ⇒ 会导致
     * 「网关注入的租户明明存在，端点却报缺少租户上下文」（外委复核探针实测）。</p>
     *
     * @return 租户 ID；无则 {@code null}
     */
    private Long currentTenantId() {
        return TenantContext.getTenantId().or(tenantProvider::getCurrentTenantId).orElse(null);
    }

    /**
     * 聚合结果取值（驱动差异：MySQL 的 COUNT 是 Long、SUM 可能是 BigDecimal/Integer）。
     *
     * @param value 聚合值（可空）
     * @return 长整型；空返回 0
     */
    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    /** 取两个时刻里较早的非空值。 */
    private static LocalDateTime earliest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    /** 取两个时刻里较晚的非空值。 */
    private static LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    /**
     * 同一设备在一批观察里的聚合结果。
     *
     * @param deviceId         设备 ID
     * @param pollIntervalMs   采集周期（取批次里最后一个非空值）
     * @param firstObservedAt  最早观测时刻
     * @param lastObservedAt   最晚观测时刻
     * @param lastGoodAt       最晚有效数据时刻（无有效数据为 {@code null}）
     * @param count            观察条数
     */
    private record DeviceReadingBatch(Long deviceId, Integer pollIntervalMs, LocalDateTime firstObservedAt,
                                      LocalDateTime lastObservedAt, LocalDateTime lastGoodAt, int count) {
    }

    /**
     * 「设备 + 点位」复合键（影子增量批内去重用）。
     *
     * <p>用记录而不是拼字符串做键：拼串需要一个人造分隔符（魔法值），且点位标识里若恰好含该分隔符
     * 就会把两条不同的点位判成同一条（合并出一个不存在的点位）。</p>
     *
     * @param deviceId   设备 ID
     * @param propertyId 点位标识
     */
    private record DevicePropertyKey(Long deviceId, String propertyId) {
    }
}
