# Design

## Context

动机见 proposal.md。与实现方式相关的既有约定：

- 表结构用 Flyway 迁移脚本管理（`src/main/resources/db/migration`，当前最新为 `V3`），本次为 `V4`。
- 控制器返回 `RestResult<T>`，可预期的业务错误用"携带 HTTP 状态码的未受检异常 +
  `GlobalExceptionHandler` 分支"表达（参照 `FileStorageException`）。
- 单表 CRUD 用 MyBatis-Plus `BaseMapper<T>` + `@Mapper`，无需 XML；状态用字符串常量类
  （参照 `OutboxEventStatus` / `FileStatus`）。
- 模块按功能分包（参照 `file`、`outbox`），本次新增 `com.example.template.transfer`。
- 仓库已有 Redis 分布式锁 `DistributedLockService`（非可重入、带自动续期、按持有者身份释放，
  键前缀由 `template.redis-lock.key-prefix` 统一配置），本次直接复用作为幂等锁，不改动它。
  其接口注释与 spec 已明确："调用方仍应通过幂等、唯一约束或条件更新保护业务数据"——
  即锁不能作为唯一防线，这决定了下面的两层结构。

## Goals / Non-Goals

**Goals:**
- 用"Redis 幂等锁 + MySQL 唯一索引"两层防护把接口幂等做"对"：串行重试、并发重复、
  同键不同参三种情况都有确定行为，并讲清每一层各自负责什么、缺了哪层会出什么问题。
- 转账本身在并发下正确：不透支、不死锁、扣款入账原子。
- 代码结构清晰可照搬，关键点（为什么这么写）用中文注释讲透。

**Non-Goals:**
- 不做通用的幂等注解/切面/拦截器框架——本次幂等逻辑直接写在转账业务里，先把一个场景讲清楚。
- 不做跨服务/跨库转账、分布式事务、对账、冲正、限额风控。
- 不做鉴权（谁能操作哪个账户），不做开户/充值/余额查询/流水查询接口。
- 不记录失败流水：业务失败整体回滚，不落库（对应 spec"失败请求不占用幂等键"）。
- 不做幂等键过期清理：流水本身就是幂等记录，永久保留。

## Decisions

### 1. 幂等键由调用方生成，通过请求头 `Idempotency-Key` 传递

调用方为"一次业务意图"生成一个唯一值（如 UUID），重试时原样带上。

- 为什么不用服务端下发 token（先取 token 再提交）：多一次往返，且 token 通常放 Redis，
  引入"Redis 删 token 成功但数据库事务失败"的不一致窗口；对资金接口不如数据库唯一约束可靠。
- 为什么放请求头而不是请求体字段：这是业界通行约定（Stripe、支付宝开放平台等），
  幂等键属于"请求的元信息"而非业务数据，放头里也便于将来抽成通用拦截器。
  控制器用 `@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64)` 接收，
  缺失/超长由现有的全局异常处理转成 400。

### 2. 两层幂等防护：Redis 幂等锁挡并发，数据库唯一索引定结果

| 层 | 机制 | 负责什么 | 不负责什么 |
|---|---|---|---|
| 第一层 | Redis 幂等锁（按幂等键） | 同一幂等键同一时刻只放行一个请求；并发重复请求在入口被快速拒绝，不占数据库连接、不在唯一索引上排队 | 不保证"只执行一次"——锁可能因租期、主从切换、进程长暂停而提前失效 |
| 第二层 | `transfer_order.idempotency_key` 唯一索引 | "只执行一次"的最终保证，同时保存首次结果供重放 | 不负责快速失败 |

转账流水表同时充当幂等记录表："写流水"与"改余额"在同一个本地事务里提交，幂等记录和业务结果
天然原子——不存在"记录了幂等键但钱没转"或反过来的中间态。Redis 里只放锁，不放幂等结果。

- 备选：只用 Redis（`SETNX` 记录"已处理"标记）。否决：Redis 与 MySQL 无法原子提交，
  会出现"标记写了但事务回滚"或"事务提交了但标记丢失"，资金接口不可接受。
- 备选：只用数据库唯一索引。可行且正确，但并发重复请求全部打到数据库、在唯一索引上阻塞到
  先到事务提交；加锁后这类请求在 Redis 一次往返内就被拒绝。
