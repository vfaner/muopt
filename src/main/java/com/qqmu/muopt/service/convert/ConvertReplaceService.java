package com.qqmu.muopt.service.convert;

import com.qqmu.muopt.common.ConversionItem;
import com.qqmu.muopt.common.ConvertScanTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConvertReplaceService {

    private final ConvertScanService scannerService;

    @Autowired
    public ConvertReplaceService(ConvertScanService scannerService) {
        this.scannerService = scannerService;
    }

    public ReplaceResult replaceByTaskId(String taskId) {
        ConvertScanTask task = scannerService.getConvertScanTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        if (!"completed".equals(task.getStatus())) {
            throw new IllegalStateException("任务尚未完成，当前状态: " + task.getStatus());
        }

        return replaceItems(task.getItems());
    }

    public ReplaceResult replaceItems(List<ConversionItem> items) {
        // 按文件分组
        Map<String, List<ConversionItem>> groupedByFile = items.stream()
                .collect(Collectors.groupingBy(ConversionItem::getFilePath));

        int totalFiles = 0;
        int successFiles = 0;
        int skippedFiles = 0;
        int totalReplacements = 0;
        int unmatchedItems = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, List<ConversionItem>> entry : groupedByFile.entrySet()) {
            String filePath = entry.getKey();
            List<ConversionItem> fileItems = entry.getValue();
            totalFiles++;

            try {
                FileReplaceOutcome outcome = replaceInFile(filePath, fileItems);
                totalReplacements += outcome.occurrences;
                unmatchedItems += outcome.unmatched.size();

                // 只有真正改动过文件才算成功；一处都没命中的文件单独计为跳过
                if (outcome.matchedItems > 0) {
                    successFiles++;
                } else {
                    skippedFiles++;
                }

                if (!outcome.unmatched.isEmpty()) {
                    warnings.add(buildUnmatchedWarning(filePath, fileItems.size(), outcome.unmatched));
                    log.warn("文件 {} 有 {}/{} 个条目未能在原文中定位，已跳过",
                            filePath, outcome.unmatched.size(), fileItems.size());
                }
            } catch (Exception e) {
                log.error("替换文件失败: {} - {}", filePath, e.getMessage());
                errors.add(filePath + ": " + e.getMessage());
            }
        }

        ReplaceResult result = new ReplaceResult();
        result.setTotalFiles(totalFiles);
        result.setSuccessFiles(successFiles);
        result.setSkippedFiles(skippedFiles);
        result.setTotalReplacements(totalReplacements);
        result.setUnmatchedItems(unmatchedItems);
        result.setErrors(errors);
        result.setWarnings(warnings);

        return result;
    }

    /**
     * 生成未命中条目的告警信息。
     * <p>匹配失败的根因通常是扫描阶段对 SQL 做了归一化（XML 去标签 / {@code #{}} 转 {@code ?}、
     * Java 反转义与多行压平），归一化后的文本在原文里并不逐字存在。
     */
    private String buildUnmatchedWarning(String filePath, int totalItems, List<ConversionItem> unmatched) {
        String sample = unmatched.get(0).getSourceSql();
        if (sample != null && sample.length() > 80) {
            sample = sample.substring(0, 80) + "...";
        }
        return String.format("%s: %d/%d 个条目未能在原文中定位，该文件未做对应改动（首个未命中: %s）",
                filePath, unmatched.size(), totalItems, sample);
    }

    private FileReplaceOutcome replaceInFile(String filePath, List<ConversionItem> items) throws IOException {
        // 自定义清单由客户端提交，文件必须落在已扫描登记的根目录内，防任意路径写入
        if (!scannerService.isUnderScannedRoot(filePath)) {
            throw new IOException("目标文件不在已扫描的项目目录内，拒绝写入（请先扫描该目录）: " + filePath);
        }
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IOException("文件不存在: " + filePath);
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        FileReplaceOutcome outcome = new FileReplaceOutcome();

        // 有区间的按位置替换：必须从后往前，否则前面的改动会让后面的下标全部错位
        List<ConversionItem> byOffset = new ArrayList<>();
        List<ConversionItem> byText = new ArrayList<>();
        for (ConversionItem item : items) {
            if (isNoop(item)) continue;
            (item.hasOffsets() ? byOffset : byText).add(item);
        }
        byOffset.sort(Comparator.comparingInt(ConversionItem::getStartOffset).reversed());

        for (ConversionItem item : byOffset) {
            String replaced = spliceAtOffset(content, item, outcome);
            if (replaced != null) {
                content = replaced;
                outcome.matchedItems++;
                outcome.occurrences++;
            }
        }

        // 没有区间信息的（如手工提交的自定义清单）退回逐字匹配
        for (ConversionItem item : byText) {
            String replaced = replaceByText(content, item, outcome);
            if (replaced != null) {
                content = replaced;
            }
        }

        if (outcome.matchedItems > 0) {
            createBackupIfAbsent(path);
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("文件已更新: {}，{} 个条目共替换 {} 处",
                    filePath, outcome.matchedItems, outcome.occurrences);
        }

        return outcome;
    }

    private boolean isNoop(ConversionItem item) {
        return item.getSourceSql() == null
                || item.getTargetSql() == null
                || item.getSourceSql().equals(item.getTargetSql());
    }

    /**
     * 按扫描时记录的区间原样覆盖。
     * <p>先校验该区间的内容仍与 {@code sourceSql} 逐字一致——文件在扫描后被改过、
     * 或用户手工编辑过清单，下标就可能失效，此时宁可不改。
     */
    private String spliceAtOffset(String content, ConversionItem item, FileReplaceOutcome outcome) {
        int start = item.getStartOffset();
        int end = item.getEndOffset();

        if (end > content.length()) {
            outcome.unmatched.add(item);
            return null;
        }
        if (!content.substring(start, end).equals(item.getSourceSql())) {
            outcome.unmatched.add(item);
            return null;
        }
        if (!isSafeReplacement(item)) {
            outcome.unmatched.add(item);
            return null;
        }

        return content.substring(0, start) + item.getTargetSql() + content.substring(end);
    }

    /**
     * 结构安全兜底：按偏移写回是原样覆盖，替换文本若比原文多出换行或双引号，
     * 就可能提前结束 Java 单行字符串字面量 / XML 属性。少于原文是安全的（AI 压行）。
     */
    private boolean isSafeReplacement(ConversionItem item) {
        String source = item.getSourceSql();
        String target = item.getTargetSql();

        if (countChar(target, '\n') > countChar(source, '\n')) {
            log.warn("跳过条目：替换文本比原文多出换行，可能破坏源码结构 - {}", item.getFilePath());
            return false;
        }
        if (countChar(target, '"') > countChar(source, '"')) {
            log.warn("跳过条目：替换文本比原文多出双引号，可能提前结束字符串字面量 - {}", item.getFilePath());
            return false;
        }
        return true;
    }

    private int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    /** 无区间信息时的退化路径：逐字匹配。 */
    private String replaceByText(String content, ConversionItem item, FileReplaceOutcome outcome) {
        String sourceSql = item.getSourceSql();
        String targetSql = item.getTargetSql();

        int hits = countOccurrences(content, sourceSql);
        if (hits > 0) {
            outcome.matchedItems++;
            outcome.occurrences += hits;
            return content.replace(sourceSql, targetSql);
        }

        int index = indexOfIgnoreCase(content, sourceSql);
        if (index >= 0) {
            outcome.matchedItems++;
            outcome.occurrences++;
            return content.substring(0, index) + targetSql
                    + content.substring(index + sourceSql.length());
        }

        // 匹配失败必须留下痕迹，不能静默跳过后仍然报成功
        outcome.unmatched.add(item);
        return null;
    }

    /**
     * 仅在备份不存在时创建，避免第二次改造把 .bak 覆盖成"已改造"的内容而永久丢失原始版本。
     */
    private void createBackupIfAbsent(Path path) throws IOException {
        Path backupPath = Paths.get(path + ".bak");
        if (Files.exists(backupPath)) {
            log.info("备份已存在，保留最初版本不覆盖: {}", backupPath);
            return;
        }
        Files.copy(path, backupPath);
        log.info("已创建备份: {}", backupPath);
    }

    /** 统计非重叠出现次数，与 {@link String#replace} 的替换口径一致。 */
    private int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = content.indexOf(needle, from)) >= 0) {
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    /**
     * 忽略大小写查找。使用 {@code Locale.ROOT} 避免土耳其语等区域的 I/i 规则异常；
     * 若大小写转换改变了长度（如 ß/İ），按原长度切片会错位并写坏文件，此时放弃匹配。
     */
    private int indexOfIgnoreCase(String content, String sourceSql) {
        String lowerSource = sourceSql.toLowerCase(Locale.ROOT);
        if (lowerSource.length() != sourceSql.length()) {
            return -1;
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        if (lowerContent.length() != content.length()) {
            return -1;
        }
        return lowerContent.indexOf(lowerSource);
    }

    /** 单个文件的替换结果。 */
    private static class FileReplaceOutcome {
        /** 成功命中并替换的条目数 */
        private int matchedItems;
        /** 实际发生替换的文本出现次数（一个条目可能命中多处） */
        private int occurrences;
        /** 未能在原文中定位的条目 */
        private final List<ConversionItem> unmatched = new ArrayList<>();
    }

    public static class ReplaceResult {
        private int totalFiles;
        private int successFiles;
        private int skippedFiles;
        private int totalReplacements;
        private int unmatchedItems;
        private List<String> errors;
        private List<String> warnings;

        public int getTotalFiles() {
            return totalFiles;
        }

        public void setTotalFiles(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        public int getSuccessFiles() {
            return successFiles;
        }

        public void setSuccessFiles(int successFiles) {
            this.successFiles = successFiles;
        }

        public int getSkippedFiles() {
            return skippedFiles;
        }

        public void setSkippedFiles(int skippedFiles) {
            this.skippedFiles = skippedFiles;
        }

        public int getTotalReplacements() {
            return totalReplacements;
        }

        public void setTotalReplacements(int totalReplacements) {
            this.totalReplacements = totalReplacements;
        }

        public int getUnmatchedItems() {
            return unmatchedItems;
        }

        public void setUnmatchedItems(int unmatchedItems) {
            this.unmatchedItems = unmatchedItems;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void setErrors(List<String> errors) {
            this.errors = errors;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public void setWarnings(List<String> warnings) {
            this.warnings = warnings;
        }

        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }

        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
    }
}
