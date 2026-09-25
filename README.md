# cc-schema-registry

管理数据契约主题、版本和兼容性规则。

## 主要业务规则

### 主题与契约

- 主题（subject）具有唯一名称和兼容模式：`BACKWARD`、`FORWARD` 或 `FULL`，创建后不可变更。
- 发布内容限定为对象契约：`{"properties": {"名称": {"type": ..., "required": ..., "enum": ..., "default": ...}}}`。
- 属性类型仅支持 `string`、`integer`、`number`、`boolean`；`required` 缺省为 `false`；
  `enum` 仅允许 `string` 属性声明（值必须为非空且不重复的字符串）；`default` 的类型必须与声明类型一致，
  且若声明了 `enum`，默认值必须属于枚举值之一。
- 重复属性名、未知类型、未知字段及上述不一致声明一律拒绝（`CONTRACT_INVALID`）。

### 规范化与幂等

- 发布前契约被规范化：属性按名称排序、键序固定、去除空白、数字默认值归一化，
  规范化文本的 SHA-256 即契约的内容身份。属性顺序和空白差异不影响内容身份。
- 同一主题提交语义相同的契约时返回已有版本（HTTP 200），不新增记录。
- 发布可携带 `Idempotency-Key` 请求头：相同幂等键提交相同契约返回已有版本；
  相同幂等键提交不同契约返回冲突（`IDEMPOTENCY_KEY_CONFLICT`，HTTP 409）。

### 兼容性检查

- `BACKWARD`（新契约能读取旧数据）：
  - 旧版本的 required 属性不能被删除或改变类型；
  - 新契约相对旧版本新增的 required 属性必须声明明确的默认值；
  - string 属性的新 enum 必须包含旧枚举值（旧契约未声明 enum 时不允许新增 enum 限制）。
- `FORWARD` 按相反方向执行同一组检查（旧契约能读取新数据）。
- `FULL` 必须同时满足两个方向。
- 检查针对主题的**全部历史版本**执行，而非只比较上一版。
- 不兼容时返回稳定错误 `CONTRACT_INCOMPATIBLE`（HTTP 422），差异按
  （历史版本号、属性名、规则码）确定性排序，响应中携带首个差异 `firstDiff` 与全部差异 `diffs`；
  校验失败不会写入任何半成品版本。

### 并发与版本号

- 发布流程在事务内对主题行加悲观写锁（JPA `PESSIMISTIC_WRITE`，H2 `SELECT ... FOR UPDATE`），
  同一主题的并发发布被串行化。
- 每个版本都在锁内基于包含更早并发提交的完整历史重新校验，版本号从 1 开始连续且唯一；
  数据库唯一约束（主题 + 版本号、主题 + 内容哈希、主题 + 幂等键）作为兜底。

## API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/subjects` | 创建主题（`name` + `compatibilityMode`） |
| GET | `/api/subjects` / `/api/subjects/{name}` | 主题查询 |
| POST | `/api/subjects/{name}/versions` | 发布契约（请求体为契约 JSON，可选 `Idempotency-Key` 头） |
| GET | `/api/subjects/{name}/versions` / `.../versions/{version}` | 版本查询 |
| POST | `/api/subjects/{name}/compatibility-checks` | 兼容性差异查询（dry-run，不写入） |

错误响应统一为 `{"code", "message", "firstDiff?", "diffs?"}`，`code` 取值稳定：
`CONTRACT_INVALID`、`CONTRACT_INCOMPATIBLE`、`SUBJECT_NOT_FOUND`、`VERSION_NOT_FOUND`、
`SUBJECT_ALREADY_EXISTS`、`IDEMPOTENCY_KEY_CONFLICT`、`VALIDATION_FAILED`、`INVALID_REQUEST`、
`CONCURRENT_MODIFICATION`。

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
