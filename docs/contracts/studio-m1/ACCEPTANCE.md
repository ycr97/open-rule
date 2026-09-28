# Studio M1 验收映射

状态：预期结果已冻结为 D0 测试输入，**尚未由 Java 运行时执行**。基础定义为 [`fixtures/valid/order-admission.definition.json`](fixtures/valid/order-admission.definition.json)，完整事实和逐例预期在 [`fixtures/valid/order-admission.cases.json`](fixtures/valid/order-admission.cases.json)。该文件每条记录含 `id`、精确定义文件、完整或故意缺失的 facts、status、决策码、原因、分数、segment 和 Terminal 节点。`OA-03`、`OA-12` 使用独立 Definition 文件，避免在运行期暗改规则。正常 facts 中 `/risk/blacklisted`、`/buyer/ageDays` 和 `/metrics/refundRate` 均由调用方直接提供；没有 Provider。

| 验收编号 | Fixture / 期望重点 |
| --- | --- |
| OA-01 | 正常：APPROVE、15、LOW、approve |
| OA-02 | 新客高退款：REVIEW、75、MEDIUM、risk_review |
| OA-03 | 仅高退款率分箱 45→5：APPROVE、35；独立 score-35 定义 |
| OA-04 | 黑名单：REJECT，评分与后续节点 SKIPPED/TERMINATED |
| OA-05a/b | 金额 50000 REJECT；49999.99 APPROVE |
| OA-06a/b | 地区 XX、ZZ 均 REJECT |
| OA-07 | 缺失 refundRate：buyer_score FAILED/CONTINUE，data_review REVIEW；failure 说明 missing |
| OA-08 | 显式 null：buyer_score FAILED/CONTINUE，data_review REVIEW；failure 不得称 missing |
| OA-09 | amount 字符串：base_rules FAILED/ABORT，技术失败、decision=null |
| OA-10a/b | ageDays 30/29 且 refundRate 0.3：50 APPROVE / 75 REVIEW |
| OA-11a/b/c/d | amount 999/1000/4999/5000：LOW/MEDIUM/MEDIUM/HIGH |
| OA-12a/b | 专用分值 59/60：APPROVE/REVIEW；发布阈值仍是 60 |

ST 与契约资产对应关系：

| 编号 | D0 契约资产 / 后续实施验证 |
| --- | --- |
| ST-01 | `incomplete.draft.json` 通过 Draft Schema、未通过 Executable Schema；B2 保存/禁止发布 |
| ST-02～ST-06 | OpenAPI 的 revision CAS、发布、读取、模拟操作及 ADR 生命周期；B2 MySQL 事务/重启测试 |
| ST-07 | `parallel-terminal.definition.json`、`terminal-missing.definition.json` 及默认兜底编译约束；B1 编译测试 |
| ST-08 | `future-node.definition.json`、`parallel-output.definition.json`；B1 静态校验与运行保护 |
| ST-09 | OA-10/OA-12 分箱边界及运行时恰好命中一箱；B3 增加重叠/缺口执行测试 |
| ST-10 | OpenAPI 精确版本 LIVE 与 DRAFT SIMULATE；B2 API 集成测试 |
| ST-11 | `checksum-vectors.json`、OA-05b 小数边界；B1/B2 BigDecimal 和前端无损 JSON 适配 |
| ST-12 | `v1-priority.raw.json`/meta 原始摘要、发布 CAS 与损坏定义错误；B2 JDBC 集成测试 |
| ST-13 | ADR-0003 node timeout CONTINUE 与 request timeout 技术失败；B1 受控时钟测试 |

D0 校验脚本验证 Schema、OpenAPI 结构/引用、静态语义反例、checksum 向量和 OA 输入文件关联；上表的 Java 执行/HTTP/MySQL 行为由 B1/B2/B3/V1 验收，不能用 D0 静态检查代替。
