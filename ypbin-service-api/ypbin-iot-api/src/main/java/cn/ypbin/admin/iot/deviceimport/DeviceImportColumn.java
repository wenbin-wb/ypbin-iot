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
package cn.ypbin.admin.iot.deviceimport;

/**
 * CSV 模板的**列契约**（表头名 ↔ 设备字段 ↔ 是否必填）。
 *
 * <p><b>列是从既有创建设备接口反推出来的，不是自创的</b>：后端
 * {@code IotDeviceReq} 的必填项是 {@code deviceCode / deviceName / protocol / endpoint}
 * （带 {@code @NotBlank}），选填是 {@code productId / productVersion / status / remark}。
 * 本枚举与之一一对应——模板多一列会诱导用户填没用的东西，少一列则用户必须再去接口里补一次，
 * 两种都违背「不读文档就能用」的目标。</p>
 *
 * <p><b>{@code groupIds} 是唯一的新增列</b>：设备分组在多设备场景下是刚需（一趟导入几百台
 * 却要逐台去分组页手工加成员），而它在创建设备的**单条**接口里没有（成员是独立端点
 * {@code POST /groups/{id}/devices}）。这里把它做成选填列（分号或竖线分隔的多个分组 ID），
 * 让「建完顺手归组」一步到位；分组 ID 非法**只让该行失败**，不影响其它行。</p>
 *
 * <p>表头必须与模板**逐字一致**（含顺序）：{@link DeviceImportCsvParser} 按名字定位列，
 * 允许缺选填列、不允许缺必填列、不允许出现无法识别的列——用户从模板重新导出一份就能修好。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum DeviceImportColumn {

    /** 设备编码（必填，租户内唯一）。 */
    DEVICE_CODE("deviceCode", true, "设备编码，租户内唯一（重复会报错，不会被覆盖）"),

    /** 设备名称（必填）。 */
    DEVICE_NAME("deviceName", true, "设备名称，最长 100 字"),

    /** 接入协议码（必填）。 */
    PROTOCOL("protocol", true, "接入协议：modbus / tcp / mqtt / opcua（小写，可用连字符）"),

    /** 端点 URI（必填，必须带 scheme）。 */
    ENDPOINT("endpoint", true, "端点，必须带协议头，如 tcp://10.0.0.9:502"),

    /** 绑定产品 ID（选填）。 */
    PRODUCT_ID("productId", false, "产品 ID（选填；填了必须是本租户**已发布**物模型的产品）"),

    /** 绑定物模型版本（选填）。 */
    PRODUCT_VERSION("productVersion", false, "物模型版本，如 v1.0（选填）"),

    /** 设备分组 ID 列表（选填，多个用竖线分隔）。 */
    GROUP_IDS("groupIds", false, "设备分组 ID，多个用竖线 | 分隔（选填，如 12|15）"),

    /** 启停位（选填，空=启用）。 */
    STATUS("status", false, "启停位：1 启用 / 0 停用，留空表示启用（选填）"),

    /** 备注（选填）。 */
    REMARK("remark", false, "备注，最长 500 字（选填）");

    /** 分组 ID 的分隔符（一个列里要塞多个 ID 时的约定）。 */
    public static final String GROUP_ID_SEPARATOR = "|";

    private final String header;

    private final boolean required;

    private final String description;

    DeviceImportColumn(String header, boolean required, String description) {
        this.header = header;
        this.required = required;
        this.description = description;
    }

    /**
     * CSV 表头名（与模板逐字一致）。
     *
     * @return 表头名
     */
    public String getHeader() {
        return header;
    }

    /**
     * 是否必填列。
     *
     * @return 必填返回 {@code true}
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * 模板说明行里的列说明（模板第 2 行，以 {@code #} 开头 ⇒ 上传时被当作说明行跳过）。
     *
     * @return 说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 按表头名解析列（大小写不敏感、去除首尾空白，容忍 Excel 另存带来的空白）。
     *
     * @param header 表头文本
     * @return 匹配的列；无匹配返回 {@code null}
     */
    public static DeviceImportColumn parse(String header) {
        if (header == null) {
            return null;
        }
        String normalized = header.trim();
        for (DeviceImportColumn column : values()) {
            if (column.header.equalsIgnoreCase(normalized)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 模板表头行（逗号分隔，顺序即 {@link #values()} 顺序）。
     *
     * @return 表头行
     */
    public static String headerLine() {
        StringBuilder builder = new StringBuilder();
        for (DeviceImportColumn column : values()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(column.header);
        }
        return builder.toString();
    }

    /**
     * 模板说明行（以 {@code #} 开头，上传时被跳过；让用户**不读文档**就知道每列填什么）。
     *
     * @return 说明行
     */
    public static String noteLine() {
        StringBuilder builder = new StringBuilder(DeviceImportLimits.TEMPLATE_NOTE_PREFIX);
        for (DeviceImportColumn column : values()) {
            if (builder.length() > 1) {
                builder.append(" | ");
            }
            builder.append(column.header)
                .append(column.required ? "(必填)" : "(选填)")
                .append("：")
                .append(column.description);
        }
        return builder.toString();
    }

    /**
     * 模板示例行（让用户照着抄一行就能跑通，是最有效的「无需文档」手段）。
     *
     * @return 示例数据行
     */
    public static String exampleLine() {
        return String.join(",",
            "GW-DEMO-001",
            "示例网关-1",
            "tcp",
            "tcp://10.0.0.9:502",
            "",
            "",
            "",
            "",
            "这一行是示例，可删可改");
    }
}
