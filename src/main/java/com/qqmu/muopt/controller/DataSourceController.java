package com.qqmu.muopt.controller;

import com.qqmu.muopt.common.Result;
import com.qqmu.muopt.entity.DatabaseConfig;
import com.qqmu.muopt.service.DatabaseConfigService;
import com.qqmu.muopt.service.DataSourceService;
import com.qqmu.muopt.service.connection.DriverLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源配置 API：多连接 CRUD + 互斥启用（同一时刻只有一个连接处于启用状态）。
 */
@Slf4j
@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private final DataSourceService dataSourceService;
    private final DatabaseConfigService configService;
    private final DriverLoader driverLoader;

    public DataSourceController(DataSourceService dataSourceService,
                                DatabaseConfigService configService,
                                DriverLoader driverLoader) {
        this.dataSourceService = dataSourceService;
        this.configService = configService;
        this.driverLoader = driverLoader;
    }

    /** 扫描驱动 jar 中声明的驱动类名（META-INF/services），供自定义类型自动填充 */
    @GetMapping("/discover-drivers")
    public Result<Map<String, Object>> discoverDrivers(@RequestParam String jarPath) {
        List<String> drivers = driverLoader.discoverDriverClasses(jarPath);
        Map<String, Object> body = new HashMap<>();
        body.put("drivers", drivers);
        return Result.success(body);
    }

    /** 全部已保存的连接（密码不回传） */
    @GetMapping("/list")
    public Result<List<DatabaseConfig>> list() {
        return Result.success(configService.findAll());
    }

    /**
     * 新建或更新连接。请求体中的 password 为明文（响应永不回传）；
     * 编辑时留空表示沿用已存储的密码。
     */
    @PostMapping("/save")
    public Result<DatabaseConfig> save(@RequestBody DatabaseConfig config) {
        String rawPassword = config.getPassword();
        DatabaseConfig saved = configService.save(config, rawPassword);
        // 更新的若是当前启用连接，用新设置重建连接池；失败会抛异常提示，旧连接保持可用
        dataSourceService.refreshIfActive(saved.getId());
        return Result.success(saved);
    }

    /**
     * 启用连接：测试通过后建立连接池并热切换，其他连接自动停用。
     */
    @PostMapping("/{id}/enable")
    public Result<?> enable(@PathVariable Long id) {
        dataSourceService.activate(id);
        return Result.success(currentStatus());
    }

    /** 停用连接并关闭连接池 */
    @PostMapping("/{id}/disable")
    public Result<?> disable(@PathVariable Long id) {
        dataSourceService.deactivate(id);
        return Result.success(currentStatus());
    }

    /** 删除连接（已启用的需先停用） */
    @PostMapping("/{id}/delete")
    public Result<?> delete(@PathVariable Long id) {
        configService.delete(id);
        return Result.success();
    }

    /** 测试已保存的连接（使用库中密码） */
    @PostMapping("/{id}/test")
    public Result<DataSourceService.TestResult> testSaved(@PathVariable Long id) {
        DatabaseConfig config = configService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("数据源配置不存在"));
        return Result.success(dataSourceService.testConnection(config, null));
    }

    /** 测试表单中尚未保存的连接（密码取请求体明文；编辑场景留空则回退库中密码） */
    @PostMapping("/test")
    public Result<DataSourceService.TestResult> testTransient(@RequestBody DatabaseConfig config) {
        return Result.success(dataSourceService.testConnection(config, config.getPassword()));
    }

    /** 当前活动连接状态 */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(currentStatus());
    }

    private Map<String, Object> currentStatus() {
        Map<String, Object> map = new HashMap<>();
        boolean connected = dataSourceService.isConnected();
        map.put("connected", connected);
        map.put("config", connected ? dataSourceService.getCurrentConfig() : null);
        return map;
    }
}
