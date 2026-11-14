# Tasks

## 1. 数据库

- [x] 1.1 新增 `src/main/resources/db/migration/V4__create_transfer_tables.sql`：创建
      `transfer_account`、`transfer_order`（`idempotency_key`、`transfer_no` 唯一索引）并预置
      `ACC1001`/`ACC1002` 两个演示账户，注释风格参照 `V3__create_outbox_tables.sql`；
      验证：启动应用（或运行任一 `@SpringBootTest`）后 Flyway 成功应用 V4，库中可见两张表和两条账户数据。

## 2. 实体、Mapper 与 DTO

- [x] 2.1 新增 `transfer.entity.TransferAccount`、`TransferOrder`（`@TableName` 实体）和
      `TransferOrderStatus`（字符串常量类），字段与迁移脚本一一对应，带类/字段中文注释；
      验证：`./gradlew compileJava` 通过。
- [x] 2.2 新增 `transfer.mapper.TransferAccountMapper`（`BaseMapper` + `@Update` 条件扣款
      `debit`、入账 `credit`）与 `TransferOrderMapper`（`BaseMapper`），方法注释说明条件更新的
      用意和返回的影响行数含义；验证：`./gradlew compileJava` 通过，且在第 3 组的集成测试中被实际执行。
- [x] 2.3 新增 `transfer.dto.TransferRequest`（Bean Validation 注解，中文校验消息）与
      `TransferResponse`，带 springdoc `@Schema` 说明；验证：`./gradlew compileJava` 通过。

## 3. 业务实现与幂等

- [x] 3.1 新增 `transfer.exception.TransferException`（携带 `HttpStatus`），并在
      `GlobalExceptionHandler` 新增对应 `@ExceptionHandler`；验证：第 3.4 的测试中业务错误返回预期状态码。
- [x] 3.2 新增 `transfer.service.TransferExecutor`（`@Transactional`）：校验同账户/账户存在 →
      先插流水 → 按账户号字典序执行扣款与入账 → 余额不足抛 422 回滚；注释讲清"先插流水"和
      "固定加锁顺序"的原因；验证：第 3.4 的测试通过。
- [x] 3.3 新增 `transfer.service.TransferService`：注入 `DistributedLockService`，以
      `transfer:idempotent:{幂等键}` 为锁键零等待调用 `executeWithLock`，抢锁失败
      （`LockAcquisitionException`）转 409"请求正在处理中"，`InterruptedException` 恢复中断标志后
      抛出，Redis 故障与锁所有权丢失不捕获；锁内：先查流水 → 调用执行器 → 捕获
      `DuplicateKeyException` 后事务外回查 → 比对请求内容，一致重放、不一致抛 409"幂等键已被其他
      请求使用"；注释讲清两层防护各自的职责、"锁必须包住事务"以及"只先查后做"为什么不够；
      验证：第 3.4 的测试通过。
- [x] 3.4 新增服务层集成测试（`@SpringBootTest`，每个用例自建独立账户和幂等键）：
      成功转账、重复提交返回相同流水且余额只变一次、同键不同参 409、余额不足 422 且幂等键可复用、
      账户不存在 404、同账户 400；
      幂等锁：手动占住幂等键的锁后转账得到 409"处理中"且无流水/余额不变、此时换幂等键转账不受影响、
      释放后原幂等键转账成功；
      唯一索引兜底：直接对 `TransferExecutor` 用同一幂等键调用两次，第二次抛
      `DuplicateKeyException` 且余额只变一次；
      并发：同幂等键并发提交结果只有"成功"或 409"处理中"、至少一个成功、只扣一次且流水仅 1 条，
      不同幂等键并发超额转出余额不为负，A→B 与 B→A 并发对转无死锁且总额守恒；
      验证：`./gradlew test --tests "com.example.template.transfer.*"` 通过（需本地 MySQL 与 Redis）。

## 4. 控制器

- [x] 4.1 新增 `transfer.controller.TransferController`：`POST /api/transfers`，
      `@RequestHeader("Idempotency-Key")` + `@Valid @RequestBody TransferRequest`，返回
      `RestResult<TransferResponse>`，补 `@Tag`/`@Operation`/`@Parameter`；并确认
      `springdoc.packages-to-scan` 能扫到该包（扫不到则按现有 `file` 模块的做法处理）；
      验证：Swagger UI 中出现该接口。
      （实际结果：`springdoc.packages-to-scan` 原先只扫 `com.example.template.controller`，已追加
      `com.example.template.file.controller` 与 `com.example.template.transfer.controller`，
      启动后 `/v3/api-docs` 中可见 `/api/transfers`，`/api/files/**` 也随之出现。）
- [x] 4.2 新增控制器层 MockMvc 测试：缺少 `Idempotency-Key`、幂等键超长、金额为 0/负数/三位小数、
      缺少账户号均返回 400 的 `RestResult`；正常请求返回 200 且包含流水号；
      验证：`./gradlew test --tests "com.example.template.transfer.*"` 通过。

## 5. 集成验证

- [x] 5.1 执行 `./gradlew build`，确认全量编译与测试通过（本地基础设施不可用导致的既有测试失败
      需在结果中单独说明，不与本次改动混淆）。
      （实际结果：`./gradlew build` 全量通过，转账模块 22 个测试全部通过。为此顺带处理了两个与转账
      无关的既有失败：`HelloOpenFeignTest` 依赖 Nacos 中存在可用的 BootDemo 下游实例，改为仅在环境变量
      `OPENFEIGN_INTEGRATION_ENABLED=true` 时执行（默认跳过）；`SpringContextHolderTest` 增加
      `@BeforeEach` 清空静态上下文，不再受同一 JVM 中先执行的 `@SpringBootTest` 影响。）
- [x] 5.2 `bootRun` 后用 curl 对 `ACC1001 → ACC1002` 以同一 `Idempotency-Key` 连续提交两次，
      确认两次响应的流水号相同、余额只变化一次；条件允许时再停掉本地 Redis 提交一次，确认返回 500
      且余额不变；若本地基础设施不可用则明确说明未做手动验证。
      （实际结果：已验证同键两次提交流水号相同、余额只变一次，以及同键不同参 409、缺幂等键 400、
      余额不足 422；因配置中的 Nacos 地址不可达，启动时用命令行参数临时关闭了 Nacos 注册与配置。
      "Redis 不可用返回 500"没有通过停掉真实 Redis 验证，改由自动化测试覆盖：
      `TransferServiceLockFailureTest`（锁服务抛 Redis 连接异常时原样传播、不查流水、不执行转账）与
      `TransferControllerTest`（该异常最终返回 500 和固定提示）。）
