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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyReq;
import cn.ypbin.admin.iot.credential.DeviceCredentialVerifyResp;
import cn.ypbin.admin.iot.credential.DeviceMqttNaming;
import cn.ypbin.admin.iot.credential.DevicePasswordGenerator;
import cn.ypbin.admin.iot.credential.DevicePasswordHasher;
import cn.ypbin.admin.iot.emqx.EmqxProperties;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceCredential;
import cn.ypbin.admin.iot.enums.DeviceCredentialDenyReason;
import cn.ypbin.admin.iot.mapper.IotDeviceCredentialMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.model.resp.DeviceConnectionResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialIssuedResp;
import cn.ypbin.admin.iot.model.resp.DeviceCredentialResp;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备凭据生命周期的纯逻辑单测（不起 Spring 上下文、不连库）。
 *
 * <p>本文件要钉住的是这台状态机的**四类不可逆后果**（写错了都不会报错，只会静默放行或静默失效）：</p>
 * <ol>
 *   <li><b>明文只落响应、库中只有哈希</b>——落库断言直接对插入/更新的实体做，且逐字段确认不等于口令；</li>
 *   <li><b>重置使旧口令立即失效</b>——轮换后老口令必须校验不过，且版本号 +1；</li>
 *   <li><b>重新签发必须把吊销时刻清成 NULL</b>——{@code updateById(entity)} 的「非 NULL 才更新」策略会
 *       把它静默跳过（于是设备永远吊销、接口却返回 200），故断言走的是显式 set 的 wrapper；</li>
 *   <li><b>吊销幂等且清空秘密</b>——重复吊销不得改写吊销时刻，也不得报错。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-27
 */
class DeviceCredentialServiceImplTest {

    private static final long TENANT_ID = 1L;

