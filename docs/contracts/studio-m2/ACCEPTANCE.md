# Studio M2 验收矩阵

> 2026-10-28：当前实际工作区的运行结论见 [M2-V1总体验收](V1-ACCEPTANCE.md)。下文D0状态和矩阵保留为原始阶段背景，冻结fixture和预期不变。

状态：**M2-V1运行验收通过**，逐项结果和层级见上方报告。D0原阶段只检查文件、Schema和引用一致性，当时运行验收为NOT_RUN。MF输入见 [行为fixture](fixtures/order-admission.cases.json)，MS输入见 [语义fixture](fixtures/semantic.cases.json)。

层级：D=静态契约；J=Java单元/受控时钟/MockMvc；I=真实MySQL；H=实际HTTP服务；B=真实浏览器；V=原型视觉对照。MockMvc不能标H，MockProvider不能替代真实HTTP超时证据。

## MF：订单事实补全

| ID | 重点 | 后续验证层级 |
| --- | --- | --- |
| MF-01 | 一次POST补齐三个字段，APPROVE/15 | J/H/B |
| MF-02 | 完整facts，HTTP次数0 | J/H/B |
| MF-03 | 已有ageDays=12不被365覆盖，得40分 | J/H |
| MF-04 | 显式refundRate=null不补数，Core类型失败转REVIEW | J/H/B |
| MF-05 | partial保留成功字段，缺退款率转REVIEW | J/H/B |
| MF-06 | Provider超时、请求仍有预算，Core数据复核 | J/H/B |
| MF-07 | 缺buyer/id，不发HTTP，原Core复核 | J/H |
| MF-08 | 响应退款率字符串被拒绝 | J/H/B |
| MF-09 | 总deadline先到，HTTP504，未进入Core | J/H/B |
| MF-10 | Mock成功，HTTP和secret解析次数均0 | J/H/B |
| MF-11 | HTTP503不回退Mock、不重试 | J/H |
| MF-12 | 停用Provider不发请求 | J/H/B |
| MF-13 | 父metrics=null不替换，其余字段仍补全 | J/H |
| MF-14 | 父metrics为标量不替换 | J/H |
| MF-15 | 未允许的真实模拟不发HTTP | J/H/B |
| MF-16 | LIVE指定PUBLISHED版本，通过真实HTTP补数 | J/H |
| MF-17 | 响应null为类型错误，区别响应missing | J/H |
| MF-18 | 原输入amount字符串，Core ABORT，无decision | J/H/B |
| MF-19 | Mock TIMEOUT，无真实等待/HTTP，Core复核 | J/H/B |
| MF-20 | 映射buyer/id=null不发请求 | J/H |
| MF-21 | 原M1缺失输入不受新目录影响 | J/H/B |
| MF-22 | 原M1完整输入保持APPROVE/15 | J/H/B |
| MF-23 | secret缺失，不发HTTP、不回退Mock | J/H |
| MF-24 | 已有blacklisted=true保留，静态预取后REJECT | J/H |
| MF-25 | 父metrics为数组不替换 | J/H |
| MF-26 | 非法JSON响应，字段失败且不重试 | J/H |
| MF-27 | 重复响应键拒绝，不使用最后一个值 | J/H |
| MF-28 | Mock ERROR不触网 | J/H |
| MF-29 | 连接错误保留字段错误，Core复核 | J/H |
| MF-30 | LIVE绑定MOCK配置，无HTTP/Mock执行 | J/H |

每例检查最终决策、失败字段、来源、实际Provider调用数、配置revision及统一traceId。MF-09使用受控延迟，保留超时与取消后的上游/许可证据。任何预期为REVIEW的例子都必须证明Terminal实际执行。

## MS：管理语义反例

| ID | Schema之后必须拒绝的行为 | 后续层级 |
| --- | --- | --- |
| MS-01 | Catalog写路径包含数字token | J/H |
| MS-02 | Catalog写路径含危险名 | J/H |
| MS-03 | binding引用不存在的Provider | I/H |
| MS-04 | connectTimeout大于readTimeout | J/H |
| MS-05 | 路径与已有Catalog互为前缀 | I/H |
| MS-06 | 修改既有事实type | I/H/B |
| MS-07 | expectedRevision与当前revision不一致 | I/H/B |
| MS-08 | revision超出BIGINT范围 | J/H |

## MT：工程与完整性

| ID | 场景与必须证据 | 层级 |
| --- | --- | --- |
| MT-01 | 五种节点/Stage.when、嵌套all/any/not、presence-only、重复路径、根/转义/数组读；VARIABLE/NODE/scoreRef排除；原文索引与稳定ID在重排后正确 | J/H |
| MT-02 | 同一路径类型提示冲突只诊断；不因静态缺失拒绝短路后未访问字段；presence显式补数与M1无补数分别测试 | J/H |
| MT-03 | 两Provider排序串行，一Provider多字段一次；第二Provider的映射不读第一Provider补值；无绑定字段不发请求 | J/H |
| MT-04 | Provider局部超时与请求总deadline同时到达时请求失败优先；补数后Core剩余预算不足；取消恢复中断位；迟到body不写快照；上限拒绝、许可释放正确 | J/H |
| MT-05 | POST重复ID/path、PUT CAS一胜一冲突；目录空表/已有表的前缀并发不双成功；索引列/document事务一致；引用受FK保护 | I/H |
| MT-06 | 请求读快照后并发修改绑定/Provider，当前请求仍使用旧完整快照；下一请求使用新revision；数据库事务不跨HTTP调用 | I/H |
| MT-07 | Java/MySQL重启配置恢复；原M1 PUBLISHED checksum不变；错误配置不污染已保存版本；凭证仅保存引用 | I/H |
| MT-08 | 39位小数、大于2^53整数在常量、Mock、HTTP响应、DB、Vue保存中保持原值；128位数字边界；无double/Number转换；body长度边界和慢body超时 | J/I/H/B |
| MT-09 | 敏感/未知字段默认屏蔽；错误及traceId一致；所有新入口的结果投影不泄漏原始facts/凭证/上游body；输入值null和类型失败不混淆 | J/H/B |
| MT-10 | 保存刷新、响应丢失后GET核对、409保留输入、未保存禁预览、结果过时、迟到响应、默认M1模式、LIVE不能Mock；1440×900/1920×1080/窄屏真实视觉 | B/V |
| MT-11 | 冻结M1全部19个OA子用例与ST-01～13回归；M2 request不被误投M1；旧v1读取和checksum守卫仍通过 | J/I/H/B |

所有行在D0阶段均为NOT_RUN。后续报告逐行列出状态、命令、代码版本、实际输入/输出和证据路径。只有全部必验项PASS才声明Studio M2验收通过。