- 备选：独立的幂等记录表。否决：本场景流水表已经能承担，独立表只在做通用框架时才有价值。

### 3. 幂等锁的用法

- **锁键**：`transfer:idempotent:{幂等键}`（实际 Redis 键再由锁服务拼上全局前缀）。
  幂等键已在入口校验非空且不超过 64 字符，直接拼接即可。
- **零等待**：调用 `executeWithLock(key, Duration.ZERO, leaseTime, action)`。抢不到锁时锁服务抛
  `LockAcquisitionException`，转换为 409"请求正在处理中，请勿重复提交"。不等待的原因：
  重复请求的正确结局是"拿到首次结果"，而首次结果要等先到的请求做完，让调用方稍后重试
  比占着容器线程等待更合适。
- **租期**：使用锁服务默认租期（30 秒，带自动续期），远大于一次转账事务的耗时。
- **锁必须包住整个事务**：`executeWithLock` 的回调里才调用带 `@Transactional` 的执行器，
  因此顺序是"加锁 → 开事务 → 提交 → 释放锁"。若反过来在事务内加锁、提交前就释放，
  后到的请求拿到锁时查不到尚未提交的流水，会重复执行——这是幂等锁最常见的错误用法，
  代码注释里要点明。
- **异常处理**：
  - `LockAcquisitionException` → 409（处理中）。
  - Redis 故障（锁服务原样传播）→ 不捕获，落入全局兜底返回 500；**不降级为无锁执行**。
  - `LockOwnershipLostException`（回调已执行完但释放时发现锁已失效）→ 不捕获，返回 500。
    此时转账事务可能已提交，调用方按约定用同一幂等键重试即可拿到重放结果，不会重复扣款。
  - `InterruptedException` → 恢复线程中断标志后抛出运行时异常，返回 500。

### 4. 处理流程：加锁 → 先查 → 事务内先插流水 → 唯一键冲突后回查

服务层拆成两个 Bean，避免同类自调用导致 `@Transactional` 失效：

- `TransferService`（无事务，编排幂等流程：加锁、查重、重放）
- `TransferExecutor`（`@Transactional`，执行一次真正的转账）

`TransferService.transfer(idempotencyKey, request)` 的步骤：

1. **加锁**：按决策 3 零等待获取幂等锁，失败即 409。以下步骤都在锁内执行。
2. **查重**：按幂等键查流水。查到 → 走第 5 步的"重放"逻辑。
3. **执行**：调用 `TransferExecutor.execute(...)`，在一个事务内依次：
   1. 校验转出/转入账户不同（400）、两个账户都存在（404）；
   2. **先插入流水**（状态 `SUCCESS`）。正常情况下锁已保证此处无并发；锁提前失效时，
      并发的相同幂等键请求会阻塞在唯一索引上，等先到的事务提交后收到 `DuplicateKeyException`；
   3. 扣减转出账户、增加转入账户（见决策 5）；余额不足抛业务异常（422），整个事务回滚，
      流水也随之回滚，幂等键不被占用。
4. **冲突回查（兜底路径）**：捕获 `DuplicateKeyException` 后（此时执行事务已回滚结束），
   再按幂等键查一次流水，走"重放"逻辑。在事务之外回查，保证读到的是对方已提交的数据，
   不受可重复读快照影响。
5. **重放**：比较已有流水与本次请求的转出账户、转入账户、金额（`BigDecimal.compareTo`，
   忽略 `100` 与 `100.00` 的标度差异）。一致 → 返回已有流水对应的响应；不一致 → 抛 409
   "幂等键已被其他请求使用"。
6. **释放锁**：回调返回或抛异常后由 `executeWithLock` 负责释放。

先插流水再动余额的原因：兜底路径下让重复请求在"第一条写语句"就被唯一索引拦下，
冲突事务里没有任何余额变更需要回滚，也不会白白持有账户行锁。

- 备选：有了锁就去掉唯一索引和 `DuplicateKeyException` 处理。否决：见决策 2，锁不是严格互斥。
- 备选：不加锁也不靠唯一索引，只做"先查后做"。否决：两个并发请求会同时查不到，然后都执行。

