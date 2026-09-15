package com.correction.borami.xvarm.service;

import com.correction.borami.xvarm.adapter.XvarmAdapter;
import com.correction.borami.xvarm.config.BrokerProperties;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractAccepted;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractStatusResponse;
import com.correction.borami.xvarm.model.ExtractDtos.JobStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 추출 작업을 접수하고 진행 상태를 관리한다.
 *
 * <p><b>비동기로 받는 이유</b>: XVARM 추출이 오래 걸릴 수 있는데, 동기로 붙잡고 있으면
 * 망연계 구간의 타임아웃에 먼저 걸린다. 그래서 {@code 202 Accepted} 로 받고 상태를 따로 묻게 한다.</p>
 *
 * <p><b>멱등</b>: 같은 {@code requestId} 로 다시 오면 새로 추출하지 않고 기존 상태를 돌려준다.
 * AI 플랫폼의 배치는 재실행될 수 있고, 그때마다 XVARM 에 같은 일을 시키면 안 된다.</p>
 *
 * <p><b>상태는 메모리에 둔다.</b> 재시작하면 진행 중이던 작업 정보가 사라지는데, 그래도 괜찮다 —
 * 호출 측이 같은 {@code requestId} 로 다시 요청하면 재추출되고, 이미 만들어진 파일은
 * 어차피 ESB 가 가져간 뒤다. DB 를 두면 보라미 서버에 스키마를 하나 더 요구하게 되어
 * 배포·심사 부담만 는다.</p>
 */
@Slf4j
@Service
public class XvarmExtractService {

