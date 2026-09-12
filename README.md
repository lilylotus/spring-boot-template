# Flowable
[Flowable](https://www.flowable.com/open-source) 审批处理流程示例模版。 [Flowable Engine](https://github.com/flowable/flowable-engine) Git仓库。

## 可视化审批模板

“审批配置”支持为 `USER_CREATE`、`USER_EDIT` 分别绘制顺序审批流程。编辑后先保存草稿，再显式发布；发布会生成不可变模板版本并部署 BPMN，运行中实例继续绑定其发起时的版本。

模板通过 `bizType + scope` 关联业务，当前 scope 固定为 `GLOBAL`。业务发起审批时只提交 `bizType` 和 `bizId`，审批域解析当前发布版本，并以 `bizType:bizId` 作为 Flowable businessKey。`approval_biz_link` 同时保存模板、版本、流程定义和实例 ID，支持双向追溯。

主要接口：

- `GET /api/approval/templates/{bizType}`：读取草稿和当前版本。
- `PUT /api/approval/templates/{bizType}/draft`：按 `expectedDraftRevision` 保存草稿。
- `POST /api/approval/templates/{bizType}/publish`：校验画布并发布生效。
- `GET /api/approval/templates/{bizType}/versions`：查询不可变发布历史。

首版只支持一个开始节点、一个结束节点及至少一个指定用户审批节点，不支持分支、汇聚或环路。生产回滚时不要删除已部署定义或模板版本；将模板的当前版本指针切回目标历史版本，并保留 `approval_biz_link` 审计数据。
