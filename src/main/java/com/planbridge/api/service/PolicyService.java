package com.planbridge.api.service;

import com.planbridge.api.dto.request.PolicyCreateRequest;
import com.planbridge.api.dto.request.PolicyUpdateRequest;
import com.planbridge.api.dto.response.PolicyImpactResponse;
import com.planbridge.api.dto.response.PolicyResponse;
import com.planbridge.api.entity.*;
import com.planbridge.api.exception.ResourceNotFoundException;
import com.planbridge.api.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class PolicyService {

    private final PbPolicyRepository policyRepository;
    private final PbPolicyVersionRepository policyVersionRepository;
    private final PbPolicyLinkRepository policyLinkRepository;
    private final PbProjectRepository projectRepository;
    private final PbPageRepository pageRepository;
    private final PbComponentRepository componentRepository;
    private final ValidationRuleService validationRuleService;

    public PolicyService(PbPolicyRepository policyRepository,
                         PbPolicyVersionRepository policyVersionRepository,
                         PbPolicyLinkRepository policyLinkRepository,
                         PbProjectRepository projectRepository,
                         PbPageRepository pageRepository,
                         PbComponentRepository componentRepository,
                         @Lazy ValidationRuleService validationRuleService) {
        this.policyRepository = policyRepository;
        this.policyVersionRepository = policyVersionRepository;
        this.policyLinkRepository = policyLinkRepository;
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.componentRepository = componentRepository;
        this.validationRuleService = validationRuleService;
    }

    public List<PolicyResponse> findByComponent(String componentId) {
        // 중복 제거용 policyId 추적 Set
        Set<String> seen = new LinkedHashSet<>();
        List<PolicyResponse> result = new ArrayList<>();

        // 1. 직접 적용된 정책 (SCOPE=COMPONENT, COMPONENT_ID=componentId)
        List<PbPolicy> direct = policyRepository.findByComponent_ComponentIdAndStatus(componentId, "ACTIVE");
        direct.forEach(p -> {
            if (seen.add(p.getPolicyId())) {
                PolicyResponse resp = PolicyResponse.from(p);
                resp.setLinkType("APPLIED");
                result.add(resp);
            }
        });

        // 2. PB_POLICY_LINK 통해 연결된 정책
        List<PbPolicyLink> links = policyLinkRepository.findByComponent_ComponentId(componentId);
        links.forEach(l -> {
            String pid = l.getPolicy().getPolicyId();
            if (seen.add(pid)) {
                PolicyResponse resp = PolicyResponse.from(l.getPolicy());
                resp.setLinkType(l.getLinkType());
                result.add(resp);
            }
        });

        // 3. 계층 상속: PAGE / GLOBAL 정책 추가 (page, project eager 로딩)
        PbComponent component = componentRepository.findWithPageAndProjectByComponentId(componentId).orElse(null);
        if (component != null && component.getPage() != null) {
            PbPage page = component.getPage();
            String pageId = page.getPageId();

            // 3a. PAGE 범위 정책
            List<PbPolicy> pagePolicies = policyRepository.findByPage_PageIdAndStatus(pageId, "ACTIVE");
            pagePolicies.forEach(p -> {
                if (seen.add(p.getPolicyId())) {
                    PolicyResponse resp = PolicyResponse.from(p);
                    resp.setLinkType("INHERITED_PAGE");
                    result.add(resp);
                }
            });

            // 3b. GLOBAL 범위 정책 (프로젝트 전체)
            if (page.getProject() != null) {
                String projectId = page.getProject().getProjectId();
                List<PbPolicy> globalPolicies = policyRepository
                        .findByScopeAndProject_ProjectIdAndStatus("GLOBAL", projectId, "ACTIVE");
                globalPolicies.forEach(p -> {
                    if (seen.add(p.getPolicyId())) {
                        PolicyResponse resp = PolicyResponse.from(p);
                        resp.setLinkType("INHERITED_GLOBAL");
                        result.add(resp);
                    }
                });
            }
        }

        // DemoPage 폴백: DB에 정책이 없으면 하드코딩 정책 반환
        // ① pbId 직접 전달 (componentId가 "Demo..."로 시작)
        // ② UUID 전달 시 → component의 pbId로 재시도
        if (result.isEmpty()) {
            String demoFallbackPbId = null;
            if (componentId != null && componentId.startsWith("Demo")) {
                demoFallbackPbId = componentId;
            } else if (component != null && component.getPbId() != null
                       && component.getPbId().startsWith("Demo")) {
                demoFallbackPbId = component.getPbId();
            }
            if (demoFallbackPbId != null) {
                return getDemoPolicies(demoFallbackPbId);
            }
        }

        return result;
    }

    private List<PolicyResponse> getDemoPolicies(String pbId) {
        List<PolicyResponse> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // pbId 계층 분해: DemoPage.SearchForm.CustomerInput → DemoPage, DemoPage.SearchForm, DemoPage.SearchForm.CustomerInput
        String[] parts = pbId.split("\\.");
        List<String> hierarchy = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(".");
            sb.append(p);
            hierarchy.add(sb.toString());
        }

        // 요소별 직접 정책
        Map<String, List<PolicyResponse>> directPolicies = buildDemoPolicyMap(now);
        Set<String> seen = new LinkedHashSet<>();

        // 자기 자신 정책 (COMPONENT 스코프)
        List<PolicyResponse> own = directPolicies.getOrDefault(pbId, List.of());
        own.forEach(p -> { if (seen.add(p.getPolicyId())) list.add(p); });

        // 부모 정책 (PAGE 스코프로 변환)
        for (int i = 0; i < hierarchy.size() - 1; i++) {
            String parentPbId = hierarchy.get(i);
            List<PolicyResponse> parentPolicies = directPolicies.getOrDefault(parentPbId, List.of());
            parentPolicies.forEach(p -> {
                if (seen.add(p.getPolicyId())) {
                    PolicyResponse inherited = PolicyResponse.builder()
                            .policyId(p.getPolicyId())
                            .scope("PAGE").policyType(p.getPolicyType())
                            .policyTitle(p.getPolicyTitle()).policyContent(p.getPolicyContent())
                            .currentVersion(p.getCurrentVersion()).status("ACTIVE")
                            .createdBy(p.getCreatedBy()).createdAt(now)
                            .linkType("INHERITED_PAGE").build();
                    list.add(inherited);
                }
            });
        }
        return list;
    }

    private Map<String, List<PolicyResponse>> buildDemoPolicyMap(LocalDateTime now) {
        Map<String, List<PolicyResponse>> m = new LinkedHashMap<>();
        m.put("DemoPage", List.of(
            pr("demo-pol-page-001","COMPONENT","DATA_POLICY","[DemoPage] 주문 관리 화면 접근 정책","주문 관리 화면은 운영자 및 CS 담당자만 접근 가능합니다. 역할 기반 접근 제어(RBAC)를 적용하며, 미인증 사용자는 로그인 페이지로 리다이렉트합니다.",1,now)
        ));
        m.put("DemoPage.StatsRow", List.of(
            pr("demo-pol-stat-001","COMPONENT","UI_POLICY","[통계 카드] 실시간 갱신 정책","통계 수치는 60초마다 자동 갱신됩니다. 갱신 중에는 스피너를 표시하며 이전 값을 유지합니다.",1,now)
        ));
        m.put("DemoPage.StatsRow.오늘주문Card", List.of(
            pr("demo-pol-today-001","COMPONENT","DATA_POLICY","[오늘 주문 카드] 집계 기준 정책","오늘 주문 수는 당일 00:00~현재 시각까지 CONFIRMED 이상 상태의 주문을 집계합니다. 취소·반품 건은 제외합니다.",1,now),
            pr("demo-pol-today-002","COMPONENT","UI_POLICY","[오늘 주문 카드] 증감 표시 정책","전일 대비 증감은 ±N건 형식으로 표시하며, 증가는 초록색 ▲, 감소는 빨간색 ▼로 구분합니다.",1,now)
        ));
        m.put("DemoPage.StatsRow.신규회원Card", List.of(
            pr("demo-pol-nc-001","COMPONENT","DATA_POLICY","[신규 회원] 집계 기준 정책","오늘 가입 완료(이메일 인증 포함)한 신규 회원 수를 표시합니다. 탈퇴 회원은 제외합니다.",1,now),
            pr("demo-pol-nc-002","COMPONENT","UI_POLICY","[신규 회원] 증감 표시 정책","전일 대비 증감을 ±N명으로 표시합니다. 5명 이상 증가 시 초록색 강조, 감소 시 주황색으로 표시합니다.",1,now)
        ));
        m.put("DemoPage.StatsRow.오늘매출Card", List.of(
            pr("demo-pol-rev-001","COMPONENT","DATA_POLICY","[오늘 매출] 집계 기준 정책","오늘 결제 완료된 주문의 총 금액 합산입니다. PAID·SHIPPED·DELIVERED 상태만 포함하며 취소·환불 금액은 차감합니다.",1,now),
            pr("demo-pol-rev-002","COMPONENT","BIZ_RULE","[오늘 매출] 목표 대비 표시 정책","당일 목표 매출 대비 달성률을 % 형태로 표시합니다. 100% 초과 시 초록색 강조, 70% 미만 시 빨간색 경고를 표시합니다.",1,now)
        ));
        m.put("DemoPage.StatsRow.처리대기Card", List.of(
            pr("demo-pol-pend-001","COMPONENT","BUSINESS_POLICY","[처리 대기] 알림 기준 정책","처리 대기 건이 5건 이상이면 카드 테두리를 빨간색으로 강조하고 담당자에게 슬랙 알림을 발송합니다.",1,now)
        ));
        m.put("DemoPage.SearchForm", List.of(
            pr("demo-pol-form-001","COMPONENT","UI_POLICY","[검색 폼] 입력 길이 제한 정책","고객명/주문번호 검색어는 최소 2자 이상 입력해야 검색 버튼이 활성화됩니다. 특수문자는 허용하지 않습니다.",1,now)
        ));
        m.put("DemoPage.SearchForm.CustomerInput", List.of(
            pr("demo-pol-ci-001","COMPONENT","VALIDATION_POLICY","[고객명 입력] 유효성 검사 정책","고객명은 2~50자의 한글/영문/숫자만 허용합니다. SQL Injection 방지를 위해 특수문자 입력 시 즉시 차단합니다.",1,now),
            pr("demo-pol-ci-002","COMPONENT","DATA_POLICY","[고객명 입력] 자동완성 정책","최근 3개월 이내 검색한 고객명 상위 5건을 자동완성으로 제안합니다. 개인정보 마스킹 규칙을 따릅니다.",1,now)
        ));
        m.put("DemoPage.SearchForm.StatusSelect", List.of(
            pr("demo-pol-ss-001","COMPONENT","BUSINESS_POLICY","[주문 상태 필터] 표시 항목 정책","상태 필터 목록: ALL / PENDING(결제 대기) / CONFIRMED(주문 확인) / SHIPPING(배송 중) / DELIVERED(배송 완료) / CANCELLED(취소).",1,now)
        ));
        m.put("DemoPage.SearchForm.SearchButton", List.of(
            pr("demo-pol-sb-001","COMPONENT","UI_POLICY","[검색 버튼] 중복 클릭 방지 정책","검색 실행 중에는 버튼을 비활성화하고 로딩 스피너를 표시합니다. 이전 요청이 완료되기 전에 중복 요청을 차단합니다.",1,now)
        ));
        m.put("DemoPage.SearchForm.ResetButton", List.of(
            pr("demo-pol-rb-001","COMPONENT","UI_POLICY","[초기화 버튼] 동작 정책","초기화 시 검색어, 상태 필터를 모두 비우고 전체 주문 목록을 다시 로드합니다. 선택된 주문 상세 패널은 닫힙니다.",1,now)
        ));
        m.put("DemoPage.OrderTable", List.of(
            pr("demo-pol-tbl-001","COMPONENT","DATA_POLICY","[주문 테이블] 페이지네이션 정책","한 페이지에 최대 20건을 표시합니다. 전체 건수가 100건 초과 시 서버 사이드 페이지네이션으로 전환합니다.",1,now),
            pr("demo-pol-tbl-002","COMPONENT","UI_POLICY","[주문 테이블] 행 클릭 정책","주문 행 클릭 시 우측 상세 패널을 열고, 이전에 열려 있던 패널은 자동으로 닫힙니다.",1,now)
        ));
        m.put("DemoPage.OrderTable.OrderIdCol", List.of(
            pr("demo-pol-oid-001","COMPONENT","DATA_POLICY","[주문번호 컬럼] 형식 정책","주문번호는 ORD-YYYYMMDD-NNNNNN 형식으로 표시합니다. 클릭 시 주문 상세 패널이 열립니다.",1,now)
        ));
        m.put("DemoPage.OrderTable.CustomerCol", List.of(
            pr("demo-pol-cust-001","COMPONENT","DATA_POLICY","[고객명 컬럼] 개인정보 마스킹 정책","고객명은 성을 제외한 나머지 이름을 '*'로 마스킹합니다(예: 김**). 관리자 권한 이상만 전체 이름 확인 가능합니다.",1,now),
            pr("demo-pol-cust-002","COMPONENT","UI_POLICY","[고객명 컬럼] 정렬 정책","고객명 컬럼은 가나다 순 오름차순/내림차순 정렬을 지원합니다. 기본 정렬은 주문 일시 내림차순입니다.",1,now)
        ));
        m.put("DemoPage.OrderTable.PriceCol", List.of(
            pr("demo-pol-price-001","COMPONENT","DATA_POLICY","[결제금액 컬럼] 표시 형식 정책","금액은 원화 기준 천 단위 콤마(,)를 적용하며 '원' 단위로 표시합니다. 부가세 포함 금액입니다.",1,now)
        ));
        m.put("DemoPage.OrderTable.StatusCol", List.of(
            pr("demo-pol-status-001","COMPONENT","UI_POLICY","[상태 컬럼] 색상 코딩 정책","PENDING: 회색, CONFIRMED: 파란색, SHIPPING: 주황색, DELIVERED: 초록색, CANCELLED: 빨간색으로 뱃지 색상을 구분합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel", List.of(
            pr("demo-pol-det-001","COMPONENT","BUSINESS_POLICY","[주문 상세] 수정 권한 정책","DELIVERED 또는 CANCELLED 상태인 주문은 상태 변경 및 메모 수정이 불가능합니다. 저장/취소 버튼을 비활성화합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.CustomerInfo", List.of(
            pr("demo-pol-ci2-001","COMPONENT","DATA_POLICY","[고객 정보] 표시 정책","고객 이름, 연락처, 배송지 주소를 표시합니다. 연락처는 뒷 4자리만 표시하며(예: 010-****-5678) 관리자만 전체 확인 가능합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.ProductInfo", List.of(
            pr("demo-pol-pi-001","COMPONENT","DATA_POLICY","[상품 정보] 표시 정책","주문 상품명, 수량, 단가, 총액을 목록 형태로 표시합니다. 이미지 썸네일은 70x70px 크기로 표시합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.StatusChanger", List.of(
            pr("demo-pol-sc-001","COMPONENT","BUSINESS_POLICY","[상태 변경] 상태 전이 규칙","허용된 상태 전이: PENDING→CONFIRMED, CONFIRMED→SHIPPING, SHIPPING→DELIVERED. 역방향 전이 및 임의 변경은 서버에서 거부합니다.",1,now),
            pr("demo-pol-sc-002","COMPONENT","SECURITY_POLICY","[상태 변경] 감사 로그 정책","상태 변경 시 변경자 ID, 이전 상태, 변경 후 상태, 타임스탬프를 ORDER_AUDIT_LOG 테이블에 기록합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.MemoField", List.of(
            pr("demo-pol-memo-001","COMPONENT","DATA_POLICY","[메모 필드] 입력 제한 정책","메모는 최대 500자까지 입력 가능합니다. 저장 시 XSS 방지를 위해 HTML 태그를 이스케이프 처리합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.ActionButtons", List.of(
            pr("demo-pol-ab-001","COMPONENT","UI_POLICY","[액션 버튼 영역] 표시 조건 정책","저장/취소 버튼은 내용이 수정된 경우에만 활성화됩니다. 수정 사항 없이 닫기 시 확인 다이얼로그를 생략합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.ActionButtons.SaveButton", List.of(
            pr("demo-pol-save-001","COMPONENT","BUSINESS_POLICY","[저장 버튼] 저장 처리 정책","저장 클릭 시 상태 변경 및 메모 수정 내용을 동시에 저장합니다. 저장 완료 후 성공 토스트 메시지를 2초간 표시합니다.",1,now),
            pr("demo-pol-save-002","COMPONENT","UI_POLICY","[저장 버튼] 중복 클릭 방지 정책","저장 요청 중에는 버튼을 비활성화하고 로딩 스피너를 표시합니다. 응답 후 버튼 상태를 복원합니다.",1,now)
        ));
        m.put("DemoPage.OrderDetailPanel.ActionButtons.CancelButton", List.of(
            pr("demo-pol-cancel-001","COMPONENT","UI_POLICY","[취소 버튼] 동작 정책","취소 클릭 시 수정 내용을 버리고 원래 값으로 복원합니다. 수정 중이라면 '변경 사항을 취소하시겠습니까?' 확인 다이얼로그를 표시합니다.",1,now)
        ));
        return m;
    }

    private PolicyResponse pr(String id, String scope, String type, String title, String content, int ver, LocalDateTime now) {
        return PolicyResponse.builder()
                .policyId(id).scope(scope).policyType(type)
                .policyTitle(title).policyContent(content)
                .currentVersion(ver).status("ACTIVE")
                .createdBy("demo").createdAt(now)
                .linkType("APPLIED").build();
    }

    public PolicyResponse findById(String policyId) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));
        return PolicyResponse.from(policy);
    }

    public List<PolicyResponse> search(String projectId, String q) {
        String keyword = "%" + (q == null ? "" : q.toLowerCase()) + "%";
        List<PbPolicy> policies;
        if (projectId == null || projectId.isBlank()) {
            // projectId 없으면 전체 검색
            policies = policyRepository.searchAll(keyword);
        } else {
            policies = policyRepository.searchByKeyword(projectId, keyword);
        }
        return policies.stream().map(PolicyResponse::from).collect(Collectors.toList());
    }

    public List<PolicyResponse> findHistoryByPolicy(String policyId) {
        return policyVersionRepository.findByPolicy_PolicyIdOrderByVersionNoDesc(policyId)
                .stream()
                .map(v -> PolicyResponse.builder()
                        .policyId(policyId)
                        .currentVersion(v.getVersionNo())
                        .policyContent(v.getPolicyContent())
                        .policySchema(v.getPolicySchema())
                        .createdBy(v.getCreatedBy())
                        .createdAt(v.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public PolicyResponse create(PolicyCreateRequest req) {
        PbProject project = projectRepository.findById(req.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", req.getProjectId()));

        PbPolicy.PbPolicyBuilder builder = PbPolicy.builder()
                .project(project)
                .scope(req.getScope())
                .policyType(req.getPolicyType())
                .policyTitle(req.getPolicyTitle())
                .policyContent(req.getPolicyContent())
                .policySchema(req.getPolicySchema())
                .tags(req.getTags())
                .createdBy(req.getCreatedBy());

        if (req.getPageId() != null) {
            PbPage page = pageRepository.findById(req.getPageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Page", req.getPageId()));
            builder.page(page);
        }

        if (req.getComponentId() != null) {
            PbComponent component = componentRepository.findById(req.getComponentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Component", req.getComponentId()));
            builder.component(component);
        }

        PbPolicy policy = policyRepository.save(builder.build());

        // 컴포넌트 연결이 있으면 PB_POLICY_LINK도 생성
        if (req.getComponentId() != null) {
            PbComponent component = componentRepository.findById(req.getComponentId()).get();
            PbPolicyLink link = PbPolicyLink.builder()
                    .policy(policy)
                    .component(component)
                    .linkType("APPLIED")
                    .build();
            policyLinkRepository.save(link);
        }

        // VALIDATION 타입이면 정책 내용에서 룰 자동 파싱
        if ("VALIDATION".equals(req.getPolicyType()) && req.getPolicyContent() != null) {
            validationRuleService.parseFromPolicyContent(policy.getPolicyId(), req.getPolicyContent());
        }

        return PolicyResponse.from(policy);
    }

    @Transactional
    public PolicyResponse update(String policyId, PolicyUpdateRequest req) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));

        // 버전 이력 저장
        PbPolicyVersion version = PbPolicyVersion.builder()
                .policy(policy)
                .versionNo(policy.getCurrentVersion())
                .policyContent(policy.getPolicyContent())
                .policySchema(policy.getPolicySchema())
                .changeReason(req.getChangeReason())
                .createdBy(req.getUpdatedBy() != null ? req.getUpdatedBy() : "system")
                .build();
        policyVersionRepository.save(version);

        // 정책 업데이트
        boolean contentChanged = req.getPolicyContent() != null
                && !req.getPolicyContent().equals(policy.getPolicyContent());

        if (req.getPolicyTitle() != null) policy.setPolicyTitle(req.getPolicyTitle());
        if (req.getPolicyContent() != null) policy.setPolicyContent(req.getPolicyContent());
        if (req.getPolicySchema() != null) policy.setPolicySchema(req.getPolicySchema());
        if (req.getTags() != null) policy.setTags(req.getTags());
        if (req.getUpdatedBy() != null) policy.setUpdatedBy(req.getUpdatedBy());
        if (req.getStatus() != null) policy.setStatus(req.getStatus());
        policy.setCurrentVersion(policy.getCurrentVersion() + 1);

        PbPolicy saved = policyRepository.save(policy);

        // VALIDATION 타입이고 content가 변경됐으면 룰 재파싱
        if ("VALIDATION".equals(saved.getPolicyType()) && contentChanged) {
            validationRuleService.parseFromPolicyContent(saved.getPolicyId(), saved.getPolicyContent());
        }

        return PolicyResponse.from(saved);
    }

    // -----------------------------------------------------------------------
    // Policy Link (Override)
    // -----------------------------------------------------------------------

    @Transactional
    public void linkToComponent(String policyId, String componentId, String overrideContent, String linkType) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));
        PbComponent component = componentRepository.findById(componentId)
                .orElseThrow(() -> new ResourceNotFoundException("Component", componentId));

        // 이미 존재하면 덮어쓰기
        policyLinkRepository.findByPolicy_PolicyIdAndComponent_ComponentId(policyId, componentId)
                .ifPresent(existing -> policyLinkRepository.deleteByPolicy_PolicyIdAndComponent_ComponentId(policyId, componentId));

        PbPolicyLink link = PbPolicyLink.builder()
                .policy(policy)
                .component(component)
                .linkType(linkType != null ? linkType : "APPLIED")
                .overrideContent(overrideContent)
                .build();
        policyLinkRepository.save(link);
    }

    @Transactional
    public void delete(String policyId) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));
        policy.setStatus("DELETED");
        policyRepository.save(policy);
    }

    // -----------------------------------------------------------------------
    // Consistency check
    // -----------------------------------------------------------------------

    public List<Map<String, Object>> consistencyCheck(String projectId) {
        List<PbPolicy> policies = policyRepository.findByProject_ProjectIdAndStatus(projectId, "ACTIVE");
        List<Map<String, Object>> issues = new ArrayList<>();

        // 1. DUPLICATE_TITLE — policies whose title shares the first 20 characters
        Map<String, List<PbPolicy>> byPrefix = new java.util.LinkedHashMap<>();
        for (PbPolicy p : policies) {
            String title = p.getPolicyTitle() != null ? p.getPolicyTitle() : "";
            String prefix = title.length() > 20 ? title.substring(0, 20) : title;
            byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<PbPolicy>> entry : byPrefix.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> ids = entry.getValue().stream()
                        .map(PbPolicy::getPolicyId)
                        .collect(Collectors.toList());
                Map<String, Object> issue = new java.util.LinkedHashMap<>();
                issue.put("type", "DUPLICATE_TITLE");
                issue.put("policyIds", ids);
                issue.put("description", "정책 제목의 앞 20자가 동일합니다: \"" + entry.getKey() + "\"");
                issues.add(issue);
            }
        }

        // 2. CONFLICTING_SCOPE — same policyType + scope + same component
        // Group by policyType + scope + componentId (non-null)
        Map<String, List<PbPolicy>> byTypeScope = new java.util.LinkedHashMap<>();
        for (PbPolicy p : policies) {
            String componentId = p.getComponent() != null ? p.getComponent().getComponentId() : null;
            if (componentId == null) continue; // Only flag component-level conflicts
            String key = p.getPolicyType() + "||" + p.getScope() + "||" + componentId;
            byTypeScope.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<PbPolicy>> entry : byTypeScope.entrySet()) {
            if (entry.getValue().size() > 1) {
                String[] parts = entry.getKey().split("\\|\\|", 3);
                List<String> ids = entry.getValue().stream()
                        .map(PbPolicy::getPolicyId)
                        .collect(Collectors.toList());
                Map<String, Object> issue = new java.util.LinkedHashMap<>();
                issue.put("type", "CONFLICTING_SCOPE");
                issue.put("policyIds", ids);
                issue.put("description", "동일한 컴포넌트에 같은 유형/범위의 정책이 중복 적용되었습니다"
                        + " (type=" + parts[0] + ", scope=" + parts[1] + ", componentId=" + parts[2] + ")");
                issues.add(issue);
            }
        }

        return issues;
    }

    public PolicyImpactResponse getImpact(String policyId) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));

        String scope = policy.getScope();
        List<PolicyImpactResponse.AffectedComponent> affected = new ArrayList<>();

        if ("GLOBAL".equals(scope)) {
            // 프로젝트 전체 활성 컴포넌트
            String projectId = policy.getProject().getProjectId();
            List<PbComponent> components = componentRepository.findByProjectId(projectId);
            components.stream()
                    .filter(c -> "ACTIVE".equals(c.getStatus()))
                    .forEach(c -> affected.add(PolicyImpactResponse.AffectedComponent.builder()
                            .componentId(c.getComponentId())
                            .componentName(c.getComponentName())
                            .pagePath(c.getPage() != null ? c.getPage().getRoutePath() : null)
                            .build()));

        } else if ("PAGE".equals(scope)) {
            // 해당 페이지의 컴포넌트
            if (policy.getPage() != null) {
                String pageId = policy.getPage().getPageId();
                List<PbComponent> components =
                        componentRepository.findByPage_PageIdOrderByDepthLevelAscSortOrderAsc(pageId);
                components.stream()
                        .filter(c -> "ACTIVE".equals(c.getStatus()))
                        .forEach(c -> affected.add(PolicyImpactResponse.AffectedComponent.builder()
                                .componentId(c.getComponentId())
                                .componentName(c.getComponentName())
                                .pagePath(c.getPage() != null ? c.getPage().getRoutePath() : null)
                                .build()));
            }

        } else {
            // COMPONENT scope: PB_POLICY_LINK + 직접 연결 컴포넌트
            Set<String> seen = new LinkedHashSet<>();

            // 직접 연결된 컴포넌트
            if (policy.getComponent() != null) {
                PbComponent c = policy.getComponent();
                if (seen.add(c.getComponentId())) {
                    affected.add(PolicyImpactResponse.AffectedComponent.builder()
                            .componentId(c.getComponentId())
                            .componentName(c.getComponentName())
                            .pagePath(c.getPage() != null ? c.getPage().getRoutePath() : null)
                            .build());
                }
            }

            // POLICY_LINK를 통해 연결된 컴포넌트
            List<PbPolicyLink> links = policyLinkRepository.findByPolicy_PolicyId(policyId);
            links.forEach(link -> {
                PbComponent c = link.getComponent();
                if (c != null && seen.add(c.getComponentId())) {
                    affected.add(PolicyImpactResponse.AffectedComponent.builder()
                            .componentId(c.getComponentId())
                            .componentName(c.getComponentName())
                            .pagePath(c.getPage() != null ? c.getPage().getRoutePath() : null)
                            .build());
                }
            });
        }

        return PolicyImpactResponse.builder()
                .scope(scope)
                .affectedCount((long) affected.size())
                .affectedComponents(affected)
                .build();
    }
}
