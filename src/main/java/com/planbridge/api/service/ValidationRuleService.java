package com.planbridge.api.service;

import com.planbridge.api.dto.request.ValidationRuleRequest;
import com.planbridge.api.dto.response.ValidationRuleResponse;
import com.planbridge.api.entity.PbPolicy;
import com.planbridge.api.entity.PbValidationRule;
import com.planbridge.api.exception.ResourceNotFoundException;
import com.planbridge.api.repository.PbPolicyRepository;
import com.planbridge.api.repository.PbValidationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ValidationRuleService {

    private final PbValidationRuleRepository validationRuleRepository;
    private final PbPolicyRepository policyRepository;

    public List<ValidationRuleResponse> findByPolicyId(String policyId) {
        return validationRuleRepository.findByPolicy_PolicyIdOrderBySortOrder(policyId)
                .stream()
                .map(ValidationRuleResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<ValidationRuleResponse> saveRules(String policyId, List<ValidationRuleRequest> rules) {
        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));

        validationRuleRepository.deleteByPolicy_PolicyId(policyId);

        List<PbValidationRule> saved = new ArrayList<>();
        int order = 0;
        for (ValidationRuleRequest req : rules) {
            PbValidationRule rule = PbValidationRule.builder()
                    .policy(policy)
                    .ruleType(req.getRuleType())
                    .fieldName(req.getFieldName())
                    .ruleValue(req.getRuleValue())
                    .errorMessage(req.getErrorMessage())
                    .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : order)
                    .build();
            saved.add(validationRuleRepository.save(rule));
            order++;
        }

        return saved.stream().map(ValidationRuleResponse::from).collect(Collectors.toList());
    }

    @Transactional
    public void parseFromPolicyContent(String policyId, String policyContent) {
        if (policyContent == null || policyContent.isBlank()) return;

        PbPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));

        validationRuleRepository.deleteByPolicy_PolicyId(policyId);

        List<PbValidationRule> rules = new ArrayList<>();
        int order = 0;

        if (policyContent.contains("필수")) {
            rules.add(PbValidationRule.builder()
                    .policy(policy).ruleType("REQUIRED")
                    .errorMessage("필수 입력 항목입니다.").sortOrder(order++).build());
        }

        Pattern minPattern = Pattern.compile("최소\\s*(\\d+)\\s*자|([\\d]+)\\s*자\\s*이상");
        Matcher minMatcher = minPattern.matcher(policyContent);
        while (minMatcher.find()) {
            String value = minMatcher.group(1) != null ? minMatcher.group(1) : minMatcher.group(2);
            rules.add(PbValidationRule.builder()
                    .policy(policy).ruleType("MIN_LENGTH").ruleValue(value)
                    .errorMessage("최소 " + value + "자 이상 입력해야 합니다.").sortOrder(order++).build());
        }

        Pattern maxPattern = Pattern.compile("최대\\s*(\\d+)\\s*자|([\\d]+)\\s*자\\s*이하");
        Matcher maxMatcher = maxPattern.matcher(policyContent);
        while (maxMatcher.find()) {
            String value = maxMatcher.group(1) != null ? maxMatcher.group(1) : maxMatcher.group(2);
            rules.add(PbValidationRule.builder()
                    .policy(policy).ruleType("MAX_LENGTH").ruleValue(value)
                    .errorMessage("최대 " + value + "자까지 입력 가능합니다.").sortOrder(order++).build());
        }

        for (PbValidationRule rule : rules) {
            validationRuleRepository.save(rule);
        }

        log.info("Parsed {} validation rules from policy {}", rules.size(), policyId);
    }
}
