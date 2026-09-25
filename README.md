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

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/subjects` | 创建主题 `{name, compatibility}` |
| GET | `/api/subjects` / `/api/subjects/{name}` | 查询主题 |
| POST | `/api/subjects/{name}/versions` | 发布契约（请求体为契约 JSON，可带 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions` / `.../versions/{v}` | 查询版本列表 / 单个版本 |
| POST | `/api/subjects/{name}/compatibility/check` | 对契约做兼容性预检，返回差异列表 |

错误响应统一为 `{code, message, details, diffs}`，稳定错误码包括 `SUBJECT_NOT_FOUND`、
`SUBJECT_EXISTS`、`VERSION_NOT_FOUND`、`INVALID_CONTRACT`、`CONTRACT_INCOMPATIBLE`、
`IDEMPOTENCY_CONFLICT`、`INVALID_REQUEST`。

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
