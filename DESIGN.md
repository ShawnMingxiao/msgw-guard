# IBM i MSGW 无人值守守卫 — 设计方案

- 版本：v0.2（按评审结论定稿，进入实现）
- 日期：2026-11-24（初稿 2026-11-23）
- 技术约束：**Spring Boot 2.7.18 + Java 8**；JTOpen `net.sf.jt400:jt400:11.2`（已核对 API 签名与字节码兼容性）
- 工作目录：`E:/WBProject/spaces/2026_11_23_11-09-48`（全部产物在 E 盘）
- 交付形态：普通可执行 jar（`java -jar`）；界面单人使用

---

## 0. 评审结论（2026-11-24 用户确认）

| # | 用户决定 | 对设计的影响 |
|---|---|---|
| 1 | 使用 JTOpen；手动指定登录服务器、登录用户、监控的 subsystem | 设置页新增 host / user / password / subsystem 四项；JTOpen 为唯一生产适配器，模拟器仅用于本地演练 |
| 2 | 回复内容可配置，通过规则引擎配置规则 | 所有回复内容（含 `I`/`C`/`R`/自定义）来自规则配置，代码不硬编码任何回复字符串 |
| 3 | 被 cancel 后停止并提醒 | 守卫在 cancel 回复后**停止处理该 job**，产生醒目告警与审计记录，不尝试续跑调度链 |
| 4 | 允许直接结束 job，但必须按配置的规则结束 | `END_JOB` 作为规则动作之一，只有规则显式配置且总开关开启时才执行，默认仍禁用 |
| 5 | 普通可执行 jar | jt400 作为 Maven 依赖打进 fat jar，不再外置 `lib/`，`java -jar` 直接运行 |
| 6 | 界面只考虑单人使用 | 不实现多用户、角色与权限体系 |

---

## 1. 问题定义与目标

### 1.1 现状

IBM i（iSeries）上有一个 subsystem，其中运行着批处理程序。该程序按预定调度顺序依次执行 job。只要其中**任何一个 job 进入 MSGW（message wait）状态**，整条批处理链就停住，必须人工登录回答这条 inquiry message，job 完成后后续 job 才能继续。

### 1.2 目标

做一个独立的 Java 守卫服务（带 Web 可视化界面），自动完成：

1. 周期性发现处于 MSGW 状态的 job，并读取其 inquiry message。
2. 按**可配置的模糊规则**决定如何回答：
   - 消息文本包含某些字眼 → 回复 `I`（ignore）或 `C`（cancel）或自定义内容；
   - 指定 job（支持通配）→ 无论消息内容如何，固定回复同一内容。
3. 记录每一次发现、决策和回复的审计日志。
4. 提供界面配置规则、查看 MSGW 队列、测试规则、手工干预。

### 1.3 明确约束

- **不调用任何外部 AI / LLM / 云服务**；规则匹配全部为本地纯 Java 计算。
- 不改变 IBM i 上现有批处理程序与调度程序的逻辑；守卫只负责"回答 MSGW 消息"这一个动作。
- 不臆测 IBM i API 细节：所有与 IBM i 的交互集中在**单一可插拔适配器**内，并提供模拟器，规则逻辑可完全脱离真实 iSeries 验证。适配器中标明需现场核对的点。

---

## 2. 范围

### 2.1 In scope

| 编号 | 能力 |
|---|---|
| S1 | MSGW job 发现 + inquiry 消息读取；服务器地址、登录用户、监控 subsystem 均界面可配置 |
| S2 | 规则引擎：消息文本/消息 ID/job 名/用户/subsystem 的包含、不包含、等于、通配、正则、模糊相似度匹配 |
| S3 | 按 job 固定回复规则（白名单式，优先级高于通用规则） |
| S4 | 动作：`I` / `C` / `R` / 自定义文本 / 仅告警 / 按规则结束 job；所有回复内容来自规则配置，不硬编码 |
| S5 | 优先级 first-match-wins + 默认动作（保守：仅告警） |
| S6 | 定时轮询、手动触发、限流、幂等、失败重试、连接熔断 |
| S7 | Dry-run 模式：只决策和记录，不真正回复（首次上线默认开启） |
| S8 | 本地 JSON 持久化：规则、配置、审计日志 |
| S9 | Web 可视化界面：概览、规则管理、规则测试台、审计日志、模拟注入、设置 |
| S10 | 本地模拟器：无需 IBM i 即可跑通全链路 |
| S11 | job 被 cancel 后：停止处理该 job，并产生醒目告警（界面提醒 + 审计记录），不自动续跑 |

