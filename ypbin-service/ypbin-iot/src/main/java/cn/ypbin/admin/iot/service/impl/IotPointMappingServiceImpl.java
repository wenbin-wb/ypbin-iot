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

import cn.ypbin.admin.iot.entity.IotCommand;
import cn.ypbin.admin.iot.entity.IotDevice;
import cn.ypbin.admin.iot.entity.IotPointMapping;
import cn.ypbin.admin.iot.enums.AccessMode;
import cn.ypbin.admin.iot.enums.IotProtocol;
import cn.ypbin.admin.iot.enums.ModelStatus;
import cn.ypbin.admin.iot.enums.PointRefType;
import cn.ypbin.admin.iot.enums.ThingModelDataType;
import cn.ypbin.admin.iot.entity.IotProduct;
import cn.ypbin.admin.iot.entity.IotProperty;
import cn.ypbin.admin.iot.entity.IotService;
import cn.ypbin.admin.iot.lease.TenantLedgerService;
import cn.ypbin.admin.iot.mapper.IotCommandMapper;
import cn.ypbin.admin.iot.mapper.IotDeviceMapper;
import cn.ypbin.admin.iot.mapper.IotPointMappingMapper;
import cn.ypbin.admin.iot.mapper.IotProductMapper;
import cn.ypbin.admin.iot.mapper.IotPropertyMapper;
import cn.ypbin.admin.iot.mapper.IotServiceMapper;
import cn.ypbin.admin.iot.modbus.ModbusRegisterAddress;
import cn.ypbin.admin.iot.model.req.IotPointMappingReq;
import cn.ypbin.admin.iot.model.resp.IotPointMappingResp;
import cn.ypbin.admin.iot.service.IotPointMappingService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.core.exception.GlobalErrorCode;
import cn.ypbin.starter.crud.service.BaseServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT 点位映射服务实现（§3.9）。
 *
 * <p>约束：映射必须引用<b>已发布版本</b>的属性（设备绑定产品+版本，属性经产品/服务可达）；
 * 读写权限与属性 {@code access_mode} 联动校验；协议能力不支持时由 iot-starter 显式失败（I6）。</p>
 *
 * <p><b>采集配置变更要发信号</b>：点位映射是「采什么点位」的输入，增删改都必须推进台账的
 * {@code config_epoch}（与业务写入同一事务）——否则接入侧永远不知道点位变了（M-2 / G7）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Service
