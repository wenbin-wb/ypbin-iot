/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.ai.service.impl;

import cn.ypbin.starter.data.core.EntityStatus;
import cn.ypbin.admin.ai.core.AiKeyCipher;
import cn.ypbin.admin.ai.entity.AiModelConfig;
import cn.ypbin.admin.ai.mapper.AiModelConfigMapper;
import cn.ypbin.admin.ai.model.req.AiModelConfigSaveReq;
import cn.ypbin.admin.ai.model.resp.AiModelConfigResp;
import cn.ypbin.admin.ai.service.AiModelConfigService;
import cn.ypbin.starter.core.exception.BusinessException;
import cn.ypbin.starter.security.core.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 模型配置业务实现。
 *
 * @author wenbin
 * @since 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class AiModelConfigServiceImpl implements AiModelConfigService {

    /** 对话模型 */
    public static final String MODEL_TYPE_CHAT = "CHAT";
    /** 向量化模型 */
    public static final String MODEL_TYPE_EMBEDDING = "EMBEDDING";

    /** 连接超时（秒）——**显式**取值，禁止无超时的默认客户端（仓内铁律）。 */
    static final int CONNECT_TIMEOUT_SECONDS = 5;
    /** 读超时（秒）——同上，显式取值。 */
    static final int READ_TIMEOUT_SECONDS = 15;

    /**
     * 共享的 HTTP 客户端（**进程内单例，必须复用**）。
     *
     * <p><b>为什么是静态单例而不是每次调用 {@code HttpClient.newBuilder()} 新建</b>：
     * JDK 的 {@code HttpClient} 自带连接池与 selector 线程；每次新建等于**丢掉连接池**，
     * 还会让超时/协议策略散落到各调用点（历史上 {@code testConnection} 就是每次新建）。
     * 抽到一处后，"超时是多少、用哪个协议版本"只有一个答案。</p>
     *
     * <p><b>为什么显式指定 HTTP/1.1</b>：JDK 的默认是 HTTP/2（明文下走 h2c upgrade）。
     * **已实测的失败只发生在"服务端接受 h2c upgrade 随后断开"的那类服务端上**：2026-09-27 用同款 JDK 客户端
     * 对 EMQX/Cowboy（{@code /api/v5/publish}）实测，<b>带体 POST 作为某条新连接上的第一个请求</b>会抛
     * {@code java.io.IOException: EOF reached while reading}，强制 HTTP/1.1 则正常
     * （判据可复跑，见 {@code deploy/emqx/diagnose-emqx-admin-h2c/}）。</p>
     *
     * <p>⚠️ <b>不要读成"明文 {@code http://} 必踩坑"</b>：若服务端不对这个 upgrade 做 h2c 切换，JDK 会
     * **优雅回落 HTTP/1.1 并成功**（独立复核用朴素 JDK {@code HttpServer} 实测即如此）。
     * ⇒ 本隐患**取决于服务端如何处理"带体 h2c upgrade"，对其它明文服务端未实测**。
     * 本类的连通性测试恰好是"新连接 + 带体 POST"，且 {@code baseUrl} **允许用户填明文 {@code http://}**
     * （本机模型/内网网关常见）⇒ 只要对端属于"接受 upgrade 再断"那一类就会踩到；固定 1.1 可一次消掉这一类。</p>
     *
     * <p>关于 HTTPS：实测只到「默认客户端可用且**回落** HTTP/1.1」（自签 {@code HttpsServer} 下
     * status=200、proto=HTTP/1.1）；**h2-over-TLS 未实测**，也**未查到** JDK 21 文档里关于客户端 ALPN
     * 协商细节的一手说明（独立复核在 {@code HttpClient}/{@code Builder} 文档 grep "ALPN" = 0 命中）
     * ⇒ 此处**不做**"HTTPS 一样正确"的断言。统一固定 HTTP/1.1 的目的是**少一类未知**，对 OpenAI 兼容接口足够。</p>
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    /**
     * 取共享客户端（包级可见，仅为让测试能断言"同一个实例 + 协议版本/超时已显式设置"，不做真实网络调用）。
     *
     * @return 进程内共享的 {@link HttpClient}
     */
    static HttpClient httpClient() {
        return HTTP_CLIENT;
    }

    private final AiModelConfigMapper modelConfigMapper;
    private final AiKeyCipher keyCipher;

    @Override
    public List<AiModelConfigResp> listModels() {
        return listModels(null);
    }

    @Override
    public List<AiModelConfigResp> listModels(String modelType) {
        return listModels(modelType, null);
    }

    @Override
    public List<AiModelConfigResp> listModels(String modelType, Integer status) {
        Long tenantId = currentTenantId();
        // 状态：null=仅启用（与历史行为一致），0/1 精确过滤供管理端找回已停用模型
        Integer filterStatus = status == null ? EntityStatus.ENABLED.getCode() : status;
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<AiModelConfig>()
            .eq(AiModelConfig::getTenantId, tenantId)
            .eq(AiModelConfig::getStatus, filterStatus);
        if (modelType != null && !modelType.isBlank()) {
            wrapper.eq(AiModelConfig::getModelType, modelType);
        }
        wrapper.orderByAsc(AiModelConfig::getModelType)
            .orderByDesc(AiModelConfig::getIsDefault)
            .orderByDesc(AiModelConfig::getCreateTime);
        List<AiModelConfig> list = modelConfigMapper.selectList(wrapper);
        return list.stream().map(this::toResp).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createModel(AiModelConfigSaveReq req) {
        AiModelConfig config = new AiModelConfig();
        BeanUtils.copyProperties(req, config);
        config.setTenantId(currentTenantId());
        // 类型缺省按对话模型处理，兼容存量调用
        if (config.getModelType() == null || config.getModelType().isBlank()) {
            config.setModelType(MODEL_TYPE_CHAT);
        }
        config.setIsDefault(0);
        // API Key AES-GCM 加密后存储，禁止明文落库
        config.setApiKey(keyCipher.encrypt(req.getApiKey()));
        modelConfigMapper.insert(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(Long id, AiModelConfigSaveReq req) {
        requireModel(id);
        AiModelConfig config = new AiModelConfig();
        BeanUtils.copyProperties(req, config, "id", "tenantId", "isDefault", "status", "apiKey");
        config.setId(id);
        // 留空表示不修改，非空则重新加密
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            config.setApiKey(keyCipher.encrypt(req.getApiKey()));
        }
        modelConfigMapper.updateById(config);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id) {
        AiModelConfig existing = requireModel(id);
        if (existing.getIsDefault() != null && existing.getIsDefault() == 1) {
            throw new BusinessException("默认模型不能删除，请先更换默认模型");
        }
        modelConfigMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        AiModelConfig target = requireModel(id);
        Long tenantId = currentTenantId();
        String modelType = target.getModelType() == null ? MODEL_TYPE_CHAT : target.getModelType();
        // 先清空同租户同类型的所有默认标记，避免不同类型互相覆盖
        modelConfigMapper.update(null,
            new LambdaUpdateWrapper<AiModelConfig>()
                .eq(AiModelConfig::getTenantId, tenantId)
                .eq(AiModelConfig::getModelType, modelType)
                .set(AiModelConfig::getIsDefault, 0));
        // 设置新默认
        modelConfigMapper.update(null,
            new LambdaUpdateWrapper<AiModelConfig>()
                .eq(AiModelConfig::getId, id)
                .set(AiModelConfig::getIsDefault, 1));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        AiModelConfig config = requireModel(id);
        if (status != null && status == 0
            && config.getIsDefault() != null && config.getIsDefault() == 1) {
            throw new BusinessException("默认模型不能停用，请先更换默认模型");
        }
        AiModelConfig update = new AiModelConfig();
        update.setId(id);
        update.setStatus(status);
        modelConfigMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long duplicate(Long id) {
        AiModelConfig source = requireModel(id);
        AiModelConfig copy = new AiModelConfig();
        BeanUtils.copyProperties(source, copy, "id", "isDefault", "status",
            "createTime", "updateTime", "createUser", "updateUser");
        copy.setName(source.getName() + "（副本）");
        copy.setIsDefault(0);
        copy.setStatus(EntityStatus.ENABLED.getCode());
        // API Key 密文可直接复用（同一把密钥加密，解密语义不变）
        modelConfigMapper.insert(copy);
        return copy.getId();
    }

    @Override
    public long testConnection(Long id) {
        AiModelConfig config = requireModel(id);
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException("该模型未配置接口地址（baseUrl），无法测试");
        }
        String apiKey = keyCipher.decrypt(config.getApiKey());
        long start = System.currentTimeMillis();
        try {
            HttpClient client = httpClient();
            String payload = """
                {"model":"%s","messages":[{"role":"user","content":"ping"}],"max_tokens":5}
                """.formatted(config.getModelName());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            String[] candidates = completionUrls(baseUrl);
            HttpResponse<String> resp = null;
            for (String url : candidates) {
                resp = client.send(builder.copy().uri(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());
                // 404 时尝试下一个候选地址，其余错误码直接返回
                if (resp.statusCode() != 404) {
                    break;
                }
            }
            if (resp == null || resp.statusCode() != 200) {
                int code = resp == null ? 0 : resp.statusCode();
                String body = resp == null ? "" : resp.body();
                throw new BusinessException("连接失败（HTTP " + code + "）："
                    + (body == null || body.isBlank() ? "无响应内容" : truncate(body)));
            }
            return System.currentTimeMillis() - start;
        } catch (IOException e) {
            throw new BusinessException("连接失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("连接超时或中断");
        }
    }

    /**
     * 根据用户填写的 baseUrl 推导 OpenAI 兼容的 chat/completions 地址：
     * 依次尝试原路径、补 /v1 前缀、去掉多余 /v1 前缀。
     */
    private String[] completionUrls(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return new String[] {normalized};
        }
        if (normalized.endsWith("/v1")) {
            return new String[] {normalized + "/chat/completions"};
        }
        // 根路径（如 https://api.deepseek.com）：先试 /chat/completions，404 再试 /v1/chat/completions
        return new String[] {normalized + "/chat/completions", normalized + "/v1/chat/completions"};
    }

    private String truncate(String text) {
        return text == null ? "" : (text.length() > 300 ? text.substring(0, 300) + "..." : text);
    }

    @Override
    public AiModelConfig getDefaultModel() {
        return getDefaultModel(MODEL_TYPE_CHAT);
    }

    @Override
    public AiModelConfig getDefaultModel(String modelType) {
        Long tenantId = currentTenantId();
        String type = modelType == null || modelType.isBlank() ? MODEL_TYPE_CHAT : modelType;
        return modelConfigMapper.selectOne(
            new LambdaQueryWrapper<AiModelConfig>()
                .eq(AiModelConfig::getTenantId, tenantId)
                .eq(AiModelConfig::getModelType, type)
                .eq(AiModelConfig::getIsDefault, 1)
                .eq(AiModelConfig::getStatus, EntityStatus.ENABLED.getCode())
                .last("LIMIT 1"));
    }

    /**
     * 当前登录用户的租户 ID；无登录上下文时明确失败，禁止静默回退默认租户。
     */
    private static Long currentTenantId() {
        return UserContext.getTenantId()
            .orElseThrow(() -> new BusinessException("无法获取当前租户上下文"));
    }

    private AiModelConfig requireModel(Long id) {
        AiModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("模型配置不存在");
        }
        return config;
    }

    private AiModelConfigResp toResp(AiModelConfig config) {
        AiModelConfigResp resp = new AiModelConfigResp();
        BeanUtils.copyProperties(config, resp, "apiKey");
        // API Key 脱敏
        if (config.getApiKey() != null && config.getApiKey().length() > 6) {
            resp.setApiKeyMasked(config.getApiKey().substring(0, 6) + "****");
        } else if (config.getApiKey() != null) {
            resp.setApiKeyMasked("****");
        }
        return resp;
    }
}