### 2.2 Out of scope

| 编号 | 不做的事 | 原因 |
|---|---|---|
| O1 | 修改 IBM i 上的批处理程序 / CL 程序 / 调度表 | 用户现有流程保持不变 |
| O2 | cancel 之后自动续跑后续 job | 用户确认：cancel 后**停止并提醒**，由人工决定下一步，守卫不自动续跑 |
| O3 | 集群 / 高可用 / 数据库持久化 | 单机守卫足够；JSON 文件降低部署复杂度 |
| O4 | IBM i 侧 CL 程序的自动部署 | 由现场按文档部署，守卫只调用 |
| O5 | 多用户与权限体系 | 用户确认界面仅单人使用，不实现用户管理与角色权限 |
| O6 | AI / 机器学习分类 | 用户明确禁止调用外部 AI |

> **重要边界说明**：若某 job 被回复 `C`（cancel）后，用户现有批处理程序因为该 job 失败而中止整个调度链，这不是守卫能单独解决的问题。评审时需确认现场批处理程序的失败处理策略：是"失败即停"还是"继续下一个"。若需守卫介入续跑，请作为 V2 需求提出。

---

## 3. 总体架构

```
┌──────────────────────────────────────────────┐
│ IBM i 目标系统                                │
│  Subsystem → 批处理程序 → MSGW job 队列        │
└───────────────────┬──────────────────────────┘
                    │ IbmIClient SPI
┌───────────────────▼──────────────────────────┐
│ 接入层（可插拔适配器）                          │
│  ① Simulator  ② JTOpen  ③ CL 命令模板          │
└───────────────────┬──────────────────────────┘
                    │ JobSnapshot + InquiryMessage
┌───────────────────▼──────────────────────────┐
│ 核心层                                        │
│  GuardService（轮询/幂等/重试/熔断）            │
│  RuleEngine（纯函数规则决策）                   │
└───────────────────┬──────────────────────────┘
                    │ Decision + AuditRecord
┌───────────────────▼──────────────────────────┐
│ 交互与存储层                                   │
│  REST API + 单页 Web UI ｜ JSON 规则/配置/审计   │
└──────────────────────────────────────────────┘
```

### 3.1 分层职责

| 层 | 包 | 职责 | 是否依赖 IBM i |
|---|---|---|---|
| 接入层 | `com.msgwguard.ibmi` | 发现 MSGW job、读消息、发送回复 | 是 |
| 规则层 | `com.msgwguard.engine` | 输入上下文，输出决策；纯函数 | 否 |
| 服务层 | `com.msgwguard.service` | 调度、幂等、重试、熔断、审计 | 否 |
| 存储层 | `com.msgwguard.store` | JSON 原子读写、审计追加 | 否 |
| Web 层 | `com.msgwguard.web` | REST API + 静态页面 | 否 |

---

## 4. 关键数据模型

### 4.1 `JobSnapshot`（MSGW job 快照）

| 字段 | 类型 | 说明 |
|---|---|---|
| jobName | String | job 名 |
| user | String | job 用户 |
| jobNumber | String | 作业号 |
| subsystem | String | 所在 subsystem（如 `QUSRWRK`） |
| status | String | 固定 `MSGW` |
| detectedAt | Instant | 发现时间 |

### 4.2 `InquiryMessage`（待回答消息）

| 字段 | 类型 | 说明 |
|---|---|---|
| messageId | String | 消息 ID，如 `CPA3708` |
| text | String | 消息首级文本（规则主要匹配对象） |
| secondLevelText | String | 二级文本（可选，参与 ALL_TEXT 匹配） |
| messageKey | String/byte[] | 用于精确定位并回复该条消息 |
| severity | int | 严重级别 |
| timestamp | Instant | 消息时间 |

