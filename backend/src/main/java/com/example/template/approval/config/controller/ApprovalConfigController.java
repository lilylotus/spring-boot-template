package com.example.template.approval.config.controller;

import com.example.template.approval.config.dto.ApprovalChainLevelVO;
import com.example.template.approval.config.dto.ApprovalChainSaveRequest;
import com.example.template.approval.config.dto.ApprovalSwitchUpdateRequest;
import com.example.template.approval.config.dto.ApprovalSwitchVO;
import com.example.template.approval.config.service.ApprovalConfigService;
import com.example.template.common.RestResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 审批全局开关与审批链配置接口：供前端配置指定审批人、审批级数与全局总开关。
 * 只依赖 {@code approval.config} 与 {@code approval.api}，不直接使用任何 Flowable 运行时类型。
 */
@RestController
@RequiredArgsConstructor
public class ApprovalConfigController {

    private final ApprovalConfigService approvalConfigService;

    @GetMapping("/api/approval/switch")
    public RestResult<ApprovalSwitchVO> getSwitch() {
        return RestResult.success(new ApprovalSwitchVO(approvalConfigService.isApprovalEnabled()));
    }

    @PutMapping("/api/approval/switch")
    public RestResult<Void> updateSwitch(@Valid @RequestBody ApprovalSwitchUpdateRequest request) {
        approvalConfigService.updateApprovalSwitch(request.getApprovalEnabled());
        return RestResult.success(null);
    }

    @GetMapping("/api/approval/chains/{bizType}")
    public RestResult<List<ApprovalChainLevelVO>> listChain(@PathVariable String bizType) {
        List<ApprovalChainLevelVO> levels = approvalConfigService.listChain(bizType).stream()
                .map(item -> new ApprovalChainLevelVO(item.getLevelNo(), item.getApproverUserId()))
                .collect(Collectors.toList());
        return RestResult.success(levels);
    }

    @PutMapping("/api/approval/chains/{bizType}")
    public RestResult<Void> saveChain(@PathVariable String bizType,
                                       @Valid @RequestBody ApprovalChainSaveRequest request) {
        approvalConfigService.saveChain(bizType, request.getLevels());
        return RestResult.success(null);
    }
}
