package com.planbridge.api.service;

import com.planbridge.api.entity.PbAnalysisQueue;
import com.planbridge.api.repository.PbAnalysisQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 분석 큐 폴링 스케줄러.
 * QUEUED 상태인 항목을 주기적으로 감지하여 AiAnalysisService에 위임.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisWorkerService {

    private final PbAnalysisQueueRepository queueRepository;
    private final AiAnalysisService aiAnalysisService;

    @Scheduled(fixedDelay = 10000) // 10초마다 폴링
    @Transactional
    public void processQueue() {
        List<PbAnalysisQueue> queued = queueRepository.findByStatusOrderByCreatedAtAsc("QUEUED");
        if (queued.isEmpty()) return;

        log.info("분석 대기 항목 {}건 처리 시작", queued.size());
        for (PbAnalysisQueue item : queued) {
            try {
                // 먼저 PROCESSING으로 상태 변경 (중복 처리 방지)
                item.setStatus("PROCESSING");
                item.setStartedAt(LocalDateTime.now());
                queueRepository.save(item);

                // 비동기 AI 분석 실행
                aiAnalysisService.analyzeAsync(item.getQueueId());
            } catch (Exception e) {
                log.error("큐 아이템 처리 실패: queueId={}", item.getQueueId(), e);
            }
        }
    }
}
