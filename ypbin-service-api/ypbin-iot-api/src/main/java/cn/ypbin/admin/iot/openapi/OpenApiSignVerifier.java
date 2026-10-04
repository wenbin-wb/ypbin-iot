package cn.ypbin.admin.iot.openapi;

import cn.ypbin.starter.sign.core.SignAlgorithm;
import cn.ypbin.starter.sign.core.SignGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * 开放 API 请求签名校验（纯函数，零 IO —— 可穷举单测）。
 *
 * <p><b>为什么在 iot 侧验签、而不是网关</b>（看板「开放 API 签名合并」方案 c）：
 * 网关验签必须持有明文密钥或 pepper，等于把机密扩散到第二个进程；且网关是 WebFlux，
 * body 流式不可重复读，签名覆盖 body 会引出"转发后下游收不到 body"的问题。
 * 故由网关<b>只透传参数</b>（已重建好的规范串），本服务用既有 pepper/hash 体系完成验证。</p>
 *
 * <p><b>与客户端口径的一致性</b>：签名算法与规范串拼接<b>直接复用 starter 的
 * {@link SignGenerator}</b>，不另写一套 —— 否则两侧规范化差异会导致"时对时错"。</p>
 *
 * <p><b>fail-closed</b>：时间戳格式错、超期、未来偏移超限、签名不匹配一律返回 {@code false}，
 * 且不区分失败原因（网关侧统一 401，防枚举）。</p>
 *
 * @author wenbin
 * @since 2026-10-05
 */
public final class OpenApiSignVerifier {

    private OpenApiSignVerifier() {
    }

    /**
     * 判定请求是否<b>意图使用</b>签名（四个参数任一非空白即为是）。
     *
     * <p>用于灰度期分流：全空 ⇒ 未启用签名（放行，保持既有行为）；任一非空 ⇒ 必须完成验签。
     * <b>不得</b>改为"只要有任意一个就算、缺失的当默认值"——那会被"只带一个参数"绕过。</p>
     *
     * @param timestamp 时间戳（可空）
     * @param nonce     nonce（可空）
     * @param sign      签名值（可空）
     * @param signParams 参与签名的参数（可空）
     * @return 任一非空白返回 {@code true}
     */
    public static boolean intendsSignature(String timestamp, String nonce, String sign,
                                           Map<String, String> signParams) {
        return notBlank(timestamp) || notBlank(nonce) || notBlank(sign)
            || hasSignableParam(signParams);
    }

    /**
     * 校验签名四件套是否齐备（意图使用签名时，缺一即不可验）。
     *
     * <p>`signParams` 只要求**键存在**（可为空 Map）：无业务参数的端点（whoami 等）
     * 收集到的是空集，此时签名覆盖空规范串 —— "没有可签参数"不等于"缺参数"。
     * 若要求非空，这类端点将**永远**过不了签名（曾因此导致 whoami 在强制模式下恒 401）。</p>
     *
     * <p>`signParams == null`（调用方连键都没传）仍判不齐备：网关契约要求始终下发该键，
     * 缺键说明调用链异常，按 fail-closed 拒绝。</p>
     *
     * @param timestamp 时间戳
     * @param nonce     nonce
     * @param sign      签名值
     * @param signParams 参与签名的参数
     * @return 齐备返回 {@code true}
     */
    public static boolean complete(String timestamp, String nonce, String sign,
                                   Map<String, String> signParams) {
        return notBlank(timestamp) && notBlank(nonce) && notBlank(sign)
            && signParams != null;
    }

    /**
     * 校验时间戳是否在有效期内。
     *
     * <p>过去方向按 {@link OpenApiKeyConstants#SIGN_TIMEOUT_SECONDS}；未来方向只容忍
     * {@link OpenApiKeyConstants#SIGN_CLOCK_SKEW_SECONDS}（防"未来时间戳"扩大重放窗口）。</p>
     *
     * <p>用<b>比较</b>而非<b>相减</b>：{@code requestTime} 完全由请求方提供，相减在极值
     * （如 {@code Long.MIN_VALUE}）下会溢出，溢出后可能两个条件都不满足 ⇒ 绕过有效期校验。</p>
     *
     * @param timestamp     时间戳字符串（秒级 epoch）
     * @param nowEpochSecond 当前时间（秒级 epoch，由调用方传入以便测试）
     * @return 合法返回 {@code true}
     */
    public static boolean timestampValid(String timestamp, long nowEpochSecond) {
        if (!notBlank(timestamp)) {
            return false;
        }
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        long lowerBound = nowEpochSecond - OpenApiKeyConstants.SIGN_TIMEOUT_SECONDS;
        long upperBound = nowEpochSecond + OpenApiKeyConstants.SIGN_CLOCK_SKEW_SECONDS;
        return requestTime >= lowerBound && requestTime <= upperBound;
    }

