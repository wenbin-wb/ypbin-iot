/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.availability.ReadingIngestReq;
import cn.ypbin.admin.iot.service.AvailabilityService;
import cn.ypbin.starter.core.model.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读数上报内部端点（access → iot，M-2）。
 *
 * <p>路径在 {@code /internal/**} 下 ⇒ 由 {@code InternalTokenGuardWebConfig} 的守卫统一保护
 * （只有 {@code X-Internal-Token}，没有租户身份；服务侧按设备解析租户后再写，见
 * {@code AvailabilityServiceImpl} 的类注释）。</p>
 *
 * <p><b>为什么收「读数观察」而不是完整读数</b>：M-2 的可用率口径只需要「有没有有效数据 + 时刻」；
 * 读数**值的存储**属数据面（IoTDB/Redis），依赖 Q8 选型，本轮不做——见 ROADMAP 四点十二。</p>
 *
 * @author wenbin
 * @since 2026-09-22
 */
@RestController
@RequestMapping("/internal/readings")
@RequiredArgsConstructor
public class InternalReadingController {

    private final AvailabilityService availabilityService;

    /**
     * 接收一批读数观察。
     *
     * @param req 上报请求（单批上限见 {@link ReadingIngestReq#MAX_BATCH_SIZE}）
     * @return 实际处理的观察条数
     */
    @PostMapping
    public R<Integer> ingest(@Valid @RequestBody ReadingIngestReq req) {
        return R.ok(availabilityService.ingest(req));
    }
}
