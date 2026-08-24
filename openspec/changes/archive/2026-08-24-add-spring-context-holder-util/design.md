## Context

参见 proposal.md - Why。仓库内已有 `RedisUtils`、`ThreadPoolUtils` 等以 `@Component` +
实例方法封装第三方能力的工具类风格，但本次要封装的是 `ApplicationContext` 本身——它只能在
Spring 容器启动完成后，通过 `ApplicationContextAware` 回调拿到，且需要在部分**非 Spring 管理**
的静态上下文中被访问，因此不能走纯实例方法路线，需要一个持有静态引用的组件。

## Goals / Non-Goals

**Goals:**
- 提供静态方法即可完成"按名称/类型/名称+类型获取 Bean"和"读取 Environment 配置项"。
- 容器未就绪时快速失败（fail-fast），不要返回 `null` 掩盖问题。

**Non-Goals:**
- 不封装 Bean 的注册/动态刷新等能力，仅做只读访问。
- 不对 Spring 原生异常（`NoSuchBeanDefinitionException` 等）做二次包装，保持与直接使用
  `ApplicationContext` 一致的异常语义，降低调用方的心智负担。

## Decisions

- **实现 `ApplicationContextAware` + `@Component`，并用一个 `static` 字段持有
  `ApplicationContext`**：这是 Spring 生态中该类工具的标准做法——`@Component` 保证容器启动时
  该 Bean 被实例化并收到 `setApplicationContext` 回调，`static` 字段让非 Spring 管理的代码
  （静态工具方法、非托管对象的静态初始化块等）也能拿到同一个引用。
  - 备选方案：让每个需要访问容器的类自行注入 `ApplicationContext`——被否决，因为这正是
    proposal 里提到的"各处自行持有引用，做法不统一"的问题本身，且对静态上下文/非 Spring 管理
    代码不适用。
- **按类型获取 Bean 直接复用 `ApplicationContext#getBean(Class)` 语义**：单候选返回、无候选或
  多候选各自抛出 Spring 原生异常，不做"取第一个"之类的静默兜底，避免掩盖容器配置问题。
- **读取配置项复用 `Environment` 原生的 4 个 `getProperty` 重载**——
  `getProperty(String)`、`getProperty(String, String)`、`getProperty(String, Class<T>)`、
  `getProperty(String, Class<T>, T)`：不存在且无默认值时返回 `null`；类型转换失败时直接抛出
  `Environment` 原生的 `org.springframework.core.convert.ConversionFailedException`（实测得到，
  并非最初设想的 `IllegalArgumentException`），不做额外包装或吞掉，与不指定类型的重载保持一致的
  "不介入 Spring 原生异常语义"原则。
  - 备选方案：自行 catch 转换异常后返回 `null` 或默认值——被否决，会掩盖配置项类型写错的问题，
    且与"按类型获取 Bean"一节里"不做静默兜底"的决策不一致。
- **容器未就绪时抛出明确异常而不是返回 `null`**：`SpringContextHolder` 本身没有强制其它 Bean
  在它之后初始化的手段，如果静默返回 `null`，调用方大概率会在使用返回值时抛出更难定位的
  `NullPointerException`；直接在 `SpringContextHolder` 内部判空并抛出信息明确的
  `IllegalStateException`，能让问题在第一现场暴露。

## Risks / Trade-offs

- [静态持有 `ApplicationContext` 在多个 Spring 容器并存的场景（如测试中反复
  启动/关闭容器、或同一 JVM 内跑多个 `ApplicationContext`）可能出现串扰] → 本仓库是单体应用
  模板，运行时只有一个容器实例；测试场景中 Spring Test 会在容器刷新时重新调用
  `setApplicationContext`，覆盖旧引用，可接受。若未来出现多容器场景需要重新评估。
- [静态方法在单元测试中不便 mock] → 通过 `@SpringBootTest` 或手动构造
  `AnnotationConfigApplicationContext` 触发真实的 `setApplicationContext` 回调来测试，不引入
  额外的可测试性开销。
