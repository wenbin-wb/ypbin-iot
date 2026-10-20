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
 * 数字孪生场景类型。
 *
 * <p>数据库与接口存/传 {@code code}（park | building | floor | room）。类型只表达
 * 「这一层在场景树里是什么粒度」，不约束模型内容：{@code park} 也可以是几个方块，
 * {@code room} 也可以是一整栋楼的精模。</p>
 *
 * @author wenbin
 * @since 2026-10-21
 */
public enum TwinSceneType {

    /** 园区（场景树根部的典型类型） */
    PARK("park", "园区"),

    /** 楼栋 */
    BUILDING("building", "楼栋"),

    /** 楼层 */
    FLOOR("floor", "楼层"),

    /** 房间 */
    ROOM("room", "房间");

    private final String code;
    private final String desc;

    TwinSceneType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查枚举，未匹配返回 {@code null}。
     *
     * @param code 场景类型码
     * @return 枚举；未匹配时 {@code null}
     */
    public static TwinSceneType of(String code) {
        for (TwinSceneType item : values()) {
            if (item.code.equals(code)) {
                return item;
            }
        }
        return null;
    }
}
