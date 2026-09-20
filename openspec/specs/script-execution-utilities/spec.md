# 脚本执行工具规范

## Purpose

为 Java 21 应用提供统一的 Groovy 与 GraalVM JavaScript 脚本执行工具，将参数映射传入独立执行上下文并返回字符串，明确输入、结果、异常及资源生命周期约定。

## Requirements

### Requirement: 统一脚本执行入口
系统 MUST 提供 GroovyScriptUtils 和 GraalVMUtils，均通过 `execute(Map<String, Object> param, String script)` 接收参数与脚本并返回 String。系统 MUST 将顶层参数映射副本以 param 绑定，支持空映射与 null 值，不展开键为独立全局变量。

#### Scenario: 参数参与业务逻辑
- **WHEN** 脚本使用 `param.get("name")` 读取中文字符串，并通过条件、循环和 return 生成结果
- **THEN** 对应工具返回脚本计算的字符串，参数值未被拼接入脚本源码

#### Scenario: 参数校验
- **WHEN** param 为 null，脚本为 null/空白，或映射包含 null 键
- **THEN** 工具在执行脚本前抛出中文 IllegalArgumentException

### Requirement: 明确脚本返回语义
Groovy 工具 MUST 支持显式 return 及最后表达式结果；JS 工具 MUST 将脚本作为函数体执行，支持顶层 return，无 return 时返回 Java null。工具 MUST 原样返回字符串，将 GString 及标量转换为 String，将 null/undefined 映射为 Java null，并拒绝未显式转换的复杂对象结果。

#### Scenario: 返回字符串与标量
- **WHEN** 脚本分别返回中文、空字符串、整数或布尔值
- **THEN** 工具返回对应字符串，整数 12 返回 `"12"`，布尔 true 返回 `"true"`

#### Scenario: 返回空值与复杂值
- **WHEN** 脚本返回 null，或 JS 无 return，或脚本返回复杂对象
- **THEN** 前两类返回 Java null，复杂对象抛出 ScriptExecutionException；不擅自转换为 JSON

### Requirement: 异常与资源生命周期
系统 MUST 将语法、运行及结果转换失败包装为保留 cause 的 ScriptExecutionException，使用中文说明且不主动记录源码或参数。每次调用 MUST 使用独立脚本状态，在成功及异常路径释放自身拥有的上下文或类加载资源。

#### Scenario: 失败后再次执行
- **WHEN** 首次执行语法错误或显式抛出异常，随后执行有效脚本
- **THEN** 首次调用报告对应语言异常，后续调用正常返回，不继承上次绑定或全局变量

#### Scenario: 并发隔离
- **WHEN** 多个线程同时使用不同参数执行同一脚本，脚本修改自己的顶层参数映射
- **THEN** 每次调用仅返回自身参数对应结果，不串用全局变量，调用方原始映射不发生顶层变化

### Requirement: Java 21 运行与互操作说明
系统 MUST 使用项目 Java 21 环境验证 Groovy 与 GraalJS 实际启动及执行，公开说明两种脚本访问参数的方法、顶层浅复制和嵌套共享语义。JS MUST 不依赖 Nashorn 或引擎名称自动发现。

#### Scenario: 两种引擎实际执行
- **WHEN** 在项目 Java 21 测试环境执行双引擎参数映射示例
- **THEN** 两种工具均得到预期字符串，依赖和运行时问题不得伪装为脚本空结果
