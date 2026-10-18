/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.common.log;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.log.model.LogRecord;
import cn.ypbin.starter.log.spi.RemoteLogIngestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志跨服务上报的 <b>SPI 实现</b>：把采集完成的 {@link LogRecord} 上报给 system 服务落 {@code sys_log}。
 *
 * <p><b>自建实现已删除，改由 starter 4.1.0 装配（starter #100）</b>：本仓原有一套
 * 自建的「日志远程上报自动配置」+「上报用 LogDao 实现」两个类，
 * starter 4.1.0 起把这两层上收到基座（基座的日志远程上报自动配置内的
 * {@code @Bean @ConditionalOnMissingBean LogDao remoteLogDao(RemoteLogIngestClient)}）⇒
 * 本仓**只保留这一个 SPI 实现**，不再自带自动配置与 {@code LogDao}。这样：
 * ① 装配顺序、{@code @ConditionalOnMissingBean} 互斥、{@code beforeName} 等易错点全由基座统一；
 * ② 各业务仓（admin/iot）不再各写一份重复实现。</p>
 *
 * <p><b>为什么是上报而不是直连库：</b>auth 无数据源，ai 的数据源属于 AI 自己的库表域；跨服务数据访问
 * 必须走 {@link ISystemClient}（"新增跨服务数据访问，先扩展 ISystemClient + SystemClientImpl，
 * 禁止在调用方服务加 Mapper 直连"）。本类因此只做搬运，不做任何字段改名映射——
 * {@code sys_log} 的建表映射全仓只有 {@code DbLogProviders.DbLogDao} 一份。</p>
 *
 * <p><b>失败语义（禁静默降级）</b>：system 不可达时 {@code ISystemClientFallback} 返回失败 {@code R}
 * （{@code code=500}）。若此处静默 return，就是"登录日志没落库且无任何痕迹"。故本类<b>自己记完整堆栈
 * 并上抛</b>：记录异常必须带 Throwable（只写 {@code e.getMessage()} 会丢掉异常类型与调用栈），
 * 上抛后由 starter 的 {@code LogEventListener} 捕获（异常不逃逸到业务线程，登录活动照常返回）。</p>
 *
 * <p><b>调用线程</b>：由 {@code LogEventListener.onLogEvent} 调用（该方法带 {@code @Async}，本仓已引入
 * {@code ypbin-starter-async} ⇒ 实际在异步线程执行）；{@code LogRecord} 的采集已在业务线程完成，
 * 故本类不得依赖任何请求线程 ThreadLocal（租户/登录态）。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
public class SystemRemoteLogIngestClient implements RemoteLogIngestClient {

    private static final Logger log = LoggerFactory.getLogger(SystemRemoteLogIngestClient.class);

    private final ISystemClient systemClient;

    /**
     * 创建上报客户端。
     *
     * @param systemClient system 服务 Feign 客户端
     */
    public SystemRemoteLogIngestClient(ISystemClient systemClient) {
        this.systemClient = systemClient;
    }

    @Override
    public R<Void> ingestLog(LogRecord logRecord) {
        R<Void> result;
        try {
            result = systemClient.ingestLog(logRecord);
        } catch (RuntimeException ex) {
            // 传输层失败：连接/读取超时、熔断、响应解码异常。记完整堆栈后原样上抛（保留原始栈）
            log.error("[ypbin-admin] 操作日志上报 system 服务失败（传输异常），本条日志未落库: "
                + "module={}, description={}, requestUri={}",
                logRecord.getModule(), logRecord.getDescription(), logRecord.getRequestUri(), ex);
            throw ex;
        }
        if (result == null || !result.isSuccess()) {
            // 业务码失败（/internal 凭证不匹配 401、system 落库异常 500、降级兜底 500）没有异常对象，
            // 显式构造以产出可定位的堆栈
            IllegalStateException failure = new IllegalStateException(
                "[ypbin-admin] 操作日志上报 system 服务失败，本条日志未落库: "
                    + "code=" + (result == null ? "null" : result.getCode())
                    + ", message=" + (result == null ? "响应为空" : result.getMessage()));
            log.error("[ypbin-admin] 操作日志上报 system 服务失败（业务码）, module={}, description={}, requestUri={}",
                logRecord.getModule(), logRecord.getDescription(), logRecord.getRequestUri(), failure);
            throw failure;
        }
        return result;
    }
}
