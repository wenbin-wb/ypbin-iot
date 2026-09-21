/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.model.tsl;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * TSL 文档根（对齐 IoTDA 双文件结构：产品级 + 服务级，合并为单一 JSON 便于导出/导入）。
 *
 * <p>导出方向：平台表 → TSL JSON（对外对齐 IoTDA）；导入方向：TSL JSON → 平台表，
 * 导入必须全字段校验（命名规范/类型枚举/引用完整性），失败逐项报错，禁静默丢弃（§3.7）。</p>
 *
 * @author wenbin
 * @since 2026-09-20
 */
@Getter
@Setter
public class TslDocument {

    /** 产品级定义（devicetype-capability.json 的 devices 数组元素）。 */
    @Valid
    private List<TslDevice> devices;

    /** 服务级定义（servicetype-capability.json 的 services 数组元素）。 */
    @Valid
    private List<TslService> services;

    /** 产品级定义（devicetype-capability.json 的 devices 数组元素）。 */
    @Getter
    @Setter
    public static class TslDevice {

        /** 厂商 ID。 */
        @Size(max = 128, message = "厂商 ID 长度不能超过 128")
        private String manufacturerId;

        /** 厂商名称。 */
        @Size(max = 128, message = "厂商名称长度不能超过 128")
        private String manufacturerName;

        /** 接入协议码（tcp | modbus | mqtt | opcua）。 */
        @NotBlank(message = "接入协议不能为空")
        private String protocolType;

        /** 设备类型描述（IoTDA deviceType）。 */
        @Size(max = 64, message = "设备类型长度不能超过 64")
        private String deviceType;

        /** 服务引用列表（与 services 数组按 serviceType 对应）。 */
        @Valid
        private List<TslServiceRef> serviceTypeCapabilities;
    }

    /** 产品级定义中的服务引用（devicetype-capability.json 的 serviceTypeCapabilities 数组元素）。 */
    @Getter
    @Setter
    public static class TslServiceRef {

        /** 服务标识（PascalCase，须与 services 数组中某个 serviceType 一致）。 */
        @NotBlank(message = "服务引用标识不能为空")
        @Size(max = 64, message = "服务引用标识长度不能超过 64")
        private String serviceId;

        /** 服务类型（与 serviceId 一致的冗余字段，IoTDA 结构要求）。 */
        @NotBlank(message = "服务类型不能为空")
        @Size(max = 64, message = "服务类型长度不能超过 64")
        private String serviceType;

        /** 服务选项：master | mandatory | optional。 */
        @NotBlank(message = "服务选项不能为空")
        private String option;
    }
}
