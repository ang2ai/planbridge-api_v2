package com.planbridge.api.repository;

import com.planbridge.api.entity.PbPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PbPageRepository extends JpaRepository<PbPage, String> {
    List<PbPage> findByProject_ProjectId(String projectId);
    // 동시 스캔 요청 경합 시 같은 (project,routePath)로 페이지가 중복 생성될 수 있어 First로 완화
    Optional<PbPage> findFirstByProject_ProjectIdAndRoutePathOrderByPageIdAsc(String projectId, String routePath);
    List<PbPage> findByProject_ProjectIdAndStatus(String projectId, String status);
}
