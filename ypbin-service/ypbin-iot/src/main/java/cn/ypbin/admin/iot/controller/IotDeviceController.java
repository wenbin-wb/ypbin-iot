/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.iot.controller;

import cn.ypbin.admin.iot.model.query.IotDeviceQuery;
import cn.ypbin.admin.iot.model.req.IotDeviceBatchDeleteReq;
import cn.ypbin.admin.iot.model.req.IotDeviceBatchStatusReq;
import cn.ypbin.admin.iot.model.req.IotDeviceReq;
import cn.ypbin.admin.iot.model.req.DeviceSpaceSyncReq;
import cn.ypbin.admin.iot.model.req.DeviceSpaceBindReq;
import cn.ypbin.admin.iot.model.resp.IotDeviceResp;
import cn.ypbin.admin.iot.service.IotDeviceService;
import cn.ypbin.starter.core.model.R;
import cn.ypbin.starter.crud.model.PageResult;
import cn.ypbin.starter.log.annotation.Log;
import cn.ypbin.starter.tools.idempotent.Idempotent;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IoT 设备台账接口。
 *
 * <p>权限码形如 {@code iot:device:list}（与 admin 其它业务域一致：{@code 域:资源:动作}）。
 * 路径是<b>纯资源路径</b>：网关按 {@code Path=/iot/**} + {@code StripPrefix=1} 转发，
 * 因此客户端调 {@code /iot/devices}、服务内看到 {@code /devices}（与 ai/system 同构）。</p>
 *
 * @author wenbin
 * @since 2026-09-19
 */
@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class IotDeviceController {

    /** 导出附件固定文件名。 */
    private static final String DEVICE_EXPORT_FILE_NAME = "iot-devices.csv";

    /** CSV MIME（带 charset：与导入模板下载一致，避免表格软件按本机编码解读）。 */
    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";

    private final IotDeviceService iotDeviceService;

    /**
     * 分页查询设备。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    @SaCheckPermission("iot:device:list")
    public R<PageResult<IotDeviceResp>> page(IotDeviceQuery query) {
        return R.ok(iotDeviceService.pageDevices(query));
    }

    /**
     * 导出设备台账 CSV（复用列表筛选条件；单次最多 10000 条，超限由服务层明确拒绝并提示先筛选）。
     *
     * <p>文件下载直接返回 {@link ResponseEntity} 字节：若返回 {@code R<String>}，浏览器拿到的是
     * 被引号包裹的 JSON，用户点「导出」会得到一个打不开的 .csv。</p>
     *
     * @param query 查询条件（keyword/protocol/productId）
     * @return CSV 附件
     */
    @GetMapping("/export")
    @SaCheckPermission("iot:device:list")
    public ResponseEntity<byte[]> export(IotDeviceQuery query) {
        return csvResponse(DEVICE_EXPORT_FILE_NAME, iotDeviceService.exportCsv(query));
    }

    /**
     * 新增设备。
     *
     * @param req 设备信息
     * @return 新设备主键
     */
    @PostMapping
    @SaCheckPermission("iot:device:create")
    @Idempotent
    @Log("新增 IoT 设备")
    public R<Long> create(@Valid @RequestBody IotDeviceReq req) {
        return R.ok(iotDeviceService.createDevice(req));
    }

    /**
     * 删除设备。
     *
     * @param id 设备主键
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("iot:device:delete")
    @Idempotent
    @Log("删除 IoT 设备")
    public R<Void> remove(@PathVariable Long id) {
        iotDeviceService.removeDevice(id);
        return R.ok();
    }

    /**
     * 编辑设备（含绑定产品与物模型版本，§4.1）。
     *
     * @param id  设备主键
     * @param req 设备信息
     * @return 空响应
     */
    @PutMapping("/{id}")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("编辑 IoT 设备")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody IotDeviceReq req) {
        iotDeviceService.updateDevice(id, req);
        return R.ok();
    }

    /**
     * 启用/停用设备（设备台账的启停开关，G7′）。
     *
     * <p>形态与 admin 既有的 {@code PUT /{id}/status/{status}} 一致（如 AI 模型配置、Prompt 模板）：
     * 单点动作不改整表单，避免把「读到什么就写回什么」变成静默覆盖。停用后该设备不再进入
     * 采集规格下发（{@code DeviceSpecServiceImpl} 只取 {@code status=1}）⇒ 接入侧解绑并停止采集。</p>
     *
     * @param id     设备主键
     * @param status 启停位：1 启用 / 0 停用
     * @return 空响应
     */
    @PutMapping("/{id}/status/{status}")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("切换 IoT 设备状态")
    public R<Void> updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        iotDeviceService.updateStatus(id, status);
        return R.ok();
    }

    /**
     * 批量启用/停用设备（列表页多选；单次最多 500 台）。
     *
     * <p>与单个启停同权限同语义，只是一次提交一批：服务层一条批量 UPDATE + 台账版本号只推进一次。
     * 状态仅接受 0/1，第三态会被拒（理由见单台 {@link #updateStatus}）。</p>
     *
     * @param req 批量启停请求
     * @return 实际生效台数
     */
    @PutMapping("/batch/status")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("批量切换 IoT 设备状态")
    public R<Integer> batchUpdateStatus(@Valid @RequestBody IotDeviceBatchStatusReq req) {
        return R.ok(iotDeviceService.batchUpdateStatus(req.getIds(), req.getStatus()));
    }

    /**
     * 批量删除设备（列表页多选；单次最多 500 台）。
     *
     * <p>逻辑删除 + 一次批量 SQL；删除成功后推进台账版本号，接入侧据此解绑停采。</p>
     *
     * @param req 批量删除请求
     * @return 实际删除台数
     */
    /**
     * 绑定一批设备到指定空间。
     *
     * @param id        空间 ID
     * @param req       绑定请求（设备 ID 列表 + 是否主空间 + 是否清空其他位置）
     * @return 实际生效的关联条数
     */
    /**
     * 取设备当前所在的空间 ID 列表（表单回显用）。
     *
     * @param id 设备 ID
     * @return 空间 ID 列表（无关联时为空数组）
     */
    @GetMapping("/{id}/spaces")
    @SaCheckPermission("iot:device:list")
    public R<List<String>> spacesOf(@PathVariable Long id) {
        return R.ok(iotDeviceService.spacesOfDevice(id));
    }

    @PostMapping("/space/{id}/bind")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("设备绑定空间")
    public R<Integer> bindSpace(@PathVariable Long id, @Valid @RequestBody DeviceSpaceBindReq req) {
        return R.ok(iotDeviceService.bindSpace(id, req.getDeviceIds(),
            Boolean.TRUE.equals(req.getIsPrimary()), Boolean.TRUE.equals(req.getClearOthers())));
    }

    /**
     * 解绑一批设备与指定空间。
     *
     * @param id  空间 ID
     * @param req 解绑请求（设备 ID 列表）
     * @return 实际删除的关联条数
     */
    @PostMapping("/space/{id}/unbind")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("设备解绑空间")
    public R<Integer> unbindSpace(@PathVariable Long id,
        @Valid @RequestBody DeviceSpaceBindReq req) {
        return R.ok(iotDeviceService.unbindSpace(id, req.getDeviceIds()));
    }

    /**
     * 整体设置某设备的空间集合（单事务：算差集后批量增删）。
     *
     * <p>编辑设备时用这个而不是 bind+unbind：换位置若由前端做两步，
     * 中间失败会留下「两头都不在」或「两头都在」的不一致状态。</p>
     *
     * @param id  设备 ID
     * @param req 同步请求（目标空间集合，空数组 = 清空）
     * @return 实际变更条数
     */
    @PostMapping("/{id}/spaces/sync")
    @SaCheckPermission("iot:device:update")
    @Idempotent
    @Log("同步设备空间")
    public R<Integer> syncSpaces(@PathVariable Long id,
        @Valid @RequestBody DeviceSpaceSyncReq req) {
        return R.ok(iotDeviceService.syncDeviceSpaces(id, req.getSpaceIds()));
    }

    @PostMapping("/batch/delete")
    @SaCheckPermission("iot:device:delete")
    @Idempotent
    @Log("批量删除 IoT 设备")
    public R<Integer> batchRemove(@Valid @RequestBody IotDeviceBatchDeleteReq req) {
        return R.ok(iotDeviceService.batchRemove(req.getIds()));
    }

    /**
     * 构造 CSV 下载响应（UTF-8、BOM 由 CSV 文本自带、文件名 RFC 5987 编码、禁缓存）。
     *
     * <p>与批量导入模板下载同构：文件名走 {@code filename*=UTF-8''} 避免中文/特殊字符在
     * 不同浏览器下编码歧义；{@code no-store} 防止中间层缓存住某个租户的导出结果。</p>
     *
     * @param fileName 文件名
     * @param content  CSV 文本
     * @return 字节响应
     */
    private static ResponseEntity<byte[]> csvResponse(String fileName, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(CSV_CONTENT_TYPE));
        headers.setContentLength(body.length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
