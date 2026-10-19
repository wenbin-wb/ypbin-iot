/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.admin.system.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.system.entity.SysDept;
import cn.ypbin.admin.system.entity.SysRole;
import cn.ypbin.admin.system.mapper.SysDeptMapper;
import cn.ypbin.admin.system.mapper.SysRoleDeptMapper;
import cn.ypbin.admin.system.mapper.SysRoleMapper;
import cn.ypbin.admin.system.service.SysPermissionService;
import cn.ypbin.starter.security.core.LoginUser;
import cn.ypbin.starter.security.identity.IdentityContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 数据范围迁移（starter #107）的<b>补充边界</b>回归——与 upstream 的
 * {@code DataScopeMigrationBoundaryTest}（10 条）**不重复**，只钉住它没覆盖的四类风险。
 *
 * <p><b>为什么不直接改 upstream 的边界测试</b>：该文件在上游已存在，改它会让本仓与
 * {@code upstream/main} 产生分歧面、必须进同步白名单（每次同步都是冲突点）。本仓的补充用例
 * 全部放这个<b>新文件</b>里，零同步成本（{@code SYNC.md} 第二节的判据只约束「改动的既有文件」）。</p>
 *
 * <p>四条补充语义（都是「不加测试就只会在生产里静默出错」的类型）：</p>
 * <ol>
 *   <li><b>脏码不污染有效角色</b>：一条 {@code data_scope=99} 不能让同一用户的其它有效角色失效；</li>
 *   <li><b>三角色并集 + 部门去重</b>：同一部门被多个角色重复贡献时，片段文本不得随角色数膨胀；</li>
 *   <li><b>写路径的成环防护</b>：读路径测过环，写路径同样走后代展开，也必须不死循环；</li>
 *   <li><b>重入标记不泄漏到写路径</b>：读路径置位 {@code RESOLVING} 后必须清理，否则写路径会被永久拒绝。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-19
 */
class DataScopeSupplementaryBoundaryTest {

    private static final String MAPPED_STATEMENT =
        "cn.ypbin.admin.system.mapper.SysUserMapper.selectList";

    private static final Long USER_ID = 42L;

    private static final Long USER_DEPT_ID = 10L;

    private SysRoleMapper roleMapper;

    private SysRoleDeptMapper roleDeptMapper;

    private SysDeptMapper deptMapper;

    private SysPermissionService permissionService;

    private AdminDataScopeHandler handler;

    @BeforeEach
    void setUp() {
        roleMapper = mock(SysRoleMapper.class);
        roleDeptMapper = mock(SysRoleDeptMapper.class);
        deptMapper = mock(SysDeptMapper.class);
        permissionService = mock(SysPermissionService.class);
        handler = new AdminDataScopeHandler(provider(SysRoleMapper.class, roleMapper),
            provider(SysRoleDeptMapper.class, roleDeptMapper), provider(SysDeptMapper.class, deptMapper),
            provider(SysPermissionService.class, permissionService));
        when(permissionService.isSuperAdmin(any())).thenReturn(false);
        login(USER_DEPT_ID);
    }

    @AfterEach
    void clearIdentity() {
        IdentityContext.clear();
    }

    @Test
    @DisplayName("脏码不污染有效角色：一条 data_scope=99 被忽略，其它角色的范围照常生效")
    void dirtyScopeShouldNotPoisonValidRole() {
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 99), role(2L, 3)));

        assertThat(handler.getDataScopeSql(MAPPED_STATEMENT, "sys_user"))
            .as("脏码只让「它自己那个角色」不贡献范围；整条查询不得因此变成拒绝全部")
            .isEqualTo("dept_id IN (10)");
    }

    @Test
    @DisplayName("脏码角色不触发部门树加载：只有 DEPT_AND_CHILD / CUSTOM 才需要树")
    void dirtyScopeShouldNotLoadDeptTree() {
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 99)));

        assertThat(handler.getDataScopeSql(MAPPED_STATEMENT, "sys_user")).isEqualTo("id = -1");
        verify(deptMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("三角色并集 + 部门去重：同一部门被重复贡献时片段文本不随角色数膨胀")
    void threeRolesShouldUnionAndDeduplicate() {
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 3), role(2L, 2), role(3L, 4)));
        when(deptMapper.selectList(any())).thenReturn(List.of(
            dept(10L, 0L), dept(11L, 10L), dept(20L, 0L)));

        assertThat(handler.getDataScopeSql(MAPPED_STATEMENT, "sys_user"))
            .as("DEPT(10) + DEPT_AND_CHILD(10,11) + SELF ⇒ 部门去重后仍是 (10,11)，再 OR 本人条件")
            .isEqualTo("(dept_id IN (10,11) OR id = 42)");
    }

    @Test
    @DisplayName("写路径也走后代展开：部门成环时不栈溢出，环内可写、环外拒绝")
    void deptCycleShouldNotOverflowOnWritePath() {
        // 10 → 11 → 10 的环（业务校验只拒绝「父是自己」，两次编辑即可造出）
        when(deptMapper.selectList(any())).thenReturn(List.of(dept(10L, 11L), dept(11L, 10L)));
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 2)));

        assertThat(handler.isDeptWithinScope(11L))
            .as("环内后代在可见部门集合内，写路径必须放行")
            .isTrue();
        assertThat(handler.isDeptWithinScope(99L))
            .as("环外部门必须拒绝")
            .isFalse();
    }

    @Test
    @DisplayName("重入标记不泄漏：读路径解析完必须清理 RESOLVING，否则写路径会被永久拒绝")
    void reentrantMarkerShouldNotLeakIntoWritePath() {
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 3)));

        assertThat(handler.getDataScopeSql(MAPPED_STATEMENT, "sys_user")).isEqualTo("dept_id IN (10)");
        assertThat(handler.isDeptWithinScope(10L))
            .as("读路径置位后必须 finally remove；否则同一线程的写校验会一直命中「重入」分支")
            .isTrue();
    }

    @Test
    @DisplayName("写路径 fail-closed：用户自己没有部门时，任何目标部门都拒绝")
    void writeWithoutUserDeptShouldBeRejected() {
        login(null);
        when(roleMapper.selectByUserId(USER_ID)).thenReturn(List.of(role(1L, 2)));

        assertThat(handler.isDeptWithinScope(USER_DEPT_ID))
            .as("没有部门 ⇒ 可见部门集合为空 ⇒ 写路径必须拒绝而不是放行")
            .isFalse();
    }

    private void login(Long deptId) {
        LoginUser loginUser = new LoginUser(USER_ID, "tester");
        loginUser.setDeptId(deptId);
        IdentityContext.setLoginUser(loginUser);
    }

    private static SysRole role(Long id, Integer dataScope) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setDataScope(dataScope);
        role.setTenantId(10L);
        return role;
    }

    private static SysDept dept(Long id, Long pid) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setPid(pid);
        return dept;
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("target", instance);
        return beanFactory.getBeanProvider(type, false);
    }
}
