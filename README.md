[![CI](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/Aelleek/Lottery-Service)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/Aelleek/Lottery-Service)](https://github.com/Aelleek/Lottery-Service/commits/main)
![Java](https://img.shields.io/badge/Java-17-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A)
![DB](https://img.shields.io/badge/DB-MySQL%20(local)%20%7C%20H2%20(test)-informational)
![Auth](https://img.shields.io/badge/Auth-OAuth2%20(Google%2FKakao%2FNaver)-yellow)

# Lottery Service

> Spring Boot 기반 로또 번호 추천/구매 기록 서비스  
> OAuth2 소셜 로그인(카카오/구글/네이버)을 지원하며,  
> **로컬 개발 실행은 Docker Compose MySQL + `local` profile**,  
> **테스트는 H2 기반 `test` 환경**을 사용합니다.

---

## ✨ Key Features

- 로또 번호 **추천/구매** API
- 사용자별 **구매 기록** 조회
- **OAuth2 로그인**: 카카오, 구글, 네이버
- 로컬 개발 환경: **Docker Compose + MySQL 8**
- 테스트 환경: **H2 in-memory**
- 코드 품질: **Spotless / Checkstyle / PMD**

---

## 🛠 Tech Stack

- **Language**: Java 17
- **Framework**: Spring Boot 3.x
- **Build**: Gradle (Wrapper 포함)
- **DB (local runtime)**: MySQL 8.x via Docker Compose
- **DB (test)**: H2 in-memory
- **Auth**: Spring Security OAuth2 Client
- **Quality**: Spotless, Checkstyle, PMD

---

## 📦 Project Structure

```text
src/
 ├─ main/
 │   ├─ java/com/lottery/lottery_service/
 │   │   ├─ lotto/ ...        # 로또 도메인(컨트롤러/서비스/도메인)
 │   │   └─ member/ ...       # 회원/인증 관련
 │   └─ resources/
 │       ├─ application.yml         # 공통 설정
 │       ├─ application-local.yml   # local runtime 설정
 │       └─ data.sql
 └─ test/
     └─ resources/
         └─ application-test.yml    # test(H2) 설정

config/
 ├─ local-db.example.yml
 └─ oauth-client.yml                # gitignore

docker-compose.yml
.env.example
```

---

## ⚙️ Setup (Local Development)

### 1) Prerequisites

- **JDK 17**
- **Gradle Wrapper 포함** → 별도 Gradle 설치 불필요
- **Docker / Docker Compose**

### 2) 로컬 설정 파일 준비

예시 파일을 복사해서 실제 로컬 설정 파일을 생성합니다.

```bash
cp .env.example .env
cp config/local-db.example.yml config/local-db.yml
```

### 3) `.env` 설정

`docker-compose.yml` 에서 사용할 MySQL 컨테이너 환경변수입니다.

예시:

```dotenv
MYSQL_HOST_PORT=13306
MYSQL_DATABASE=lottery
MYSQL_USER=lottery
MYSQL_PASSWORD=lottery
MYSQL_ROOT_PASSWORD=rootpassword
```

### 4) `config/local-db.yml` 설정

Spring Boot `local` profile 에서 사용할 DB 연결 정보입니다.

예시:

```yaml
lottery:
  db:
    host: localhost
    port: 13306
    name: lottery
    username: lottery
    password: lottery
```

### 5) MySQL 실행

```bash
docker compose up -d
docker compose ps
```

정상 기동 시 `lottery-mysql` 이 `healthy` 상태가 되어야 합니다.

### 6) 애플리케이션 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

### 7) 테스트 실행

```bash
./gradlew test
```

> 참고: 현재 일부 테스트는 레거시 상태로 복구 작업이 필요합니다.

---

## 🔐 OAuth2 설정

소셜 로그인을 사용하려면 민감정보를 별도 파일로 분리합니다.

`config/oauth-client.yml` 예시:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: YOUR_GOOGLE_ID
            client-secret: YOUR_GOOGLE_SECRET
            scope: [openid, profile, email]
            redirect-uri: "{baseUrl}/login/oauth2/code/google"

          kakao:
            client-id: YOUR_KAKAO_REST_API_KEY
            client-secret: YOUR_KAKAO_CLIENT_SECRET
            client-authentication-method: client_secret_post
            redirect-uri: "{baseUrl}/login/oauth2/code/kakao"
            scope: [profile_nickname, account_email]

          naver:
            client-id: YOUR_NAVER_CLIENT_ID
            client-secret: YOUR_NAVER_CLIENT_SECRET
            client-authentication-method: client_secret_post
            redirect-uri: "{baseUrl}/login/oauth2/code/naver"
            scope: [name, email]

        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id

          naver:
            authorization-uri: https://nid.naver.com/oauth2.0/authorize
            token-uri: https://nid.naver.com/oauth2.0/token
            user-info-uri: https://openapi.naver.com/v1/nid/me
            user-name-attribute: response
```

### 외부 설정 파일 원칙

다음 파일들은 로컬 전용/민감정보 파일로 관리하고 `.gitignore` 에 포함합니다.

- `config/oauth-client.yml` - OAuth2 민감정보
- `config/local-db.yml` - local DB 연결 정보
- `.env` - Docker Compose MySQL 환경변수

---

## 🚦 Quality Gates (Code Style & Static Analysis)

```bash
./gradlew spotlessApply       # 코드 포맷팅 적용
./gradlew spotlessCheck       # 포맷 검사
./gradlew checkstyleMain      # Checkstyle
./gradlew pmdMain             # PMD
./gradlew test                # 테스트 실행
```

> 참고: 현재 테스트는 전부 green 상태가 아닐 수 있습니다.  
> 로컬 실행과 API 스모크 테스트는 확인되었지만, 테스트 컨텍스트/OAuth2 관련 복구 작업이 별도로 필요합니다.

---

## 📚 API

> 실제 컨트롤러/DTO에 맞춰 수치·필드명을 조정하세요. 아래는 예시입니다.

### 인증 & 프로필

- **OAuth 시작**: `GET /oauth2/authorization/{provider}` (`google|kakao|naver`)
- **내 프로필**: `GET /api/me` → 로그인 필요

### 로또

| Method | Path                   | Auth     | 설명 |
| ------ | ---------------------- | -------- | ---- |
| GET    | `/api/lotto/recommend` | Optional | 추천 번호 `count`개(기본 5) 세트 반환 |
| POST   | `/api/lotto/purchase`  | Required | 추천/선택 번호를 구매 기록으로 저장 |
| GET    | `/api/lotto/records`   | Required | 내 구매 기록 목록 조회 |

#### 요청/응답 예시

**추천 번호**

```http
GET /api/lotto/recommend?count=5 HTTP/1.1
```

```json
[
  { "numbers": [1, 3, 12, 24, 33, 41] },
  { "numbers": [2, 11, 16, 20, 29, 44] }
]
```

**구매 요청**

```http
POST /api/lotto/purchase HTTP/1.1
Content-Type: application/json
```

```json
{
  "source": "recommendation",
  "sets": [
    { "numbers": [1, 3, 12, 24, 33, 41] }
  ]
}
```

**구매 기록 조회**

```http
GET /api/lotto/records HTTP/1.1
```

```json
[
  {
    "id": 123,
    "purchasedAt": "2025-09-20T12:30:05Z",
    "numbers": [1, 3, 12, 24, 33, 41]
  }
]
```

### 에러 포맷(예시)

```json
{
  "timestamp": "2025-09-24T01:23:45Z",
  "status": 400,
  "error": "Bad Request",
  "code": "LOTTO-001",
  "message": "유효하지 않은 번호입니다",
  "path": "/api/lotto/purchase"
}
```

---

## 🧭 Branch & Commit (권장)

- 브랜치: `feature/*`, `fix/*`, `chore/*`, `test/*`
- 커밋 메시지: Conventional Commits 권장

예:

- `feat(lotto): 추천 번호 생성 규칙 추가`
- `fix(auth): OAuth redirect-uri 인코딩 문제 수정`
- `chore(env): add docker mysql setup for local runtime`
- `test(auth): recover oauth test context`

---

## ❗ Troubleshooting

- **MySQL 컨테이너가 뜨지 않음**
  - `.env` 값과 `docker-compose.yml` 변수명이 일치하는지 확인
  - `docker compose ps` 에서 `healthy` 상태인지 확인

- **OAuth 로그인 실패**
  - 제공자 대시보드의 Redirect URI 와 `config/oauth-client.yml` 설정 확인

- **테스트 컨텍스트 실패**
  - OAuth2 `ClientRegistrationRepository` 빈 누락 여부 확인
  - 테스트 프로필 및 보안 설정 분리 여부 확인

---

## 📄 License

MIT

---

## Credits

- Author: aelleek@gmail.com
- Stack: Spring Boot, Java 17, Gradle, MySQL, H2