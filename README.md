# MSGW Guard

IBM i (AS/400) 批处理 MSGw 无人值守守卫。基于 **Spring Boot 2.7.18 + Java 8 + JTOpen 11.2**，通过可配置规则自动回答消息的 `MSGW` 作业，避免批处理链因人工回复而中断。

## 功能

- 通过 `QSYS2.ACTIVE_JOB_INFO` 在 IBM i 服务端按 subsystem 和 `MSGW` 状态筛选 job（用户已批准该方案），避免拉回全量 job 再客户端过滤；SQL 不可用时自动回退 JTOpen `JobList`。
- 规则引擎按优先级匹配：包含 / 不包含 / 等于 / 通配 / 正则 / 模糊相似度（本地 Levenshtein，不调用 AI）。
- 动作可配置：`IGNORE`、`CANCEL`、`RETRY`、`CUSTOM`、`ALERT_ONLY`、`END_JOB`。
- `CANCEL` 后停止处理该 job 并产生醒目告警，等待人工确认。
- `END_JOB` 必须规则显式配置且总开关打开才执行，默认关闭。
- 默认自动扫描并按规则回复（`dry-run=false`）；需要只读验证时可临时开启 `dry-run`；未命中规则仅告警。
- 内置模拟器，无需连接 IBM i 即可验证规则与全链路。
- 内置单人使用的 Web 可视化界面。

## 构建与运行

```bash
mvn clean package
java -jar target/msgw-guard-1.0.0.jar
```

打开 <http://localhost:8088>。配置与审计数据默认保存在运行目录下的 `data/`，运行日志在 `data/guard.log`（同时输出到控制台），可通过启动参数覆盖：

```bash
java -Dmsgw.guard.data-dir=/path/to/data -jar target/msgw-guard-1.0.0.jar
```

## 界面页面

| 页面 | 用途 |
|---|---|
| 概览 | 运行状态、当前 MSGW job、停止 job、告警 |
| 规则管理 | 新增/编辑/删除规则，配置匹配方式与回复 |
| 固定回复 | 按 job 名（支持 `*` 通配）指定无条件回复 |
| 规则测试台 | 输入假消息，查看命中规则与决策结果 |
| 审计日志 | 每次扫描与回复的完整记录 |
| 设置 | 服务器、用户、密码、subsystem、消息队列模板、dry-run 等 |
| 接入指南 | JTOpen 权限、API 说明与现场联调步骤 |

## 扫描不到 job 时怎么排查

在“设置”页填完连接参数后请点“保存设置”（仅“测试连接”不会保存）。然后用“诊断扫描”按钮查看：

- 连接是否成功、活动 job 总数、各状态分布；
- MSGW job 数量，以及按 subsystem 过滤后剩余数量；
- 前 20 个活动 job 的样例，用来确认 subsystem 名称。

如果 MSGW 有值但过滤后为 0，检查 subsystem 配置（支持 `*` 通配，如 `QUSR*`）。详细日志见 `data/guard.log`，其中会显示本次使用的是 SQL 还是回退的 JobList。

## 上线前现场确认

1. 在“设置”填入 IBM i 主机名、用户、密码和要监控的 subsystem。
2. 默认已关闭 `dry-run`，会自动回复；如需先只读验证，可临时开启 `dry-run`，确认规则后再关闭（概览页有快捷按钮）。
3. 确认 job 消息队列所在库，必要时修改 `messageQueuePathTemplate`（默认 `/QSYS.LIB/{job}.MSGQ`）。
4. 确认服务账号具备 `*JOBCTL` 及相关消息队列权限。
5. 小范围实跑后再关闭 `dry-run`。

## 设计文档

完整设计说明见 [`DESIGN.md`](DESIGN.md)。

## 许可

内部使用。