### 5. 余额变更用条件更新 + 固定加锁顺序

幂等锁只按幂等键互斥，不同幂等键操作同一账户的并发仍然存在，账户余额的并发安全由本决策保证。

- 扣款：`UPDATE transfer_account SET balance = balance - #{amount} WHERE account_no = #{no}
  AND balance >= #{amount}`，影响行数为 0 即余额不足。判断和扣减在一条语句内完成，
  不需要 `SELECT ... FOR UPDATE`，也不需要乐观锁版本号重试。
- 入账：`UPDATE ... SET balance = balance + #{amount} WHERE account_no = #{no}`。
- **加锁顺序**：A→B 与 B→A 两笔转账并发时，若各自"先扣转出再加转入"，会以相反顺序锁两行
  而死锁。因此按账户号字典序决定两条 `UPDATE` 的执行先后（账户号小的先更新），
  与谁是转出方无关。
- 这两条 SQL 用 Mapper 接口上的 `@Update` 注解书写（单条短 SQL，不值得为此新建 XML）。

### 6. 表结构（`V4__create_transfer_tables.sql`）

`transfer_account` 账户表：

| 列名 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT PK | 主键 |
| account_no | VARCHAR(32) NOT NULL, UNIQUE | 账户号 |
| account_name | VARCHAR(64) NOT NULL | 户名 |
| balance | DECIMAL(18,2) NOT NULL DEFAULT 0 | 余额（元） |
| create_time / update_time | DATETIME NOT NULL | 创建/更新时间 |

`transfer_order` 转账流水表：

| 列名 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT PK | 主键 |
| transfer_no | VARCHAR(32) NOT NULL, UNIQUE | 对外流水号（服务端生成的 32 位无横线 UUID） |
| idempotency_key | VARCHAR(64) NOT NULL, **UNIQUE** | 调用方幂等键，幂等判定的唯一依据 |
| from_account_no / to_account_no | VARCHAR(32) NOT NULL | 转出/转入账户号 |
| amount | DECIMAL(18,2) NOT NULL | 转账金额 |
| status | VARCHAR(16) NOT NULL | 目前只有 `SUCCESS`，预留给将来扩展 |
| remark | VARCHAR(255) | 备注，可空 |
| create_time | DATETIME NOT NULL | 创建时间 |

- 金额用 `DECIMAL` / `BigDecimal`，不用浮点。
- 迁移脚本末尾预置两个演示账户：`ACC1001`（余额 10000.00）、`ACC1002`（余额 5000.00），
  方便启动后直接在 Swagger/curl 里试。
- 幂等键作用域是全局（不按账户/用户分区），因为模板没有调用方身份体系。

### 7. 接口与 DTO

- `POST /api/transfers`，请求头 `Idempotency-Key`，请求体 `TransferRequest`
  （`fromAccountNo` / `toAccountNo` / `amount` / `remark`，Bean Validation 注解校验；
  金额用 `@NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2)`）。
- 响应 `RestResult<TransferResponse>`（`transferNo` / `idempotencyKey` / `fromAccountNo` /
  `toAccountNo` / `amount` / `status` / `createTime`）。DTO 用 `record`，与
  `FileUploadResponse` 等现有写法保持一致（实现时以现有代码的实际形态为准）。
- 首次执行与重放都返回 HTTP 200，响应体一致；调用方无需区分。
- "转出转入相同"是跨字段规则，放在服务层校验并抛 400，不为它单写一个类级校验注解。

### 8. 错误处理

新增 `TransferException`（未受检，携带 `HttpStatus` + 提示信息，形态同 `FileStorageException`），
`GlobalExceptionHandler` 新增对应分支。映射：

| 情况 | HTTP | 提示信息 |
|---|---|---|
| 转出转入相同 | 400 | 转出账户与转入账户不能相同 |
| 账户不存在 | 404 | 账户不存在 |
| 相同幂等键的请求正在处理（抢锁失败） | 409 | 请求正在处理中，请勿重复提交 |
| 幂等键已用于内容不同的请求 | 409 | 幂等键已被其他请求使用 |
| 余额不足 | 422 | 账户余额不足 |
| Redis 故障 / 锁所有权丢失 | 500 | 走全局兜底的固定提示 |

