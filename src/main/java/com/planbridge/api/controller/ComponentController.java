package com.planbridge.api.controller;

import com.planbridge.api.dto.request.ComponentResolveRequest;
import com.planbridge.api.dto.response.ApiResponse;
import com.planbridge.api.dto.response.ComponentResponse;
import com.planbridge.api.dto.response.TodoResponse;
import com.planbridge.api.service.ComponentService;
import com.planbridge.api.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService componentService;
    private final TodoService todoService;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ComponentResponse>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(componentService.findById(id)));
    }

    @PostMapping("/resolve")
    public ResponseEntity<ApiResponse<ComponentResponse>> resolve(@RequestBody ComponentResolveRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(componentService.resolve(req)));
    }

    // 컴포넌트 대상 변경요청에 딸린 TODO 목록 (크롬 익스텐션 개발자 패널용)
    @GetMapping("/{id}/todos")
    public ResponseEntity<ApiResponse<List<TodoResponse>>> todos(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.ok(todoService.findByComponentId(id)));
    }
}
