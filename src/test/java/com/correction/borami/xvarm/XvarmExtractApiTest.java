package com.correction.borami.xvarm;

import com.correction.borami.xvarm.model.ExtractDtos.JobStatus;
import com.correction.borami.xvarm.service.XvarmExtractService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 브로커 API 계약 검증.
 *
 * <p>AI 플랫폼 쪽 클라이언트가 이 형식에 맞춰져 있으므로, 응답 구조가 바뀌면 연동이 끊긴다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class XvarmExtractApiTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("broker.extract.output-dir", () -> tmp.resolve("xvarm_out").toString());
        r.add("broker.extract.simulated-delay-ms", () -> "50");   // 테스트는 짧게
        r.add("broker.extract.dummy-audio-seconds", () -> "1");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private XvarmExtractService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private String body(String requestId, String fileName) {
        return """
                {"docId":"DOC0000000000001","fileKey":"XVARM/2026/09/FILEKEY-0001",
                 "requestId":"%s","fileName":%s}
                """.formatted(requestId, fileName == null ? "null" : "\"" + fileName + "\"");
    }

    @Test
    @DisplayName("추출 요청은 202 로 즉시 받고 requestId 를 돌려준다")
    void acceptsAsynchronously() throws Exception {
        String res = mvc.perform(post("/api/v1/xvarm/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REQ-001", "mock_meet_001.m4a")))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(res);
        assertThat(json.path("requestId").asText()).isEqualTo("REQ-001");
        assertThat(json.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.path("expectedPath").asText()).endsWith("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("조금 뒤 조회하면 DONE 이 되고 실제 파일이 만들어져 있다")
    void producesRealFile() {
        service.request(new com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest(
                "DOC1", "KEY1", "REQ-002", "mock_meet_002.m4a"));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            var st = service.status("REQ-002");
            assertThat(st.status()).isEqualTo(JobStatus.DONE);
            assertThat(st.fileSize()).isPositive();
            Path f = Path.of(st.filePath());
            assertThat(Files.exists(f)).isTrue();
            // 재생 가능한 WAV 여야 한다 — 하류가 매직 넘버로 포맷을 판별한다
            byte[] head = Files.readAllBytes(f);
            assertThat(new String(head, 0, 4)).isEqualTo("RIFF");
            assertThat(new String(head, 8, 4)).isEqualTo("WAVE");
        });
    }

    @Test
    @DisplayName("같은 requestId 로 다시 요청해도 중복 추출하지 않는다 — 멱등")
    void isIdempotent() {
        var req = new com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest(
                "DOC3", "KEY3", "REQ-003", "dup.m4a");

        service.request(req);
        await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> service.status("REQ-003").status() == JobStatus.DONE);
        var first = service.status("REQ-003");

        service.request(req);   // 재요청
        var second = service.status("REQ-003");

        assertThat(second.requestedAt()).isEqualTo(first.requestedAt());
        assertThat(second.finishedAt()).isEqualTo(first.finishedAt());
    }

    @Test
    @DisplayName("fileName 을 비우면 requestId 기반 .DAT 로 만든다 — 표준 A 수신 규약 재현")
    void fallsBackToDatName() {
        service.request(new com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest(
                "DOC4", "KEY4", "REQ-004", null));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            var st = service.status("REQ-004");
            assertThat(st.status()).isEqualTo(JobStatus.DONE);
            assertThat(st.fileName()).isEqualTo("REQ-004.DAT");
        });
    }

    @Test
    @DisplayName("모르는 requestId 는 404")
    void unknownRequestIs404() throws Exception {
        mvc.perform(get("/api/v1/xvarm/extract/NOPE"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("requestId 가 없으면 400 — 멱등 처리가 불가능하다")
    void missingRequestIdIs400() throws Exception {
        mvc.perform(post("/api/v1/xvarm/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docId\":\"D\",\"fileKey\":\"K\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("docId·fileKey 가 모두 없으면 400")
    void missingIdentifiersIs400() throws Exception {
        mvc.perform(post("/api/v1/xvarm/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("파일명에 경로가 섞여 와도 디렉터리를 벗어나지 않는다")
    void sanitizesFileName() {
        service.request(new com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest(
                "DOC5", "KEY5", "REQ-005", "../../evil.m4a"));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            var st = service.status("REQ-005");
            assertThat(st.status()).isEqualTo(JobStatus.DONE);
            assertThat(st.fileName()).isEqualTo("evil.m4a");
            assertThat(Path.of(st.filePath()).normalize())
                    .startsWith(tmp.resolve("xvarm_out").normalize());
        });
    }

    @Test
    @DisplayName("브로커 상태에 어댑터 모드와 출력 디렉터리가 보인다")
    void statusShowsConfig() throws Exception {
        String res = mvc.perform(get("/api/v1/xvarm/status"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(res);
        assertThat(json.path("adapter").asText()).isEqualTo("DUMMY");
        assertThat(json.path("outputDir").asText()).contains("xvarm_out");
    }

    @Test
    @DisplayName("산출 파일이 사라졌으면 같은 requestId 라도 다시 추출한다")
    void reExtractsWhenTheOutputFileIsGone() throws Exception {
        // 시뮬레이터 초기화가 로그 컬렉터 이력을 지우면 EXEC_ID 채번이 TST001 부터 다시 시작한다.
        //   그래서 60분 보관 창 안에서 같은 requestId 가 되돌아오는데, 그 사이 초기화가
        //   수신 파일까지 지워 놓는다. 예전에는 여기서 "DONE + 없는 경로" 를 돌려줘,
        //   호출 측이 오지 않을 파일을 수신 타임아웃(개발계 300초)까지 기다렸다.
        String rid = "VOC-20260923TST001-SIM-MEET-001";

        mvc.perform(post("/api/v1/xvarm/extract").contentType(MediaType.APPLICATION_JSON)
                .content(body(rid, "mock_meet_001.m4a")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(202));
        Path out = waitForDone(rid);
        assertThat(out).exists();

        // 호출 측(또는 초기화)이 가져가고 지운다
        Files.delete(out);

        mvc.perform(post("/api/v1/xvarm/extract").contentType(MediaType.APPLICATION_JSON)
                .content(body(rid, "mock_meet_001.m4a")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(202));
        Path again = waitForDone(rid);

        assertThat(again).as("다시 만들어 주지 않으면 호출 측은 영영 기다린다").exists();
    }

    /** DONE 이 될 때까지 짧게 기다렸다 산출 경로를 돌려준다. */
    private Path waitForDone(String requestId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var st = service.status(requestId);
            if (st != null && "DONE".equals(String.valueOf(st.status()))) {
                return Path.of(st.filePath());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("추출이 끝나지 않았다 — " + requestId);
    }
}