### 4.3 `Rule`（规则）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | String | UUID |
| name | String | 规则名 |
| enabled | boolean | 是否启用 |
| priority | int | 升序，越小越先匹配 |
| scope | enum | `GLOBAL` / `JOB` |
| jobPattern | String | `JOB` 作用域下的 job 匹配串，支持 `*` `?` |
| userPattern / subsystemPattern | String | 可选，进一步限定 |
| matchField | enum | `MESSAGE_TEXT` / `MESSAGE_ID` / `JOB_NAME` / `SUBSYSTEM` / `ALL_TEXT` |
| matcher | enum | `CONTAINS` / `NOT_CONTAINS` / `EQUALS` / `WILDCARD` / `REGEX` / `FUZZY` |
| patterns | List\<String\> | 关键字/表达式列表 |
| matchMode | enum | `ANY`（任一命中）/ `ALL`（全部命中） |
| caseSensitive | boolean | 是否区分大小写，默认 false |
| fuzzyThreshold | double | `FUZZY` 用，0~1，默认 0.85 |
| action | enum | `IGNORE` / `CANCEL` / `RETRY` / `CUSTOM` / `ALERT_ONLY` / `END_JOB` |
| customReply | String | `CUSTOM` 时的回复内容（如 `I`、`C`、`Y`） |
| note | String | 备注 |

### 4.4 `Decision`（决策结果）

`action`、`replyText`、`matched`、`matchedRuleId`、`matchedRuleName`、`score`、`reason`、`decidedAt`

### 4.5 `AuditRecord`（审计记录）

`id`、`timestamp`、`job`、`messageId`、`textPreview`、`decision`、`executionStatus`（`SUCCESS`/`DRY_RUN`/`FAILED`/`SKIPPED`/`ALERT`）、`alert`（布尔，cancel/未命中/异常时为 true）、`error`、`durationMs`

### 4.6 `Settings`

`adapter`（`jt400`/`simulator`）、`pollIntervalMs`、`dryRun`、`defaultAction`、`defaultReply`、`maxRetries`、`failureThreshold`、`allowDestructive`、`ibmiHost`、`ibmiUser`、`ibmiPassword`、`ibmiSubsystem`、`messageQueuePathTemplate`、`authToken`

---

## 5. 规则引擎设计（核心）

### 5.1 决策流程

1. 取出所有 `enabled=true` 的规则，按 `priority` 升序。
2. **作用域过滤**：`JOB` 规则先用 `jobPattern`/`userPattern`/`subsystemPattern` 过滤；`*` 通配、`?` 单字符，精确串优先于通配串。
3. **字段取值**：按 `matchField` 从上下文取字符串；`ALL_TEXT` 拼接消息首级 + 二级 + job 名 + 消息 ID。
4. **匹配计算**：
   - `CONTAINS`：任一/全部 pattern 是目标串的子串（大小写按配置）。
   - `NOT_CONTAINS`：目标串不含任一/全部 pattern。
   - `EQUALS`：全等（去首尾空格，可选忽略大小写）。
   - `WILDCARD`：支持 `*` `?` 的通配匹配。
   - `REGEX`：Java `Pattern` 编译匹配；非法表达式在保存规则时校验，运行时失败记审计并跳过该规则。
   - `FUZZY`：纯本地归一化 Levenshtein 相似度（最长公共子序列思路亦可），`score >= threshold` 视为命中；用于容忍错别字、空格差异。**不调用任何 AI。**
5. **first-match-wins**：第一条命中的规则立即产生 `Decision`；不再评估后续规则。
6. **未命中**：返回 `defaultAction`，默认 `ALERT_ONLY`（只告警不回复），避免误操作。
7. **安全闸门**（在服务层执行，不属于引擎）：
   - `dryRun=true` → 记 `DRY_RUN` 审计，不发送回复；
   - `action=END_JOB` 且 `allowDestructive=false` → 降级为 `ALERT_ONLY`；
   - 同一 `job + messageKey` 已处理过 → 幂等跳过；
   - 消息文本为空 → 不自动回复。

### 5.2 规则示例

| 场景 | scope | matchField | matcher | patterns | action |
|---|---|---|---|---|---|
| 消息含 "not found" 就忽略 | GLOBAL | MESSAGE_TEXT | CONTAINS | `not found`,`不存在` | IGNORE(`I`) |
| 消息含 "MPI1001" 就取消 | GLOBAL | MESSAGE_ID | EQUALS | `MPI1001` | CANCEL(`C`) |
| 某 job 无条件回 `I` | JOB | JOB_NAME | CONTAINS | `NIGHTLY_DAILY` | IGNORE(`I`) |
| 含 "permission"/"authority" 只告警 | GLOBAL | ALL_TEXT | ANY CONTAINS | `permission`,`authority`,`权限` | ALERT_ONLY |
| 模糊容忍拼写差异 | GLOBAL | MESSAGE_TEXT | FUZZY | `object not found` | IGNORE(`I`) |

---

