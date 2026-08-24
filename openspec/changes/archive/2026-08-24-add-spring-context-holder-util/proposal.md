## Why

模板项目里部分场景（例如非 Spring 管理的静态工具类、需要在 `@PostConstruct`/静态上下文中读取
Bean 或配置项的代码）无法通过构造函数注入拿到 Spring 容器管理的 Bean 或 `Environment` 中的配置
值。目前仓库没有统一封装的 `ApplicationContext` 访问入口，缺这类场景就只能各处自行持有
`ApplicationContext` 引用，做法不统一。需要一个类似 `RedisUtils`/`ThreadPoolUtils` 风格的工具类，
统一封装"按名称/类型/名称+类型获取 Bean"以及"读取 Environment 配置项"这两类操作。

## What Changes

- 新增 `com.example.template.util.SpringContextHolder`：实现 `ApplicationContextAware`，在 Spring
  容器启动时持有 `ApplicationContext` 的静态引用。
- 提供静态方法按 **Bean 名称** 获取 Bean。
- 提供静态方法按 **Bean 类型** 获取 Bean（类型不存在或存在多个候选时的行为遵循 Spring 原生
  `getBean(Class)` 语义，直接抛出 Spring 原生异常，不做额外包装/吞掉）。
- 提供静态方法按 **Bean 名称 + 类型** 获取 Bean。
- 提供静态方法读取 `Environment` 中的配置项，支持：不指定类型（返回 `String`）、指定目标类型
  （`Class<T>`，返回类型转换后的值）、以及两者各自带默认值的重载，共 4 个重载。
- 在 `ApplicationContext` 尚未就绪（容器未完成初始化）时调用上述静态方法，直接抛出明确异常，
  不做静默降级。

## Capabilities

### New Capabilities
- `spring-context-holder-util`：统一封装的 `ApplicationContext` 静态访问工具类，覆盖按名称/类型/
  名称+类型获取 Bean，以及读取 Environment 配置项。

### Modified Capabilities
(无)

## Impact

- 新增文件：`src/main/java/com/example/template/util/SpringContextHolder.java`。
- 新增对应单元测试：`src/test/java/com/example/template/util/SpringContextHolderTest.java`。
- 不影响现有 Bean 定义、配置文件或已有工具类的行为。
