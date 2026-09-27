/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.mapper;

import cn.ypbin.admin.iot.entity.IotDeviceCredential;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 设备凭据（秘密侧）Mapper。
 *
 * <p>只用 MyBatis-Plus 的通用方法 + 条件构造器，**不写任何注解/XML SQL**：本表没有需要手写 SQL
 * 的场景，少一处 SQL 就少一处「注解 XML 少个 {@code </script>} 让整个服务起不来」的风险
 * （见 {@code IotMapperAnnotationXmlGateTest} 记录的 2026-09-25 生产事故）。</p>
 *
 * @author wenbin
 * @since 2026-09-27
 */
public interface IotDeviceCredentialMapper extends BaseMapper<IotDeviceCredential> {
}
