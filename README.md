[![CI](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/Aelleek/Lottery-Service)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/Aelleek/Lottery-Service)](https://github.com/Aelleek/Lottery-Service/commits/main)
![Java](https://img.shields.io/badge/Java-17-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A)
![DB](https://img.shields.io/badge/DB-MySQL%208.4-informational)
![Auth](https://img.shields.io/badge/Auth-OAuth2%20(Google%2FKakao%2FNaver)-yellow)

# Lottery Service

로또 번호 추천과 구매 기록, 당첨 확인, 결과 알림을 제공하는 Spring Boot 서비스입니다. 회원은 소셜 로그인(구글, 카카오, 네이버)으로, 비회원은 쿠키 자격으로 씁니다.

서비스 정의와 정책, 구조, API 계약은 [docs/](docs/README.md)에 있습니다. 이 문서는 코드를 받아 실행하고 검증하는 방법을 다룹니다.

## 주요 기능

- 번호 추천: 기본 추천(생성 필터 7개, 과거 1등 제외)과 조건 추천(넣을 번호·뺄 번호). 회원과 비회원이 같은 생성기를 쓰고, 같은 회차에 받은 조합은 다시 주지 않습니다.
- 구매 기록: 직접 등록과 취소, 회차별 내역, 비회원 기록 가져오기.
- 당첨 결과: 동행복권 결과 수집과 게시, 내용이 다르면 격리 후 관리자 승인, 결과 직접 등록, 등수 판정과 정정 뒤 재판정, 즉시 확인.
- 결과 알림: 휴대폰 번호 인증과 동의, 알림톡 발송 슬롯, 접수 여부 대조, 월 예산, 운영자 알림(Discord).
- 계정: 탈퇴와 개인 자료 삭제, 삭제 기록으로 복원 뒤 재적용, 보관 기간 정리와 집계.
- 관리자: TOTP 2차 인증, 알림 현황·통계 조회, 다시 보내기·결과 승인·직접 등록(감사 기록).

## 기술 스택

- Java 17, Spring Boot 3, Gradle Wrapper
- MySQL 8.4, Flyway(스키마), Spring Session JDBC(회원 세션), `JdbcTemplate`
- Spring Security OAuth2 Client
- 테스트: JUnit 5, H2(단위), Testcontainers MySQL(저장소·웹 통합)
- 품질: Spotless(Google Java Format), Checkstyle, PMD

## 코드 구조

`com.lottery.lottery_service` 아래를 기능 단위로 나눕니다.

| 패키지 | 내용 |
|---|---|
| `round` | 회차 일정, 판매 마감과 봉인 |
| `recommendation` | 추천 생성기, 생성 필터, 멱등 명령 |
| `record` | 구매 등록·취소, 내역, 가져오기, 보관 정리 |
| `result` | 결과 수집·게시·격리·직접 등록, 판정 |
| `notification` | 번호 인증·동의, 결과 알림 발송과 대조, 예산 |
| `account` | 회원·비회원 주체, 탈퇴, 복원 |
| `auth` | 소셜 로그인, 세션, CSRF, 재인증 |
| `admin` | 관리자 권한, 2차 인증, 감사, 관리자 API |
| `stats` | 사용량 집계 |
| `common` | job 실행기, 명령 토큰, 요청 제한, 키, 외부 호출, 운영자 알림 |
| `config` | 보안·웹 필터 설정, 서버 관리 명령 환경 |

스키마는 `src/main/resources/db/migration/V1__first_release_schema.sql`이며 [docs/contracts/schema.sql](docs/contracts/schema.sql)과 같아야 합니다.

## 로컬 실행

필요한 것: JDK 17, Docker(Compose 포함).

```bash
cp .env.example .env
cp config/local-db.example.yml config/local-db.yml
cp config/secrets.example.yml config/secrets.yml           # 키를 난수로 채운다: openssl rand -base64 32
cp config/integrations.example.yml config/integrations.yml # 알림을 쓰지 않으면 만들지 않아도 된다
docker compose up -d                                       # MySQL 8.4
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

- 스키마는 앱이 시작할 때 Flyway가 만듭니다. 옛 테이블이 남은 로컬 DB는 `docker compose down -v`로 지우고 다시 만듭니다.
- 소셜 로그인 값은 환경 변수(`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`)나 `config/oauth-client.yml`로 넣습니다.
- 주기 작업(마감 봉인, 결과 수집, 발송, 정리)은 기본으로 꺼져 있습니다. 켜려면 `LOTTERY_WORKER_ENABLED=true`를 줍니다.
- `local` 프로필은 http에서 쿠키가 붙도록 쿠키의 `Secure`를 끕니다. 운영에서는 이 프로필을 쓰지 않습니다.

`config/` 아래 실제 설정 파일과 `.env`는 git에 올리지 않습니다.

## 테스트

```bash
./gradlew test        # 단위 테스트(H2, Docker 불필요)
./gradlew mysqlTest   # 실제 MySQL 8.4 컨테이너로 도는 저장소·웹 통합 테스트(Docker 필요)
./gradlew loadTest    # 부하 목표 확인(Docker 필요, -Dload.seconds=60 -Dload.users=100 -Dload.rps=20)
```

수용 시나리오(T01~T65)는 [docs/08-delivery.md](docs/08-delivery.md#5-수용-시나리오)에 있고, 테스트 이름에 시나리오 ID를 적습니다. 웹 통합 테스트는 응답이 [openapi.yaml](docs/contracts/openapi.yaml)과 맞는지도 확인합니다.

## 품질 검사

```bash
./gradlew spotlessApply   # 포맷 적용
./gradlew spotlessCheck   # 포맷 검사(CI에서 항상)
./gradlew checkstyleMain checkstyleTest pmdMain pmdTest -DchangedFiles=<쉼표로 이은 경로>
```

CI는 바뀐 파일에만 Checkstyle과 PMD를 적용하고, 위반이 있으면 실패합니다. `-DchangedFiles` 없이 돌리면 리포트만 만듭니다.

## API

모든 API는 `/api/v1` 아래에 있고 계약은 [docs/contracts/openapi.yaml](docs/contracts/openapi.yaml), 설명은 [docs/06-api.md](docs/06-api.md)에 있습니다.

- 세션과 자격: `GET /session`(CSRF 토큰), `POST /guest-credentials`(비회원 자격)
- 추천: `POST /command-tokens`, `POST /lotto/recommendations`
- 기록: `POST /me/lotto/purchases`, `POST /me/lotto/records/{recordId}/cancel-purchase`, `GET /me/lotto/records`, `GET /guest/lotto/records`
- 결과: `GET /lotto/rounds/{round}/result`, `POST /lotto/check`
- 알림: `POST /me/notification-verifications`, `PUT /me/notification-preference` 등
- 관리자: `/admin/**`(2차 인증 필요)

변경 요청에는 `X-CSRF-TOKEN` 헤더가 필요하고, 추천·구매 같은 명령은 명령 토큰(`X-Command-Token`)이나 `Idempotency-Key`로 한 번만 처리됩니다.

## 서버 관리 명령

웹 서버와 주기 작업을 띄우지 않고 한 번 실행한 뒤 끝나는 명령입니다. 운영 절차는 [docs/07-operations.md](docs/07-operations.md)에 있습니다.

| 명령 | 용도 |
|---|---|
| `--lottery.admin.grant.member=<회원 외부 ID> --lottery.admin.grant.action=grant\|revoke\|reset-mfa --lottery.admin.grant.reason=<사유>` | 관리자 부여, 회수, 2차 인증 초기화 |
| `--lottery.secrets.reencrypt.ring=contact\|admin-mfa` | 키를 바꾼 뒤 남은 암호문을 현재 키로 다시 암호화 |
| `--lottery.restore.replay-journal=true` | 백업 복원 뒤 삭제 기록 재적용 |
| `--lottery.incident.revoke-sessions=true` | 보안 사고 때 모든 회원 세션 회수 |

## License

MIT

## Credits

- Author: aelleek@gmail.com
