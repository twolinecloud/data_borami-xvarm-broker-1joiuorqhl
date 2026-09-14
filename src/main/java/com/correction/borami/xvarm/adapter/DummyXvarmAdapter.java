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
        byte[] audio = SilentWav.of(props.extract().dummyAudioSeconds());
        Files.write(file, audio, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("[XVARM:DUMMY] 파일 생성 — {} ({} bytes) docId={} fileKey={}",
                file.getFileName(), audio.length, request.docId(), request.fileKey());
        return file;
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
