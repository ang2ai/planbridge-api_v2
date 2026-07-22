package com.planbridge.api.repository;

import com.planbridge.api.entity.PbScanHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PbScanHistoryRepository extends JpaRepository<PbScanHistory, String> {
    List<PbScanHistory> findByProject_ProjectIdOrderByScannedAtDesc(String projectId);
    List<PbScanHistory> findByProject_ProjectIdAndPage_PageIdOrderByScannedAtDesc(String projectId, String pageId);

    // 자동 스캔 반복 시 이력 무한 누적 방지: 페이지당 최신 1건만 유지
    void deleteByPage_PageId(String pageId);
}
