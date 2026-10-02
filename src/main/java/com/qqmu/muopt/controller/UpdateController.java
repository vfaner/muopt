package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.service.UpdateService;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在线自更新：下载最新 release 的 jar 并自动重启（见 {@link UpdateService}）。
 */
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    private final UpdateService updateService;

    public UpdateController(UpdateService updateService) {
        this.updateService = updateService;
    }

    @PostMapping("/apply")
    public Result<String> apply(@RequestBody UpdateRequest request) {
        if (request == null || request.getOwner() == null || request.getOwner().isBlank()
                || request.getRepo() == null || request.getRepo().isBlank()) {
            return Result.error(400, "仓库信息缺失");
        }
        String msg = updateService.applyUpdate(request.getOwner().trim(), request.getGiteeOwner(),
                request.getRepo().trim(), request.getVersion());
        return Result.success(msg);
    }

    @Data
    public static class UpdateRequest {
        /** GitHub 仓库 owner */
        private String owner;
        /** Gitee 仓库 owner（与 GitHub 不同账号时传；缺省沿用 owner） */
        private String giteeOwner;
        private String repo;
        /** 最新版本号（用于判断是否已是最新、校验资产版本一致） */
        private String version;
    }
}