# Proposal

## Why

`RandomUserInfoUtils` 目前要从 classpath 资源 `/area-codes.csv` 读取地区码表，把这个类单独拷到别的工程里用时会因为缺少资源文件而在类加载阶段直接抛异常，丧失了"单文件工具类"的便利性。同时随机姓名只支持"1 字姓 + 1~2 字名"（总长 2~3 字），取名用字池只有 100 个字，生成结果重复感强、也完全没有少数民族姓名，不足以覆盖真实业务里 2~4 字姓名和多民族姓名的测试场景。

## What Changes

- 把民政部 2023 年 1 月县级地区码表（2842 条）内置到 `RandomUserInfoUtils` 源码中，类加载不再读取任何 classpath 资源；`src/main/resources/area-codes.csv` 原样保留，作为该内置数据的来源与测试参照。
- 随机姓名长度从"2~3 字"扩展为"2~4 字"：新增 2 字复姓池，单姓后的名字部分支持 1~3 字，复姓后的名字部分支持 1~2 字。
- 新增少数民族姓名池：内置一批真实常见的 2~4 字少数民族姓名（蒙古族、维吾尔族、藏族、哈萨克族、彝族等），按整条随机抽取。
- `GIVEN_NAME_CHARS` 取名用字池从 100 个字扩充到 200 个字。
- 补充测试：断言内置地区码表与 `area-codes.csv` 逐条一致（防止两份数据漂移）、断言生成姓名长度恒在 2~4 之间、断言足量采样下能同时取到汉族与少数民族姓名。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `random-user-info-generation`：姓名生成要求从"随机姓名"细化为"长度 2~4 个汉字、覆盖汉族单姓/复姓与少数民族姓名"；新增"地区码表内置于源码、不依赖 classpath 资源"的要求。

## Impact

- `src/main/java/org/example/simple/util/RandomUserInfoUtils.java`：新增内置地区码表常量与解析逻辑，替换 `loadAreaCodes()` 的资源读取实现；扩充姓名相关常量池与 `buildRandomName()` 分支。
- `src/test/java/org/example/simple/util/RandomUserInfoUtilsTest.java`：新增内置表与 CSV 的一致性测试、姓名长度与民族姓名覆盖测试。
- `src/main/resources/area-codes.csv`：内容不变，用途从"运行时数据源"变为"内置数据的来源与测试参照"。
- `docs/RandomUserInfoUtils使用说明.md`：同步说明零资源依赖与新的姓名规则。
- 无新增依赖，无 API 签名变化，调用方代码无需改动。
