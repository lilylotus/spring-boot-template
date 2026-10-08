# Multipart 文件媒体类型枚举规范

## Purpose

集中定义常见的 multipart 文件媒体类型常量，供调用方以类型安全的方式为 `MultipartFilePart` 提供 `contentType`，避免手写媒体类型字符串字面量。

## Requirements

### Requirement: 提供常见媒体类型枚举常量

系统 SHALL 提供 `MultipartContentTypeEnum` 枚举，收录覆盖常见二进制流、文本、图片和文档类别的预定义常量；每个常量 SHALL 关联一个符合 `type/subtype` 格式的标准 MIME 字符串和一段非空的中文描述。

#### Scenario: 获取预定义媒体类型字符串

- **WHEN** 调用方访问 `MultipartContentTypeEnum.OCTET_STREAM.getContentType()`
- **THEN** 系统返回字符串 `"application/octet-stream"`

#### Scenario: 预定义常量可直接用于 MultipartFilePart

- **WHEN** 调用方将任意 `MultipartContentTypeEnum` 常量的 `getContentType()` 返回值作为 `contentType` 参数传入 `MultipartFilePart.of(fieldName, path, contentType)`
- **THEN** 系统接受该值并通过现有媒体类型校验，不抛出异常

#### Scenario: 获取媒体类型的中文描述

- **WHEN** 调用方访问任意 `MultipartContentTypeEnum` 常量的描述方法
- **THEN** 系统返回该常量对应的非空中文描述文本

### Requirement: 媒体类型常量互不重复

系统 SHALL 保证 `MultipartContentTypeEnum` 中每个常量对应的 MIME 字符串在该枚举内唯一。

#### Scenario: 遍历全部常量校验唯一性

- **WHEN** 调用方遍历 `MultipartContentTypeEnum.values()` 并收集每个常量的 `getContentType()` 返回值
- **THEN** 收集结果中不存在两个常量返回相同的 MIME 字符串
