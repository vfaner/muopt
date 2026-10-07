package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.service.UpdateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线自更新：下载最新 release 的 jar 并自动重启（见 {@link UpdateService}）。
 *
 * <p>仓库坐标由服务端写死，客户端不再能指定 owner/repo；且仅允许回环 Host 调用，
 * 阻断 DNS rebinding 冒用。
 */
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    private final UpdateService updateService;

    public UpdateController(UpdateService updateService) {
        this.updateService = updateService;
    }

    @PostMapping("/apply")
    public Result<String> apply(@RequestBody UpdateRequest request, HttpServletRequest httpRequest) {
        // Host 必须是回环地址：默认只绑 127.0.0.1，但 DNS rebinding 会让浏览器带着
        // 攻击者域名的 Host 访问本机，故再校验一道。
        if (!UpdateService.isLocalhostHost(httpRequest.getHeader("Host"))) {
            return Result.error(403, "仅允许在本机执行更新");
        }
        String version = request == null ? null : request.getVersion();
        String msg = updateService.applyUpdate(version);
        return Result.success(msg);
    }

    @Data
    public static class UpdateRequest {
        /** 最新版本号（用于判断是否已是最新、校验资产版本一致）；可空 */
        private String version;
    }
}
