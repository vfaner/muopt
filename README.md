# SQL优化工具

**中文** | [English](readme_en.md)

## 📖 项目简介

SQL 优化工具是一个基于 Spring Boot 3 的 Web 应用，针对给定的查询 SQL 进行智能优化分析，给出合适的索引建议，并支持连接真实数据源做深度优化与执行计划分析。

**核心特性：索引建议颜色区分** —— 已建立的索引以**灰色**（删除线）展示，尚未建立的以**橙色**展示，均支持一键复制。

### ✨ 主要特性

- 🎯 **智能索引建议**：基于 JSqlParser 解析 AST，从 WHERE 等值/范围条件、JOIN ON、ORDER BY、GROUP BY 中提取候选列，生成合理的（组合）索引
- 🎨 **颜色区分状态**：已存在=灰色、缺失/未核实=橙色，一目了然，支持复制粘贴
- ✏️ **手工优化**：粘贴单条 SQL 即时分析
- 🔍 **扫描优化**：扫描项目目录，提取 MyBatis XML、Java 代码中的 SQL 字符串、注解中的 SQL（`@Select`/`@Insert`/`@Update`/`@Delete`/`@Query`），逐条分析并支持一键原地替换
- 🤖 **AI 深度优化**（可选）：集成阿里云百炼（通义千问），对 SQL 智能改写
- 🔗 **配置数据源**（可选）：配置后可进行——
  - 索引存在性检测（区分灰/橙）
  - 冗余索引检测（给出 DROP 建议）
  - 大小表驱动判断（小表驱动大表建议）
- 📊 **SQL 执行计划**：连接数据源后执行 EXPLAIN，类 Navicat 表格化展示；成本过高才建议加索引，数据量很少则提示无需建立

### 🎨 设计逻辑

| 场景 | 行为 |
|------|------|
| **未配置数据源** | 索引建议全部**橙色**（无法核实是否已存在），可复制 `CREATE INDEX`；执行计划页提示"请先配置数据源" |
| **已配置数据源** | 索引区分**灰色（已存在）/ 橙色（缺失）**；额外给出冗余索引、大小表驱动建议；可用执行计划分析 |
| **手工/扫描页头** | 配置数据源后顶部显示"✅ 已配置数据源，可进行深度优化" |

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2, JSqlParser 4.9, HikariCP, OkHttp 4.12 |
| 前端 | Vue 3 CDN + Element Plus CDN（纯 HTML，无构建工具） |
| AI 接口 | 阿里云百炼 OpenAI 兼容接口（qwen-max） |
| 数据库驱动 | MySQL Connector/J、PostgreSQL JDBC、Oracle JDBC；达梦需手动加入 |
| 构建 | Maven, JDK 17+ |

## 🚀 快速开始

```bash
cd sql-optimizer-tool

# 编译打包
mvn clean package

# 运行（默认端口 8090）
java -jar target/sql-optimizer-tool-1.0.0.jar
```

访问：http://localhost:8090

### 配置 AI（可选）

```bash
export AI_API_KEY=你的百炼API_Key
```

不配置 API Key 也可正常使用索引分析（仅 AI 深度改写不可用）。

## 📊 支持的数据库（数据源连接）

| 数据库 | 类型标识 | 驱动 | 默认端口 |
|--------|---------|------|---------|
| MySQL | `mysql` | MySQL 驱动 | 3306 |
| Oracle | `oracle` | Oracle 驱动 | 1521 |
| PostgreSQL | `postgresql` | PostgreSQL 驱动 | 5432 |
| 达梦 DM | `dameng` | 需手动加入（见下） | 5236 |
| 高斯/openGauss | `gaussdb` | PostgreSQL 驱动 | 5432 |
| 人大金仓 KingBase | `kingbase` | PostgreSQL 驱动 | 54321 |
| OceanBase | `oceanbase` | MySQL 驱动 | 2881 |
| TiDB | `tidb` | MySQL 驱动 | 4000 |

### 达梦（DM）驱动说明

达梦 JDBC 驱动未发布到 Maven 中央仓，需手动引入：

