package com.planbridge.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planbridge.api.entity.*;
import com.planbridge.api.repository.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * planbridge-worker_v2 의 changeRequestAnalyzer.ts + agent.ts 를 Java로 통합.
 * @Async("analysisExecutor") 로 백그라운드 실행, 트랜잭션은 sub-method 단위로 분리.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAnalysisService {

    private final PbAnalysisQueueRepository queueRepository;
    private final PbChangeRequestRepository changeRequestRepository;
    private final PbComponentRepository componentRepository;
    private final PbPolicyRepository policyRepository;
    private final PbTodoItemRepository todoItemRepository;
    private final PbProjectRepository projectRepository;
    private final AiService aiService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private AiAnalysisService self; // 프록시를 통한 @Transactional 적용

    // ── 비동기 진입점 ──────────────────────────────────────────────────────────

    @Async("analysisExecutor")
    public void analyzeAsync(String queueId) {
        log.info("AI 분석 시작: queueId={}", queueId);

        AnalysisContext ctx;
        try {
            ctx = self.loadContext(queueId);
        } catch (Exception e) {
            log.error("컨텍스트 로딩 실패: queueId={}", queueId, e);
            safeMarkFailed(queueId, "데이터 로딩 실패: " + truncate(e.getMessage()));
            return;
        }

        String aiResult;
        try {
            aiResult = aiService.call(ctx.systemPrompt, ctx.userPrompt);
        } catch (Exception e) {
            log.error("AI 호출 실패: queueId={}", queueId, e);
            safeMarkFailed(queueId, "AI 호출 실패: " + truncate(e.getMessage()));
            return;
        }

        try {
            self.saveResults(queueId, ctx, aiResult);
            log.info("AI 분석 완료: queueId={}", queueId);
        } catch (Exception e) {
            log.error("결과 저장 실패: queueId={}", queueId, e);
            safeMarkFailed(queueId, "결과 저장 실패: " + truncate(e.getMessage()));
        }
    }

    // ── 데이터 로딩 (읽기 전용 트랜잭션) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public AnalysisContext loadContext(String queueId) {
        PbAnalysisQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found: " + queueId));

        String requestId = queue.getRequestId();
        String componentId = parseComponentId(queue.getRequestPayload());

        PbChangeRequest cr = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("ChangeRequest not found: " + requestId));

        PbComponent component = (componentId != null)
                ? componentRepository.findById(componentId).orElse(null)
                : null;

        List<PbPolicy> policies = (component != null)
                ? policyRepository.findByComponent_ComponentIdAndStatus(component.getComponentId(), "ACTIVE")
                : List.of();

        String projectId = queue.getProject().getProjectId(); // 트랜잭션 내 lazy loading
        PbProject project = projectRepository.findById(projectId).orElse(null);

        String projectName = project != null ? project.getProjectName() : "PlanBridge";
        String framework = project != null && project.getFramework() != null ? project.getFramework() : "React";
        String repoPath = project != null && project.getRepoLocalPath() != null
                ? project.getRepoLocalPath()
                : "/repos/" + projectId;

        String systemPrompt = buildSystemPrompt(repoPath);
        String userPrompt = buildUserPrompt(cr, component, policies, projectName, framework, repoPath);

        AnalysisContext ctx = new AnalysisContext();
        ctx.requestId = requestId;
        ctx.systemPrompt = systemPrompt;
        ctx.userPrompt = userPrompt;
        ctx.changeRequestId = requestId;
        return ctx;
    }

    // ── 결과 저장 (쓰기 트랜잭션) ─────────────────────────────────────────────

    @Transactional
    public void saveResults(String queueId, AnalysisContext ctx, String aiResult) {
        AnalysisResult result = parseResult(aiResult);

        PbChangeRequest cr = changeRequestRepository.findById(ctx.changeRequestId)
                .orElseThrow(() -> new RuntimeException("ChangeRequest not found"));

        // TODO 저장
        if (result.todos != null && !result.todos.isEmpty()) {
            for (int i = 0; i < result.todos.size(); i++) {
                TodoDto todo = result.todos.get(i);
                String targetFilesJson = toJson(todo.targetFiles);
                todoItemRepository.save(PbTodoItem.builder()
                        .changeRequest(cr)
                        .title(todo.title != null ? todo.title : "TODO " + (i + 1))
                        .prompt(todo.prompt != null ? todo.prompt : "")
                        .targetFiles(targetFilesJson)
                        .complexity(todo.complexity != null ? todo.complexity : "MODERATE")
                        .sortOrder(i)
                        .build());
            }
            log.info("TODO {}건 저장 완료: requestId={}", result.todos.size(), ctx.changeRequestId);
        }

        // 변경요청 상태 업데이트
        cr.setAiAnalysis(toJson(result));
        cr.setStatus("ANALYZED");
        changeRequestRepository.save(cr);

        // 큐 상태 완료
        PbAnalysisQueue queue = queueRepository.findById(queueId)
                .orElseThrow(() -> new RuntimeException("Queue not found"));
        String resultStr = toJson(result);
        queue.setStatus("COMPLETED");
        queue.setResult(truncate(resultStr, 4000));
        queue.setCompletedAt(LocalDateTime.now());
        queueRepository.save(queue);
    }

    @Transactional
    public void markFailed(String queueId, String errorMsg) {
        queueRepository.findById(queueId).ifPresent(q -> {
            q.setStatus("FAILED");
            q.setErrorMessage(truncate(errorMsg));
            q.setCompletedAt(LocalDateTime.now());
            queueRepository.save(q);

            if (q.getRequestId() != null) {
                changeRequestRepository.findById(q.getRequestId()).ifPresent(cr -> {
                    cr.setStatus("FAILED");
                    changeRequestRepository.save(cr);
                });
            }
        });
    }

    // ── 프롬프트 빌더 ─────────────────────────────────────────────────────────

    private String buildSystemPrompt(String repoPath) {
        return "당신은 소프트웨어 개발 전문 AI 분석가입니다.\n" +
               "기획자의 변경 요청을 분석하여 개발팀을 위한 구체적인 TODO 목록을 생성합니다.\n\n" +
               "출력 형식 (JSON만 반환, 설명 없음):\n" +
               "{\n" +
               "  \"summary\": \"분석 요약 (2-3문장)\",\n" +
               "  \"todos\": [\n" +
               "    {\n" +
               "      \"title\": \"TODO 제목\",\n" +
               "      \"prompt\": \"개발자에게 전달할 구체적인 구현 지시사항 (한국어)\",\n" +
               "      \"targetFiles\": [\"관련 파일 경로 (추정)\"],\n" +
               "      \"complexity\": \"SIMPLE|MODERATE|COMPLEX\"\n" +
               "    }\n" +
               "  ]\n" +
               "}";
    }

    private String buildUserPrompt(PbChangeRequest cr, PbComponent component,
                                   List<PbPolicy> policies, String projectName,
                                   String framework, String repoPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("다음 변경 요청을 분석하고 개발 TODO 목록을 JSON으로 반환하세요.\n\n");

        sb.append("## 프로젝트\n");
        sb.append("- 이름: ").append(projectName).append("\n");
        sb.append("- 프레임워크: ").append(framework).append("\n\n");

        if (component != null) {
            sb.append("## 대상 컴포넌트\n");
            sb.append("- pbId: ").append(component.getPbId()).append("\n");
            sb.append("- 이름: ").append(component.getComponentName()).append("\n");
            sb.append("- 타입: ").append(component.getComponentType()).append("\n");
            if (component.getElementRole() != null) sb.append("- 역할: ").append(component.getElementRole()).append("\n");
            if (component.getTreePath() != null) sb.append("- 계층: ").append(component.getTreePath()).append("\n");
            if (component.getCurrentSpec() != null) sb.append("- 현재 스펙: ").append(component.getCurrentSpec()).append("\n");
            sb.append("\n");
        } else if (cr.getComponentDescription() != null) {
            sb.append("## 대상 컴포넌트\n");
            sb.append("- 설명: ").append(cr.getComponentDescription()).append("\n\n");
        }

        sb.append("## 현재 정책\n");
        if (!policies.isEmpty()) {
            for (PbPolicy p : policies) {
                sb.append("### [").append(p.getPolicyType()).append("] ").append(p.getPolicyTitle()).append("\n");
                sb.append(p.getPolicyContent()).append("\n\n");
            }
        } else {
            sb.append("등록된 정책 없음\n\n");
        }

        sb.append("## 변경 요청\n");
        sb.append("- 제목: ").append(cr.getTitle()).append("\n");
        sb.append("- 우선순위: ").append(cr.getPriority()).append("\n");
        sb.append("- 내용:\n").append(cr.getDescription()).append("\n");
        if (cr.getCurrentState() != null) sb.append("\n## 현재 상태\n").append(cr.getCurrentState()).append("\n");
        if (cr.getDesiredState() != null) sb.append("\n## 원하는 결과\n").append(cr.getDesiredState()).append("\n");

        return sb.toString();
    }

    // ── 유틸리티 ──────────────────────────────────────────────────────────────

    private String parseComponentId(String payload) {
        try {
            Map<String, Object> map = objectMapper.readValue(payload, new TypeReference<>() {});
            return (String) map.get("componentId");
        } catch (Exception e) {
            return null;
        }
    }

    private AnalysisResult parseResult(String text) {
        String json = extractJson(text);
        if (json != null) {
            try {
                return objectMapper.readValue(json, AnalysisResult.class);
            } catch (Exception e) {
                log.warn("JSON 파싱 실패: {}", e.getMessage());
            }
        }
        AnalysisResult fallback = new AnalysisResult();
        fallback.summary = text;
        return fallback;
    }

    private String extractJson(String text) {
        if (text == null) return null;
        // ```json ... ``` 패턴
        int start = text.indexOf("```json");
        if (start != -1) {
            int end = text.indexOf("```", start + 7);
            if (end != -1) return text.substring(start + 7, end).trim();
        }
        // ``` ... ``` 패턴
        start = text.indexOf("```");
        if (start != -1) {
            int end = text.indexOf("```", start + 3);
            if (end != -1) {
                String candidate = text.substring(start + 3, end).trim();
                if (candidate.startsWith("{")) return candidate;
            }
        }
        // { ... } 패턴
        int bStart = text.indexOf("{");
        int bEnd = text.lastIndexOf("}");
        if (bStart != -1 && bEnd > bStart) return text.substring(bStart, bEnd + 1);
        return null;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private String truncate(String s) {
        return truncate(s, 4000);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void safeMarkFailed(String queueId, String msg) {
        try {
            self.markFailed(queueId, msg);
        } catch (Exception e) {
            log.error("markFailed 실패: queueId={}", queueId, e);
        }
    }

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    @Data
    public static class AnalysisContext {
        String requestId;
        String changeRequestId;
        String systemPrompt;
        String userPrompt;
    }

    @Data
    @NoArgsConstructor
    public static class AnalysisResult {
        private String summary;
        private List<TodoDto> todos;
    }

    @Data
    @NoArgsConstructor
    public static class TodoDto {
        private String title;
        private String prompt;
        private List<String> targetFiles;
        private String complexity;
    }
}
