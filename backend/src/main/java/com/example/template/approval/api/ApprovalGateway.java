package com.example.template.approval.api;

import java.util.List;

import com.example.template.approval.api.dto.ApprovalActCommand;
import com.example.template.approval.api.dto.ApprovalDetailView;
import com.example.template.approval.api.dto.ApprovalInstanceView;
import com.example.template.approval.api.dto.ApprovalRecordView;
import com.example.template.approval.api.dto.ApprovalTaskView;
import com.example.template.approval.api.dto.StartApprovalCommand;

/**
 * 审批能力对外提供的唯一网关接口（防腐层契约）。业务模块（如 {@code identity}）只依赖该接口
 * 及 {@code approval.api} 下的数据结构发起审批、查询审批状态，不直接创建或操作具体工作流引擎的
 * 运行时对象；更换底层工作流引擎实现时，本接口的调用方式保持不变。
 */
public interface ApprovalGateway {

    /**
     * 发起一次审批。
     *
     * @param command 发起审批命令
     * @return 发起后的审批实例视图
     */
    ApprovalInstanceView start(StartApprovalCommand command);

    /**
     * 依据业务单据标识查询当前审批状态。
     *
     * @param bizType 业务动作类型
     * @param bizId   业务单据标识
     * @return 审批实例视图；若从未针对该业务单据发起过审批，返回 {@code null}
     */
    ApprovalInstanceView queryByBizKey(String bizType, String bizId);

    /**
     * 审批人对当前审批任务执行同意/驳回操作。
     *
     * @param command 审批操作命令
     */
    void act(ApprovalActCommand command);

    /**
     * 查询某个审批人当前的待办审批任务列表。
     *
     * @param approverUserId 审批人标识
     * @return 待办任务列表
     */
    List<ApprovalTaskView> listPendingTasks(String approverUserId);

    /**
     * 查询某个审批人已经完成的审批动作记录。
     *
     * @param approverUserId 审批人标识
     * @return 按处理时间倒序排列的审批记录
     */
    List<ApprovalRecordView> listApprovalRecords(String approverUserId);

    /**
     * 查询单次审批实例详情，并校验查看人是否参与该实例。
     *
     * @param approvalId  审批实例标识
     * @param viewerUserId 当前查看人标识
     * @return 审批实例详情
     * @throws com.example.template.common.BusinessException 审批实例不存在或查看人无权访问
     */
    ApprovalDetailView getApprovalDetail(Long approvalId, String viewerUserId);
}
