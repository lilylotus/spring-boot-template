# Tasks

## 1. 严格标题结构校验

- [x] 1.1 修改 `ExcelObjectMapper#validateHeaders`，先比较实际标题数与排序后声明列数，数量不一致时抛出包含工作表、期望列数和实际列数的 `MAPPING` 异常，并通过缺列、额外列测试验证。
- [x] 1.2 在标题数量一致后按索引比较实际标题与对应 `ColumnBinding` 标题，首次不一致时抛出包含工作表、一基列号、期望标题和实际标题的 `MAPPING` 异常，并通过标题乱序测试验证。
- [x] 1.3 更新 `validateHeaders`、`ExcelColumn` 及相关调用点的中文 Javadoc，使导入标题顺序和 `required` 仅约束单元格值的语义清晰，并通过 Javadoc 检查验证无新增警告。

## 2. 回归验证

- [x] 2.1 补充标题与声明完全一致时成功导入的测试，覆盖显式 `order`、未显式 `order` 及继承字段排序，验证现有对象转换结果保持不变。
- [ ] 2.2 运行 `gradlew.bat test` 和 `openspec validate enforce-xlsx-header-order --strict`，确认全部测试和 OpenSpec 严格校验通过。