public class IotPointMappingServiceImpl extends BaseServiceImpl<IotPointMappingMapper, IotPointMapping>
    implements IotPointMappingService {

    /** 默认连续寄存器个数（1 = 单寄存器，v1 行为）。 */
    private static final int DEFAULT_WORD_COUNT = 1;

    /** 默认寄存器值形态（int = v1 行为）。 */
    private static final String DEFAULT_REGISTER_FORMAT = "int";

    private final IotDeviceMapper iotDeviceMapper;
    private final IotPropertyMapper iotPropertyMapper;
    private final IotProductMapper iotProductMapper;
    private final IotServiceMapper iotServiceMapper;
    private final IotCommandMapper iotCommandMapper;
    private final TenantLedgerService tenantLedgerService;

    public IotPointMappingServiceImpl(IotDeviceMapper iotDeviceMapper,
                                      IotPropertyMapper iotPropertyMapper,
                                      IotProductMapper iotProductMapper,
                                      IotServiceMapper iotServiceMapper,
                                      IotCommandMapper iotCommandMapper,
                                      TenantLedgerService tenantLedgerService) {
        this.iotDeviceMapper = iotDeviceMapper;
        this.iotPropertyMapper = iotPropertyMapper;
        this.iotProductMapper = iotProductMapper;
        this.iotServiceMapper = iotServiceMapper;
        this.iotCommandMapper = iotCommandMapper;
        this.tenantLedgerService = tenantLedgerService;
    }

    @Override
    public List<IotPointMappingResp> listByDevice(Long deviceId) {
        requireDevice(deviceId);
        LambdaQueryWrapper<IotPointMapping> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(IotPointMapping::getDeviceId, deviceId)
            .orderByAsc(IotPointMapping::getId);
        return baseMapper.selectList(wrapper).stream().map(this::toResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(IotPointMappingReq req) {
        IotDevice device = requireDevice(req.getDeviceId());
        validateReference(device, req);
        IotPointMapping mapping = new IotPointMapping();
        apply(mapping, req);
        save(mapping);
        notifyConfigChanged();
        return mapping.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, IotPointMappingReq req) {
        IotPointMapping mapping = requireMapping(id);
        // 映射归属由路径设备决定：请求体的 deviceId 不得把映射迁到别的设备
        // （否则可用一个已发布产品的设备路径去改挂到未发布产品的设备上，绕过 §3.9）
        if (!mapping.getDeviceId().equals(req.getDeviceId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射不属于该设备，禁止跨设备迁移：" + id);
        }
        IotDevice device = requireDevice(req.getDeviceId());
        validateReference(device, req);
        apply(mapping, req);
        updateById(mapping);
        notifyConfigChanged();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long deviceId, Long id) {
        IotPointMapping mapping = requireMapping(id);
        if (!mapping.getDeviceId().equals(deviceId)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射不属于该设备：" + id);
        }
        removeById(id);
        notifyConfigChanged();
    }

    /**
     * 采集配置变更后推进台账版本号（同一事务；台账无该租户时为已登记的已知限制，不阻断业务写入）。
     */
    private void notifyConfigChanged() {
        tenantLedgerService.bumpConfigEpochOfCurrentTenant();
    }

    /**
     * 校验映射引用：按 {@code refType} 分流（§3.9）。
     *
     * <p>{@code property} 走属性校验（已发布版本的属性 + 读写联动 + 寄存器解码）；
     * {@code command} 走命令校验（命令存在且属于设备所绑定产品的物模型）。命令点位是**下行写点**，
     * 不承载读侧解码字段（见 {@link #rejectReadDecodeFields}）。</p>
     *
     * @param device 设备
     * @param req    请求
     */
    private void validateReference(IotDevice device, IotPointMappingReq req) {
        PointRefType refType = PointRefType.of(req.getRefType());
        if (refType == null) {
            // DTO 已用 @Pattern 兜住 HTTP 入参；服务层直调（导入/脚本/其它服务）也必须拒绝
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射关联类型非法（应为 property|command）：" + req.getRefType());
        }
        if (refType == PointRefType.COMMAND) {
            validateCommandReference(device, req);
            return;
        }
        validatePropertyReference(device, req);
    }

    /**
     * 校验属性引用：属性存在且所属产品为已发布（§3.9 约束），并联动校验读写权限。
     *
     * @param device 设备
     * @param req    请求
     */
    private void validatePropertyReference(IotDevice device, IotPointMappingReq req) {
        IotProperty property = iotPropertyMapper.selectById(req.getPropertyId());
        if (property == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "属性不存在：" + req.getPropertyId());
        }
        IotProduct product = iotProductMapper.selectById(device.getProductId());
        if (product == null || !ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射必须引用已发布版本的属性：请先给设备绑定产品并发布物模型");
        }
        // 属性必须经「服务的产品」可达：否则可引用其它产品（甚至其它租户产品）的属性
        IotService service = iotServiceMapper.selectById(property.getServiceId());
        if (service == null || !product.getId().equals(service.getProductId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "属性不属于设备所绑定产品的物模型：" + req.getPropertyId());
        }
        // access_mode 可能为 null（历史/导入数据）：用 Objects.equals 避免 NPE（null != READ_WRITE ⇒ 拒绝）
        if (!AccessMode.READ_WRITE.getCode().equals(property.getAccessMode())
            && !Objects.equals(property.getAccessMode(), req.getRw())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "映射读写权限与属性 access_mode 不一致：属性=" + property.getAccessMode()
                    + " 映射=" + req.getRw());
        }
        validateRegisterDecode(device, property, req);
    }

    /**
     * 校验命令引用（{@code ref_type=command}）：命令存在、产品已发布、命令经「服务的产品」可达（§3.9）。
     *
     * <p>命令点位是**下行写点**（M-3）：地址与方向由映射承载，读侧解码字段不适用，
     * 非默认值一律显式拒绝——不允许静默忽略（否则运维以为配了缩放，实际未生效）。</p>
     *
     * @param device 设备
     * @param req    请求
     */
    private void validateCommandReference(IotDevice device, IotPointMappingReq req) {
        IotCommand command = iotCommandMapper.selectById(req.getPropertyId());
        if (command == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令不存在：" + req.getPropertyId());
        }
        IotProduct product = iotProductMapper.selectById(device.getProductId());
        if (product == null || !ModelStatus.PUBLISHED.getCode().equals(product.getModelStatus())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "点位映射必须引用已发布版本的命令：请先给设备绑定产品并发布物模型");
        }
        // 命令必须经「服务的产品」可达：否则可引用其它产品（甚至其它租户产品）的命令
        IotService service = iotServiceMapper.selectById(command.getServiceId());
        if (service == null || !product.getId().equals(service.getProductId())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令不属于设备所绑定产品的物模型：" + req.getPropertyId());
        }
        rejectReadDecodeFields(req);
    }

    /**
     * 命令点位（下行写点）拒绝读侧解码字段。
     *
     * <p>缩放/字序/多寄存器/有符号/位域/寄存器形态都是**读数解码**语义，对命令写入无意义；
     * 开发阶段按「非默认即拒绝」处理，不做静默忽略。</p>
     *
     * @param req 请求
     */
    private static void rejectReadDecodeFields(IotPointMappingReq req) {
        if (req.getScaleFactor() != null || req.getOffsetValue() != null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位是下行写点，不支持缩放（scale_factor/offset_value）：请清空这两个字段");
        }
        if (req.getByteOrder() != null && !req.getByteOrder().isBlank()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位不支持字序（byte_order）：请清空该字段");
        }
        if (req.getWordCount() != null && req.getWordCount() != DEFAULT_WORD_COUNT) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位不支持多寄存器（word_count 只能为 1）：请清空该字段");
        }
        if (Boolean.TRUE.equals(req.getSigned())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位不支持有符号解码（signed 只能为 false）：请清空该字段");
        }
        if (req.getRegisterFormat() != null && !req.getRegisterFormat().isBlank()
            && !DEFAULT_REGISTER_FORMAT.equals(req.getRegisterFormat().trim())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位不支持寄存器值形态（register_format 只能为 int）：请清空该字段");
        }
        if (req.getBitOffset() != null || req.getBitLength() != null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "命令点位不支持位域（bit_offset/bit_length）：请清空这两个字段");
        }
    }

    /**
     * 寄存器解码 v1 的映射校验（{@code word_count}/{@code signed}）。
     *
     * <p><b>统一严格</b>（2026-10-13 收紧，见 {@code docs/VALUE-DECODE-DESIGN.md} §4.3.2）：
     * {@code word_count}/{@code signed} 非适用场景（非 Modbus、位类型、非整型 {@code dataType}、
     * 多寄存器非显式地址）一律 fail-fast；{@code byte_order} 也统一严格——只在「Modbus int+word_count=2」
     * 「float」或「double」真正生效，其余场景非空即拒绝（此前「历史宽容」的理由已不成立）。</p>
     *
     * @param device   设备（提供协议码）
     * @param property 属性（提供 dataType）
     * @param req      映射请求
     */
    private void validateRegisterDecode(IotDevice device, IotProperty property, IotPointMappingReq req) {
        String format = req.getRegisterFormat() == null || req.getRegisterFormat().isBlank()
            ? "int" : req.getRegisterFormat().trim();
        if (!"int".equals(format) && !"float".equals(format) && !"double".equals(format)
            && !"bits".equals(format) && !"string".equals(format)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "寄存器值形态（register_format）只能是 int|float|double|bits|string：" + format);
        }
        int wordCount = req.getWordCount() == null ? 1 : req.getWordCount();
        boolean signed = Boolean.TRUE.equals(req.getSigned());
        Integer bitOffset = req.getBitOffset();
        Integer bitLength = req.getBitLength();
        if ("string".equals(format)) {
            // v3：string 放宽到 1..32（≤64 字节）；int/float/bits 的 1|2 范围不变
            if (wordCount < 1 || wordCount > 32) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "register_format=string 的 word_count 必须在 1..32（≤64 字节）：" + wordCount);
            }
        } else if ("double".equals(format)) {
            // v4：double 必须 4（64 位占 4 个寄存器），声明与宽度一一对应（不靠 float+4 推断）
            if (wordCount != 4) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "register_format=double 要求 word_count=4（64 位占 4 个寄存器），当前=" + wordCount
                        + "；请把 word_count 改为 4");
            }
        } else if (wordCount != 1 && wordCount != 2) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "寄存器个数（word_count）只能是 1 或 2：" + wordCount);
        }
        boolean modbus = IotProtocol.MODBUS.getCode().equals(device.getProtocol());
        boolean registerType = ModbusRegisterAddress.REGISTER_TYPES.contains(req.getAddressType());
        ThingModelDataType dataType = ThingModelDataType.of(property.getDataType());
        if ("int".equals(format)) {
            // 新字段严格：int 形态不得携带 bit_offset/bit_length（仅 bits 使用）
            if (bitOffset != null || bitLength != null) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "register_format=int 不支持 bit_offset/bit_length（仅 bits 形态使用）：请清空这两个字段");
            }
            validateIntFormat(device, property, req, wordCount, signed, dataType, modbus, registerType);
            return;
        }
        // float / bits / string 是新形态：先统一适用性（新字段严格：非 Modbus / 位类型一律拒绝）
        if (!modbus) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "仅 Modbus 寄存器点位支持 register_format=" + format + "（当前协议=" + device.getProtocol() + "）");
        }
        if (!registerType) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "位类型（" + req.getAddressType() + "）不支持 register_format=" + format
                    + "（寄存器解码仅对 holding/input 生效）");
        }
        if ("float".equals(format)) {
            validateFloatFormat(req, wordCount, signed, bitOffset, bitLength, dataType, property);
        } else if ("double".equals(format)) {
            validateDoubleFormat(req, wordCount, signed, bitOffset, bitLength, dataType, property);
        } else if ("string".equals(format)) {
            validateStringFormat(req, wordCount, signed, bitOffset, bitLength, dataType, property);
        } else {
            validateBitsFormat(req, wordCount, signed, bitOffset, bitLength, dataType, property);
        }
    }

    /**
     * {@code string} 形态校验（v3）：多寄存器拼严格 UTF-8（固定，不新增 encoding 列）。
     *
     * <p>新字段严格：{@code signed=false}、{@code bit_*}=null、{@code data_type=string}；
     * {@code word_count>1} 时地址必须是显式 {@code type:offset}（与 v1 多寄存器同规则）；
     * {@code word_count=1} 时传统 5 位编号照常（无需扩地址）。</p>
     */
    private void validateStringFormat(IotPointMappingReq req, int wordCount, boolean signed,
                                      Integer bitOffset, Integer bitLength, ThingModelDataType dataType,
                                      IotProperty property) {
        rejectByteOrderIfPresent(req);
        if (signed) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=string 不支持有符号（signed 必须为 false）：请清空或置 false");
        }
        if (bitOffset != null || bitLength != null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=string 不支持 bit_offset/bit_length：请清空这两个字段");
        }
        if (dataType != ThingModelDataType.STRING) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=string 要求属性类型是 string，当前=" + property.getDataType());
        }
        if (wordCount > 1) {
            validateExplicitMultiRegisterAddress(req);
        }
    }

    /**
     * {@code int} 形态校验 = v1 规则（**行为逐字节不变**）。
     *
     * <p>只对 {@code word_count=2} 或 {@code signed=true} 触发 data_type 约束；{@code word_count=1/signed=false}
     * 直接返回（不引入新的拒绝路径，传统 5 位编号照常）。</p>
     */
    private void validateIntFormat(IotDevice device, IotProperty property, IotPointMappingReq req,
                                   int wordCount, boolean signed, ThingModelDataType dataType,
                                   boolean modbus, boolean registerType) {
        if (wordCount != 2) {
            // byteOrder 仅在 int+word_count=2（或 float）真正生效；其余场景非空即拒绝
            rejectByteOrderIfPresent(req);
        }
        if (wordCount == 1 && !signed) {
            // 默认 = 旧行为：不触发任何新字段校验（传统 5 位编号照常可用）
            return;
        }
        if (!modbus) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "仅 Modbus 寄存器点位支持 word_count/signed（当前协议=" + device.getProtocol() + "）");
        }
        if (!registerType) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "位类型（" + req.getAddressType() + "）不支持 word_count/signed（寄存器解码仅对 holding/input 生效）");
        }
        if (dataType != ThingModelDataType.INT && dataType != ThingModelDataType.LONG) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "寄存器解码 int 形态仅支持 int/long 数据类型（当前=" + property.getDataType() + "）");
        }
        if (wordCount == 2) {
            validateExplicitMultiRegisterAddress(req);
        }
    }

    /** {@code float} 形态校验：IEEE754 单精度（32 位 ⟺ 2 寄存器）。 */
    private void validateFloatFormat(IotPointMappingReq req, int wordCount, boolean signed,
                                     Integer bitOffset, Integer bitLength, ThingModelDataType dataType,
                                     IotProperty property) {
        if (wordCount != 2) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=float 要求 word_count=2（32 位占 2 个寄存器），当前=" + wordCount
                    + "；请把 word_count 改为 2");
        }
        if (signed) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=float 的 signed 必须为 false（浮点无符号语义）：请清空或置 false");
        }
        if (bitOffset != null || bitLength != null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=float 不支持 bit_offset/bit_length：请清空这两个字段");
        }
        if (dataType != ThingModelDataType.DECIMAL) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=float 要求属性类型是 decimal（小数），当前=" + property.getDataType());
        }
        validateExplicitMultiRegisterAddress(req);
    }

    /** {@code double} 形态校验：IEEE754 双精度（64 位 ⟺ 4 寄存器；字序生效 big=ABCD/little=DCBA）。 */
    private void validateDoubleFormat(IotPointMappingReq req, int wordCount, boolean signed,
                                      Integer bitOffset, Integer bitLength, ThingModelDataType dataType,
                                      IotProperty property) {
        if (wordCount != 4) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=double 要求 word_count=4（64 位占 4 个寄存器），当前=" + wordCount
                    + "；请把 word_count 改为 4");
        }
        if (signed) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=double 的 signed 必须为 false（浮点无符号语义）：请清空或置 false");
        }
        if (bitOffset != null || bitLength != null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=double 不支持 bit_offset/bit_length：请清空这两个字段");
        }
        if (dataType != ThingModelDataType.DECIMAL) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=double 要求属性类型是 decimal（小数），当前=" + property.getDataType());
        }
        validateExplicitMultiRegisterAddress(req);
    }

    /** {@code bits} 形态校验：单寄存器无符号位域。 */
    private void validateBitsFormat(IotPointMappingReq req, int wordCount, boolean signed,
                                    Integer bitOffset, Integer bitLength, ThingModelDataType dataType,
                                    IotProperty property) {
        rejectByteOrderIfPresent(req);
        if (wordCount != 1) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=bits 要求 word_count=1（单寄存器位域），当前=" + wordCount
                    + "；请把 word_count 改为 1");
        }
        if (signed) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=bits 仅支持无符号位域（signed 必须为 false）：请清空或置 false");
        }
        if (bitOffset == null || bitLength == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=bits 必须同时给出 bit_offset 与 bit_length");
        }
        if (bitOffset < 0 || bitOffset > 15) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "bit_offset 必须在 0..15：" + bitOffset);
        }
        if (bitLength < 1 || bitOffset + bitLength > 16) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "bit_length 必须在 1.." + (16 - bitOffset) + "（bit_offset+bit_length ≤ 16）："
                    + "bit_offset=" + bitOffset + " bit_length=" + bitLength);
        }
        if (bitLength == 1) {
            if (dataType != ThingModelDataType.BOOL) {
                throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                    "register_format=bits 且 bit_length=1 要求属性类型是 bool，当前=" + property.getDataType());
            }
        } else if (dataType != ThingModelDataType.INT && dataType != ThingModelDataType.LONG) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "register_format=bits 且 bit_length>1 要求属性类型是 int/long，当前=" + property.getDataType());
        }
    }

    /**
     * {@code byte_order} 统一严格校验（2026-10-13 收紧，见 docs/VALUE-DECODE-DESIGN.md §4.3.2）：
     * 只在「Modbus {@code int}+{@code word_count=2}」或「{@code float}」真正生效；其余场景非空即拒绝。
     *
     * <p>此前「历史宽容」的理由（可能有脏数据）已不成立（开发阶段、无真实对接）。</p>
     *
     * @param req 映射请求
     * @throws BusinessException 非生效场景却携带非空 byteOrder
     */
    private static void rejectByteOrderIfPresent(IotPointMappingReq req) {
        String byteOrder = req.getByteOrder();
        if (byteOrder != null && !byteOrder.isBlank()) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "字序（byte_order）仅在 Modbus int+word_count=2、float 或 double 形态生效：请清空该字段"
                    + "（当前 register_format=" + req.getRegisterFormat() + " word_count="
                    + req.getWordCount() + "）");
        }
    }

    /** {@code word_count>1} 的地址规则（v1）：显式 {@code type:offset} 且与 address_type 一致、偏移不越界。 */
    private void validateExplicitMultiRegisterAddress(IotPointMappingReq req) {
        if (!ModbusRegisterAddress.isExplicitForm(req.getRawAddress())) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "word_count>1 需要显式 type:offset 形式（如 holding:0）；传统 5 位编号不支持多寄存器");
        }
        String typeCode = ModbusRegisterAddress.typeCode(req.getRawAddress());
        if (!req.getAddressType().equalsIgnoreCase(typeCode)) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "word_count>1 的 raw_address 寄存器区必须与 address_type 一致：address_type="
                    + req.getAddressType() + " raw_address=" + req.getRawAddress());
        }
        if (ModbusRegisterAddress.offset(req.getRawAddress()) + 1 > ModbusRegisterAddress.MAX_OFFSET) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR,
                "word_count=2 的寄存器基址越界（offset+1 > " + ModbusRegisterAddress.MAX_OFFSET
                    + "）：" + req.getRawAddress());
        }
    }

    private void apply(IotPointMapping mapping, IotPointMappingReq req) {
        mapping.setDeviceId(req.getDeviceId());
        mapping.setPropertyId(req.getPropertyId());
        mapping.setRefType(req.getRefType());
        mapping.setRawAddress(req.getRawAddress());
        mapping.setAddressType(req.getAddressType());
        mapping.setPollIntervalMs(req.getPollIntervalMs() == null ? 0 : req.getPollIntervalMs());
        mapping.setScaleFactor(req.getScaleFactor());
        mapping.setOffsetValue(req.getOffsetValue());
        mapping.setByteOrder(req.getByteOrder());
        mapping.setWordCount(req.getWordCount() == null ? DEFAULT_WORD_COUNT : req.getWordCount());
        mapping.setSigned(Boolean.TRUE.equals(req.getSigned()));
        mapping.setRegisterFormat(req.getRegisterFormat() == null || req.getRegisterFormat().isBlank()
            ? DEFAULT_REGISTER_FORMAT : req.getRegisterFormat().trim());
        mapping.setBitOffset(req.getBitOffset());
        mapping.setBitLength(req.getBitLength());
        mapping.setRw(req.getRw());
        mapping.setEnabled(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
    }

    private IotDevice requireDevice(Long deviceId) {
        IotDevice device = iotDeviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "设备不存在：" + deviceId);
        }
        return device;
    }

    private IotPointMapping requireMapping(Long id) {
        IotPointMapping mapping = getById(id);
        if (mapping == null) {
            throw new BusinessException(GlobalErrorCode.BUSINESS_ERROR, "点位映射不存在：" + id);
        }
        return mapping;
    }

    /**
     * 实体转响应（包内可见以便单测）。
     *
     * @param entity 实体
     * @return 响应
     */
    IotPointMappingResp toResp(IotPointMapping entity) {
        IotPointMappingResp resp = new IotPointMappingResp();
        resp.setId(entity.getId());
        resp.setDeviceId(entity.getDeviceId());
        resp.setPropertyId(entity.getPropertyId());
        resp.setRefType(entity.getRefType());
        resp.setRawAddress(entity.getRawAddress());
        resp.setAddressType(entity.getAddressType());
        resp.setPollIntervalMs(entity.getPollIntervalMs());
        resp.setScaleFactor(entity.getScaleFactor());
        resp.setOffsetValue(entity.getOffsetValue());
        resp.setByteOrder(entity.getByteOrder());
        resp.setWordCount(entity.getWordCount());
        resp.setSigned(entity.getSigned());
        resp.setRegisterFormat(entity.getRegisterFormat());
        resp.setBitOffset(entity.getBitOffset());
        resp.setBitLength(entity.getBitLength());
        resp.setRw(entity.getRw());
        resp.setEnabled(entity.getEnabled());
        resp.setCreateTime(entity.getCreateTime());
        return resp;
    }
}
