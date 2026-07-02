package com.planbridge.api.controller;

import com.planbridge.api.dto.request.ScanDataRequest;
import com.planbridge.api.dto.response.ApiResponse;
import com.planbridge.api.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ScanController {

    private final ComponentService componentService;

    @PostMapping("/api/projects/{projectId}/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receiveScan(
            @PathVariable String projectId,
            @RequestBody ScanDataRequest req) {
        Map<String, Object> result = componentService.processScan(projectId, req);
        return ResponseEntity.ok(ApiResponse.ok("스캔 데이터가 처리되었습니다", result));
    }
}
