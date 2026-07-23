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
    // 충돌한 쪽은 다른 요청이 이미 같은 데이터를 커밋한 것이므로,
    // 잠깐 쉬었다 재시도하면 find-or-create가 기존 행을 찾아 정상 성공한다.
    // 동시 5발 이상이 한꺼번에 부딪히는 경우까지 흡수하도록 최대 3회 재시도.
    @PostMapping("/api/projects/{projectId}/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receiveScan(
            @PathVariable String projectId,
            @RequestBody ScanDataRequest req) {
        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Map<String, Object> result = componentService.processScan(projectId, req);
                return ResponseEntity.ok(ApiResponse.ok("스캔 데이터가 처리되었습니다", result));
            } catch (DataIntegrityViolationException e) {
                last = e;
                log.debug("동시 스캔 충돌 감지 (시도 {}/4): projectId={}", attempt, projectId);
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
