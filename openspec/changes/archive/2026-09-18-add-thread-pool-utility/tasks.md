## 1. 线程池工具实现

- [x] 1.1 在 `org.example.simple.util` 下新增不可实例化的 `ExecutorServiceUtils`，封装固定四线程、容量 1024 的有界队列和具名守护线程工厂，并通过编译验证
- [x] 1.2 实现 `execute(Runnable)`、`submit(Runnable)` 和泛型 `submit(Callable<T>)`，通过测试验证任务执行、结果返回、异常传播和空参数校验
- [x] 1.3 实现自定义拒绝策略，在线程与队列均满时抛出包含中文原因、活动线程数、排队数和容量的 `RejectedExecutionException`，并通过队列饱和测试验证
- [x] 1.4 为类、字段和公共方法补充中文 Javadoc，说明共享执行器、守护线程、有界队列和拒绝行为，并运行 Javadoc 任务验证

## 2. 行为与构建验证

- [x] 2.1 编写 `ExecutorServiceUtilsTest`，验证四任务并发、线程名称与守护属性、任务结果、异常传播、队列上限及拒绝错误，并运行专项测试
- [x] 2.2 运行 `gradlew.bat test` 和 `gradlew.bat build`，验证新增与既有测试全部通过且没有新增编译警告
