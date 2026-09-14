# 배포 — 투트랙

브로커는 **개발계와 운영의 배포 방식이 다르다.** 같은 코드, 같은 jar, 다른 배포 경로다.

| | 개발 (Dev) | 운영 (Prod) |
|---|---|---|
| 배포 위치 | AI 플랫폼 K8s (`data-pipeline` ns) | 교정본부 **보라미 서버** |
| 형태 | 파드 (컨테이너 이미지) | **단일 실행 jar** |
| 배포 경로 | Jenkins → Harbor → ArgoCD | **수동 전달·수동 기동** |
| XVARM | 없음 → `DUMMY` 어댑터 | 실제 XVARM → `REAL` 어댑터 |
| 스프링 프로파일 | `dev` | `prod` |
| 용도 | 시뮬레이터 연동, 투라코 내부망 통신 시험 | 실연동 |

**두 트랙은 서로 독립적이다.** K8s 배포 설정이 있든 없든 `mvn clean package` 는
독립 실행 가능한 단일 jar 를 그대로 뱉는다 — pom 의 `dockerfile-maven-plugin` 은
`<executions>` 가 없어 어떤 라이프사이클에도 묶여 있지 않고, K8s 전용 의존성도 없다.

---

## 1. 개발 (K8s 파드)

### 1.1 구성 파일

```
src/main/docker/Dockerfile              컨테이너 이미지 (투라코 표준, openjdk:17-alpine)
Jenkinsfile                             투라코 CI — maven build → podman build → Harbor push → ArgoCD sync
src/main/resources/application-dev.yml  개발계 설정 (포트 8080, XVARM DUMMY)
deploy/helm/borami-xvarm-broker/        Helm 차트 (Deployment · Service · PVC · ConfigMap)
```

### 1.2 포트가 세 곳에서 같아야 한다

`Dockerfile` 의 `EXPOSE`, `application-dev.yml` 의 `server.port`,
차트의 `container.port`/`service.targetPort` — **전부 8080**이다.
하나만 어긋나면 파드는 뜨는데 probe 가 계속 실패해 CrashLoop 처럼 보인다.

> 운영(`prod`)의 기본 포트는 8082 다. 보라미 WAS 와 겹치지 않는 포트를 배포 전 확인해야 한다.

### 1.3 replicas 는 1 이고 HPA 를 두지 않는다

브로커는 추출 작업 상태(`requestId` → `RUNNING`/`DONE`/파일경로)를 **메모리에** 들고 있다.
파드가 둘이면 `POST /extract` 를 받은 파드와 `GET /extract/{id}` 를 받는 파드가 달라져
방금 접수한 작업이 404 로 보인다.

상태를 공유하려면 DB 나 캐시가 필요한데, **운영 배포지인 보라미 서버에 스키마를
하나 더 요구하게 되어** 배포·보안심사 부담만 는다. 그래서 단일 인스턴스를 전제로 설계했다.
`strategy.type` 도 같은 이유로 `Recreate` 다(롤링 중 두 파드가 겹치면 안 된다).

부하가 문제가 되면 수평 확장이 아니라 XVARM 호출 스레드 풀을 키우는 쪽이 맞다.

### 1.4 로컬에서 차트 확인

```bash
helm lint deploy/helm/borami-xvarm-broker
```

```bash
helm template borami-xvarm-broker deploy/helm/borami-xvarm-broker
```

### 1.5 투라코 HelmChart 레포 등록 (인프라 담당 협의 필요)

투라코 파이프라인은 차트를 서비스 레포가 아니라 **중앙 `data_HelmChart` 레포**에서 읽는다.
Jenkinsfile 이 그 레포를 클론해 `$STAGE/$SERVICE_NAME/values.yaml` 의 이미지 태그를
커밋하고, ArgoCD 가 그걸 동기화한다.

따라서 이 차트를 실제로 굴리려면 다음이 필요하다.

1. `data_HelmChart/{stage}/borami-xvarm-broker-1joiuorqhl/` 에 차트 등록
   (`default`, `pipeline` 두 스테이지 존재)