    /**
     * 按"参数 Map + 明文密钥"重算签名并与客户端签名做<b>常量时间</b>比对。
     *
     * <p>参数 Map 直接交给 starter 的 {@link SignGenerator#generate} 规范化 ——
     * <b>不得</b>先自行拼串再传入（那会被二次 percent-encode，与客户端永不匹配）。</p>
     *
     * @param params    参与签名的参数（非空；可为空集＝无业务参数时签空规范串）
     * @param rawSecret 明文密钥（非空）
     * @param sign      客户端签名
     * @param algorithm 算法（与客户端约定一致）
     * @return 匹配返回 {@code true}；任何缺失/异常返回 {@code false}（不抛异常）
     */
    public static boolean signatureMatches(Map<String, String> params, String rawSecret, String sign,
                                           SignAlgorithm algorithm) {
        if (params == null || !notBlank(rawSecret) || !notBlank(sign)) {
            return false;
        }
        String expected;
        try {
            expected = SignGenerator.generate(params, rawSecret, algorithm);
        } catch (RuntimeException ex) {
            // 不抛异常（密钥异常如空 key 属"校验失败"而非系统错误）
            return false;
        }
        // 客户端签名容忍大小写差异（SignGenerator 输出大写十六进制）
        String actual = sign.trim();
        return MessageDigest.isEqual(expected.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
            actual.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 nonce 的防重放存活秒数（覆盖时间戳整个有效期末尾）。
     *
     * <p>固定 {@code timeout + 1} 在时间戳偏未来时会早于时间戳失效前过期，留出重放真空期；
     * 故按 {@code timeout + delta + 1} 动态计算（delta = 请求时间戳 - 当前时间）。
     * 时间戳合法时该值必落在 {@code [1, 2*timeout+1]}。</p>
     *
     * @param timestamp      时间戳（秒级 epoch）
     * @param nowEpochSecond 当前时间（秒级 epoch）
     * @return 存活秒数（至少 1）
     */
    public static long nonceTtlSeconds(String timestamp, long nowEpochSecond) {
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp.trim());
        } catch (RuntimeException ex) {
            return OpenApiKeyConstants.SIGN_TIMEOUT_SECONDS + 1;
        }
        // 🔴 溢出防护（CodeQL: "User-controlled data in arithmetic expression"）：
        // requestTime 完全由请求方提供，`requestTime - nowEpochSecond` 在极值（Long.MIN/MAX）
        // 下会**下溢回绕**，回绕后 Math.max(1L, ...) 可能得到一个"看起来正常"的 TTL，
        // 使 nonce 过早过期 ⇒ 出现重放真空期。
        // 故用**差值比较**替代直接相减（与 timestampValid 同口径），只用相减后的安全区间：
        // 先夹到合法窗口 [-timeout, +skew]，再参与运算，杜绝回绕。
        long lowerBound = nowEpochSecond - OpenApiKeyConstants.SIGN_TIMEOUT_SECONDS;
        long upperBound = nowEpochSecond + OpenApiKeyConstants.SIGN_CLOCK_SKEW_SECONDS;
        if (requestTime < lowerBound || requestTime > upperBound) {
            // 非法/极值时间戳：调用方 timestampValid 已会拒绝；此处回落到安全默认，
            // 保证 TTL 仍覆盖一个完整有效期（fail-closed，不放宽重放窗口）。
            return OpenApiKeyConstants.SIGN_TIMEOUT_SECONDS * 2 + 1;
        }
        // 走到这里 requestTime ∈ [now-timeout, now+skew] ⇒ 相减结果必落在
        // [-timeout, +skew]，不可能回绕。
        long delta = requestTime - nowEpochSecond;
        long ttl = OpenApiKeyConstants.SIGN_TIMEOUT_SECONDS + delta + 1;
        return Math.max(1L, ttl);
    }

    /**
     * 判定参数集里是否存在**可参与签名**的项（键与值均非空白）。
     *
     * <p>刻意不用 {@code !isEmpty()}：{@code {"":""}} 这种"非空但全空白"的 Map 会让
     * 规范串变成空串，而空串在密码学上是合法可签的输入 ⇒ 若判为"未启用签名"就会放行，
     * 若判为"齐备"又等于允许一个无内容的签名。故统一按"有无有效参数"判定。</p>
     *
     * @param params 参数集（可空）
     * @return 存在有效参数返回 {@code true}
     */
    private static boolean hasSignableParam(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (notBlank(entry.getKey()) && notBlank(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
