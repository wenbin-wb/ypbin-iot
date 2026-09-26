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

import org.apache.ibatis.annotations.Select;

/**
 * 规则自检夹具：**非** {@code <script>} 的文本 SQL。
 *
 * <p>MyBatis 只把以 {@code <script>} 开头的注解当 XML 解析，文本 SQL 里的裸 {@code <} 比较符
 * 完全合法 ⇒ 门禁不得把它误报成「XML 不合法」（否则会逼着大家改写正常 SQL）。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
interface SyntheticPlainSqlMapper {

    /**
     * 文本 SQL（含裸 {@code <} 与 {@code <}}）。
     *
     * @return 行数
     */
    @Select("SELECT count(*) FROM demo WHERE a < 10 AND b <= 20")
    long count();
}
