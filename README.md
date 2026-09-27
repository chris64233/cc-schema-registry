# cc-schema-registry

管理数据契约主题、版本和兼容性规则。

## 主要业务规则

### 主题与契约

- 主题（subject）具有唯一名称和兼容模式：`BACKWARD`、`FORWARD` 或 `FULL`，创建时指定。
- 发布内容限定为对象契约：`properties` 中属性名映射到类型声明，类型仅支持
  `string`、`integer`、`number`、`boolean`。
- 每个属性可声明 `required`（布尔）；仅 `string` 属性可声明 `enum`（非空、不重复的字符串数组）；
  任何属性可声明 `default`，默认值必须与声明类型一致，且 string 属性的默认值必须属于其 enum。
- 重复属性、未知类型、未知字段、不一致声明（如 enum 用于非 string、默认值类型不匹配）一律拒绝，
  返回 `INVALID_CONTRACT`。

### 规范化与幂等

- 发布前契约被规范化：属性按名称排序、required/enum/default 以固定结构序列化、数字统一格式，
  因此属性顺序和空白不影响内容身份；规范化文本的 SHA-256 作为内容哈希。
- 同一主题提交语义相同的契约时返回已有版本（`created=false`），不新增记录。
- 发布可携带 `Idempotency-Key` 请求头：相同键提交相同契约返回已有版本；相同键提交不同契约返回
  `IDEMPOTENCY_CONFLICT`（409）。

### 兼容性检查

- `BACKWARD`（新契约能读取旧数据）：
  - 旧契约的 required 属性不能删除或改变类型；
  - 新增加的 required 属性必须声明明确的默认值；
  - string 属性的新 enum 必须包含旧 enum 的全部值。
- `FORWARD`：按相反方向应用同一组规则（旧契约能读取新数据）。
- `FULL`：两个方向必须同时满足。
- 兼容性始终针对主题的**全部历史版本**检查，而非只比较上一版。
- 不兼容时返回稳定错误码 `CONTRACT_INCOMPATIBLE`（422）及按（版本号、属性名、规则、方向）排序的
  确定性差异列表，首个差异即确定的首个冲突；失败不会写入半成品版本。

### 并发发布

- 发布在事务内对主题行加悲观写锁（`SELECT ... FOR UPDATE`），同一主题的并发发布被串行化：
  版本号连续且唯一，每次发布都基于包含更早并发提交的完整历史重新校验。
- 版本号从 1 开始递增；`(subject, version)` 与 `(subject, content_hash)` 均有唯一约束兜底。
- 每次发布都会记录兼容性检查依赖边：新版本针对当时每个存活历史版本做过检查（`from → to`）。
  该依赖边用于受控删除资格判定。

## 版本生命周期、消费者依赖与受控删除

### 版本生命周期

每个版本处于四种状态之一：`ACTIVE` → `DEPRECATION_SCHEDULED` → `DEPRECATED` → `TOMBSTONE`。

- 废弃请求把版本标记为**待废弃**并设置生效时间（`effectiveAt`，缺省为立即）与删除保留期
  （`retentionMillis`，默认 30 天）。
- 生效时间到达**且不存在有效消费者依赖**时，版本才进入**已废弃**：
  - 废弃请求在生效时间已到时，于同事务内立即尝试推进；
  - 否则由废弃扫描（`POST /api/subjects/deprecation-scans`，可由调度器周期调用）推进。
- 存在有效消费者依赖时不得进入已废弃，除非该消费者**迁移到其他版本**或其**租约过期**。

### 消费者依赖登记

- 消费者登记主题、正在使用的契约版本与租约到期时间（`leaseExpiresAt`）。
- 每次更新携带：
  - `updateSeq`：单调递增更新号，**迟到心跳/重放（seq 不大于已见值）被拒绝**
    （`STALE_UPDATE`），不能覆盖较新的依赖版本；
  - `expectedVersion`：版本条件，必须与当前登记版本一致，否则返回 `VERSION_CONDITION_MISMATCH`。
    迁移时用它做 compare-and-set（如当前为 v1，请求 `version=2, expectedVersion=1`）。
