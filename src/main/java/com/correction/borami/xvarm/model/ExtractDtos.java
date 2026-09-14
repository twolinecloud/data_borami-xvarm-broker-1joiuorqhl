package com.correction.borami.xvarm.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 추출 API 의 요청·응답 형식.
 *
 * <p>계획서 6.2 의 제안 규격을 그대로 구현한다. XVARM 의 실제 호출 사양이 확정되면(Q3)
 * 요청 필드가 늘 수 있으나, <b>응답 형식은 유지</b>해야 한다 — AI 플랫폼 쪽 클라이언트가
 * 이 모양에 맞춰져 있다.</p>
 */
public final class ExtractDtos {

    private ExtractDtos() {
    }

    /** 작업 상태. */
    public enum JobStatus {
        /** 요청을 받아 큐에 넣었다 */
        ACCEPTED,
        /** XVARM 호출이 진행 중이다 */
        RUNNING,
        /** 파일이 임시 폴더에 생성됐다 */
        DONE,
        /** 실패했다 — {@code errorCode}·{@code errorMessage} 참조 */
        FAILED
    }

    /**
     * 추출 요청.
     *
     * @param docId     문서ID — {@code TB_SMSM_CMFI_BS.DOC_ID}
     * @param fileKey   XVARM 파일 키 — {@code ASYSCONTENTELEMENT.FILEKEY}
     * @param requestId 멱등 키. 같은 값으로 다시 요청하면 중복 추출하지 않고 기존 상태를 돌려준다
     * @param fileName  원하는 파일명(선택). ESB 가 업무 파일명을 그대로 옮기는 규약일 때
     *                  호출 측이 지정한다. 비우면 {@code requestId} 기반으로 만든다
     */
    public record ExtractRequest(
            @Schema(description = "문서ID (TB_SMSM_CMFI_BS.DOC_ID)", example = "DOC0000000000001")
            String docId,
            @Schema(description = "XVARM 파일 키 (ASYSCONTENTELEMENT.FILEKEY)", example = "XVARM/2026/09/FILEKEY-0001")
            String fileKey,
            @Schema(description = "멱등 키. 재요청 시 중복 추출하지 않는다", example = "VOC-MEET-0001")
            String requestId,
            @Schema(description = "생성할 파일명(선택). 비우면 requestId 기반", example = "mock_meet_001.m4a")
            String fileName
    ) {}

    /**
     * 추출 접수 응답 — {@code 202 Accepted}.
     *
     * <p>동기로 기다리지 않는다. 추출이 오래 걸릴 수 있는데 망연계 구간의 타임아웃에 먼저 걸린다.</p>
     *
     * @param expectedPath 완료되면 파일이 놓일 예정 경로(참고용). 확정은 상태 조회로 받는다
     */
    public record ExtractAccepted(
            String requestId,
            JobStatus status,
            String expectedPath
    ) {}

    /**
     * 추출 상태 응답.
     *
     * @param filePath 완료 시 실제 생성된 경로. <b>호출 측은 이 경로의 파일명을 그대로 써야 한다</b>
     *                 — 브로커가 정한 이름과 다르게 추측하면 파일을 찾지 못한다
     */
    public record ExtractStatusResponse(
            String requestId,
            JobStatus status,
            String filePath,
            String fileName,
            long fileSize,
            String errorCode,
            String errorMessage,
            LocalDateTime requestedAt,
            LocalDateTime finishedAt
    ) {}
}
