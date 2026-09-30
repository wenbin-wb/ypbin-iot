package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.entity.IotPlatformAlert;
import cn.ypbin.admin.iot.mapper.IotPlatformAlertMapper;
import cn.ypbin.admin.iot.model.req.PlatformAlertQuery;
import cn.ypbin.admin.iot.model.resp.PlatformAlertResp;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台告警读端点（看板 #10 二批「前端平台告警列表」）。
 *
 * <p>读侧与评估器（PlatformAlertService）解耦：只查落库结果，不做判定。</p>
 *
 * <p><b>权限复用 iot:alert:list</b>：平台告警与设备告警同属「告警」读语义，
 * 不新造权限码（新码意味着新菜单与授权波及全部租户，属本环节不需要的 L3 附面）。</p>
 *
 * <p><b>租户隔离</b>：IotPlatformAlert 继承 TenantBaseEntity，由租户插件自动过滤，
 * 与既有端点同机制（无需手写 tenant 条件）。</p>
 */
@RestController
@RequestMapping("/platform-alerts")
public class PlatformAlertController {

    private final IotPlatformAlertMapper alertMapper;

    public PlatformAlertController(IotPlatformAlertMapper alertMapper) {
        this.alertMapper = alertMapper;
    }

    /**
     * 平台告警分页列表（按首次异常时刻倒序）。
     *
     * @param query 分页与筛选（状态/级别可空）
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:alert:list")
    public R<PageResult<PlatformAlertResp>> page(@Valid PlatformAlertQuery query) {
        LambdaQueryWrapper<IotPlatformAlert> wrapper = new LambdaQueryWrapper<>();
        if (query.getState() != null && !query.getState().isBlank()) {
            wrapper.eq(IotPlatformAlert::getState, query.getState());
        }
        if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            wrapper.eq(IotPlatformAlert::getSeverity, query.getSeverity());
        }
        wrapper.orderByDesc(IotPlatformAlert::getStartTs);
        wrapper.orderByDesc(IotPlatformAlert::getId);
        IPage<IotPlatformAlert> page = alertMapper.selectPage(
            new Page<>(query.getPage(), query.getPageSize()), wrapper);
        List<PlatformAlertResp> records = page.getRecords().stream()
            .map(this::toResp)
            .toList();
        return R.ok(new PageResult<>(
            records,
            page.getTotal(),
            page.getCurrent(),
            page.getSize()));
    }

    /** 实体 → 读模型（字段一一对应，无计算）。 */
    private PlatformAlertResp toResp(IotPlatformAlert entity) {
        PlatformAlertResp resp = new PlatformAlertResp();
        resp.setId(entity.getId());
        resp.setRuleCode(entity.getRuleCode());
        resp.setSeverity(entity.getSeverity());
        resp.setState(entity.getState());
        resp.setSummary(entity.getSummary());
        resp.setMetricSnapshot(entity.getMetricSnapshot());
        resp.setStartTs(entity.getStartTs());
        resp.setFiringTs(entity.getFiringTs());
        resp.setResolvedTs(entity.getResolvedTs());
        resp.setObservedRounds(entity.getObservedRounds());
        return resp;
    }
}