## 6. IBM i 接入层设计

### 6.1 SPI 接口

```java
public interface IbmIClient {
    boolean testConnection();
    List<JobSnapshot> listMsgwJobs();                       // 发现 MSGW
    InquiryMessage readInquiry(JobSnapshot job);            // 读消息
    void reply(JobSnapshot job, InquiryMessage msg, String replyText); // 回复内容来自规则配置
    void endJob(JobSnapshot job);                           // 结束 job（仅规则授权且总开关开启）
}
```

### 6.2 适配器（JTOpen 为生产实现）

| 适配器 | 适用场景 | 实现方式 |
|---|---|---|
| `jt400` | 生产（用户指定） | JTOpen 11.2：`AS400(host,user,pwd)` 连接；`JobList.addJobSelectionCriteria(JobList.SELECTION_ACTIVE_JOB_STATUS, Job.ACTIVE_JOB_STATUS_WAIT_MESSAGE)` 找 MSGW；`Job.getSubsystem()` 客户端过滤；`MessageQueue(path).getMessages(0,n)` 读消息；`reply(msg.getKey(), replyText, true)` 回复；`Job.end(0)` 结束 job |
| `simulator` | 本地演练 | 内存队列 + 手工注入接口 |

**已核对的真实 API（jt400 11.2，通过 javap 验证）**：

- `JobList(AS400)`、`addJobSelectionCriteria(int, Object)`、`getJobs(int,int): Job[]`
- `MessageQueue(AS400,String)`、`getMessages(int,int): QueuedMessage[]`、`reply(byte[],String,boolean)`、`setSelection(String)`
- `QueuedMessage.getKey(): byte[]`、`getID(): String`、`getMessage(): String`、`getSeverity(): int`
- `Job.getSubsystem(): String`、`getStatus(): String`、`end(int delay)`（0 = `*IMMED`）
- `AS400(String host, String user, String password)`、`CommandCall.run(String)`

**实现策略**：

- jt400 通过 Maven 依赖 `net.sf.jt400:jt400:11.2` 打进可执行 jar，用户 `java -jar` 直接运行。
- 服务器、用户、密码、subsystem 全部由界面设置，运行时可切换，不写死在代码里。
- job 消息队列路径用可配置模板（默认 `/QSYS.LIB/{job}.MSGQ`），现场可调整。
- 所有 IBM i 调用只在 `com.msgwguard.ibmi` 包内；上层只依赖 `IbmIClient` 接口。
- 统一走 JTOpen 高层 API，不在代码里假设 IBM i SQL 服务的字段可用性。

### 6.3 权限与安全

- 使用专用服务账号，仅授予必要权限（读 job 列表、读目标消息队列、发送 reply）；`END_JOB` 权限按需单独授予。
- 密码不写死在配置文件：建议通过环境变量 `IBMI_PASSWORD` 注入；界面保存设置时密码字段只写不回显。
- 所有回复动作记录"谁/何时/对哪个 job/回复了什么"，审计文件 append-only。

---

## 7. Web 可视化界面

单页应用，原生 HTML/CSS/JavaScript（不引入前端构建工具），浅色主题。左侧导航，六个页面：

| 页面 | 内容 |
|---|---|
| 概览 | 运行/停止开关、适配器状态、轮询间隔、今日统计（扫描/回复/告警/失败）、当前 MSGW job 列表（可手动"立即处理"） |
| 规则管理 | 表格展示，支持新增/编辑/启停/删除/优先级排序/导入导出 JSON；表单按 4.3 字段组织 |
| 固定回复 | `scope=JOB` 规则的快捷视图，集中管理"某 job 无条件回某内容" |
| 规则测试台 | 粘贴一段模拟消息 + job 信息，实时展示命中哪条规则、会执行什么动作；支持批量测试 |
| 审计日志 | 按时间/job/动作/结果筛选，查看决策理由与错误信息 |
| 设置 | 适配器选择、IBM i 服务器/用户/密码/subsystem、消息队列路径模板、轮询间隔、dry-run、默认动作、结束 job 总开关 |
| 接入指南 | 所需权限、消息队列路径说明、常见问题 |

模拟器配套"注入 MSGW"对话框：手工添加一条带指定消息文本的 job，然后点击"立即扫描"即可验证规则链路。

---

