package com.correction.borami.xvarm.controller;

import com.correction.borami.xvarm.model.ExtractDtos.ExtractAccepted;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractStatusResponse;
import com.correction.borami.xvarm.service.XvarmExtractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XVARM 추출 중계 API — 계획서 6.2 규격.
 *
 * <pre>
 *   POST /api/v1/xvarm/extract        → 202 {requestId, status, expectedPath}
 *   GET  /api/v1/xvarm/extract/{id}   → 200 {status: ACCEPTED|RUNNING|DONE|FAILED, filePath, ...}
 * </pre>
 */
@Tag(name = "1. XVARM 추출 중계",
        description = "AI 플랫폼이 보라미 내부의 XVARM 을 호출할 수 있게 중계한다")
@Slf4j
@RestController
@RequestMapping(value = "/api/v1/xvarm", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class XvarmExtractController {

    private final XvarmExtractService service;

    @Operation(summary = "추출 요청",
            description = """
                    XVARM 에 파일 추출을 지시하고 **즉시 202 로 응답**합니다.
                    추출이 오래 걸릴 수 있어 동기로 붙잡지 않습니다 — 망연계 구간 타임아웃에 먼저 걸립니다.

                    **멱등**: 같은 `requestId` 로 다시 요청하면 중복 추출하지 않고 기존 상태를 돌려줍니다.

                    `fileName` 을 주면 그 이름으로 만듭니다. 비우면 `requestId` 기반 `.DAT` 입니다.
                    """)
    @PostMapping("/extract")
    public ResponseEntity<ExtractAccepted> extract(@RequestBody ExtractRequest request) {
        ExtractAccepted accepted = service.request(request);
        return ResponseEntity.accepted().body(accepted);
    }

    @Operation(summary = "추출 상태 조회",
            description = """
                    `DONE` 이면 `filePath` 에 실제 생성 경로가 담깁니다.

                    **호출 측은 이 경로의 파일명을 그대로 써야 합니다.** 브로커가 정한 이름과
                    다르게 추측하면 동기화된 파일을 찾지 못합니다.
                    """)
    @GetMapping("/extract/{requestId}")
    public ResponseEntity<ExtractStatusResponse> status(@PathVariable String requestId) {
        ExtractStatusResponse res = service.status(requestId);
        if (res == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(res);
    }

    @Operation(summary = "브로커 상태",
            description = "어댑터 모드(DUMMY/REAL), 출력 디렉터리, 보관 중인 작업 수를 봅니다.")
    @GetMapping("/status")
    public Map<String, Object> brokerStatus() {
        return service.summary();
    }

    /** 요청이 잘못된 경우는 400 — 서버 오류가 아니다. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        log.warn("[Broker] 잘못된 요청 — {}", e.getMessage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "BAD_REQUEST");
        out.put("message", e.getMessage());
        return out;
    }
}
