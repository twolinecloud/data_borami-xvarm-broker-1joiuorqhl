package com.correction.borami.xvarm.adapter;

import com.correction.borami.xvarm.config.BrokerProperties;
import com.correction.borami.xvarm.model.ExtractDtos.ExtractRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 실제 XVARM 솔루션 호출 — <b>아직 구현하지 않았다</b>.
 *
 * <p>필요한 것이 하나도 확정되지 않았다(계획서 Q3).</p>
 * <ul>
 *   <li>XVARM 을 <b>어떻게</b> 부르는가 — REST API 인가, jar 라이브러리 직접 호출인가
 *       (회의에서는 "XVARM 이라고 솔루션이 있는데 거기에 API를 호출하면" 이라고만 언급됐다)</li>
 *   <li>입력 파라미터 — {@code DOC_ID} 와 {@code FILEKEY} 만으로 되는가</li>
 *   <li>출력 경로 규칙 — XVARM 이 정하는가, 우리가 지정하는가</li>
 *   <li>파일명 규칙 — 원본명이 유지되는가</li>
 *   <li>에러 코드 체계</li>
 * </ul>
 *
 * <p><b>조용히 빈 결과를 돌려주지 않는다.</b> "추출했는데 파일이 없음" 과
 * "아직 구현 안 됨" 은 전혀 다른 사실이고, 전자로 위장하면 AI 플랫폼 쪽에서
 * 파일을 기다리다 타임아웃 날 때까지 원인을 알 수 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealXvarmAdapter implements XvarmAdapter {

    private final BrokerProperties props;

    @Override
    public Path extract(ExtractRequest request, Path outputDir) {
        throw new UnsupportedOperationException(
                "XVARM 실연동 미구현 — 호출 방식·입력 파라미터·출력 경로 규칙이 확정되지 않았다. "
                        + "계획서 11장 Q3 참조. 현재 설정된 endpoint=" + props.xvarm().endpoint());
    }

    @Override
    public String mode() {
        return "REAL";
    }
}
