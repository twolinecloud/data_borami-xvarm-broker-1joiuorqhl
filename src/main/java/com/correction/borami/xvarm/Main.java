package com.correction.borami.xvarm;

import com.correction.borami.xvarm.config.BrokerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

/**
 * 보라미 XVARM 브로커 — 보라미 WAS 서버 안에서 도는 최소 REST 서비스.
 *
 * <p><b>왜 이 서비스가 필요한가</b>: AI 플랫폼에서 보라미 내부의 XVARM 솔루션 API 를 직접
 * 호출할 수 없다. 그래서 보라미 서버 안에 "XVARM 을 대신 불러 주는" 얇은 중계 서비스를 하나 둔다
 * (2026-09-11 회의 {@code [00:09:38]}, {@code [00:10:11]}).</p>
 *
 * <p><b>하는 일은 둘뿐이다.</b></p>
 * <ol>
 *   <li>추출 요청을 받아 XVARM 을 호출하고, 임시 폴더에 파일이 생기게 한다</li>
 *   <li>그 진행 상태를 알려준다</li>
 * </ol>
 *
 * <p><b>하지 않는 일</b>: 파일 내용을 읽거나 돌려주지 않는다. 파일을 AI 플랫폼으로 옮기는 것은
 * 메타빌드 ESB 의 몫이다. 브로커가 파일을 들고 망을 넘으면 연계 구조가 무너진다.</p>
 */
@EnableConfigurationProperties(BrokerProperties.class)
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        // 보라미와 AI 플랫폼이 같은 시계를 봐야 요청 시각·파일 타임스탬프가 어긋나지 않는다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(Main.class, args);
    }
}
