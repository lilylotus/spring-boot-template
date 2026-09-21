## Why

项目缺少无需中心数据库即可生成全局趋势递增长整型 ID 的统一能力。业务自行实现雪花算法容易在节点号冲突、时钟回拨、并发竞争或序列溢出时产生重复 ID，因此需要提供具备明确生产约束的静态线程安全工具。

## What Changes

- 在 `org.example.simple.util` 包新增不可实例化的静态工具类 `SnowflakeUtils`。
- 支持直接调用 `nextId()`，未初始化时以默认节点 `(0, 0)` 自动完成一次性初始化；也支持发号前显式配置节点，非法参数或冲突初始化时明确失败。
- 提供线程安全的 `nextId()`，生成正数 `long` ID，并保证单 JVM、单节点配置下不重复且趋势递增。
- 使用 41 位毫秒时间戳、5 位数据中心编号、5 位工作节点编号和 12 位毫秒内序列号的标准布局。
- 对轻微时钟回拨进行有限等待，对超过容忍阈值的回拨快速失败；对同毫秒序列耗尽等待下一毫秒。
- 补充参数边界、并发唯一性、趋势递增、位布局、序列容量和异常行为测试。

## Capabilities

### New Capabilities

- `snowflake-id-generation`: 定义静态雪花 ID 工具的节点初始化、ID 布局、并发唯一性、时钟安全和失败行为。

### Modified Capabilities

无。

## Impact

- 新增 `org.example.simple.util.SnowflakeUtils` 公共工具类及对应 JUnit 5 测试。
- 调用方部署时必须为每个同时运行的实例分配唯一的 `datacenterId` 与 `workerId` 组合。
- 不引入第三方依赖，不访问数据库、网络或本机硬件标识。