## 8. REST API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/status` | 运行状态、统计、适配器信息 |
| POST | `/api/guard/start` `/stop` | 启动/停止轮询 |
| POST | `/api/guard/scan` | 手动触发一次扫描 |
| GET/POST/PUT/DELETE | `/api/rules` | 规则 CRUD |
| POST | `/api/rules/test` | 规则试算，返回命中详情 |
| GET | `/api/audit?limit=&job=&action=` | 审计查询 |
| GET/PUT | `/api/settings` | 设置读写 |
| POST | `/api/simulator/inject` | 注入模拟 MSGW 消息 |
| POST | `/api/ibmi/test` | 测试 IBM i 连接 |

---

## 9. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 基础框架 | Spring Boot **2.7.18** | 2.x 最终维护版，官方支持 Java 8 |
| Java | **1.8** | 用户环境 JDK 1.8.0_202 |
| Web | `spring-boot-starter-web` | 内嵌 Tomcat，单 jar 部署 |
| 校验 | `spring-boot-starter-validation` | 规则表单校验 |
| JSON | Jackson（starter 自带） | 规则/配置/审计序列化 |
| 前端 | 原生 HTML/CSS/JS | 无 npm 构建，降低交付复杂度 |
| 存储 | 本地 JSON 文件（`./data/`） | 规则 `rules.json`、配置 `settings.json`、审计 `audit.jsonl` |
| IBM i | `net.sf.jt400:jt400:11.2`（打进 fat jar） | 用户指定 JTOpen；API 与 Java 8 兼容性已核对 |
| 构建 | Maven 3.9.11，`mvn clean package` | 环境已具备 |

---

## 10. 可靠性与安全设计

1. **默认 dry-run=true**：首次上线只观察决策是否正确，界面一键切换为实跑。
2. **幂等**：以 `jobName + user + jobNumber + messageKey` 为键，处理过的消息不再重复回复。
3. **并发**：单线程轮询，回复动作走有界线程池；同一 job 加本地锁，避免并发回复同一消息。
4. **重试与熔断**：回复失败按指数退避重试（默认 3 次）；连续连接失败达到阈值（默认 5 次）暂停轮询并告警，界面显示熔断状态。
5. **原子写**：规则与设置写入临时文件后 rename，避免写坏文件；审计日志 append-only。
6. **保守默认**：规则未命中时 `ALERT_ONLY`；`END_JOB` 默认禁用，只有规则显式配置且总开关开启才执行；cancel 后停止该 job 并告警。
7. **访问控制**：可配置 token，所有 `/api/**` 需带 token；默认仅监听内网地址。

---

## 11. 实施阶段（评审通过后执行）

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P1 | 项目骨架、数据模型、JSON 存储、模拟器 | 应用可启动，界面可打开 |
| P2 | 规则引擎 + 单元测试 | 各 matcher、优先级、默认动作、安全闸门全部通过 |
| P3 | 守卫服务（轮询、幂等、重试、熔断）+ REST API | 注入模拟消息 → 自动回复 → 审计完整 |
| P4 | Web 六个页面 | 规则增删改查、测试台、审计、设置可用 |
| P5 | JTOpen 适配器 + 现场联调 | 连接测试、只读扫描、dry-run、小范围实跑 |

> P1~P4 完全不依赖 IBM i 环境，可在本机完成；P5 需要现场 iSeries 配合。

---

## 12. 验证方案

- **单元测试**：6 种 matcher × 大小写/ANY/ALL 组合、优先级 first-match、JOB 通配、dry-run 降级、幂等、熔断状态机。
- **集成测试**：模拟器注入一条含 "object not found" 的 MSGW 消息 → 扫描 → 命中 IGNORE 规则 → 审计产生 `DRY_RUN` 记录 → 关闭 dry-run 后产生 `SUCCESS` 记录。
- **现场验证顺序**：只读连接测试 → 只读列出 MSGW job → dry-run 观察一周 → 选择单一低风险 job 实跑 → 逐步扩大。

---

## 13. 评审结论与现场联调待确认

评审已确认的 6 项见第 0 节。现场联调时还需确认（不影响本期实现）：

1. IBM i 版本与 job 消息队列对象所在库——队列路径模板默认 `/QSYS.LIB/{job}.MSGQ`，如不一致在设置页调整。
2. 现有程序实际接受的 reply 字符串——通过规则配置，无需改代码。
3. 服务账号权限：读 job 列表（含 `*JOBCTL`）、读消息队列、发送 reply；`END_JOB` 权限按需授予。
4. 是否需要开机自启（Windows 服务）——本期交付普通可执行 jar。
