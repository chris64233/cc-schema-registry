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

### 消费者依赖登记

- 消费者通过 `PUT /api/subjects/{name}/consumers/{consumerId}` 登记：正在使用的契约版本
  `version`、单调递增的更新序号 `updateSeq`、租约到期时间 `leaseExpiresAt`，可选幂等键
  `idempotencyKey`。
- 更新带版本条件：仅当 `updateSeq` 严格大于已存序号时才生效；较小或相等序号的迟到心跳
  不会覆盖较新的依赖（返回 `applied=false, stale=true` 及当前状态）。
- 消费者更新号幂等：相同幂等键重放相同请求返回当前状态；相同键携带不同参数返回
  `IDEMPOTENCY_CONFLICT`（409）。
- 已废弃（DEPRECATED）或已删除的版本拒绝新的依赖登记/续租（409），消费者必须迁移。

### 版本废弃

- 生命周期：`ACTIVE → DEPRECATING → DEPRECATED`；删除只是附加状态（`deletedAt`），不改变该枚举。
- `POST .../versions/{v}/deprecation` 登记废弃请求：`effectiveAt` 为生效时间，
  `retentionSeconds` 为删除保留期，`requestKey` 为废弃请求号（幂等：同键同参重放返回当前状态，
  同键不同参返回 409）。
- `POST .../deprecation-scan` 执行废弃扫描：生效时间已到、且不存在有效消费者依赖
  （全部迁移或租约过期）的 DEPRECATING 版本进入 DEPRECATED。
- 并发一致性：消费者续租/迁移、废弃扫描、删除都先对主题行加悲观写锁，扫描与续租被串行化——
  要么续租先提交（扫描看到有效租约，保持 DEPRECATING），要么扫描先提交（版本已废弃，
  后续续租被拒绝），不会出现中间态。

### 受控删除

- `DELETE .../versions/{v}`（可带 `Idempotency-Key` 头作为删除请求号）。仅当同时满足：
  1. 版本已废弃（DEPRECATED）；
  2. 保留期届满（`deprecatedAt + retentionSeconds <= now`）；
  3. 该版本上没有未过期租约的消费者；
  4. 没有更晚版本的兼容性检查依赖（即不存在更新的未删版本——更晚版本发布时基于本版本契约
     校验过，本版本契约须保留）。
- 删除只清除可变载荷（`content` 置空）：版本号、内容哈希、审计记录全部保留；
  已删载荷的版本不再参与兼容性历史检查。
- 删除请求号幂等：同键重放返回原删除结果；已删版本携带新键删除返回 `VERSION_DELETED`（409）。
- 不满足条件时删除返回 `DELETION_NOT_ELIGIBLE`（409），具体原因可通过资格查询接口获取。

### 生命周期与审计查询

- 版本生命周期事件（PUBLISHED、DEPRECATION_REQUESTED、DEPRECATED、DELETED）写入审计表，
  版本删除后审计记录继续保留。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/subjects` | 创建主题 `{name, compatibility}` |
| GET | `/api/subjects` / `/api/subjects/{name}` | 查询主题 |
| POST | `/api/subjects/{name}/versions` | 发布契约（请求体为契约 JSON，可带 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions` / `.../versions/{v}` | 查询版本列表 / 单个版本（已删版本 `contract` 为 null） |
| POST | `/api/subjects/{name}/compatibility/check` | 对契约做兼容性预检，返回差异列表 |
| PUT | `/api/subjects/{name}/consumers/{consumerId}` | 登记/更新消费者依赖 `{version, updateSeq, leaseExpiresAt, idempotencyKey?}` |
| GET | `/api/subjects/{name}/consumers` | 查询主题的消费者依赖列表 |
| POST | `/api/subjects/{name}/versions/{v}/deprecation` | 登记废弃请求 `{effectiveAt, retentionSeconds, requestKey?}` |
| POST | `/api/subjects/{name}/deprecation-scan` | 执行废弃扫描，返回本次废弃的版本号 |
| DELETE | `/api/subjects/{name}/versions/{v}` | 受控删除（可带 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions/{v}/lifecycle` | 版本生命周期查询 |
| GET | `/api/subjects/{name}/versions/{v}/deprecation-blockers` | 阻断废弃的消费者列表 |
| GET | `/api/subjects/{name}/versions/{v}/deletion-eligibility` | 删除资格解释（eligible + reasons） |
| GET | `/api/subjects/{name}/versions/{v}/audit` | 版本审计记录 |

删除资格原因码：`VERSION_NOT_DEPRECATED`、`RETENTION_PERIOD_NOT_ELAPSED`、
`ACTIVE_CONSUMERS_PRESENT`、`COMPATIBILITY_DEPENDENTS_PRESENT`、`ALREADY_DELETED`。

错误响应统一为 `{code, message, details, diffs}`，稳定错误码包括 `SUBJECT_NOT_FOUND`、
`SUBJECT_EXISTS`、`VERSION_NOT_FOUND`、`INVALID_CONTRACT`、`CONTRACT_INCOMPATIBLE`、
`IDEMPOTENCY_CONFLICT`、`INVALID_REQUEST`、`VERSION_DEPRECATED`、`VERSION_DELETED`、
`DELETION_NOT_ELIGIBLE`。

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
