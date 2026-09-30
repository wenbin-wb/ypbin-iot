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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开放 API 作用域白名单的一致性门禁（看板 #11；独立复核 2026-09-30 C9/D3）。
 *
 * <p>为什么要有它：ALLOWED_SCOPES（本类）与 deploy/sql/007-iot-data.sql 的权限码是
 * 两份真相。若白名单里出现 SQL 里不存在的码（拼错/臆造），Key 配了该 scope 也不会在任何
 * 端点上生效——静默失效；若 SQL 里删了某个码而白名单没删，同样。本门禁把两边钉在一起。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
class OpenApiAllowlistConsistencyGateTest {

    /** 仓库根（沿用 IotTenantIsolationGateTest 的定位口径：模块 basedir 向上两级）。 */
    private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

    private static final Path SQL_FILE = REPO_ROOT.resolve("deploy/sql/007-iot-data.sql");

    @Test
    @DisplayName("白名单里的每个 scope 都必须是 SQL 中真实定义过的权限码（防臆造/防拼错）")
    void allowlistMustBeDefinedInSql() throws IOException {
        // 读不到 SQL => 本门禁空跑（假绿），必须显式失败（仓库惯例：防空跑）
        assertThat(Files.exists(SQL_FILE))
            .as("读不到 %s => 本门禁空跑，不能算通过", SQL_FILE)
            .isTrue();
        String sql = Files.readString(SQL_FILE, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        for (String scope : OpenApiPrincipal.ALLOWED_SCOPES) {
            if (!sql.contains("'" + scope + "'")) {
                missing.add(scope);
            }
        }
        assertThat(missing)
            .as("这些 scope 在 deploy/sql/007-iot-data.sql 中不存在 => 要么拼错、要么臆造，"
                + "用户配了也永远不生效（静默失效）")
            .isEmpty();
    }

    @Test
    @DisplayName("白名单必须非空且每一项非空白")
    void allowlistMustNotBeEmpty() {
        assertThat(OpenApiPrincipal.ALLOWED_SCOPES)
            .as("白名单为空 => 所有虚拟主体一律 403，等于功能不可用")
            .isNotEmpty();
        for (String scope : OpenApiPrincipal.ALLOWED_SCOPES) {
            assertThat(scope).as("scope 不允许为空串").isNotBlank();
        }
    }
}
