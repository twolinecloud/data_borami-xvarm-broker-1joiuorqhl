package com.correction.borami.xvarm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 브로커 설정.
 *
 * <p>배포 환경(보라미 WAS)의 경로와 XVARM 접속 정보를 밖에서 주입받는다.
 * 내부망이라 우리가 값을 미리 알 수 없다.</p>
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(
        @DefaultValue Xvarm xvarm,
        @DefaultValue Extract extract
) {

    /** 실제 XVARM 솔루션 연동 설정. */
    public record Xvarm(
            /**
             * 어댑터 모드.
             * <ul>
             *   <li>{@code DUMMY} — XVARM 없이 가짜 파일을 만든다(개발·시연)</li>
             *   <li>{@code REAL}  — 실제 XVARM API/라이브러리를 호출한다(미구현, 사양 미확정 Q3)</li>
             * </ul>
             */
            @DefaultValue("DUMMY") Mode mode,
            /** REAL 모드에서 쓸 XVARM 엔드포인트·라이브러리 경로 등. 사양 확정 후 채운다. */
            @DefaultValue("") String endpoint
    ) {}

    /** 추출 동작. */
    public record Extract(
            /**
             * XVARM 이 파일을 떨궈 놓을 임시 디렉터리.
             *
             * <p>실제 보라미에서는 {@code /data001/doc01/recv/XVARM} 같은 경로다
             * (2026-09-11 회의). ESB Agent 가 이 폴더를 보고 AI 플랫폼으로 동기화한다.</p>
             */
            @DefaultValue("./work/xvarm_out") String outputDir,
            /**
             * 추출에 걸리는 시간(ms). DUMMY 모드에서 <b>실제처럼 비동기로 보이게</b> 하려고 둔다.
             * 0 이면 즉시 완료된다.
             */
            @DefaultValue("1500") long simulatedDelayMs,
            /** DUMMY 가 만들 가짜 음성의 길이(초). 파일 크기를 좌우한다. */
            @DefaultValue("2") int dummyAudioSeconds,
            /** 완료된 작업을 메모리에 얼마나 들고 있을지(분). 지나면 정리한다. */
            @DefaultValue("60") int jobRetentionMin
    ) {}

    public enum Mode { DUMMY, REAL }
}
