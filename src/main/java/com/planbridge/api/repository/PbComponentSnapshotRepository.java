package com.planbridge.api.repository;

import com.planbridge.api.entity.PbComponentSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PbComponentSnapshotRepository extends JpaRepository<PbComponentSnapshot, String> {
    List<PbComponentSnapshot> findByComponent_ComponentIdOrderByCreatedAtDesc(String componentId);

    // 스캔 시 해당 페이지의 이전 스냅샷을 전부 지우고 최신 상태만 남긴다
    // (자동 스캔이 반복될 때 스냅샷이 무한 누적되는 것을 방지)
    void deleteByComponent_Page_PageId(String pageId);
}
