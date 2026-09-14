package com.correction.borami.xvarm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger 문서 메타.
 *
 * <p>보라미 내부망에 배포되면 우리가 붙어 볼 수단이 이 화면뿐일 수 있다.
 * 그래서 "무엇을 확인해야 하는지"까지 설명에 담아 둔다.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI brokerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("borami-xvarm-broker")
                .version("0.0.1")
                .description("""
                        보라미 WAS 안에서 **XVARM 을 대신 호출**해 주는 최소 REST 브로커입니다.

                        ### 왜 필요한가
                        AI 플랫폼에서 보라미 내부의 XVARM API 를 직접 부를 수 없습니다
                        (2026-09-11 회의). 그래서 보라미 서버 안에 얇은 중계 서비스를 하나 둡니다.

                        ### 하는 일 / 하지 않는 일
                        - **한다**: XVARM 호출 지시, 임시 폴더에 파일 생성, 진행 상태 응답
                        - **안 한다**: 파일 내용을 읽거나 반환. 파일을 AI 플랫폼으로 옮기는 것은
                          메타빌드 ESB 의 몫입니다 — 브로커가 파일을 들고 망을 넘으면 연계 구조가 무너집니다

                        ### 시험 순서
                        1. `GET /api/v1/xvarm/status` — 어댑터 모드와 출력 디렉터리 확인
                        2. `POST /api/v1/xvarm/extract` — 추출 요청 (202 + requestId)
                        3. `GET /api/v1/xvarm/extract/{requestId}` — `DONE` 이 될 때까지 조회
                        4. 같은 `requestId` 로 2번을 다시 호출 — 중복 추출하지 않는지 확인(멱등)

                        ### 현재 상태
                        XVARM 실제 호출 사양이 확정되지 않아(계획서 Q3) **DUMMY 어댑터**가 기본입니다.
                        진짜 XVARM 대신 재생 가능한 무음 WAV 를 임시 폴더에 만듭니다.
                        """));
    }
}
