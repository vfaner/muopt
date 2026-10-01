package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.entity.AiProtocol;
import com.qqmu.muopt.entity.AiProvider;
import com.qqmu.muopt.service.AiConnectionTestService;
import com.qqmu.muopt.service.AiProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置 API：多模型 CRUD + 互斥启用（同一时刻只有一个模型处于启用状态）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiProviderController {

    private final AiProviderService providerService;

    public AiProviderController(AiProviderService providerService) {
        this.providerService = providerService;
    }

    /** 全部模型配置（API Key 不回传） */
    @GetMapping("/list")
    public Result<List<AiProvider>> list() {
        return Result.success(providerService.findAll());
    }

    /**
     * 新建或更新模型。请求体 apiKey 为明文（响应永不回传）；
     * 编辑时留空表示沿用已存储的 Key。
     */
    @PostMapping("/save")
    public Result<AiProvider> save(@RequestBody AiProvider provider) {
        String rawApiKey = provider.getApiKey();
        return Result.success(providerService.save(provider, rawApiKey));
    }

    /**
     * 启用模型：其他模型自动停用，随后立即探测一次（探测失败不撤销启用，
     * 最近探测结果随实体返回，由前端展示徽章）。
     */
    @PostMapping("/{id}/enable")
    public Result<AiProvider> enable(@PathVariable Long id) {
        return Result.success(providerService.enable(id));
    }

    /** 停用模型 */
    @PostMapping("/{id}/disable")
    public Result<?> disable(@PathVariable Long id) {
        providerService.disable(id);
        return Result.success();
    }

    /** 删除模型配置 */
    @PostMapping("/{id}/delete")
    public Result<?> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.success();
    }

    /** 探测已保存的模型并记录结果 */
    @PostMapping("/{id}/test")
    public Result<AiConnectionTestService.TestResult> testSaved(@PathVariable Long id) {
        return Result.success(providerService.test(id));
    }

    /** 探测表单中尚未保存的模型（Key 留空且为编辑场景时回退库中 Key） */
    @PostMapping("/test")
    public Result<AiConnectionTestService.TestResult> testTransient(@RequestBody AiProvider provider) {
        return Result.success(providerService.testTransient(provider, provider.getApiKey()));
    }

    /** 协议默认值，切换协议时预填 Base URL */
    @GetMapping("/protocol-defaults")
    public Result<Map<String, Object>> protocolDefaults(@RequestParam String protocol) {
        AiProtocol p = AiProtocol.fromName(protocol);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", p.name());
        body.put("displayName", p.getDisplayName());
        body.put("defaultBaseUrl", p.getDefaultBaseUrl());
        body.put("chatPath", p.getChatPath());
        return Result.success(body);
    }
}
