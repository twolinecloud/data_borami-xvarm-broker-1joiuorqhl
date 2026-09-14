# borami-xvarm-broker

보라미 WAS 서버 안에서 **XVARM 을 대신 호출해 주는 최소 REST 브로커**.

> 설계 근거: `data_agent-connector-dp8qbi7xqh/study/회의록/9_11/음성수집_서비스_개발계획_초안_v2.md` 3장·6장

---

## 1. 왜 필요한가

AI 플랫폼에서 보라미 내부의 XVARM 솔루션 API를 **직접 호출할 수 없다.**

> "우리가 보라미 서버에 접속하기 위해서는 무조건 [불명확]를 통해서 접속한다.
> XVARM을 우리가 호출을 못하기 때문에 우리 서비스를 하나, 브로커를 하나 추가해서
> XVARM을 호출하는 REST … 딱 하나만 있으면 되겠죠."
> — 2026-09-11 회의 `[00:10:11]`

```
[AI 플랫폼]                    [보라미 내부망]
voice-collector  ──REST──▶  borami-xvarm-broker  ──▶  XVARM
                                      │
                                      ▼
                              임시 폴더에 파일 생성
                                      │
                              메타빌드 ESB 가 동기화
                                      ▼
                            AI 플랫폼 수신 스토리지
```

### 하는 일 / 하지 않는 일

| 한다 | 하지 않는다 |
|---|---|
| XVARM 호출 지시 | **파일 내용을 읽거나 반환** |
| 임시 폴더에 파일 생성 | 파일을 AI 플랫폼으로 전송 (ESB 의 몫) |
| 진행 상태 응답 | DB 접근, 인증, 업무 로직 |

**브로커가 파일을 들고 망을 넘으면 연계 구조가 무너진다.** 파일 이동은 메타빌드 ESB 가 한다.

---

## 2. 왜 별도 레포인가

**운영 배포지가 우리 K8s 가 아니라 교정본부 보라미 WAS 서버**이기 때문이다.

- CI/CD 파이프라인을 탈 수 없다(내부망) → **수동 전달 가능한 단일 실행 jar**
- 소스·산출물을 발주처에 제출할 가능성이 있다 → 우리 플랫폼 코드와 섞이면 안 된다
- 보안 심사 대상이다 → 의존성이 적을수록 통과가 쉽다

그래서 의존성이 **web + springdoc + lombok(컴파일 전용)** 뿐이다. DB도 ORM도 메시징도 없다.

개발계에서는 시뮬레이터 연동·투라코 내부망 통신 시험을 위해 **같은 코드를 K8s 파드로도 띄운다.**
배포 방식만 다르고 산출물은 하나다 → 5장 참조.

---

## 3. API

```
POST /api/v1/xvarm/extract         → 202 {requestId, status, expectedPath}
GET  /api/v1/xvarm/extract/{id}    → 200 {status, filePath, fileName, fileSize, ...}
GET  /api/v1/xvarm/status          → 어댑터 모드·출력 디렉터리·작업 수
```

Swagger: `http://localhost:8082/swagger-ui.html`

### 비동기인 이유

추출이 오래 걸릴 수 있는데 동기로 붙잡으면 **망연계 구간 타임아웃에 먼저 걸린다.**
그래서 `202` 로 받고 상태를 따로 묻게 한다.

### 멱등

같은 `requestId` 로 다시 오면 새로 추출하지 않고 기존 상태를 돌려준다.
AI 플랫폼의 배치는 재실행될 수 있고, 그때마다 XVARM 에 같은 일을 시키면 안 된다.

### 파일명

요청의 `fileName` 을 주면 그 이름으로, 비우면 `requestId` 기반 `.DAT` 로 만든다.
(표준 인터페이스 설계서의 수신 파일 규약이 `.DAT` 라 원본 확장자가 사라지는 상황을 재현한다)

> **호출 측은 응답의 `filePath` 에 담긴 파일명을 그대로 써야 한다.**
> 브로커가 정한 이름과 다르게 추측하면 동기화된 파일을 찾지 못하고 타임아웃이 난다.

---

## 4. 로컬 실행

```bash
mvn spring-boot:run
```

포트 **8082**. 기본은 `DUMMY` 어댑터라 XVARM 없이 재생 가능한 무음 WAV 를 만든다.

```bash
curl -X POST http://localhost:8082/api/v1/xvarm/extract -H "Content-Type: application/json" -d "{\"docId\":\"DOC1\",\"fileKey\":\"FK1\",\"requestId\":\"REQ-1\",\"fileName\":\"test.m4a\"}"
```

```bash
curl http://localhost:8082/api/v1/xvarm/extract/REQ-1
```

