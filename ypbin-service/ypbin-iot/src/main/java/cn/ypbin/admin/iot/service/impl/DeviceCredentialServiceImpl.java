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
import cn.ypbin.admin.iot.service.DeviceCredentialService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import cn.ypbin.starter.tenant.core.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备凭据服务实现。
 *
 * <p><b>租户隔离</b>：与同模块其它端点一致——**不手写** {@code tenant_id} 条件，
 * 由 MyBatis-Plus 租户插件按当前租户上下文注入。因此「跨租户」与「设备不存在」在服务端
 * 是同一条路径（查不到 ⇒ 抛同一个业务异常），**不区分**二者（区分即信息泄露）。</p>
 *
 * <p><b>秘密的可见面</b>：明文只在 {@link #issue} 的返回值里出现一次；库中只有
 * {@code sha256(password + salt)}；日志只记设备 id 与版本号。校验失败的原因用枚举码记录，
 * 口令本身**从不**进入任何日志/异常消息（{@code DeviceCredentialSecretLeakTest} 钉住这一点）。</p>
 *
 * <p><b>为什么不推进 config_epoch</b>：凭据不是采集参数（{@code credential_ref} 对 access 是不透明值，
 * 不影响「采什么」），推进 {@code config_epoch} 会让 access 无谓地重建设备链路——反而在变更窗口内
 * 制造采集中断风险。设备/点位变更才推进。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
@Service
public class DeviceCredentialServiceImpl
    extends BaseServiceImpl<IotDeviceCredentialMapper, IotDeviceCredential>
    implements DeviceCredentialService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialServiceImpl.class);

    /** 「设备不存在」的业务提示（跨租户与此**同一句**，不区分）。 */
    private static final String DEVICE_NOT_FOUND_MESSAGE = "设备不存在：";

    /** 首次签发的版本号。 */
    private static final int FIRST_CREDENTIAL_VERSION = 1;

    /** 吊销后的秘密列取值（空字符串 ⇒ 任何口令都算不出来）。 */
    private static final String REVOKED_SECRET = "";

    /** 校验通过/拒绝的结果码（对齐 EMQX HTTP 认证源的 result 语义）。 */
    private static final String RESULT_ALLOW = "allow";

    private static final String RESULT_DENY = "deny";

    private final IotDeviceMapper iotDeviceMapper;

    private final EmqxProperties emqxProperties;

    public DeviceCredentialServiceImpl(IotDeviceMapper iotDeviceMapper,
                                       EmqxProperties emqxProperties) {
        this.iotDeviceMapper = iotDeviceMapper;
        this.emqxProperties = emqxProperties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceCredentialIssuedResp issue(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        Long tenantId = device.getTenantId();
        int version = device.getCredentialVersion() == null
            ? FIRST_CREDENTIAL_VERSION : device.getCredentialVersion() + 1;
        String password = DevicePasswordGenerator.generate(emqxProperties.getCredentialPasswordLength());
        String salt = DevicePasswordHasher.newSaltHex();
        LocalDateTime issuedAt = LocalDateTime.now();

        IotDeviceCredential row = selectCredential(deviceId);
        if (row == null) {
            row = new IotDeviceCredential();
            row.setDeviceId(deviceId);
            row.setTenantId(tenantId);
        }
        row.setUsername(DeviceMqttNaming.username(tenantId, deviceId));
        row.setCredentialVersion(version);
        row.setPasswordAlgo(DevicePasswordHasher.ALGO_SHA256_SUFFIX);
        row.setPasswordSalt(salt);
        row.setPasswordHash(DevicePasswordHasher.hashHex(password, salt));
        if (row.getId() == null) {
            save(row);
        } else {
            updateById(row);
        }
        updateDeviceCredentialState(deviceId, DeviceMqttNaming.credentialRef(tenantId, deviceId),
            version, issuedAt, null);

        // 只记设备 id 与版本号：明文口令与哈希**不进日志**（这是本条链路上最容易被顺手写坏的地方）
        log.info("[iot] 设备凭据已签发/轮换：deviceId={} version={}（明文仅在响应中出现一次）",
            deviceId, version);

        DeviceCredentialIssuedResp resp = new DeviceCredentialIssuedResp();
        resp.setDeviceId(deviceId);
        resp.setUsername(row.getUsername());
        resp.setCredentialVersion(version);
        resp.setCredentialIssuedAt(issuedAt);
        resp.setPassword(password);
        return resp;
    }

    @Override
    public DeviceCredentialResp view(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        DeviceCredentialResp resp = new DeviceCredentialResp();
        resp.setDeviceId(deviceId);
        boolean issued = device.getCredentialVersion() != null && device.getCredentialRef() != null;
        resp.setIssued(issued);
        resp.setValid(issued && device.getCredentialRevokedAt() == null);
        resp.setCredentialVersion(device.getCredentialVersion());
        resp.setCredentialIssuedAt(device.getCredentialIssuedAt());
        resp.setCredentialRevokedAt(device.getCredentialRevokedAt());
        resp.setUsername(issued ? DeviceMqttNaming.username(device.getTenantId(), deviceId) : null);
        return resp;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        if (device.getCredentialRevokedAt() != null) {
            // 幂等：重复吊销保留**首次**吊销时刻（改成当前时刻会让审计时间线每次调用都漂移）
            log.info("[iot] 设备凭据已处于吊销态，本次调用无副作用：deviceId={} version={}",
                deviceId, device.getCredentialVersion());
            return;
        }
        IotDeviceCredential row = selectCredential(deviceId);
        if (row != null) {
            // 清空秘密列（而不是删行）：行留着才能让「版本号 → 哈希」的唯一映射保持可审计，
            // 空哈希又保证任何口令都校验不过（见 DevicePasswordHasher.matches 的空值分支）
            row.setPasswordSalt(REVOKED_SECRET);
            row.setPasswordHash(REVOKED_SECRET);
            updateById(row);
        }
        updateDeviceCredentialState(deviceId, null, device.getCredentialVersion(),
            device.getCredentialIssuedAt(), LocalDateTime.now());
        log.info("[iot] 设备凭据已吊销：deviceId={} version={}（此后该设备不得再认证成功）",
            deviceId, device.getCredentialVersion());
    }

    @Override
    public DeviceConnectionResp connection(Long deviceId) {
        IotDevice device = requireDevice(deviceId);
        Long tenantId = device.getTenantId();
        boolean issued = device.getCredentialVersion() != null && device.getCredentialRef() != null;

        DeviceConnectionResp resp = new DeviceConnectionResp();
        resp.setDeviceId(deviceId);
        resp.setUsername(DeviceMqttNaming.username(tenantId, deviceId));
        resp.setClientId(DeviceMqttNaming.clientId(tenantId, deviceId));
        resp.setTopicUpPrefix(DeviceMqttNaming.topicUpPrefix(tenantId, deviceId));
        resp.setTopicDownPrefix(DeviceMqttNaming.topicDownPrefix(tenantId, deviceId));
        resp.setCredentialIssued(issued);
        resp.setCredentialVersion(device.getCredentialVersion());
        resp.setCredentialValid(issued && device.getCredentialRevokedAt() == null);
        // broker 地址如实回本环境配置：enabled=false 时地址为空**不是**占位，而是真实状态（EMQX 未接入）
        resp.setEmqxEnabled(emqxProperties.isEnabled());
        resp.setBrokerHost(emqxProperties.getBrokerHost());
        resp.setBrokerPort(emqxProperties.getBrokerPort());
        resp.setBrokerTlsEnabled(emqxProperties.isBrokerTlsEnabled());
        return resp;
    }

    @Override
    public DeviceCredentialVerifyResp verify(DeviceCredentialVerifyReq req) {
        String username = req.getUsername();
        Optional<Long> tenantId = DeviceMqttNaming.parseTenantId(username);
        Optional<Long> parsedDeviceId = DeviceMqttNaming.parseDeviceId(username);
        if (tenantId.isEmpty() || parsedDeviceId.isEmpty()) {
            return deny(DeviceCredentialDenyReason.MALFORMED_USERNAME, null, null);
        }
        Long deviceId = parsedDeviceId.get();
        // 用户名里的租户段就是本次校验的租户上下文：跨租户的 username 进来后查不到设备 ⇒ 拒绝。
        // 必须显式进租户上下文：本端点无身份头，而租户插件是 fail-closed 的（无上下文直接抛）
        return TenantContext.executeWithTenant(tenantId.get(), () -> verifyInTenant(deviceId, req));
    }

    /**
     * 在租户上下文内校验（拆出来是为了让「进上下文」这一步在调用点一眼可见）。
     *
     * @param deviceId 设备主键
     * @param req      校验请求
     * @return 校验结果
     */
    private DeviceCredentialVerifyResp verifyInTenant(Long deviceId, DeviceCredentialVerifyReq req) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            return deny(DeviceCredentialDenyReason.DEVICE_NOT_FOUND, null, null);
        }
        Integer version = device.getCredentialVersion();
        if (device.getCredentialRevokedAt() != null) {
            return deny(DeviceCredentialDenyReason.REVOKED, deviceId, version);
        }
        if (version == null || device.getCredentialRef() == null) {
            return deny(DeviceCredentialDenyReason.NOT_ISSUED, deviceId, version);
        }
        IotDeviceCredential row = selectCredential(deviceId);
        if (row == null) {
            return deny(DeviceCredentialDenyReason.SECRET_MISSING, deviceId, version);
        }
        if (!Objects.equals(row.getCredentialVersion(), version)) {
            return deny(DeviceCredentialDenyReason.VERSION_STALE, deviceId, version);
        }
        if (!DevicePasswordHasher.isUsable(row.getPasswordSalt(), row.getPasswordHash())) {
            // 秘密被清空（吊销过 / 人工清过列）与「口令打错」是两种运维结论，必须分开报
            return deny(DeviceCredentialDenyReason.SECRET_MISSING, deviceId, version);
        }
        if (!DevicePasswordHasher.matches(req.getPassword(), row.getPasswordSalt(), row.getPasswordHash())) {
            // 口令不匹配也照记：这是排障与暴力尝试的共同线索；日志里只有设备 id、版本与原因码
            return deny(DeviceCredentialDenyReason.BAD_PASSWORD, deviceId, version);
        }
        DeviceCredentialVerifyResp resp = new DeviceCredentialVerifyResp();
        resp.setAllowed(Boolean.TRUE);
        resp.setResult(RESULT_ALLOW);
        resp.setDeviceId(deviceId);
        resp.setCredentialVersion(version);
        return resp;
    }

    /**
     * 组装拒绝结果（含原因码；**不含任何秘密**）。
     *
     * @param reason   拒绝原因
     * @param deviceId 设备主键（未解析出时为空）
     * @param version  凭据版本号（未签发时为空）
     * @return 拒绝结果
     */
    private DeviceCredentialVerifyResp deny(DeviceCredentialDenyReason reason, Long deviceId,
                                            Integer version) {
        log.info("[iot] 设备凭据校验未通过：deviceId={} version={} 原因={}({})",
            deviceId, version, reason.getCode(), reason.getDesc());
        DeviceCredentialVerifyResp resp = new DeviceCredentialVerifyResp();
        resp.setAllowed(Boolean.FALSE);
        resp.setResult(RESULT_DENY);
        resp.setReason(reason.getCode());
        resp.setDeviceId(deviceId);
        resp.setCredentialVersion(version);
        return resp;
    }

    /**
     * 取设备（**租户范围内**：查不到即「设备不存在」，跨租户同此语义）。
     *
     * @param deviceId 设备主键
     * @return 设备实体
     */
    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, DEVICE_NOT_FOUND_MESSAGE + deviceId);
        }
        return device;
    }

    /**
     * 取该设备当前凭据行（租户范围内；无则 {@code null}）。
     *
     * @param deviceId 设备主键
     * @return 凭据行；无则 {@code null}
     */
    private IotDeviceCredential selectCredential(Long deviceId) {
        LambdaQueryWrapper<IotDeviceCredential> wrapper = Wrappers.lambdaQuery(IotDeviceCredential.class)
            .eq(IotDeviceCredential::getDeviceId, deviceId);
        return getOne(wrapper, false);
    }

    /**
     * 更新设备上的凭据元信息（**显式 set**，包括把吊销时刻置回 NULL）。
     *
     * <p>为什么不用 {@code updateById(entity)}：MyBatis-Plus 默认的字段策略是「非 NULL 才更新」，
     * 于是「重新签发要清掉吊销时刻」这一步会被**静默跳过** ⇒ 设备永远处于已吊销态、
     * 校验永远拒绝，而签发接口返回 200。用 {@code LambdaUpdateWrapper} 显式 set 才能把列写成 NULL。</p>
     *
     * @param deviceId    设备主键
     * @param credentialRef 凭据引用（吊销时传 {@code null}）
     * @param version     版本号
     * @param issuedAt    签发时刻（吊销时保留原值）
     * @param revokedAt   吊销时刻（签发/轮换时传 {@code null} 以清除）
     */
    private void updateDeviceCredentialState(Long deviceId, String credentialRef, Integer version,
                                             LocalDateTime issuedAt, LocalDateTime revokedAt) {
        LambdaUpdateWrapper<IotDevice> update = Wrappers.lambdaUpdate(IotDevice.class)
            .eq(IotDevice::getId, deviceId)
            .set(IotDevice::getCredentialRef, credentialRef)
            .set(IotDevice::getCredentialVersion, version)
            .set(IotDevice::getCredentialIssuedAt, issuedAt)
            .set(IotDevice::getCredentialRevokedAt, revokedAt);
        iotDeviceMapper.update(null, update);
    }
}
