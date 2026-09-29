/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.deviceimport;

/**
 * CSVT 批量导入的**具名上限常量**（全部可配，默认对齐阿里云 IoT「批量创建设备」的量级）。
 *
 * <p>为什么单独一个类：这些数字同时出现在「解析层拒绝」「页面提示」「失败行 CSV 的生成条数」
 * 三处；散落成字面量时改一处忘一处，用户就会看到「提示最多 1 万行、实际 8000 行就被拒」
 * 这种自相矛盾的行为（禁止魔法值）。</p>
 *
 * <p>对标依据（一手，2026-09-30 核对）：阿里云 IoT「批量创建设备」一次**最多 1 万个设备**、
 * CSV 文件**不超过 2 MB**；本类默认值与它一致，便于用户从其它平台迁移时不必换模板。</p>
 *
 * <p><b>公开接口不是配置类</b>：目前按常量提供，接 Spring 配置绑定的开关留在服务层
 * （避免为一个还没出现的调参需求先引入 {@code @ConfigurationProperties}）；常量集中在此，
 * 将来改为绑定时只需改这一个类。</p>
 *
 * @author wenbin
 * @since 2026-09-30
 */
public final class DeviceImportLimits {

    /** 单次导入的数据行上限（不含表头与说明行）。 */
    public static final int MAX_ROWS = 10_000;

    /** 上传文件字节上限（UTF-8 编码后）。 */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * 单行字符数上限。
     *
     * <p>必须显式卡住：{@code rawLine} 要落库，而 CSV 一个「字段」可以有任意长度；
     * 不卡的话一行 10 万字符的备注会变成一次写库失败（整批回滚）——
     * 那时用户看到的是「导入失败」，而不是「第 3 行的备注太长」。</p>
     */
    public static final int MAX_LINE_CHARS = 4_000;

    /** 单列取值长度上限（与设备表的列宽对齐：deviceName 100 / endpoint 300 / remark 500）。 */
    public static final int MAX_FIELD_CHARS = 500;

    /** 表头行之后、第一行数据之前允许的说明行前缀（模板里的「说明行」以此开头）。 */
    public static final String TEMPLATE_NOTE_PREFIX = "#";

    private DeviceImportLimits() {
    }
}