### voice-collector 와 연동

로컬에는 ESB 가 없다. 브로커가 **voice-collector 의 수신 폴더에 직접 쓰게** 해서 그 구간을 생략한다.

```bash
set BROKER_OUTPUT_DIR=D:/Twoline_Project/data_voice-collector/work/voice_raw/meet
mvn spring-boot:run
```

그리고 voice-collector 쪽에서 브로커 모드를 `REST` 로 바꾼다.

```bash
curl -X PUT "http://localhost:8081/api/v1/mock/modes/broker?value=REST"
```

| 포트 | 서비스 |
|---:|---|
| 8080 | agent-connector |
| 8081 | voice-collector |
| **8082** | **borami-xvarm-broker** |
| 8090 | log-collector |

---

## 5. 배포 — 투트랙

| | 개발 (Dev) | 운영 (Prod) |
|---|---|---|
| 배포 위치 | AI 플랫폼 K8s (`data-pipeline` ns) | 교정본부 **보라미 서버** |
| 형태 | 파드 (컨테이너 이미지) | **단일 실행 jar** |
| 배포 경로 | Jenkins → Harbor → ArgoCD | **수동 전달·수동 기동** |
| XVARM | 없음 → `DUMMY` | 실제 XVARM → `REAL` |
| 프로파일 | `dev` (포트 8080) | `prod` (포트 8082) |

**두 트랙은 서로 독립적이다.** K8s 배포 설정이 있든 없든 `mvn clean package` 는
독립 실행 가능한 단일 jar 를 그대로 뱉는다 — pom 의 `dockerfile-maven-plugin` 은
`<executions>` 가 없어 어떤 라이프사이클에도 묶여 있지 않고, K8s 전용 의존성도 없다.

```bash
mvn clean package
```

```bash
java -jar target/borami-xvarm-broker-1joiuorqhl.jar --spring.profiles.active=prod
```

구성 파일·환경변수·배포 전 확인 항목은 **[`deploy/README.md`](deploy/README.md)** 에 정리했다.

```
src/main/docker/Dockerfile              개발계 컨테이너 이미지
Jenkinsfile                             투라코 CI (maven → podman → Harbor → ArgoCD)
deploy/helm/borami-xvarm-broker/        개발계 Helm 차트
src/main/resources/application-dev.yml  개발계 설정
src/main/resources/application-prod.yml 운영 설정
```

> ⚠ `replicas` 는 **1 고정이고 HPA 를 두지 않는다.** 작업 상태가 메모리에 있어
> 파드가 둘이면 접수한 파드와 조회하는 파드가 갈린다. 근거는 `deploy/README.md` 1.3 절.

---

## 6. 미확정 사항

| # | 내용 | 영향 |
|---|---|---|
| **Q3** | XVARM 의 실제 호출 방식 — REST 인가 jar 직접 호출인가, 입력 파라미터, 출력 경로 규칙, 에러 코드 | `RealXvarmAdapter` 구현 불가 |
| | 보라미 WAS 의 **JDK 버전** | Spring Boot 3.x 는 Java 17 필요. Java 8/11 이면 Boot 2.7 + `javax.*` 로 내려야 한다 |
| | 인터페이스ID (표준 A 명명규칙 `IF_송신_수신_일련`) | ESB 연계 등록 |
| | 브로커 배포 승인·보안 심사 절차 | 일정 |

미구현 지점은 **조용히 빈 결과를 돌려주지 않고 명시적으로 실패**시킨다.
"추출했는데 파일이 없음" 과 "아직 구현 안 됨" 이 같아 보이면, AI 플랫폼 쪽에서
파일을 기다리다 타임아웃 날 때까지 원인을 알 수 없다.

---

## 7. 구조

```
com.correction.borami.xvarm
├─ controller/  XvarmExtractController   API 2종 + 상태
├─ service/     XvarmExtractService      작업 접수·비동기 실행·멱등·상태 보관
├─ adapter/     XvarmAdapter             ★ XVARM 호출 추상화
│               ├ DummyXvarmAdapter      가짜 WAV 생성 (현재 기본)
│               └ RealXvarmAdapter       실연동 (미구현, Q3)
├─ model/       ExtractDtos              요청·응답·상태
├─ config/      BrokerProperties · OpenApiConfig
└─ util/        SilentWav                무음 WAV 생성
```

작업 상태는 **메모리에 둔다.** 재시작하면 진행 중이던 정보가 사라지지만, 호출 측이 같은
`requestId` 로 다시 요청하면 재추출되고 이미 만들어진 파일은 ESB 가 가져간 뒤다.
DB 를 두면 보라미 서버에 스키마를 하나 더 요구하게 되어 배포·심사 부담만 는다.
