package com.correction.borami.xvarm.adapter;

import com.correction.borami.xvarm.config.BrokerProperties;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DUMMY 어댑터 — <b>fileKey 가 가리키는 원본을 전달한다.</b>
 *
 * <p><b>왜 이 테스트가 생겼나</b>: 예전에는 {@code fileKey} 를 보지 않고 늘 새 무음 WAV 를
 * 만들었다. 그래서 수집기가 받는 파일이 DB 메타와 무관한 평문이었고, 복호화를 REAL 로 올리면
 * ({@code CMMN_FILE_ENC_YN='Y'} 인 건) 평문에 AES 를 걸다가 {@code IllegalBlockSizeException}
 * 으로 접견 건이 전부 죽었다. 원본을 암호화해 두어도 이 어댑터가 쓰지 않으면 소용이 없다.</p>
 */
class DummyXvarmAdapterTest {

    @TempDir
    Path tmp;

    private DummyXvarmAdapter adapter;

    @BeforeEach
    void setUp() {
        BrokerProperties props = new BrokerProperties(
                new BrokerProperties.Xvarm(BrokerProperties.Mode.DUMMY, ""),
                new BrokerProperties.Extract(tmp.resolve("out").toString(), 0L, 2, 60));
        adapter = new DummyXvarmAdapter(props);
    }

    private ExtractRequest req(String fileKey, String fileName) {
        return new ExtractRequest("DOC1", fileKey, "REQ-1", fileName);
    }

    @Test
    @DisplayName("원본이 읽히면 그대로 전달한다 — 암호문이면 암호문 그대로여야 한다")
    void deliversTheOriginalBytes() throws Exception {
        // 암호문을 흉내 낸다 — 오디오 매직 넘버가 아닌 바이트열
        byte[] cipher = new byte[64];
        for (int i = 0; i < cipher.length; i++) {
            cipher[i] = (byte) (i * 7 + 3);
        }
        Path src = tmp.resolve("mock_meet_001.m4a");
        Files.write(src, cipher);

        Path out = adapter.extract(req(src.toString(), "mock_meet_001.m4a"), tmp.resolve("out"));

        assertThat(Files.readAllBytes(out))
                .as("어댑터가 새로 만들면 여기서 어긋난다").isEqualTo(cipher);
        assertThat(out.getFileName()).hasToString("mock_meet_001.m4a");
    }

    @Test
    @DisplayName("원본이 없으면 무음 WAV 를 만든다 — 스토리지가 없는 환경에서도 연동은 확인된다")
    void fallsBackToGeneratedAudio() throws Exception {
        Path out = adapter.extract(
                req(tmp.resolve("없는파일.m4a").toString(), "mock_meet_009.m4a"), tmp.resolve("out"));

        assertThat(out).exists();
        byte[] body = Files.readAllBytes(out);
        assertThat(body.length).isPositive();
        assertThat(new String(body, 0, 4)).as("생성한 더미는 RIFF(WAV) 다").isEqualTo("RIFF");
    }

    @Test
    @DisplayName("fileKey 가 경로가 아니어도 죽지 않는다 — 진짜 XVARM 의 내부 식별자일 수 있다")
    void handlesNonPathFileKey() throws Exception {
        Path out = adapter.extract(
                req("XVARM/2026/09/FILEKEY-0001", "mock_meet_010.m4a"), tmp.resolve("out"));

        assertThat(out).exists();
        assertThat(new String(Files.readAllBytes(out), 0, 4)).isEqualTo("RIFF");
    }

    @Test
    @DisplayName("fileKey 가 비어도 더미로 간다")
    void handlesBlankFileKey() throws Exception {
        Path out = adapter.extract(req("  ", "mock_meet_011.m4a"), tmp.resolve("out"));

        assertThat(out).exists();
    }
}
