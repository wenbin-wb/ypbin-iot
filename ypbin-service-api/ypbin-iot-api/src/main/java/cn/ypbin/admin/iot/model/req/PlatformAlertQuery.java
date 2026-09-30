package cn.ypbin.admin.iot.model.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台告警分页查询（看板 #10 二批「前端平台告警列表」）。
 *
 * 平台健康告警是**枚举固定规则**（无设备/点位语义），读侧只需分页 + 状态/级别两个轻筛选；
 * 与设备告警查询（AlertInstanceQuery）同风格。
 */
@Getter
@Setter
public class PlatformAlertQuery {

    /** 页码（1 起）。 */
    @Min(value = 1, message = "页码从 1 起")
    private Integer page = 1;

    /** 每页条数（上限 100，防一次性拉爆）。 */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;

    /** 状态码筛选（可空；PENDING|FIRING|RESOLVED，见 PlatformAlertState）。 */
    private String state;

    /** 级别码筛选（可空；WARNING|CRITICAL，见 PlatformSeverity）。 */
    private String severity;
}