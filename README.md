# MSGW Guard

IBM i (AS/400) 批处理 MSGw 无人值守守卫。基于 **Spring Boot 2.7.18 + Java 8 + JTOpen 11.2**，通过可配置规则自动回答消息的 `MSGW` 作业，避免批处理链因人工回复而中断。

## 功能

- 发现指定 subsystem 中处于 `MSGW` 状态的 job，读取其 inquiry 消息。
- 规则引擎按优先级匹配：包含 / 不包含 / 等于 / 通配 / 正则 / 模糊相似度（本地 Levenshtein，不调用 AI）。
- 动作可配置：`IGNORE`、`CANCEL`、`RETRY`、`CUSTOM`、`ALERT_ONLY`、`END_JOB`。
- `CANCEL` 后停止处理该 job 并产生醒目告警，等待人工确认。
- `END_JOB` 必须规则显式配置且总开关打开才执行，默认关闭。
- 默认 `dry-run=true`，只决策不回复；未命中规则仅告警。
- 内置模拟器，无需连接 IBM i 即可验证规则与全链路。
- 内置单人使用的 Web 可视化界面。

## 构建与运行

```bash
mvn clean package
java -jar target/msgw-guard-1.0.0.jar
```

打开 <http://localhost:8088>。配置与审计数据默认保存在运行目录下的 `data/`，可通过启动参数覆盖：

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

## 上线前现场确认

1. 在“设置”填入 IBM i 主机名、用户、密码和要监控的 subsystem。
2. 先保持 `dry-run` 开启，只读观察扫描结果。
3. 确认 job 消息队列所在库，必要时修改 `messageQueuePathTemplate`（默认 `/QSYS.LIB/{job}.MSGQ`）。
4. 确认服务账号具备 `*JOBCTL` 及相关消息队列权限。
5. 小范围实跑后再关闭 `dry-run`。

## 设计文档

完整设计说明见 [`DESIGN.md`](DESIGN.md)。

## 许可

内部使用。
