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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotDeviceTag;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceTagMapper;
import cn.ypbin.admin.iot.model.req.IotDeviceTagReq;
import cn.ypbin.admin.iot.model.resp.BatchTagResp;
import cn.ypbin.admin.iot.model.resp.IotDeviceTagResp;
import cn.ypbin.starter.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 设备标签服务的纯逻辑单测（§3.11）。
 *
 * <p>钉死「同设备同 tag_key 唯一」这条业务约束的<b>服务层前置校验</b>：唯一键在库上兜底，
 * 但服务层必须先给出可读的业务错误，而不是把 SQL 唯一键冲突抛给用户。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
class IotDeviceTagServiceImplTest {

    private static final Long DEVICE_ID = 1L;

    private final IotDeviceMapper deviceMapper = mock(IotDeviceMapper.class);
    private final IotDeviceTagMapper tagMapper = mock(IotDeviceTagMapper.class);
    private final IotDeviceTagServiceImpl service = new IotDeviceTagServiceImpl(deviceMapper);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            IotDeviceTag.class);
    }

    @BeforeEach
    void wireBaseMapper() {
        ReflectionTestUtils.setField(service, "baseMapper", tagMapper);
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
    }

    @Test
    @DisplayName("同设备同 tag_key 已存在 → 抛业务异常且不落库（不让库唯一键冲突冒到用户）")
    void duplicateTagKeyShouldBeRejectedBeforeInsert() {
        when(tagMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(DEVICE_ID, req("location", "A 区")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("标签键已存在");
        verify(tagMapper, never()).insert(any(IotDeviceTag.class));
    }

    @Test
    @DisplayName("设备不存在 → 抛业务异常（不产生孤儿标签）")
    void unknownDeviceShouldBeRejected() {
        when(deviceMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(99L, req("location", "A 区")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在");
        verify(tagMapper, never()).insert(any(IotDeviceTag.class));
    }

    @Test
    @DisplayName("新增成功：tag_key / tag_value 按请求落库")
    void createShouldPersistKeyAndValue() {
        when(tagMapper.selectCount(any())).thenReturn(0L);

        service.create(DEVICE_ID, req("location", "A 区"));

        ArgumentCaptor<IotDeviceTag> captor = ArgumentCaptor.forClass(IotDeviceTag.class);
        verify(tagMapper).insert(captor.capture());
        assertThat(captor.getValue().getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(captor.getValue().getTagKey()).isEqualTo("location");
        assertThat(captor.getValue().getTagValue()).isEqualTo("A 区");
    }

    @Test
    @DisplayName("实体→响应：字段逐个搬运，不做改名")
    void toRespShouldMapEveryField() {
        IotDeviceTag entity = new IotDeviceTag();
        entity.setId(50L);
        entity.setDeviceId(DEVICE_ID);
        entity.setTagKey("location");
        entity.setTagValue("A 区");
        entity.setCreateTime(LocalDateTime.of(2026, 9, 20, 10, 0));

        IotDeviceTagResp resp = service.toResp(entity);

        assertThat(resp.getId()).isEqualTo(50L);
        assertThat(resp.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(resp.getTagKey()).isEqualTo("location");
        assertThat(resp.getTagValue()).isEqualTo("A 区");
        assertThat(resp.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
    }

    @Test
    @DisplayName("批量打标签-全新增：去重后一次 insertBatch，ID/tenantId/键值正确")
    void batchUpsertAllNewShouldInsertOnce() {
        when(deviceMapper.selectBatchIds(List.of(1L, 2L)))
            .thenReturn(List.of(device(1L, 7L), device(2L, 7L)));
        when(tagMapper.selectList(any())).thenReturn(List.of());

        BatchTagResp resp = service.batchUpsertTag(List.of(1L, 2L, 1L), "region", "east");

        assertThat(resp.getCreated()).as("重复的 1 去重后只算一次新增").isEqualTo(2);
        assertThat(resp.getUpdated()).isZero();
        assertThat(resp.getRemoved()).isZero();
        verify(tagMapper, never()).update(any(LambdaUpdateWrapper.class));

        ArgumentCaptor<List<IotDeviceTag>> captor = listCaptor();
        verify(tagMapper, times(1)).insertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(IotDeviceTag::getDeviceId).containsExactly(1L, 2L);
        assertThat(captor.getValue()).allSatisfy(row -> {
            assertThat(row.getId()).isNotNull();
            assertThat(row.getTenantId()).isEqualTo(7L);
            assertThat(row.getTagKey()).isEqualTo("region");
            assertThat(row.getTagValue()).isEqualTo("east");
        });
        // 设备一次 IN、已有标签一次 IN、插入一次：无循环内查库
        verify(deviceMapper, times(1)).selectBatchIds(List.of(1L, 2L));
        verify(tagMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("批量打标签-混合：已有行一次 UPDATE 新值，缺失行一次 insertBatch")
    void batchUpsertMixedShouldUpdateExistingOnceAndInsertRest() {
        when(deviceMapper.selectBatchIds(List.of(1L, 2L, 3L)))
            .thenReturn(List.of(device(1L, 7L), device(2L, 7L), device(3L, 7L)));
        when(tagMapper.selectList(any())).thenReturn(List.of(
            tag(50L, 1L, "region", "west"), tag(51L, 2L, "region", "west")));
        when(tagMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(2);

        BatchTagResp resp = service.batchUpsertTag(List.of(1L, 2L, 3L), "region", "east");

        assertThat(resp.getCreated()).isEqualTo(1);
        assertThat(resp.getUpdated()).isEqualTo(2);
        assertThat(resp.getRemoved()).isZero();

        ArgumentCaptor<LambdaUpdateWrapper<IotDeviceTag>> updateCaptor = wrapperCaptor();
        verify(tagMapper, times(1)).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSet()).contains("tag_value");
        assertThat(updateCaptor.getValue().getSqlSegment()).contains("id");
        assertThat(updateCaptor.getValue().getParamNameValuePairs().values())
            .contains("east", 50L, 51L);

        ArgumentCaptor<List<IotDeviceTag>> insertCaptor = listCaptor();
        verify(tagMapper, times(1)).insertBatch(insertCaptor.capture());
        assertThat(insertCaptor.getValue()).extracting(IotDeviceTag::getDeviceId).containsExactly(3L);
    }

    @Test
    @DisplayName("批量打标签-全部已存在：只 UPDATE，不 INSERT")
    void batchUpsertAllExistingShouldUpdateOnly() {
        when(deviceMapper.selectBatchIds(List.of(1L, 2L)))
            .thenReturn(List.of(device(1L, 7L), device(2L, 7L)));
        when(tagMapper.selectList(any())).thenReturn(List.of(
            tag(50L, 1L, "region", "west"), tag(51L, 2L, "region", "west")));
        when(tagMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(2);

        BatchTagResp resp = service.batchUpsertTag(List.of(1L, 2L), "region", "east");

        assertThat(resp.getCreated()).isZero();
        assertThat(resp.getUpdated()).isEqualTo(2);
        verify(tagMapper, times(1)).update(any(LambdaUpdateWrapper.class));
        verify(tagMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("🔴 批量打标签-缺失设备：整批拒绝并列全缺失 ID，不查标签不写库")
    void batchUpsertMissingDeviceShouldRejectWholeBatch() {
        when(deviceMapper.selectBatchIds(List.of(1L, 404L, 405L)))
            .thenReturn(List.of(device(1L, 7L)));

        assertThatThrownBy(() -> service.batchUpsertTag(List.of(1L, 404L, 405L), "region", "east"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("设备不存在")
            .hasMessageContaining("404")
            .hasMessageContaining("405");
        verify(tagMapper, never()).selectList(any());
        verify(tagMapper, never()).update(any(LambdaUpdateWrapper.class));
        verify(tagMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("批量打标签-空/含 null/超 500/键值非法：一律拒绝且不查设备不写库")
    void batchUpsertEmptyOrOverLimitShouldReject() {
        assertThatThrownBy(() -> service.batchUpsertTag(null, "region", "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpsertTag(List.of(), "region", "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpsertTag(Arrays.asList(1L, null), "region", "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchUpsertTag(manyDeviceIds(501), "region", "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 500");
        assertThatThrownBy(() -> service.batchUpsertTag(List.of(1L), "  ", "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签键不能为空");
        assertThatThrownBy(() -> service.batchUpsertTag(List.of(1L), "region", " "))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签值不能为空");
        assertThatThrownBy(() -> service.batchUpsertTag(List.of(1L), "x".repeat(65), "east"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签键长度不能超过 64");
        assertThatThrownBy(() -> service.batchUpsertTag(List.of(1L), "region", "x".repeat(256)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签值长度不能超过 255");

        verify(deviceMapper, never()).selectBatchIds(any());
        verify(tagMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("批量移除：去重后一次 DELETE（设备×键一次 IN），返回删除行数")
    void batchRemoveShouldDeleteOnceAndReturnCount() {
        when(tagMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(3);

        BatchTagResp resp = service.batchRemoveTags(List.of(1L, 2L, 1L),
            List.of("region", "env", "region"));

        assertThat(resp.getRemoved()).isEqualTo(3);
        assertThat(resp.getCreated()).isZero();
        assertThat(resp.getUpdated()).isZero();

        ArgumentCaptor<LambdaQueryWrapper<IotDeviceTag>> captor = queryCaptor();
        verify(tagMapper, times(1)).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
            .as("WHERE 必须同时限定设备与标签键").contains("device_id").contains("tag_key");
        assertThat(captor.getValue().getParamNameValuePairs().values())
            .contains(1L, 2L, "region", "env");
        // 移除语义幂等：不需要先查设备/标签，一条 DELETE 完成
        verify(deviceMapper, never()).selectBatchIds(any());
        verify(tagMapper, never()).selectList(any());
        verify(tagMapper, never()).insertBatch(any());
    }

    @Test
    @DisplayName("批量移除-空/含空白键/超限/键超长：一律拒绝且不删")
    void batchRemoveEmptyOrInvalidShouldReject() {
        assertThatThrownBy(() -> service.batchRemoveTags(null, List.of("region")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchRemoveTags(List.of(1L), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchRemoveTags(List.of(1L), List.of()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.batchRemoveTags(List.of(1L), Arrays.asList("region", " ")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签键不能为空");
        assertThatThrownBy(() -> service.batchRemoveTags(List.of(1L), List.of("x".repeat(65))))
            .isInstanceOf(BusinessException.class).hasMessageContaining("标签键长度不能超过 64");
        assertThatThrownBy(() -> service.batchRemoveTags(manyDeviceIds(501), List.of("region")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过 500");

        verify(tagMapper, never()).delete(any());
    }

    private static List<Long> manyDeviceIds(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(Long::valueOf).toList();
    }

    private static IotDevice device(Long id, Long tenantId) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setTenantId(tenantId);
        return device;
    }

    private static IotDeviceTag tag(Long id, Long deviceId, String key, String value) {
        IotDeviceTag tag = new IotDeviceTag();
        tag.setId(id);
        tag.setDeviceId(deviceId);
        tag.setTagKey(key);
        tag.setTagValue(value);
        return tag;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<IotDeviceTag>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<IotDeviceTag>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaQueryWrapper<IotDeviceTag>> queryCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    private static IotDeviceTagReq req(String key, String value) {
        IotDeviceTagReq req = new IotDeviceTagReq();
        req.setTagKey(key);
        req.setTagValue(value);
        return req;
    }

    @Test
    @DisplayName("🔴 父子错配：经其它设备的路径改/删标签 ⇒ 拒绝（路径父 ID 不是装饰）")
    void parentMismatchMustBeRejected() {
        IotDeviceTag tag = new IotDeviceTag();
        tag.setId(50L);
        tag.setDeviceId(DEVICE_ID);
        when(tagMapper.selectById(50L)).thenReturn(tag);

        // 改：路径设备 99 ≠ 标签所属设备 1
        assertThatThrownBy(() -> service.update(99L, 50L, req("location", "B 区")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("标签不存在");
        // 删：同理
        assertThatThrownBy(() -> service.remove(99L, 50L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("标签不存在");
        verify(tagMapper, never()).updateById(any(IotDeviceTag.class));
        verify(tagMapper, never()).deleteById(any(Long.class));
    }

    @Test
    @DisplayName("父子一致：同设备路径改/删标签 ⇒ 放行")
    void parentMatchMustProceed() {
        IotDeviceTag tag = new IotDeviceTag();
        tag.setId(50L);
        tag.setDeviceId(DEVICE_ID);
        when(tagMapper.selectById(50L)).thenReturn(tag);

        service.update(DEVICE_ID, 50L, req("location", "B 区"));
        verify(tagMapper).updateById(any(IotDeviceTag.class));
        service.remove(DEVICE_ID, 50L);
        verify(tagMapper).deleteById(50L);
    }
}
