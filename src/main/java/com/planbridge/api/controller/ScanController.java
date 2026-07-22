package com.planbridge.api.controller;

import com.planbridge.api.dto.request.ScanDataRequest;
import com.planbridge.api.dto.response.ApiResponse;
import com.planbridge.api.service.ComponentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ScanController {

    private final ComponentService componentService;

    // 자동 스캔(DOM MutationObserver)이 짧은 간격으로 겹쳐 발사되면
    // 같은 (page, pbId) upsert 경쟁으로 DB 유니크 제약 위반이 날 수 있음.
    // 그 시점엔 이미 다른 요청이 동일 데이터를 커밋했으므로, 1회 재시도하면
    // find-or-create가 "이미 있음"을 정상적으로 찾아 조용히 성공한다.
    @PostMapping("/api/projects/{projectId}/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receiveScan(
            @PathVariable String projectId,
            @RequestBody ScanDataRequest req) {
        try {
            Map<String, Object> result = componentService.processScan(projectId, req);
            return ResponseEntity.ok(ApiResponse.ok("스캔 데이터가 처리되었습니다", result));
        } catch (DataIntegrityViolationException e) {
            log.debug("동시 스캔 충돌 감지, 재시도: projectId={}", projectId);
            Map<String, Object> result = componentService.processScan(projectId, req);
            return ResponseEntity.ok(ApiResponse.ok("스캔 데이터가 처리되었습니다", result));
        }
    }
}
