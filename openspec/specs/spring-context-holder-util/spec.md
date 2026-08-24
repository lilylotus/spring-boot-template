# spring-context-holder-util Specification

## Purpose
在无法通过依赖注入直接拿到 Spring 容器的场景下，提供统一的静态入口按名称/类型获取 Bean，
并读取 Environment 中的配置项。

## Requirements

### Requirement: 按 Bean 名称获取 Bean
系统 SHALL 提供静态方法，允许调用方传入 Bean 名称获取该 Bean 实例。

#### Scenario: 按名称获取已注册的 Bean
- **WHEN** 调用方传入一个已在 Spring 容器中注册的 Bean 名称
- **THEN** 返回该名称对应的 Bean 实例

#### Scenario: 按名称获取不存在的 Bean
- **WHEN** 调用方传入一个未在 Spring 容器中注册的 Bean 名称
- **THEN** 抛出 Spring 原生的 `NoSuchBeanDefinitionException`，不做额外包装或吞掉

### Requirement: 按 Bean 类型获取 Bean
系统 SHALL 提供静态方法，允许调用方传入 Bean 类型获取该类型对应的唯一 Bean 实例。

#### Scenario: 按类型获取唯一匹配的 Bean
- **WHEN** 调用方传入一个在 Spring 容器中有且仅有一个匹配 Bean 的类型
- **THEN** 返回该类型对应的 Bean 实例

#### Scenario: 按类型获取不存在的 Bean
- **WHEN** 调用方传入一个在 Spring 容器中没有匹配 Bean 的类型
- **THEN** 抛出 Spring 原生的 `NoSuchBeanDefinitionException`，不做额外包装或吞掉

#### Scenario: 按类型获取存在多个候选的 Bean
- **WHEN** 调用方传入一个在 Spring 容器中匹配到多个候选 Bean 的类型
- **THEN** 抛出 Spring 原生的 `NoUniqueBeanDefinitionException`，不做额外包装或吞掉

### Requirement: 按 Bean 名称 + 类型获取 Bean
系统 SHALL 提供静态方法，允许调用方同时传入 Bean 名称与期望类型获取 Bean 实例。

#### Scenario: 按名称与类型获取匹配的 Bean
- **WHEN** 调用方传入一个已注册的 Bean 名称，以及与该 Bean 实际类型匹配（含父类/接口）的类型
- **THEN** 返回该名称对应、按该类型转换后的 Bean 实例

#### Scenario: 名称存在但类型不匹配
- **WHEN** 调用方传入一个已注册的 Bean 名称，以及与该 Bean 实际类型不匹配的类型
- **THEN** 抛出 Spring 原生的 `BeanNotOfRequiredTypeException`，不做额外包装或吞掉

### Requirement: 读取 Environment 配置项
系统 SHALL 提供静态方法，允许调用方按 key 读取 Spring `Environment` 中的配置项，且支持
不指定类型（返回 `String`）与指定目标类型（`Class<T>`，返回类型转换后的值）两种形式，
每种形式各自支持不带默认值与带默认值两种重载。

#### Scenario: 读取已存在的配置项（不指定类型）
- **WHEN** 调用方传入一个在当前生效配置（`application.yml`、Nacos 配置中心等 `Environment` 已
  合并的来源）中存在的 key，不指定目标类型
- **THEN** 返回该 key 对应的配置值（`String`）

#### Scenario: 读取不存在的配置项且未提供默认值（不指定类型）
- **WHEN** 调用方传入一个不存在的 key，不指定目标类型，且未提供默认值
- **THEN** 返回 `null`

#### Scenario: 读取不存在的配置项且提供了默认值（不指定类型）
- **WHEN** 调用方传入一个不存在的 key，不指定目标类型，并提供了默认值
- **THEN** 返回该默认值

#### Scenario: 按指定类型读取已存在且可转换的配置项
- **WHEN** 调用方传入一个存在的 key 与目标类型（如 `Integer`、`Boolean`、`Long` 等），且该 key
  对应的配置值能够转换为目标类型
- **THEN** 返回转换为目标类型后的配置值

#### Scenario: 按指定类型读取不存在的配置项且未提供默认值
- **WHEN** 调用方传入一个不存在的 key 与目标类型，且未提供默认值
- **THEN** 返回 `null`

#### Scenario: 按指定类型读取不存在的配置项且提供了默认值
- **WHEN** 调用方传入一个不存在的 key 与目标类型，并提供了该类型的默认值
- **THEN** 返回该默认值

#### Scenario: 按指定类型读取存在但无法转换的配置项
- **WHEN** 调用方传入一个存在的 key 与目标类型，但该 key 对应的配置值无法转换为目标类型
  （例如配置值为 `"abc"`、目标类型为 `Integer`）
- **THEN** 抛出 Spring 原生的类型转换异常（`org.springframework.core.convert.ConversionFailedException`），
  不做额外包装或吞掉

### Requirement: 容器未就绪时的行为
系统 SHALL 在 `ApplicationContext` 尚未完成初始化（即容器启动过程中，`SpringContextHolder`
尚未收到 `setApplicationContext` 回调）时调用上述任一静态方法时，直接抛出明确异常，不做静默
降级或返回 `null`。

#### Scenario: 容器未就绪时获取 Bean
- **WHEN** `ApplicationContext` 尚未完成初始化，调用方调用按名称/类型/名称+类型获取 Bean 的
  静态方法
- **THEN** 抛出明确指出"`ApplicationContext` 尚未初始化"的异常

#### Scenario: 容器未就绪时读取配置项
- **WHEN** `ApplicationContext` 尚未完成初始化，调用方调用读取配置项的静态方法
- **THEN** 抛出明确指出"`ApplicationContext` 尚未初始化"的异常
