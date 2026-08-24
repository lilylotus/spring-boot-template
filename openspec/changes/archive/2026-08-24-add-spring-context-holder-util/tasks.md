## 1. 实现 SpringContextHolder

- [x] 1.1 新建 `src/main/java/com/example/template/util/SpringContextHolder.java`：
      `@Component` + 实现 `ApplicationContextAware`，用 `static` 字段持有 `ApplicationContext`，
      并为类、每个非 trivial 方法补充中文注释（风格参照 `RedisUtils`）。
- [x] 1.2 实现按 Bean 名称获取 Bean 的静态方法（`getBean(String)`）。
- [x] 1.3 实现按 Bean 类型获取 Bean 的静态方法（`getBean(Class<T>)`）。
- [x] 1.4 实现按 Bean 名称 + 类型获取 Bean 的静态方法（`getBean(String, Class<T>)`）。
- [x] 1.5 实现读取 Environment 配置项的静态方法，共 4 个重载：不指定类型的
      `getProperty(String)` / `getProperty(String, String)`（带默认值），以及指定目标类型的
      `getProperty(String, Class<T>)` / `getProperty(String, Class<T>, T)`（带默认值）。
- [x] 1.6 在所有静态方法入口处校验 `ApplicationContext` 是否已就绪，未就绪时抛出信息明确的
      `IllegalStateException`。

## 2. 测试

- [x] 2.1 新建 `src/test/java/com/example/template/util/SpringContextHolderTest.java`，覆盖
      spec 中列出的全部场景：按名称/类型/名称+类型获取存在的 Bean、按名称获取不存在的 Bean
      抛出 `NoSuchBeanDefinitionException`、按类型获取不存在/多候选 Bean 分别抛出
      `NoSuchBeanDefinitionException`/`NoUniqueBeanDefinitionException`、名称存在但类型不匹配
      抛出 `BeanNotOfRequiredTypeException`、读取存在/不存在（带/不带默认值、指定/不指定类型）的
      配置项、按指定类型读取但值无法转换时抛出 `ConversionFailedException`。
- [x] 2.2 覆盖容器未就绪场景：直接构造 `SpringContextHolder`（不触发
      `setApplicationContext`）后调用各静态方法，断言抛出 `IllegalStateException`。
- [x] 2.3 运行 `./gradlew test --tests "com.example.template.util.SpringContextHolderTest"`
      确认全部通过。