2. 그때 `namespace`·`imageRepoName`·`replicas` 등은 투라코의 `-@...@-` 토큰으로 치환
3. ArgoCD Application 생성

여기 있는 차트는 **그 등록에 쓸 원본**이자, 토큰 없이 바로 `helm install` 해 볼 수 있는
독립 버전이다. 실제 등록은 인프라 담당과 협의한다.

### 1.6 voice-collector 와 파일을 주고받기

개발계에는 메타빌드 ESB 가 없다. 브로커가 만든 파일이 voice-collector 에 닿아야
파일 도착 감시·폴링 로직을 실제로 돌려볼 수 있으므로, **같은 볼륨을 보게** 한다.

`values.yaml`:

```yaml
pvc:
  existingClaim: pvc-voice-collector-x2daarjxe4   # voice-collector 의 PVC
  mountPath: /k8s/voice_raw/meet                  # voice-collector 의 수신 디렉터리
config:
  BROKER_OUTPUT_DIR: "/k8s/voice_raw/meet"        # mountPath 와 같아야 한다
```

`existingClaim` 을 채우면 이 차트는 PVC 를 새로 만들지 않는다 — 같은 이름의 PVC 를
두 차트가 각각 만들려 하면 ArgoCD 가 소유권 충돌로 계속 OutOfSync 를 낸다.

---

## 2. 운영 (보라미 서버, 단일 jar)

### 2.1 빌드

```bash
mvn clean package
```

`target/borami-xvarm-broker-1joiuorqhl.jar` 가 나온다. 도커도, K8s 도, 젠킨스도 필요 없다.

발주처에 전달할 때 투라코 접미사가 붙은 이름이 부적절하면:

```bash
mvn clean package -Dfinal-name=borami-xvarm-broker
```

### 2.2 기동

```bash
java -jar borami-xvarm-broker.jar --spring.profiles.active=prod
```

설정은 환경변수로 주입한다. jar 옆에 `application-prod.yml` 을 두어 덮어써도 된다
(외부 파일이 jar 내장본보다 우선한다).

| 환경변수 | 내용 |
|---|---|
| `SERVER_PORT` | 기본 8082. **보라미 WAS 와 겹치지 않는지 확인 필요** |
| `BROKER_XVARM_MODE` | `REAL` (운영 기본) / `DUMMY` |
| `BROKER_XVARM_ENDPOINT` | XVARM 엔드포인트 — **미확정** |
| `BROKER_OUTPUT_DIR` | XVARM 산출 임시 디렉터리 — **미확정** |
| `BROKER_SWAGGER_ENABLED` | 보안심사에서 지적되면 `false` (API 는 그대로 동작) |
| `LOG_FILE` | 로그 파일 경로. 비우면 콘솔만 |

### 2.3 배포 전 확인해야 할 것

| # | 항목 | 상태 |
|---|---|---|
| 1 | 보라미 서버 **OS·JDK 버전** | **미확인** — Java 17 전제로 빌드했다. Java 8/11 이면 Spring Boot 2.7 로 내리고 `jakarta.*` → `javax.*` 를 함께 바꿔야 한다 |
| 2 | **XVARM 호출 사양** (방식·입력 파라미터·출력 경로·에러 코드) | **미확정** (개발계획서 11장 Q3). `RealXvarmAdapter` 가 비어 있고, 호출하면 `NOT_IMPLEMENTED` 로 **명시적으로 실패**한다 |
| 3 | 산출 임시 디렉터리 실제 경로 | **미확인** (회의에서는 `/data001/doc01/recv/XVARM` 계열로 언급) |
| 4 | 포트 | **미확인** |
| 5 | ESB 인터페이스ID (`IF_송신3_수신3_일련3`) | **미확정** (Q2) |

> 2번이 조용히 빈 결과를 돌려주지 않는 것은 의도한 동작이다. "추출했는데 파일이 없음" 으로
> 위장하면 AI 플랫폼 쪽에서 파일을 기다리다 타임아웃 날 때까지 원인을 알 수 없다.
