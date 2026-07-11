package com.sqloptimizer.controller;

import com.sqloptimizer.common.DataSourceConfig;
import com.sqloptimizer.common.Result;
import com.sqloptimizer.service.DataSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据源配置 API（非必需功能）
 */
@Slf4j
@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    @Autowired
    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    /**
     * 测试并建立连接
     */
    @PostMapping("/connect")
    public Result<?> connect(@RequestBody DataSourceConfig config) {
        if (config.getDbType() == null || config.getHost() == null || config.getDatabase() == null) {
            return Result.error(400, "数据库类型、主机、数据库名不能为空");
        }
        dataSourceService.connect(config);
        return Result.success(currentStatus());
    }

    /**
     * 断开连接
     */
    @PostMapping("/disconnect")
    public Result<?> disconnect() {
        dataSourceService.disconnect();
        return Result.success(currentStatus());
    }

    /**
     * 当前数据源状态
     */
    @GetMapping("/status")
    public Result<?> status() {
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
