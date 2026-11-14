# Proposal

## Why

模板目前只有"消费方幂等"示例（outbox 的 `processed_event`），缺少"接口幂等"这一更常见场景的参考实现：
客户端超时重试、用户重复点击、网关重发都会让同一笔写请求到达多次，资金类接口一旦重复执行就是重复扣款。
本次以日常交易转账为载体，补一个从 Controller 到数据库的完整接口幂等示例，供衍生服务直接照搬。

## What Changes

- 新增转账接口 `POST /api/transfers`：调用方通过请求头 `Idempotency-Key` 携带幂等键，请求体提交
  转出账户、转入账户、金额、备注。
- 新增接口幂等处理，两层防护：
  - **Redis 幂等锁（第一层）**：按幂等键加分布式锁，同一幂等键同一时刻只放行一个请求进入业务；
    其余并发重复请求抢不到锁立即被拒绝（"请求正在处理中"），不等待、不触达数据库。
  - **数据库唯一索引（第二层，兜底）**：转账流水表的幂等键唯一索引保证即使锁失效，
    同一幂等键也只会落一条流水、只扣一次款。
  - 已成功的幂等键再次提交时直接返回首次执行的结果；同一幂等键但请求内容不一致时拒绝执行。
- 新增转账业务实现：在同一个本地事务内完成"写转账流水 + 扣减转出账户余额 + 增加转入账户余额"，
  余额不足、账户不存在、转出转入为同一账户等情况返回明确的业务错误。
- 新增数据库表（Flyway 迁移脚本 `V4`）：账户表 `transfer_account`、转账流水表 `transfer_order`
  （幂等键唯一索引），并预置两个演示账户。
- 新增对应的 Mapper、实体、请求/响应 DTO、业务异常，以及 `GlobalExceptionHandler` 中的异常映射。

## Capabilities

### New Capabilities
- `idempotent-transfer`: 基于调用方幂等键的转账接口——转账的入参校验、资金划转规则，以及重复请求
  （串行重试、并发重复、同键不同参）下的幂等行为。

### Modified Capabilities

无。`global-exception-handling` 只是多一个业务异常的映射分支，既有需求不变。

## Impact

- **新增代码**：`com.example.template.transfer` 包（`controller` / `dto` / `service` / `mapper` /
  `entity` / `exception`）。
- **修改代码**：`GlobalExceptionHandler` 新增转账业务异常处理分支。
- **数据库**：新增 Flyway 迁移 `V4__create_transfer_tables.sql`（两张新表 + 演示账户数据），
  不改动已有表。
- **API**：新增 `POST /api/transfers`，会出现在 Swagger UI 中；不影响已有接口。
- **依赖**：不新增第三方依赖（MyBatis-Plus、Validation、Flyway 均已在用）；幂等锁复用仓库已有的
  `DistributedLockService`，不改动其代码与 `redis-distributed-lock` 的既有需求。
- **运行时**：转账接口同时依赖 MySQL 与 Redis，Redis 不可用时转账请求失败（不降级为无锁执行）。
- **测试**：新增的集成测试依赖本地 MySQL 与 Redis。
