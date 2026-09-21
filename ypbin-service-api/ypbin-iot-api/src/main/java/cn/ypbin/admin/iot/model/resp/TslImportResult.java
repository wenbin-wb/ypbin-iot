/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.resp;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * TSL 导入结果（§3.7：失败逐项报错，禁静默丢弃）。
 *
 * <p>全部校验通过才落库；任一失败则整体不落库，{@code errors} 逐项列出。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslImportResult {

    /** 校验通过的元素数（服务/属性/命令/事件计数之和）。 */
    private int successCount;

    /** 逐项错误列表（空集合表示导入成功）。 */
    private List<String> errors;
}
