/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.openapi;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开放 API 相关配置（前缀 {@link #PREFIX}）。
 *
 * <p><b>为什么需要本类（starter #103）</b>：原实现把三个配置项用 {@code @Value}
 * 逐个注入到 {@code OpenApiKeyService} 的构造器参数上。{@code @Value} 的问题是：
 * ① 配置键散落在构造器签名里，无 IDE 补全、无元数据（{@code spring-configuration-metadata}）；
 * ② 新增配置项要改构造器签名 ⇒ 所有显式构造点（含单测）连带改动；
 * ③ 无法集中做取值校验与「默认值」文档化。改为 {@code @ConfigurationProperties} 后配置面收敛到一个类，
 * 服务类只依赖这个对象。</p>
 *
 * <p><b>🔴 安全约束（务必遵守）</b>：{@link #secretPepper} 是<b>真实凭据</b>
 * （开放 API 的 AK/SK 派生密钥：泄露即可伪造任意 API Key 的签名）。因此：</p>
 * <ol>
 *   <li><b>不得覆写 {@code toString()}</b>（本类刻意不写）——一旦覆写就有把 pepper 打进口志/异常消息的风险；
 *       本类用 Lombok {@code @Getter/@Setter}，即使用默认 {@code Object#toString()}（只有类型@hash，不含字段值）。</li>
 *   <li>本类<b>不得</b>出现在任何日志语句里（包括 debug）；需要打印配置时只打非敏感项。</li>
 *   <li>配置真值只由 {@code deploy/.env} → 容器 env → {@code ${YPBIN_OPENAPI_SECRET_PEPPER}} 占位符提供，
 *       <b>禁止</b>把真值写进 Nacos 配置库或仓库文件。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@Getter
@Setter
@ConfigurationProperties(prefix = OpenApiProperties.PREFIX)
public class OpenApiProperties {

    /** 配置前缀（唯一事实源：服务类与测试都引用本常量，不写裸字符串）。 */
    public static final String PREFIX = "ypbin.openapi";

    /** 默认的「最后使用时间」落库最小间隔（秒）：与迁移前的 {@code :30} 默认值一致。 */
    public static final long DEFAULT_LAST_USED_WRITE_INTERVAL_SECONDS = 30L;

    /**
     * 签名 pepper（**真实凭据，禁日志/禁入库**）。
     *
     * <p>空值即 fail-closed：创建 Key 与校验 Key 都会拒绝（见 {@code OpenApiKeyService}）。
     * 默认空串是刻意的——不提供可用的默认凭据，避免「没配也能跑」的假安全感。</p>
     */
    private String secretPepper = "";

    /**
     * 本服务是否强制要求签名（与网关侧同名开关取**逻辑或**）。
     *
     * <p>默认 {@code false}：与迁移前该配置项的默认值一致（迁移前以 {@code @Value} 注解的
     * 冒号后默认值给出）；生产由 Nacos 显式设为 {@code true}。</p>
     */
    private boolean requireSignature = false;

    /**
     * 两次 {@code last_used_at} 落库的最小间隔（秒）；{@code 0} = 不节流（每请求写）。
     *
     * <p>只影响「观测用的最后使用时间」精度，不参与鉴权判定。</p>
     */
    private long lastUsedWriteIntervalSeconds = DEFAULT_LAST_USED_WRITE_INTERVAL_SECONDS;
}
