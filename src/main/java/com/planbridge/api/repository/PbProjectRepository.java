package com.planbridge.api.repository;

import com.planbridge.api.entity.PbProject;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PbProjectRepository extends JpaRepository<PbProject, String> {
    List<PbProject> findByStatus(String status);
    List<PbProject> findByStatusOrderByCreatedAtDesc(String status);

    // 스캔 처리 직렬화용: 같은 프로젝트에 대한 동시 스캔은 프로젝트 행 잠금으로
    // 순차 실행시켜 페이지/컴포넌트 find-or-create 경쟁 자체를 제거한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PbProject> findWithLockByProjectId(String projectId);
}
