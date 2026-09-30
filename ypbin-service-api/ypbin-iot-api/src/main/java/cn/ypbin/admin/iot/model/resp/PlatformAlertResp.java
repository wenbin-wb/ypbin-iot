package cn.ypbin.admin.iot.model.resp;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台告警条目（看板 #10 二批前端列表的读模型）。
 *
 * 只含读侧字段；metricSnapshot 是判定时的指标快照（JSON 文本），由前端原样展示。
 */
@Getter
@Setter
public class PlatformAlertResp {

    /** 主键（Long 由全局序列化转字符串输出）。 */
    private Long id;

    /** 规则码（PlatformHealthRule.getCode()）。 */
    private String ruleCode;

    /** 级别码（WARNING|CRITICAL）。 */
    private String severity;

    /** 状态码（PENDING|FIRING|RESOLVED）。 */
    private String state;

    /** 面向用户的一句话概要。 */
    private String summary;

    /** 判定时刻的指标快照（JSON 文本，可能为空）。 */
    private String metricSnapshot;

    /** 首次异常时刻。 */
    private LocalDateTime startTs;

    /** FIRING 时刻（PENDING 期为空）。 */
    private LocalDateTime firingTs;

    /** 恢复时刻（未恢复为空）。 */
    private LocalDateTime resolvedTs;

    /** 连续异常轮数。 */
    private Integer observedRounds;
}