1. 从达梦安装目录获取 `DmJdbcDriver18.jar`
2. 安装到本地 Maven 仓库：
   ```bash
   mvn install:install-file -Dfile=DmJdbcDriver18.jar \
     -DgroupId=com.dameng -DartifactId=DmJdbcDriver18 \
     -Dversion=8.1 -Dpackaging=jar
   ```
3. 在 `pom.xml` 中加入对应依赖后重新打包，或启动时用 `-cp` 追加该 jar。

## 📡 API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/optimize` | POST | 优化单条 SQL |
| `/api/optimize/batch` | POST | 批量优化多条 SQL（分号分隔） |
| `/api/status` | GET | 全局状态（是否已配数据源 / AI） |
| `/api/explain` | POST | 执行计划分析（需数据源） |
| `/api/scan` | POST | 扫描项目目录并提取 SQL |
| `/api/scan/replace` | POST | 将优化后的 SQL 替换回源文件（自动 `.bak` 备份） |
| `/api/scan/dirs` | GET | 浏览服务器目录树（供目录选择器使用） |
| `/api/datasource/connect` | POST | 测试并连接数据源 |
| `/api/datasource/disconnect` | POST | 断开数据源 |
| `/api/datasource/status` | GET | 当前数据源状态 |

## 📁 项目结构

```
sql-optimizer-tool/
├── src/main/java/com/sqloptimizer/
│   ├── SqlOptimizerApplication.java         # 启动类（排除默认数据源自动配置）
│   ├── common/                              # Result / IndexSuggestion / OptimizeResult
│   │                                        # ExplainResult / ExplainRow / DataSourceConfig / ScanItem
│   ├── config/                              # 全局异常处理
│   ├── controller/
│   │   ├── OptimizeController.java          # 优化 + 批量 + 状态
│   │   ├── ExplainController.java           # 执行计划
│   │   ├── ScanController.java              # 项目扫描 + 替换 + 目录浏览
│   │   └── DataSourceController.java        # 数据源配置
│   └── service/
│       ├── IndexAnalyzerService.java        # 索引候选列提取（规则引擎核心）
│       ├── SqlOptimizerService.java         # 优化编排：组合规则/数据源/AI
│       ├── ProjectScanService.java          # 项目扫描与原地替换
│       ├── DataSourceService.java           # 连接/索引检测/大小表/冗余索引
│       ├── ExplainService.java              # EXPLAIN 执行与成本评估
│       └── AiService.java                   # 百炼 AI 服务
└── src/main/resources/
    ├── application.yml
    └── static/
        ├── index.html         # 入口（跳转手工优化）
        ├── common.css         # 共享样式
        ├── manual.html        # 手工优化
        ├── auto.html          # 扫描优化
        ├── explain.html       # SQL 执行计划
        ├── datasource.html    # 配置数据源
        ├── donate.html        # 打赏支持
        └── image/
            └── ds.png         # 打赏二维码
```

## 📝 注意事项

1. **数据源为内存态**：配置的连接仅保存在内存中，应用重启后需重新配置；密码不会回传前端。
2. **执行计划安全**：PostgreSQL 系使用 `EXPLAIN`（不含 ANALYZE），不会真实执行写操作。
3. **索引匹配规则**：检测已存在索引时采用"最左前缀匹配"，与数据库索引使用逻辑一致。
4. **成本阈值**：执行计划成本 ≥10000 判为偏高才给索引建议；预估扫描行数 ≤500 判为数据量小，提示无需建索引（阈值可在 `ExplainService` 调整）。

## ☕ 打赏支持

如果这个项目对你有帮助，欢迎请我喝杯咖啡 ❤️

<img src="src/main/resources/static/image/ds.png" alt="打赏二维码" width="280">

> 也可在应用内左侧菜单「打赏支持」查看。

## ⭐ Star 支持

觉得好用的话，欢迎给项目点个 **[Star](https://github.com/vfaner/sql-optimizer-tool)** 支持一下，这是对作者最大的鼓励！

## 📄 许可证

个人 / 非商业使用免费。
