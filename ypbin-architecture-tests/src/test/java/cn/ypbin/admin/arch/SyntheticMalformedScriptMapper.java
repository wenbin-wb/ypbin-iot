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

import org.apache.ibatis.annotations.Insert;

/**
 * 规则自检夹具：故意**缺少**结尾 {@code </script>}（2026-09-25 生产事故同款形态）。
 *
 * <p>它不进任何产物的类路径（只在 {@code ypbin-architecture-tests} 的测试源码里），
 * 用途是让 {@link IotMapperAnnotationXmlGateTest} 证明「这条规则真的会咬人」——
 * 断言一个已知违规必须被判为违规，否则门禁可能只是恒真的装饰（本仓教训七/八/二十七）。</p>
 *
 * @author wenbin
 * @since 2026-09-26
 */
interface SyntheticMalformedScriptMapper {

    /** 故意缺 {@code </script>}：MyBatis 解析到一半即抛 SAXParseException。 */
    @Insert("<script>SELECT 1 FROM dual WHERE 1 = 1")
    int broken();
}