`LockAcquisitionException`、`DuplicateKeyException` 只在 `TransferService` 内部消化，
不会漏到全局处理器。

### 9. 测试

- `@SpringBootTest` 集成测试（依赖本地 MySQL 与 Redis，与仓库现有测试一致），每个用例自建独立
  账户号和幂等键，避免相互污染和依赖预置数据的余额：
  成功转账、重复提交返回相同流水且余额只变一次、同键不同参 409、余额不足 422 且幂等键可复用、
  账户不存在 404、同账户 400。
- 幂等锁（确定性用例，不靠线程时序）：测试代码先用 `DistributedLockService.tryLock` 手动占住
  某幂等键的锁 → 调用转账得到 409"处理中"、无流水、余额不变；占住时用另一个幂等键转账不受影响；
  释放后用原幂等键再次调用 → 成功。
- 唯一索引兜底：绕过 `TransferService`，直接对 `TransferExecutor` 用同一幂等键调用两次 →
  第二次抛 `DuplicateKeyException`、余额只变一次，证明没有锁时第二层仍然成立。
- 并发用例：N 个线程用同一幂等键同时提交 → 每个结果要么是成功、要么是 409"处理中"，至少一个成功、
  成功结果流水号一致、流水 1 条、只扣一次；N 个线程用不同幂等键从同一账户超额转出 → 余额不为负；
  A→B 与 B→A 对转 → 无死锁、总额守恒。
- 控制器层参数校验（缺头、金额非法）用 MockMvc 覆盖。
- "Redis 不可用返回 500"不靠停掉本地 Redis 验证，而是用 Mockito 模拟锁服务抛出 Redis 连接异常的
  离线单元测试覆盖：服务层断言异常原样传播且没有查流水、没有执行转账；控制器层断言最终返回 500
  和固定提示。同一个测试类顺带覆盖锁所有权丢失、抢锁失败转 409、等待被中断这几条异常路径。

## Risks / Trade-offs

- [失败请求不落流水，同一幂等键失败后重试会重新执行] → 这是有意选择（见 spec），因为失败时
  没有任何状态变更，重新执行是安全的；真实系统若需要审计失败请求，可增加 `FAILED` 状态流水
  并用独立事务（`REQUIRES_NEW`）写入，本示例不展开。
- [转账接口新增对 Redis 的强依赖，Redis 故障时转账全部失败] → 有意选择"失败"而不是"降级为
  无锁执行"：虽然唯一索引仍能保证正确性，但静默降级会掩盖故障，且与 `redis-distributed-lock`
  "故障不得绕过锁"的约定相悖；真实系统若要求更高可用性，可改为捕获 Redis 异常后只走第二层。
- [并发重复请求收到 409 而不是首次结果，调用方需要自己重试] → 已在 spec 中约定为对外行为；
  重试时首个请求通常已完成，直接拿到重放结果。
- [锁提前失效（租期内续期失败、主从切换、进程长暂停）导致两个请求同时进入业务] → 唯一索引兜底，
  后到者在插流水时收到 `DuplicateKeyException` 并走回查重放，资金不会重复划转。
- [请求线程在持锁期间崩溃，锁要等租期（30 秒）到期才释放，期间同一幂等键的重试都得到 409] →
  可接受：调用方继续重试，租期到期后即可恢复。
- [流水表永久增长] → 模板演示可接受；真实系统按时间归档。
- [幂等键全局唯一，不同调用方可能撞键] → 约定调用方使用 UUID；撞键且内容不同会得到 409
  而不是错误地返回他人结果中的"成功"——但内容恰好相同时会被当成重放。接入身份体系后应把
  唯一索引改为（调用方标识，幂等键）。
- [`application.yml` 当前有未提交的本地改动] → 本次不需要改该文件，实现时不触碰。

## Migration Plan

- 发布时 Flyway 自动执行 `V4` 建表并写入演示账户；全新表，无数据迁移。
- 回滚：代码回滚后两张表留着不影响旧版本；确需清理则手工 `DROP TABLE` 并删除
  `flyway_schema_history` 中 `V4` 记录。
