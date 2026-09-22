/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.availability;

import cn.ypbin.admin.iot.availability.config.ReadingFeignConfiguration;
import cn.ypbin.starter.core.model.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 读数上报内部客户端（access → iot，M-2）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 服务端由内部 token 守卫保护（见 ypbin-iot 的
 * {@code InternalTokenGuardWebConfig}）；凭证头与超时由 {@link ReadingFeignConfiguration} 显式配置
 * （禁止无超时的默认客户端）。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@FeignClient(name = "ypbin-iot", contextId = "readingClient", path = "/internal/readings",
    configuration = ReadingFeignConfiguration.class)
public interface IReadingClient {

    /**
     * 上报一批读数观察（断档/可用率口径的输入）。
     *
     * @param req 上报请求（单批上限见 {@link ReadingIngestReq#MAX_BATCH_SIZE}）
     * @return 服务端实际处理的观察条数
     */
    @PostMapping
    R<Integer> ingest(@RequestBody ReadingIngestReq req);
}
