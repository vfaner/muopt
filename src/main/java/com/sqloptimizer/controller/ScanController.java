package com.sqloptimizer.controller;

import com.sqloptimizer.common.Result;
import com.sqloptimizer.common.ScanItem;
import com.sqloptimizer.service.ProjectScanService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 项目扫描 API
 * 扫描项目目录识别 SQL，产出优化建议，并支持将优化后的 SQL 原地替换。
 */
@Slf4j
@RestController
@RequestMapping("/api/scan")
public class ScanController {

    private final ProjectScanService scanService;

    @Autowired
    public ScanController(ProjectScanService scanService) {
        this.scanService = scanService;
    }

    /**
     * 扫描项目目录
     */
    @PostMapping
    public Result<List<ScanItem>> scan(@RequestBody ScanRequest request) {
        if (request.getProjectPath() == null || request.getProjectPath().trim().isEmpty()) {
            return Result.error(400, "项目目录不能为空");
        }
        try {
            List<ScanItem> items = scanService.scan(request.getProjectPath().trim(), request.isEnableAi());
            return Result.success(items);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 将某条 SQL 的优化结果原地替换回源文件（自动备份 .bak）
     */
    @PostMapping("/replace")
    public Result<?> replace(@RequestBody ReplaceRequest request) {
        if (request.getItem() == null) {
            return Result.error(400, "替换项不能为空");
        }
        try {
            String optimized = request.getOptimizedSql() != null
                    ? request.getOptimizedSql()
                    : request.getItem().getOptimizedSql();
            boolean ok = scanService.replace(request.getItem(), optimized);
            return Result.success(ok);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 浏览服务器磁盘目录树（供前端目录选择器使用，返回真实绝对路径）。
     * path 为空时返回根/盘符列表与用户主目录。
     */
    @GetMapping("/dirs")
    public Result<DirListing> listDirs(@RequestParam(required = false) String path) {
        try {
            DirListing listing = new DirListing();
            List<DirEntry> dirs = new ArrayList<>();

            if (path == null || path.trim().isEmpty()) {
                // 列出文件系统根（Windows 多盘符 / *nix 为 "/"）
                for (File root : File.listRoots()) {
                    dirs.add(entryOf(root));
                }
                String home = System.getProperty("user.home");
                listing.setCurrent(null);
                listing.setParent(null);
                listing.setHome(home);
                listing.setDirs(dirs);
                return Result.success(listing);
            }

            Path current = Paths.get(path).toAbsolutePath().normalize();
            File dir = current.toFile();
            if (!dir.exists() || !dir.isDirectory()) {
                return Result.error(400, "目录不存在: " + path);
            }
            File[] children = dir.listFiles(File::isDirectory);
            if (children != null) {
                for (File c : children) {
                    if (!c.isHidden()) {
                        dirs.add(entryOf(c));
                    }
                }
                dirs.sort(Comparator.comparing(e -> e.getName().toLowerCase()));
            }
            listing.setCurrent(current.toString());
            listing.setParent(current.getParent() != null ? current.getParent().toString() : null);
            listing.setHome(System.getProperty("user.home"));
            listing.setDirs(dirs);
            return Result.success(listing);
        } catch (Exception e) {
            return Result.error(400, "读取目录失败: " + e.getMessage());
        }
    }

    private DirEntry entryOf(File f) {
        DirEntry e = new DirEntry();
        String name = f.getName();
        e.setName(name.isEmpty() ? f.getPath() : name); // 盘符/根目录 name 为空时用路径
        e.setPath(f.getAbsolutePath());
        return e;
    }

    @Data
    public static class ScanRequest {
        private String projectPath;
        private boolean enableAi = false;
    }

    @Data
    public static class ReplaceRequest {
        private ScanItem item;
        /** 可选：前端编辑后的优化 SQL；为空则用 item 内的 optimizedSql */
        private String optimizedSql;
    }

    @Data
    public static class DirListing {
        /** 当前所在目录绝对路径（根列表时为 null） */
        private String current;
        /** 上级目录绝对路径（无上级时为 null） */
        private String parent;
        /** 用户主目录，供前端提供快捷入口 */
        private String home;
        /** 子目录列表 */
        private List<DirEntry> dirs;
    }

    @Data
    public static class DirEntry {
        private String name;
        private String path;
    }
}
