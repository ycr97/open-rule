# M2-D0 静态校验记录

日期：2026-10-18。范围仅为文档、Schema/OpenAPI和fixture。M2运行能力尚未实现，MF/MS/MT运行验收仍为NOT_RUN。

## 基线

- 后端：`348a48d209f0571049535f4b513eef556859876b`。
- 前端：`1c520ca753213a8fea95bd1976887fe1a8c84c02`。
- 本轮只新增指定设计、实施计划、Proposed ADR和studio-m2契约资产。

## 命令

在后端仓库根目录执行：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
python3 -u docs/contracts/studio-m2/validate.py

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin:$PATH \
PYTHONDONTWRITEBYTECODE=1 sh scripts/validate-studio-m1.sh

git diff --exit-code
git diff --cached --exit-code
```

最后两个命令检查已跟踪文件和索引未变；新增契约文件另按允许路径核对，不用空diff证明新增内容正确。

## 结果

两组校验命令均以退出码 `0` 完成。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| M2 OpenAPI 结构与引用 | PASS | 8 个新增路径、12 个唯一 operation；本地引用可解析 |
| M2 Schema 编译与实例校验 | PASS | 31 个定义可编译；271 个有效实例通过；11 个结构反例被拒绝 |
| M2 行为资产一致性 | PASS | 30 组请求与配置组合；expected 未执行 |
| requiredFacts 与 checksum | PASS | 5 个去重路径、10 个原始使用位置；引用冻结的 M1 Definition |
| M2 语义反例索引 | PASS | 8 个语义反例的输入、操作与预期已关联；运行时拒绝结果未验证 |
| 数值精度与文档链接 | PASS | 大整数、长小数原文保留；33 个本地文档链接有效 |
| M1 Schema 与 OpenAPI | PASS | 5 个合法、4 个非法 fixture；8 个路径、10 个 operation；本地引用可解析 |
| M1 语义、checksum 与订单用例资产 | PASS | 1 个合法、3 个非法语义 fixture；legacy v1 原始字节 checksum 一致；10 个 checksum 向量一致；19 对订单输入与预期已关联 |
| 修改范围 | PASS | 后端和前端的已跟踪文件与索引未变；57 个新增文件均在本轮允许路径内 |

271 个有效实例包含 fixture 及行为用例展开后的请求、配置和目录实例，不等于 271 个运行测试。M2 Schema fixture 共 42 个：31 个结构合法、11 个结构非法。8 个语义反例属于结构合法实例，需后续实现拒绝。

后端已有 `output/` 和前端已有验收产物均保留。M2 的 30 个行为用例、8 个语义反例及 11 项工程验收仍为 `NOT_RUN`。本记录中的 PASS 仅覆盖静态契约检查，不替代真实 HTTP、Core、MySQL、浏览器或视觉验收。

## 静态校验修正

- 行为fixture到M1定义的相对路径修正为从当前fixture文件解析。
- 评分明细沿用M1 `SCORE_BIN`；新增错误`SCORE`反例以防误改协议。
- providerPatch明确保留null，避免TIMEOUT的response=null被当成删除字段。
- MS语义反例使用独立创建ID/path，避免重复资源错误掩盖目标错误。
- 请求deadline报告保留已完成的caller字段，仅终止未完成字段。
- 成功/错误体的totalElapsedMillis、配置存储不可用状态与文档保持一致。

## 未验证范围

本轮没有执行M2 Provider、Core补数、数据库配置事务或浏览器交互。行为fixture的expected是后续实现验收输入，不能视为Java执行结果。没有提交、push、合并或部署。