    private final BrokerProperties props;
    private final XvarmAdapter adapter;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "xvarm-extract");
                t.setDaemon(true);
                return t;
            });

    public XvarmExtractService(BrokerProperties props, List<XvarmAdapter> adapters) {
        this.props = props;
        String want = props.xvarm().mode().name();
        this.adapter = adapters.stream()
                .filter(a -> a.mode().equals(want))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "XVARM 어댑터가 없다: " + want));

        // 출력 디렉터리를 비워 두면 Path.of("") 가 '현재 작업 디렉터리'로 조용히 풀린다.
        // 보라미 서버에 수동 배포하면서 BROKER_OUTPUT_DIR 을 빠뜨리면, 추출 파일이
        // jar 옆에 쌓이고 ESB 는 빈 폴더만 보게 된다 — 에러 없이 아무 일도 안 일어난다.
        // 기동 시점에 끊는다.
        if (!StringUtils.hasText(props.extract().outputDir())) {
            throw new IllegalStateException(
                    "broker.extract.output-dir 가 비어 있다. XVARM 산출 디렉터리를 지정해야 한다 "
                            + "(환경변수 BROKER_OUTPUT_DIR).");
        }

        log.info("[Broker] XVARM 어댑터 = {} · 출력 디렉터리 = {}",
                adapter.mode(), outputDir().toAbsolutePath());
    }

    /**
     * 추출을 접수한다. 이미 있는 {@code requestId} 면 기존 작업을 그대로 돌려준다.
     *
     * @throws IllegalArgumentException 필수 값 누락
     */
    public ExtractAccepted request(ExtractRequest req) {
        validate(req);
        purgeExpired();

        Job existing = jobs.get(req.requestId());
        if (existing != null) {
            log.info("[Broker] 중복 요청 — 기존 작업 반환 (requestId={}, status={})",
                    req.requestId(), existing.status);
            return new ExtractAccepted(existing.requestId, existing.status, pathOf(existing));
        }

        Job job = new Job(req.requestId());
        jobs.put(req.requestId(), job);
        executor.submit(() -> run(job, req));

        log.info("[Broker] 추출 접수 — requestId={} docId={} fileKey={}",
                req.requestId(), req.docId(), req.fileKey());
        return new ExtractAccepted(job.requestId, JobStatus.ACCEPTED,
                outputDir().resolve(expectedName(req)).toString());
    }

    /** 상태 조회. 모르는 {@code requestId} 면 null. */
    public ExtractStatusResponse status(String requestId) {
        Job job = jobs.get(requestId);
        if (job == null) {
            return null;
        }
        return new ExtractStatusResponse(
                job.requestId, job.status, pathOf(job),
                job.filePath == null ? null : job.filePath.getFileName().toString(),
                job.fileSize, job.errorCode, job.errorMessage,
                job.requestedAt, job.finishedAt);
    }

    /** 현재 어댑터 모드(진단용). */
    public String adapterMode() {
        return adapter.mode();
    }

    /**
     * 출력 디렉터리 — <b>절대경로로 정규화해서</b> 돌려준다.
     *
     * <p>여기서 나온 경로가 응답의 {@code filePath} 와 상태의 {@code outputDir} 가 된다.
     * 상대경로(./work/xvarm_out)를 그대로 내보내면 호출 측은 그것이 <b>누구의</b> 작업 디렉터리
     * 기준인지 알 수 없어, 파일이 엉뚱한 곳에 생겨도 수신 대기 타임아웃까지 아무 신호가 없다.
     * 절대경로면 같은 파일시스템을 보는 로컬·개발계에서 수집기가 "파일은 있는데 내 수신 폴더
     * 밖" 을 즉시 알아챌 수 있다. 운영에서는 보라미 서버 기준 경로라 수집기 쪽에 존재하지
     * 않으므로 그 판정에 걸리지 않는다.</p>
     */
    public Path outputDir() {
        return Path.of(props.extract().outputDir()).toAbsolutePath().normalize();
    }

    /** 보관 중인 작업 요약(진단용). */
    public Map<String, Object> summary() {
        Map<String, Object> counts = new HashMap<>();
        for (JobStatus s : JobStatus.values()) {
            counts.put(s.name(), 0);
        }
        jobs.values().forEach(j -> counts.merge(j.status.name(), 1, (a, b) -> (int) a + (int) b));
        Map<String, Object> out = new HashMap<>();
        out.put("adapter", adapter.mode());
        out.put("outputDir", outputDir().toAbsolutePath().toString());
        out.put("totalJobs", jobs.size());
        out.put("byStatus", counts);
        return out;
    }

    // ── 내부 ───────────────────────────────────────────────────────────────

    private void run(Job job, ExtractRequest req) {
        job.status = JobStatus.RUNNING;
        try {
            Path produced = adapter.extract(req, outputDir());
            job.filePath = produced;
            job.fileSize = Files.exists(produced) ? Files.size(produced) : 0L;
            job.status = JobStatus.DONE;
            job.finishedAt = LocalDateTime.now();
            log.info("[Broker] 추출 완료 — requestId={} file={} ({} bytes)",
                    job.requestId, produced.getFileName(), job.fileSize);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "INTERRUPTED", "추출 중 인터럽트");
        } catch (UnsupportedOperationException e) {
            // 아직 구현되지 않은 경로 — 설정 문제이지 데이터 문제가 아니다. 구분해서 남긴다.
            fail(job, "NOT_IMPLEMENTED", e.getMessage());
        } catch (Exception e) {
            fail(job, "EXTRACT_FAILED", e.getMessage());
        }
    }

    private void fail(Job job, String code, String message) {
        job.status = JobStatus.FAILED;
        job.errorCode = code;
        job.errorMessage = message;
        job.finishedAt = LocalDateTime.now();
        log.error("[Broker] 추출 실패 — requestId={} code={} msg={}", job.requestId, code, message);
    }

    private void validate(ExtractRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("요청 본문이 비어 있다");
        }
        if (!StringUtils.hasText(req.requestId())) {
            throw new IllegalArgumentException("requestId 는 필수다 (멱등 처리에 쓰인다)");
        }
        if (!StringUtils.hasText(req.docId()) && !StringUtils.hasText(req.fileKey())) {
            throw new IllegalArgumentException("docId 또는 fileKey 중 하나는 있어야 한다");
        }
    }

    private String expectedName(ExtractRequest req) {
        if (StringUtils.hasText(req.fileName())) {
            return Path.of(req.fileName()).getFileName().toString();
        }
        return req.requestId().replaceAll("[^A-Za-z0-9_.-]", "_") + ".DAT";
    }

    private String pathOf(Job job) {
        return job.filePath == null ? null : job.filePath.toString();
    }

    /** 오래된 작업을 버린다 — 장기 구동 시 메모리가 계속 늘지 않게. */
    private void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(props.extract().jobRetentionMin());
        jobs.values().removeIf(j ->
                j.finishedAt != null && j.finishedAt.isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** 작업 하나의 가변 상태. 여러 스레드가 읽고 쓴다. */
    private static final class Job {
        final String requestId;
        final LocalDateTime requestedAt = LocalDateTime.now();
        volatile JobStatus status = JobStatus.ACCEPTED;
        volatile Path filePath;
        volatile long fileSize;
        volatile String errorCode;
        volatile String errorMessage;
        volatile LocalDateTime finishedAt;

        Job(String requestId) {
            this.requestId = requestId;
        }
    }
}
