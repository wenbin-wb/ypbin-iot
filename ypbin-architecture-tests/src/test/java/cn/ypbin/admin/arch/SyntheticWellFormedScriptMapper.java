/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.arch;

import java.util.List;
import org.apache.ibatis.annotations.Insert;

/**
 * 规则自检夹具：**合法**的 {@code <script>} 注解（含动态标签），用于断言门禁不误报。
 *
 * @author wenbin
 * @since 2026-09-26
 */
interface SyntheticWellFormedScriptMapper {

    /**
     * 带 {@code <foreach>} 的合法 XML 脚本。
     *
     * @param rows 行值
     * @return 影响行数
     */
    @Insert("<script>INSERT INTO demo (a) VALUES "
        + "<foreach collection='rows' item='row' open='(' separator='),(' close=')'>#{row}</foreach>"
        + "</script>")
    int insertBatch(List<String> rows);
}
