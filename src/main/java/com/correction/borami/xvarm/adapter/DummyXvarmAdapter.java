package com.correction.borami.xvarm.adapter;

import com.correction.borami.xvarm.config.BrokerProperties;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;
import com.correction.borami.xvarm.util.SilentWav;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * XVARM 없이 <b>재생 가능한 가짜 음성 파일</b>을 만든다.
 *
 * <p>0 바이트 더미를 쓰지 않는 이유: 하류(AI 플랫폼)가 파일 크기 안정성 판정·매직 넘버 포맷 판별·
 * STT 전송을 실제로 수행한다. 빈 파일로 연동하면 그 경로들이 검증되지 않은 채 남는다.</p>
 *
 * <p><b>파일명 규칙</b>: 요청에 {@code fileName} 이 있으면 그대로 쓰고, 없으면
 * {@code requestId} 기반으로 {@code .DAT} 를 붙인다. 표준 A 의 수신 파일 규약이
 * {@code .DAT} 라 원본 확장자가 사라지는 상황을 개발 중에 재현해 두려는 것이다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DummyXvarmAdapter implements XvarmAdapter {

    private final BrokerProperties props;

    @Override
    public Path extract(ExtractRequest request, Path outputDir) throws Exception {
        long delay = props.extract().simulatedDelayMs();
        if (delay > 0) {
            // 실제 XVARM 추출이 즉시 끝나지 않는다는 사실을 흉내 낸다.
            // 호출 측의 폴링 로직이 정말 도는지 확인하려면 지연이 있어야 한다.
            Thread.sleep(delay);
        }

        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(fileNameOf(request));

        // fileKey 가 실제로 읽히면 그것을 가져온다 — XVARM 이 하는 일이 바로 그것이다.
        Path src = source(request);
        if (src != null) {
            Files.copy(src, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("[XVARM:DUMMY] 원본 전달 — {} ({} bytes) ← {}",
                    file.getFileName(), Files.size(file), src);
            return file;
        }

        byte[] audio = SilentWav.of(props.extract().dummyAudioSeconds());
        Files.write(file, audio, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("[XVARM:DUMMY] 원본이 없어 더미 생성 — {} ({} bytes) docId={} fileKey={}",
                file.getFileName(), audio.length, request.docId(), request.fileKey());
        return file;
    }

    /**
     * {@code fileKey} 가 가리키는 실제 파일 — 읽을 수 없으면 {@code null}.
     *
     * <p><b>왜 원본을 가져오는가</b>: 예전에는 {@code fileKey} 를 보지 않고 늘 새 무음 WAV 를
     * 만들었다. 그 결과 수집기가 받는 파일은 <b>DB 메타와 무관한 평문</b>이어서, 복호화를 REAL 로
     * 올리면({@code CMMN_FILE_ENC_YN='Y'} 인 건) 평문에 AES 를 걸다가
     * {@code IllegalBlockSizeException} 으로 접견 건이 전부 죽었다. 시뮬레이션 데이터가 원본을
     * 실제로 암호화해 두어도, 이 어댑터가 그것을 쓰지 않으면 소용이 없다.</p>
     *
     * <p>읽을 수 없으면 종전대로 무음 WAV 를 만든다 — 원본 스토리지가 붙지 않은 환경에서도
     * 브로커 연동 자체는 확인할 수 있어야 하기 때문이다.</p>
     */
    private Path source(ExtractRequest r) {
        if (!StringUtils.hasText(r.fileKey())) {
            return null;
        }
        try {
            Path p = Path.of(r.fileKey().trim());
            return Files.isRegularFile(p) && Files.isReadable(p) ? p : null;
        } catch (RuntimeException e) {
            // 경로로 해석되지 않는 키(진짜 XVARM 의 내부 식별자 등) — 더미로 간다
            log.debug("[XVARM:DUMMY] fileKey 를 경로로 읽지 못했다 — {} ({})", r.fileKey(), e.getMessage());
            return null;
        }
    }

    /** 경로 구분자나 상위 디렉터리 참조가 섞여 들어오지 않게 이름만 뽑는다. */
    private String fileNameOf(ExtractRequest r) {
        if (StringUtils.hasText(r.fileName())) {
            String safe = Path.of(r.fileName()).getFileName().toString();
            if (!safe.isBlank() && !safe.equals(".") && !safe.equals("..")) {
                return safe;
            }
        }
        String id = StringUtils.hasText(r.requestId()) ? r.requestId() : "UNKNOWN";
        return id.replaceAll("[^A-Za-z0-9_.-]", "_") + ".DAT";
    }

    @Override
    public String mode() {
        return "DUMMY";
    }
}
