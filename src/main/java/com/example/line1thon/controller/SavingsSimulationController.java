package com.example.line1thon.controller;

import com.example.line1thon.global.common.ApiResponse;
import com.example.line1thon.service.SavingsSimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class SavingsSimulationController {

    private final SavingsSimulationService savingsSimulationService;

    // S09: 월 저축 목표 시뮬레이션
    @PostMapping("/{analysisId}/savings/simulate")
    public ApiResponse<SavingsSimulationService.SimulationResult>
    simulate(
            @PathVariable Long analysisId,
            @RequestBody SavingsSimulationRequest request) {

        var result = savingsSimulationService.simulate(
                analysisId,
                request.monthlyGoalKrw(),
                request.lockedCategories()
        );

        return ApiResponse.ok(result);
    }

    public record SavingsSimulationRequest(
            Long monthlyGoalKrw,
            List<String> lockedCategories
    ) {}
}