- 租约到期后依赖不再有效（记录仍保留以便审计），但不能再阻断废弃。
- 已废弃/已删除版本不能再登记新依赖（`LIFECYCLE_CONFLICT`）。
- 废弃扫描与消费者续租都在事务内对同一主题行加悲观写锁，因此二者并发时结果确定一致：
  要么续租先提交（扫描看到有效依赖，保持待废弃），要么扫描先提交（续租面对已废弃版本而失败）。

### 受控删除

已废弃版本只有同时满足以下条件才能删除：

1. 状态为 `DEPRECATED`；
2. 没有其他版本的兼容性检查依赖它（无入边；版本被删除时其出边一并移除）；
3. 没有活跃消费者；
4. 保留期届满（自 `deprecatedAt` 起经过 `retentionMillis`）。

删除是**受控**的：只把可变契约载荷置空，**内容摘要（SHA-256）、版本号、生命周期时间戳与
审计记录继续保留**（状态变为 `TOMBSTONE`，版本查询中 `contract` 为空、`payloadPresent=false`）。
相同契约再次发布时，仍凭内容哈希映射回同一版本号，不新建版本。

### 幂等

三类请求号分别独立幂等（均通过 `Idempotency-Key` 头传入），作用域为主题：

- **废弃请求号**：相同号重放返回同一废弃结果；同号携带不同生效时间/保留期/版本返回
  `IDEMPOTENCY_CONFLICT`。
- **消费者更新号**：作用域细化到（主题、消费者），相同号重放返回首次登记结果。
- **删除请求号**：相同号重放返回同一删除结果；同号用于不同版本返回 `IDEMPOTENCY_CONFLICT`。

### 解释查询

- `GET .../versions/{v}/lifecycle`：完整生命周期视图——状态、各时间点、**阻断废弃的消费者**、
  兼容性引用方、**删除资格及逐条原因**、只追加的审计事件。
- `GET .../versions/{v}/deletion-eligibility`：仅返回删除资格与原因（无论当前处于何状态）。
- `GET .../consumers`：列出主题下全部消费者依赖及其是否仍在租约有效期内。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/subjects` | 创建主题 `{name, compatibility}` |
| GET | `/api/subjects` / `/api/subjects/{name}` | 查询主题 |
| POST | `/api/subjects/{name}/versions` | 发布契约（请求体为契约 JSON，可带 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions` / `.../versions/{v}` | 查询版本列表 / 单个版本（含生命周期；墓碑 `contract` 为空） |
| POST | `/api/subjects/{name}/compatibility/check` | 对契约做兼容性预检，返回差异列表 |
| POST | `/api/subjects/{name}/consumers/{consumer}` | 登记/更新消费者依赖，可带 `Idempotency-Key` 头 |
| GET | `/api/subjects/{name}/consumers` | 查询消费者依赖及租约有效性 |
| POST | `/api/subjects/{name}/versions/{v}/deprecations` | 请求废弃（`{effectiveAt?, retentionMillis?}`，可带 `Idempotency-Key` 头） |
| POST | `/api/subjects/deprecation-scans` | 推进全部到期且无消费者阻断的待废弃版本 |
| POST | `/api/subjects/{name}/versions/{v}/deletions` | 受控删除（可带 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions/{v}/lifecycle` | 生命周期、阻断消费者、删除资格、审计 |
| GET | `/api/subjects/{name}/versions/{v}/deletion-eligibility` | 删除资格解释 |

错误响应统一为 `{code, message, details, diffs}`，稳定错误码包括 `SUBJECT_NOT_FOUND`、
`SUBJECT_EXISTS`、`VERSION_NOT_FOUND`、`INVALID_CONTRACT`、`CONTRACT_INCOMPATIBLE`、
`IDEMPOTENCY_CONFLICT`、`INVALID_REQUEST`、`LIFECYCLE_CONFLICT`、
`VERSION_CONDITION_MISMATCH`、`STALE_UPDATE`、`DELETE_NOT_ELIGIBLE`。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test