    private static final long DEVICE_ID = 5L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);

    private final IotDeviceCredentialMapper credentialMapper = mock(IotDeviceCredentialMapper.class);

    private final EmqxProperties emqxProperties = new EmqxProperties();

    private final DeviceCredentialServiceImpl service =
        new DeviceCredentialServiceImpl(deviceMapper, emqxProperties);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDevice.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDeviceCredential.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", credentialMapper);
    }

    @Test
    @DisplayName("签发：明文只出现在响应里，库里只落哈希（且逐字段不含口令）；版本=1、引用=emqx 引用")
    void issueMustReturnOneTimePlaintextAndPersistOnlyHash() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(null, null, null));
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(null);

        DeviceCredentialIssuedResp resp = service.issue(DEVICE_ID);

        assertThat(resp.getPassword()).isNotBlank().hasSize(43);
        assertThat(resp.getCredentialVersion()).isEqualTo(1);
        assertThat(resp.getUsername()).isEqualTo("1.5");
        assertThat(resp.getCredentialIssuedAt()).isNotNull();

        ArgumentCaptor<IotDeviceCredential> inserted = ArgumentCaptor.forClass(IotDeviceCredential.class);
        verify(credentialMapper).insert(inserted.capture());
        IotDeviceCredential row = inserted.getValue();
        assertThat(row.getPasswordAlgo()).isEqualTo(DevicePasswordHasher.ALGO_SHA256_SUFFIX);
        assertThat(row.getPasswordSalt()).isNotBlank();
        assertThat(row.getPasswordHash())
            .as("库里必须只有哈希，且哈希 = sha256(口令 + 盐)")
            .isEqualTo(DevicePasswordHasher.hashHex(resp.getPassword(), row.getPasswordSalt()))
            .isNotEqualTo(resp.getPassword());
        assertThat(row.getCredentialVersion()).isEqualTo(1);
        assertThat(row.getUsername()).isEqualTo("1.5");
        assertThat(secretFieldValues(row))
            .as("落库实体的任何字符串字段都不得等于明文口令")
            .doesNotContain(resp.getPassword());

        Map<String, Object> params = captureDeviceStateParams();
        assertThat(params).containsValue(1);
        assertThat(params.values()).as("设备上的凭据引用必须写成 emqx 非密引用")
            .anyMatch(value -> DeviceMqttNaming.credentialRef(TENANT_ID, DEVICE_ID).equals(value));
        assertThat(params.values())
            .as("首次签发必须把吊销时刻显式写成 NULL（否则设备一出生就是吊销态）")
            .anyMatch(value -> value == null);
    }

    @Test
    @DisplayName("重置：版本 +1、旧口令立即失效、新口令可用（旧版本不再参与校验）")
    void rotateMustInvalidateOldPassword() {
        String oldSalt = DevicePasswordHasher.newSaltHex();
        String oldPassword = "old-one-time-password";
        // 先取出旧哈希的**值**：service 会就地改写同一个实体（selectOne 返回的就是它），
        // 事后再读 existing.getPasswordHash() 拿到的是新值，断言会变成恒真（教训二十六/二十七）
        String oldHash = DevicePasswordHasher.hashHex(oldPassword, oldSalt);
        IotDeviceCredential existing = credentialRow(1, oldSalt, oldHash, 11L);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(1, null, "emqx:ref-old"));
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(existing);

        DeviceCredentialIssuedResp resp = service.issue(DEVICE_ID);

        assertThat(resp.getCredentialVersion()).isEqualTo(2);
        ArgumentCaptor<IotDeviceCredential> updated = ArgumentCaptor.forClass(IotDeviceCredential.class);
        verify(credentialMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(11L);
        assertThat(updated.getValue().getPasswordHash())
            .isEqualTo(DevicePasswordHasher.hashHex(resp.getPassword(), updated.getValue().getPasswordSalt()))
            .isNotEqualTo(oldHash);
        assertThat(DevicePasswordHasher.matches(oldPassword, updated.getValue().getPasswordSalt(),
            updated.getValue().getPasswordHash()))
            .as("轮换后旧口令必须校验不过（否则「重置」只是多发了一个口令而已）")
            .isFalse();
        assertThat(updated.getValue().getPasswordSalt())
            .as("轮换必须换盐（沿用旧盐会让同一个口令在新旧版本下算出同一个哈希）")
            .isNotEqualTo(oldSalt);
    }

    @Test
    @DisplayName("查看：只回元信息（版本/时刻/是否可用），未签发设备不编造用户名")
    void viewMustReturnMetadataOnly() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(null, null, null));
        DeviceCredentialResp notIssued = service.view(DEVICE_ID);
        assertThat(notIssued.getIssued()).isFalse();
        assertThat(notIssued.getValid()).isFalse();
        assertThat(notIssued.getCredentialVersion()).isNull();
        assertThat(notIssued.getUsername()).isNull();

        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(3, null, "emqx:ref"));
        DeviceCredentialResp issued = service.view(DEVICE_ID);
        assertThat(issued.getIssued()).isTrue();
        assertThat(issued.getValid()).isTrue();
        assertThat(issued.getCredentialVersion()).isEqualTo(3);
        assertThat(issued.getUsername()).isEqualTo("1.5");
    }

    @Test
    @DisplayName("吊销：清空秘密列 + 置吊销时刻 + 清空引用；重复调用不改状态、不写库")
    void revokeMustClearSecretAndBeIdempotent() {
        IotDeviceCredential row = credentialRow(2, "salt", "hash", 12L);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(2, null, "emqx:ref"));
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(row);

        service.revoke(DEVICE_ID);

        ArgumentCaptor<IotDeviceCredential> updated = ArgumentCaptor.forClass(IotDeviceCredential.class);
        verify(credentialMapper).updateById(updated.capture());
        assertThat(updated.getValue().getPasswordHash()).isEmpty();
        assertThat(updated.getValue().getPasswordSalt()).isEmpty();
        Map<String, Object> params = captureDeviceStateParams();
        assertThat(params.values())
            .as("吊销必须写入吊销时刻（非 null）")
            .anyMatch(value -> value != null && !DeviceMqttNaming.credentialRef(TENANT_ID, DEVICE_ID).equals(value)
                && !Integer.valueOf(2).equals(value));

        // 第二次吊销：设备已带吊销时刻 ⇒ 直接返回，不产生任何写操作
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(2, java.time.LocalDateTime.now(), null));
        service.revoke(DEVICE_ID);
        verify(credentialMapper, times(1)).updateById(any(IotDeviceCredential.class));
        verify(credentialMapper, never()).insert(any(IotDeviceCredential.class));
    }

    @Test
    @DisplayName("接入信息：装配用户名/clientId/主题前缀，未接入 broker 时如实回 enabled=false 且无口令字段")
    void connectionMustDescribeBrokerWithoutSecrets() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(4, null, "emqx:ref"));

        DeviceConnectionResp resp = service.connection(DEVICE_ID);

        assertThat(resp.getUsername()).isEqualTo("1.5");
        assertThat(resp.getClientId()).isEqualTo("1.5");
        assertThat(resp.getTopicUpPrefix()).isEqualTo("ypbin/v1/1/5/up/");
        assertThat(resp.getTopicDownPrefix()).isEqualTo("ypbin/v1/1/5/down/");
        assertThat(resp.getCredentialIssued()).isTrue();
        assertThat(resp.getCredentialValid()).isTrue();
        assertThat(resp.getEmqxEnabled())
            .as("本环境未接入 broker ⇒ 必须如实回 false（不是占位值）")
            .isFalse();
        assertThat(resp.getBrokerHost()).isNull();
        assertThat(resp.getBrokerPort()).isNull();

        emqxProperties.setEnabled(true);
        emqxProperties.setBrokerHost("broker.internal");
        emqxProperties.setBrokerPort(1883);
        DeviceConnectionResp configured = service.connection(DEVICE_ID);
        assertThat(configured.getEmqxEnabled()).isTrue();
        assertThat(configured.getBrokerHost()).isEqualTo("broker.internal");
        assertThat(configured.getBrokerPort()).isEqualTo(1883);
        assertThat(configured.getBrokerTlsEnabled()).isFalse();
    }

    @Test
    @DisplayName("跨租户/不存在的设备：一律「设备不存在」，不区分二者")
    void unknownDeviceMustLookTheSameAsCrossTenant() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.issue(DEVICE_ID)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        assertThatThrownBy(() -> service.view(DEVICE_ID)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        assertThatThrownBy(() -> service.revoke(DEVICE_ID)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        assertThatThrownBy(() -> service.connection(DEVICE_ID)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        verify(credentialMapper, never()).insert(any(IotDeviceCredential.class));
    }

    @Test
    @DisplayName("校验：正确口令 allow；错口令 deny(bad-password)；未签发/已吊销/跨租户/用户名非法各有原因码")
    void verifyMustDecideByStateMachine() {
        String salt = DevicePasswordHasher.newSaltHex();
        String password = DevicePasswordGenerator.generate(32);
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(credentialRow(1, salt,
            DevicePasswordHasher.hashHex(password, salt), 21L));
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(1, null, "emqx:ref"));

        DeviceCredentialVerifyResp allowed = service.verify(verifyReq("1.5", password));
        assertThat(allowed.getAllowed()).isTrue();
        assertThat(allowed.getResult()).isEqualTo("allow");
        assertThat(allowed.getReason()).isNull();
        assertThat(allowed.getCredentialVersion()).isEqualTo(1);

        DeviceCredentialVerifyResp wrong = service.verify(verifyReq("1.5", "not-the-password"));
        assertThat(wrong.getAllowed()).isFalse();
        assertThat(wrong.getResult()).isEqualTo("deny");
        assertThat(wrong.getReason()).isEqualTo(DeviceCredentialDenyReason.BAD_PASSWORD.getCode());

        // 跨租户：用户名说租户 2，但设备只属于租户 1 ⇒ 在该租户上下文里查不到 ⇒ 设备不存在
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        assertThat(service.verify(verifyReq("2.5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.DEVICE_NOT_FOUND.getCode());

        // 用户名非法必须在解析阶段就拒绝。**两类都要有**：
        // ① 单段/无点（长度不等于 2）；② 两段但不满足 ^[0-9]+\.[0-9]+$（负号、字母、通配符）——
        //    只测①的话，把正则放宽成 ^.*$ 也不会有用例转红（独立复核 M5 实证的缺口）
        assertThat(service.verify(verifyReq("2/5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.MALFORMED_USERNAME.getCode());
        assertThat(service.verify(verifyReq("svc-ingress", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.MALFORMED_USERNAME.getCode());
        for (String illegal : List.of("-1.5", "1.5x", "1.5+#", " 1.5", "1. 5", "+1.5")) {
            assertThat(service.verify(verifyReq(illegal, password)).getReason())
                .as("两段式非法用户名 %s 必须被正则拦下（不能只靠段数判断）", illegal)
                .isEqualTo(DeviceCredentialDenyReason.MALFORMED_USERNAME.getCode());
        }

        // 已吊销
        when(deviceMapper.selectById(DEVICE_ID))
            .thenReturn(device(1, java.time.LocalDateTime.now(), "emqx:ref"));
        assertThat(service.verify(verifyReq("1.5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.REVOKED.getCode());

        // 未签发
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(null, null, null));
        assertThat(service.verify(verifyReq("1.5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.NOT_ISSUED.getCode());

        // 版本不一致（轮换中间态/脏数据）不得拿旧哈希放行
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(9, null, "emqx:ref"));
        assertThat(service.verify(verifyReq("1.5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.VERSION_STALE.getCode());

        // 秘密列被清空（吊销过/数据缺失）
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(1, null, "emqx:ref"));
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(credentialRow(1, "", "", 21L));
        assertThat(service.verify(verifyReq("1.5", password)).getReason())
            .isEqualTo(DeviceCredentialDenyReason.SECRET_MISSING.getCode());
    }

    @Test
    @DisplayName("校验必须在「用户名解析出的租户」上下文内查库，且用后还原（独立复核 M4 缺口）")
    void verifyMustRunInsideTheTenantParsedFromUsername() {
        String salt = DevicePasswordHasher.newSaltHex();
        String password = DevicePasswordGenerator.generate(32);
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(credentialRow(1, salt,
            DevicePasswordHasher.hashHex(password, salt), 41L));
        // 设备查询发生在 verifyInTenant 内：此刻租户上下文必须是用户名里的租户（2），而不是外层（空）
        List<Optional<Long>> seenTenants = new ArrayList<>();
        when(deviceMapper.selectById(DEVICE_ID)).thenAnswer(invocation -> {
            seenTenants.add(TenantContext.getTenantId());
            return device(1, null, "emqx:ref");
        });

        DeviceCredentialVerifyResp resp = service.verify(verifyReq("2." + DEVICE_ID, password));

        assertThat(resp.getAllowed()).isTrue();
        assertThat(seenTenants)
            .as("查库时租户上下文必须是用户名里的租户 2；去掉 executeWithTenant 会退化成「无上下文」（fail-closed 抛异常）"
                + "或被 tenant 插件按外层上下文过滤 —— 两者都拿不到设备")
            .containsExactly(Optional.of(2L));
        assertThat(TenantContext.getTenantId())
            .as("校验结束后必须还原（不得把租户泄漏给同一线程上的后续调用）")
            .isEmpty();
    }

    @Test
    @DisplayName("设备状态更新必须走显式 set 的 wrapper（否则 NULL 清不掉 ⇒ 设备永远吊销而接口 200）")
    void deviceStateUpdateMustNotUseEntityUpdate() {
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device(1, java.time.LocalDateTime.now(), null));
        when(credentialMapper.selectOne(any(), anyBoolean())).thenReturn(credentialRow(1, "s", "h", 31L));

        service.issue(DEVICE_ID);

        verify(deviceMapper, never()).updateById(any(IotDevice.class));
        Map<String, Object> params = captureDeviceStateParams();
        assertThat(params.values())
            .as("重新签发必须把吊销时刻清成 NULL——entity 更新策略会跳过 null 字段，只有显式 set 才行")
            .anyMatch(value -> value == null);
    }

    /**
     * 捕获设备状态更新 wrapper 的绑定参数（列表值里混着 id 条件，故只做「包含」式断言）。
     *
     * @return 绑定参数表
     */
    private Map<String, Object> captureDeviceStateParams() {
        ArgumentCaptor<Wrapper<IotDevice>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(deviceMapper).update(isNull(), captor.capture());
        Wrapper<IotDevice> wrapper = captor.getValue();
        assertThat(wrapper).isInstanceOf(LambdaUpdateWrapper.class);
        LambdaUpdateWrapper<IotDevice> update = (LambdaUpdateWrapper<IotDevice>) wrapper;
        assertThat(update.getSqlSet())
            .as("四个凭据列都必须显式 set（含吊销时刻，哪怕值是 null）")
            .contains("credential_ref=", "credential_version=", "credential_issued_at=",
                "credential_revoked_at=");
        return update.getParamNameValuePairs();
    }

    /**
     * 构造设备实体。
     *
     * @param version   凭据版本号
     * @param revokedAt 吊销时刻
     * @param ref       凭据引用
     * @return 设备
     */
    private static IotDevice device(Integer version, java.time.LocalDateTime revokedAt, String ref) {
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_ID);
        device.setCredentialVersion(version);
        device.setCredentialRevokedAt(revokedAt);
        device.setCredentialRef(ref);
        return device;
    }

    /**
     * 构造凭据行。
     *
     * @param version 版本号
     * @param salt    盐
     * @param hash    哈希
     * @param id      主键
     * @return 凭据行
     */
    private static IotDeviceCredential credentialRow(Integer version, String salt, String hash, Long id) {
        IotDeviceCredential row = new IotDeviceCredential();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setDeviceId(DEVICE_ID);
        row.setCredentialVersion(version);
        row.setUsername("1.5");
        row.setPasswordAlgo(DevicePasswordHasher.ALGO_SHA256_SUFFIX);
        row.setPasswordSalt(salt);
        row.setPasswordHash(hash);
        return row;
    }

    /**
     * 构造校验请求。
     *
     * @param username 用户名
     * @param password 口令
     * @return 请求
     */
    private static DeviceCredentialVerifyReq verifyReq(String username, String password) {
        DeviceCredentialVerifyReq req = new DeviceCredentialVerifyReq();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    /**
     * 取实体上所有字符串字段的值（用于「落库实体不含明文」的逐字段断言）。
     *
     * @param row 凭据行
     * @return 全部字符串字段值
     */
    private static List<String> secretFieldValues(IotDeviceCredential row) {
        return Arrays.stream(IotDeviceCredential.class.getDeclaredFields())
            .filter(field -> field.getType() == String.class)
            .map(field -> readString(field, row))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * 反射读字符串字段。
     *
     * @param field 字段
     * @param row   凭据行
     * @return 字段值
     */
    private static String readString(Field field, IotDeviceCredential row) {
        try {
            field.setAccessible(true);
            return (String) field.get(row);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("读取实体字段失败：" + field.getName(), ex);
        }
    }
}
