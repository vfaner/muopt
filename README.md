# MuOpt 沐优

**中文** | [English](readme_en.md)

> 🎬 **项目演示视频（Bilibili）**：[内网信创迁移神器！MuOpt 沐优：SQL 智能优化 + 18 种国产数据库方言转换，单个 Jar 包开箱即用！](https://www.bilibili.com/video/BV1vVYm6yEhf)
>
> <a href="https://www.bilibili.com/video/BV1vVYm6yEhf"><img src="docs/muopt.png" alt="MuOpt 沐优演示视频" width="760"></a>

## 💡 开发背景

随着信创改造在政企行业的全面铺开，大量存量业务系统需要从 MySQL / Oracle 迁移到达梦、人大金仓、GaussDB 等国产数据库，同时日常开发中的慢 SQL 也缺乏一个不依赖具体 IDE、开箱即用的排查工具：

- **SQL 优化门槛高**：DBA 资源有限，一线开发很难判断"这条 SQL 该不该加索引、加在哪些列上"；执行计划裸看费劲，缺少直观的可视化比对；
- **信创迁移工作量巨大**：一个项目动辄上千条 SQL 散落在 MyBatis XML、Java 注解与 `.sql` 脚本里，人工逐条改写方言既枯燥又容易漏；
- **内网环境工具匮乏**：很多单位内网无法访问在线工具，云服务又过不了安全审计，需要一个能拷进内网直接跑的本地工具。

MuOpt（沐优）就是为解决这三件事而生：**给开发一个能带进内网的 SQL 优化 + 信创转换瑞士军刀**。

## 📖 项目介绍

MuOpt 是一个基于 Spring Boot 3 的 Web SQL 优化与信创转换助手，单文件 muopt.jar 部署、浏览器即用，无需安装任何客户端。

**核心能力一览：**

- 🎯 **智能索引建议**：基于 JSqlParser 解析 AST，从 WHERE 等值/范围条件、JOIN ON、ORDER BY、GROUP BY 中提取候选列，生成合理的（组合）索引；建议区分**橙色（待创建）/ 灰色（已存在）**，连接数据源后自动核实
- ✏️ **手工优化**：粘贴单条 SQL 即时分析，AI 深度改写失败/超时自动降级本地规则改写
- 🔍 **扫描优化**：扫描项目目录，提取 MyBatis XML、Java 字符串、注解 SQL（`@Select`/`@Insert`/`@Update`/`@Delete`），逐条分析并支持一键原地替换（自动 `.bak` 备份）
- 🔄 **SQL 信创转换**：手工/批量两种模式，规则引擎毫秒级完成 18 种数据库方言转换（函数、类型、分页、自增、日期格式串），可叠加 AI 方言润色做二次把关；扫描转换支持整个项目一键改造写回
- 🤖 **AI 深度优化**（可选）：支持配置多个 AI 模型（百炼 / DeepSeek / OpenAI / Anthropic 及各类 OpenAI 兼容网关），同一时刻启用一个，可关闭推理模型的思考链防止长 SQL 被截断
- 🔗 **数据源配置**（可选）：十余种数据库驱动内置，连接后可做索引存在性检测、冗余索引检测（DROP 建议）、大小表驱动判断
- 📊 **SQL 执行计划**：连接数据源后执行 EXPLAIN，提供**表格 / 树形 / 图形**三种视图（类 DBeaver），按操作类型着色（全表扫描红、索引绿、JOIN 紫），并给出成本评估
- 🔐 **账号体系**：登录鉴权（连续失败 5 次锁定 30 分钟）、个人中心自助改密，多用户使用互不影响

### 🖼️ 功能截图

**登录页**（失败次数过多自动锁定 30 分钟）：

<img src="docs/muopt_login.png" width="480" alt="登录页">

**SQL 优化 · 手工优化**（索引建议橙/灰区分，AI 深度改写）：

<img src="docs/muopt_opt_sd.png" width="1180" alt="手工优化">

**SQL 优化 · 扫描优化**（后台任务，8 路并发 AI 改写，可一键写回源文件）：

<img src="docs/muopt_opt_zd.png" width="1180" alt="扫描优化">

**SQL 转换 · 手工转换**（单条 SQL 方言转换，支持图片 OCR 识别与 AI 润色）：

<img src="docs/muopt_convert_sd.png" width="1180" alt="手工转换">

**SQL 转换 · 扫描转换**（整个项目批量转换，自动 `.bak` 备份）：

<img src="docs/muopt_convert_zd.png" width="1180" alt="扫描转换">

**数据源配置**（多连接互斥启用，密码加密落库）：

<img src="docs/muopt_db.png" width="1180" alt="数据源配置">

**AI 模型配置**（多模型互斥启用，支持连通性探测）：

<img src="docs/muopt_ai.png" width="1180" alt="AI模型配置">

**SQL 执行计划**（表格 / 树形 / 图形三视图）：

<img src="docs/muopt_exp.png" width="1180" alt="SQL执行计划">

**关于我们**（版本对比 GitHub / Gitee release，支持在线自更新）：

<img src="docs/muopt_about.png" width="1180" alt="关于我们">

**个人中心**（可修改密码）：

<img src="docs/muopt_gerenzhongxin.png" width="1180" alt="个人中心">

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | Spring Boot 3.2, JSqlParser 4.9, HikariCP, JDK HttpClient |
| 前端 | Vue 3 CDN + Element Plus CDN（纯 HTML，无构建工具） |
| 数据存储 | H2 文件库（`./data`）+ Spring Data JPA；密码/API Key 使用 spring-security-crypto 加密落库 |
| AI 接口 | OpenAI 兼容协议（百炼 / DeepSeek / OpenAI / 内网网关）+ Anthropic Messages 协议 |
| 数据库驱动 | MySQL、PostgreSQL、Oracle、SQL Server、DB2、达梦 DM8、openGauss、人大金仓、OceanBase、瀚高、崖山等（均已内置，见下） |
| 构建与运行 | Maven, JDK 17+ |

## 🚀 部署步骤

```bash
cd muopt

# 1. 编译打包（需 Maven + JDK 17+）
mvn clean package

# 2. 运行（默认端口 8080）
java -jar target/muopt.jar
```

访问：http://localhost:8080 ，首次使用请先注册/登录账号。

打包产物为单个可执行 fat jar（约 70MB，内置十余种数据库驱动与前端页面），拷到装有 **JDK 17+** 的内网机器即可直接运行，无需 Maven、无需联网：

```bash
java -jar muopt.jar
# 可选：覆盖端口 / 监听地址 / 配置库加密口令
java -jar muopt.jar --server.port=8080 --server.address=0.0.0.0
```

部署时注意：

- 数据库与 AI 配置保存在运行目录下的 `./data`（H2 文件库），日志在 `./logs/`，注意这两个目录的持久化与备份；**不要删除 `./data`，否则所有配置丢失**。固定从同一工作目录启动，或用环境变量 `APP_DATA_DIR` 指到绝对路径（如 `APP_DATA_DIR=/var/muopt/data`）；
- 默认只监听 `127.0.0.1`，局域网访问需加 `--server.address=0.0.0.0` 并自行在网关加鉴权；
- 常用数据库驱动均已内置（见下表）；GBase 8a 等无公共仓库坐标的数据库在页面上填官网驱动 jar 路径，任意其他 JDBC 库用「自定义」类型接入；
- AI 调用从运行机器直接访问所配置的 Base URL，请确保内网到该地址的网络可达。

### 配置 AI（可选）

在左侧「AI 模型配置」页新增：选择协议（OpenAI 兼容 / Anthropic），填写 Base URL、模型名与 API Key，先「测试连通性」再保存，最后打开启用开关。多个模型只能启用一个，切换会自动停用其他。任何 OpenAI 兼容的内网网关 / 自建推理服务均可接入。

不启用任何模型也可正常使用索引分析与规则改写（仅 AI 深度改写不可用）。

## 📊 支持的数据库

**SQL 转换（目标方言，18 种）：** MySQL、MariaDB、PostgreSQL、GaussDB、openGauss、人大金仓、瀚高 HighGo、海量 Vastbase、达梦 DM、Oracle、崖山 YashanDB、SQL Server、DB2、OceanBase、TiDB、南大通用 GBase、GoldenDB、神通 Oscar。

**数据源连接：**

| 数据库 | 类型标识 | 驱动 | 默认端口 |
|--------|---------|------|---------|
| MySQL | `mysql` | **mysql-connector-j（内置）** | 3306 |
| MariaDB | `mariadb` | **mariadb-java-client（内置）** | 3306 |
| Oracle | `oracle` | **ojdbc8（内置）** | 1521 |
| PostgreSQL | `postgresql` | **postgresql（内置）** | 5432 |
| SQL Server | `sqlserver` | **mssql-jdbc（内置）** | 1433 |
| DB2 | `db2` | **jcc（内置）** | 50000 |
| 达梦 DM8 | `dameng` | **DmJdbcDriver18（内置）** | 5236 |
| GaussDB | `gaussdb` | PostgreSQL 驱动（走 PG 协议） | 5432 |
| openGauss | `opengauss` | **opengauss-jdbc（内置）** | 5432 |
| 人大金仓 KingBaseES V8 | `kingbase` | **kingbase8（内置）** | 54321 |
| OceanBase | `oceanbase` | **oceanbase-client（内置）** | 2881 |
| TiDB | `tidb` | MySQL 驱动（兼容 MySQL 协议） | 4000 |
| 瀚高 HighGo | `highgo` | **HgdbJdbc（内置）** | 5866 |
| 崖山 YashanDB | `yashandb` | **yashandb-jdbc（内置）** | 5436 |
| 海量 Vastbase | `vastbase` | 默认 PostgreSQL 兼容驱动；填 jar 后用原厂驱动 | 5432 |
| 神通 Oscar | `oscar` | 默认 PostgreSQL 兼容驱动；填 jar 后用原厂驱动 | 8080 |
| 南大通用 GBase 8a | `gbase` | 需官网驱动 jar（填写本地路径） | 5258 |
| H2 | `h2` | **h2（内置）** | — |
| 自定义 | `custom` | 完整 JDBC URL + 驱动类名；可指定本地 jar 路径，支持「扫描 jar」自动读取驱动类名与附加 URL 参数 | — |

内置驱动全部来自 Maven 中央仓库并打进 fat jar，开箱即用。无公共坐标的库按以下策略处理：TiDB / GaussDB 直接用兼容协议内置驱动；Vastbase / Oscar 默认 PG 兼容驱动、可填官网 jar 切换原厂驱动；GBase 8a 填官网 jar；其余任意 JDBC 库走「自定义」类型。驱动版本与现场不匹配时，在数据源上填新版驱动 jar 路径即可覆盖，无需重新打包。

## 📝 注意事项

1. **账号与安全**：连续登录失败 5 次锁定账号 30 分钟；登录态基于 HttpSession，多浏览器互不影响；个人中心可自助修改密码。默认只监听 `127.0.0.1`，公网/局域网暴露请自行加网关鉴权。
2. **配置持久化与加密**：数据库连接与 AI 模型均可保存多个、同一时刻各启用一个，互斥由服务端事务保证；保存在 `./data` 的 H2 文件库中，重启不丢。密码/API Key 加密存储、不回传前端，编辑时留空表示沿用原值；加密口令可用 `APP_CRYPTO_PASSWORD` / `APP_CRYPTO_SALT` 覆盖。
3. **后台任务不惧切页**：优化与扫描点击后立即返回任务 ID，在服务端线程执行——执行中切菜单、刷新页面，回来凭任务 ID 自动恢复进度；任务结果保留 30 分钟（服务重启后失效需重新执行），可随时取消。
4. **扫描优化的 AI 策略**：所有 SQL 先完成毫秒级本地规则分析，AI 改写再并发执行（默认并发 8、总时限 120 秒、单请求 45 秒上限）；挂死的请求按超时释放名额，到总时限未完成的条目自动降级本地规则改写并附提示。可用 `APP_AI_SCAN_CONCURRENCY` / `APP_AI_SCAN_TIMEOUT` / `APP_AI_SCAN_PER_REQUEST_TIMEOUT` 调整。
5. **AI 失败自动降级**：开启 AI 后无论超时、连接失败、HTTP 错误还是空返回，都会自动回退本地确定性规则改写（逗号隐式连接转 ANSI JOIN、HAVING 非聚合条件前移、`SELECT *` 按元数据展开），结果带「规则改写」标签并逐条列出改动；没有可安全改写时保留原文并说明。
6. **推理模型与思考链**：AI 改写/润色默认关闭推理模型的思考链（`enable_thinking:false` 等），让 Max Tokens 全部留给 SQL 输出，避免长 SQL 被截断或思考过长拖到超时；确需深度思考可在「AI 模型配置」编辑里关闭该开关。
7. **AI 网络与代理**：连接超时（连不上，常见于内网直连外网受限）与响应超时（模型繁忙、Base URL / 模型名填错，火山方舟须填接入点 ID `ep-xxxx`）提示不同。需要代理出网时设置环境变量 `HTTPS_PROXY`（或 `APP_AI_PROXY`）后重启；本机地址始终直连。
8. **执行计划安全**：PostgreSQL 系使用 `EXPLAIN`（不含 ANALYZE），不会真实执行写操作。
9. **写回源文件**：扫描优化/转换的「一键替换」均自动生成 `.bak` 备份；仍建议在版本控制下执行，替换前先浏览 diff。
10. **阈值可调**：执行计划成本 ≥10000 判为偏高才给索引建议；预估扫描行数 ≤500 判为小表免建索引（阈值在 `ExplainService` 调整）。

## 📞 联系方式

使用中遇到问题、想提需求或交流反馈，欢迎随时联系（每一条反馈我们都会认真看）：

| 渠道 | 账号 |
|------|------|
| QQ | 817094 |
| QQ | 2912167928 |
| QQ 群 | 426669837 |
| 微信 | hua47609 |

也可以在 GitHub / Gitee 提 Issue：

- GitHub：https://github.com/vfaner/muopt
- Gitee：https://gitee.com/super_rgh/muopt

## ☕ 打赏支持

如果这个项目对你有帮助，欢迎请作者喝杯咖啡 ❤️（应用内左侧菜单「打赏支持」可切换微信 / 支付宝 / QQ 三种方式）。

## ⭐ Star 支持

觉得好用的话，欢迎给项目点个 **Star** 支持一下，这是对作者最大的鼓励！

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。
