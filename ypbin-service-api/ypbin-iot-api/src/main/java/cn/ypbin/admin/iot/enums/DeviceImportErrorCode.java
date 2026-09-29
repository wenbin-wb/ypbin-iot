/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.enums;

/**
 * 设备批量导入**错误码字典**（有界集合，落库/失败行 CSV/日志都用 {@code code}）。
 *
 * <p><b>为什么必须枚举化而不是散落的字符串</b>：批量导入的失败行是**给用户看的**，
 * 「导入失败」四个字对用户毫无价值，而「第 7 行 deviceCode 重复」才是可执行的结论。
 * 每个码都绑定一句**面向用户的默认说明**（{@code message}），页面的失败行 CSV 直接落这列；
 * 调用方可以用 {@link #format(String)} 附上具体取值（哪一列、什么值），
 * 于是「错误码表」不只是内部约定，而是一条用户可自助闭环的路径。</p>
 *
 * <p>码的取值集合有界 ⇒ 可直接做指标标签与「按错误码统计失败行」的聚合；
 * 新增原因只会往字典里加一条，不改已有语义。</p>
 *
 * <p>失败行 CSV 的「错误信息」列存的就是本字典的 {@code message}（必要时拼上具体取值），
 * 用户把它改掉后**原样重传即可**——不需要读任何文档。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public enum DeviceImportErrorCode {

    /** 文件不是合法 UTF-8（含解码失败）。 */
    FILE_NOT_UTF8("file-not-utf8", "文件不是 UTF-8 编码（请另存为 UTF-8 后重新上传）"),

    /** 文件超过大小上限。 */
    FILE_TOO_LARGE("file-too-large", "文件超过大小上限"),

    /** 数据行数超过上限。 */
    TOO_MANY_ROWS("too-many-rows", "数据行数超过上限"),

    /** 文件为空（无表头或无数据行）。 */
    FILE_EMPTY("file-empty", "文件为空或没有数据行"),

    /** 表头缺失必填列。 */
    HEADER_MISSING_COLUMN("header-missing-column", "表头缺少必填列"),

    /** 表头存在无法识别的列。 */
    HEADER_UNKNOWN_COLUMN("header-unknown-column", "表头存在无法识别的列（请从模板重新导出）"),

    /** 表头重复出现同一列。 */
    HEADER_DUPLICATE_COLUMN("header-duplicate-column", "表头存在重复列"),

    /** 列数与表头不一致。 */
    COLUMN_COUNT_MISMATCH("column-count-mismatch", "这一行的列数与表头不一致"),

    /** 必填列取值为空。 */
    REQUIRED_VALUE_MISSING("required-value-missing", "必填列的值为空"),

    /** 取值超过该列长度上限。 */
    VALUE_TOO_LONG("value-too-long", "取值超过该列的长度上限"),

    /** 设备编码格式非法。 */
    DEVICE_CODE_INVALID("device-code-invalid", "设备编码格式非法"),

    /** 设备编码在**本租户内已存在**（含本次导入前面已成功的行）。 */
    DEVICE_CODE_DUPLICATED("device-code-duplicated", "设备编码在本租户内已存在"),

    /** 设备编码在**本次导入内重复**（同文件里出现了两次）。 */
    DEVICE_CODE_DUPLICATED_IN_FILE("device-code-duplicated-in-file", "设备编码在本文件内重复出现"),

    /** 协议码格式非法。 */
    PROTOCOL_INVALID("protocol-invalid", "接入协议码格式非法（小写字母开头，仅含小写字母、数字与连字符）"),

    /** 端点为空或格式非法（必须带 scheme）。 */
    ENDPOINT_INVALID("endpoint-invalid", "端点必须带协议头（如 tcp://host:port）"),

    /** 产品不存在。 */
    PRODUCT_NOT_FOUND("product-not-found", "产品不存在（productId 无效或不属于本租户）"),

    /** 产品未发布物模型（设备只能绑已发布版本）。 */
    PRODUCT_NOT_PUBLISHED("product-not-published", "产品物模型尚未发布，无法绑定设备"),

    /** 分组不存在。 */
    GROUP_NOT_FOUND("group-not-found", "设备分组不存在（groupIds 含无效 ID）"),

    /** 启停位取值非法（只接受 0/1，空表示默认启用）。 */
    STATUS_INVALID("status-invalid", "启停位只能填 0（停用）或 1（启用），留空表示启用"),

    /** 该行 CSV 语法非法（引号未闭合等）。 */
    LINE_SYNTAX_INVALID("line-syntax-invalid", "这一行的 CSV 语法非法（引号未闭合等）"),

    /** 写入数据库失败（保留错误原文，不吞）。 */
    PERSIST_FAILED("persist-failed", "写入数据库失败"),

    /** 兜底：未归类的内部错误（出现即应补进本字典）。 */
    INTERNAL_ERROR("internal-error", "内部错误");

    private final String code;

    private final String message;

    DeviceImportErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 错误码（数据库/失败行 CSV/接口用；稳定标识，改文案不改它）。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 面向用户的默认说明。
     *
     * @return 说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * 默认说明 + 具体取值（如「必填列的值为空：deviceName」）。
     *
     * <p>刻意把具体取值拼在后面而不是替换掉默认说明：用户在失败行 CSV 里
     * 既要看懂**错在哪一类**（可分类统计），也要知道**这一行具体哪里错**（可直接改）。</p>
     *
     * @param detail 具体取值说明；为空时只返回默认说明
     * @return 面向用户的错误信息
     */
    public String format(String detail) {
        if (detail == null || detail.isBlank()) {
            return message;
        }
        return message + "：" + detail;
    }
}
