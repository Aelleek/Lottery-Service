[![CI](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Aelleek/Lottery-Service/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/Aelleek/Lottery-Service)](LICENSE)
[![Last Commit](https://img.shields.io/github/last-commit/Aelleek/Lottery-Service)](https://github.com/Aelleek/Lottery-Service/commits/main)
![Java](https://img.shields.io/badge/Java-17-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A)
![DB](https://img.shields.io/badge/DB-H2%20%7C%20MySQL-informational)
![Auth](https://img.shields.io/badge/Auth-OAuth2%20(Google%2FKakao%2FNaver)-yellow)


# Lottery Service

> Spring Boot 기반 로또 번호 추천/구매 기록 서비스. OAuth2 소셜 로그인(카카오/구글/네이버) 연동, H2(기본) 또는 MySQL 사용.

---

## ✨ Key Features

* 로또 번호 **추천/구매** API
* 사용자별 **구매 기록** 조회
* **OAuth2 로그인**: 카카오, 구글, 네이버
* **H2 인메모리 DB**(기본) 또는 **MySQL** 전환 가능
* 코드 품질: **Spotless / Checkstyle / PMD** 적용
* CI 준비(브랜치 보호 규칙/검사 작업과 연동 용이)

---

## 🛠 Tech Stack

* **Language**: Java 17
* **Framework**: Spring Boot 3.x
* **Build**: Gradle (Wrapper 포함)
* **DB**: H2 (dev default), MySQL (optional)
* **Auth**: Spring Security OAuth2 Client
* **Quality**: Spotless, Checkstyle, PMD

---

## 📦 Project Structure (요약)

```
src/
 ├─ main/
 │   ├─ java/com/lottery/lottery_service/
 │   │   ├─ lotto/ ...        # 로또 도메인(컨트롤러/서비스/도메인)
 │   │   └─ member/ ...       # 회원/인증 관련
 │   └─ resources/
 │       ├─ application.yml   # 기본 설정(H2)
 │       └─ ...
 └─ test/ ...
```

---

## ⚙️ Setup (Local, No Docker)

### 1) Prerequisites

* **JDK 17** (Temurin/AdoptOpenJDK 권장)
* **Gradle Wrapper** 포함 → 별도 Gradle 설치 불필요
* (선택) **MySQL 8.x**

### 2) Run with H2 (기본)

`src/main/resources/application.yml`에서 H2가 기본 활성화되어 있습니다. 아래로 바로 실행하세요:

```bash
./gradlew clean bootRun
# 또는
./gradlew bootJar && java -jar build/libs/<your-jar>.jar
```

* H2 콘솔: `http://localhost:8080/h2-console` (설정 시 활성화)
* JDBC URL 예: `jdbc:h2:mem:testdb`

### 3) Run with MySQL (옵션)

**application-mysql.yml**(예시) 또는 환경변수로 전환합니다.

**application-mysql.yml (예시)**

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/lottery?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: lotto
    password: lotto
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update   # 운영에서는 validate + Flyway 권장
    properties:
      hibernate:
        format_sql: true
  profiles:
    active: mysql
```

실행:

```bash
SPRING_PROFILES_ACTIVE=mysql ./gradlew bootRun
# 또는
SPRING_PROFILES_ACTIVE=mysql java -jar build/libs/<your-jar>.jar
```

> 참고: MySQL 문자셋을 `utf8mb4`/`utf8mb4_unicode_ci`로 설정하면 이모지/다국어 안전합니다.

---

## 🔐 OAuth2 설정

소셜 로그인을 쓰려면 **민감정보를 별도 파일**로 분리하세요(예: `oauth-client.yml`).

**config/oauth-client.yml (예시)**

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

**application.yml**에서 추가 경로를 잡아주거나(예: `SPRING_CONFIG_ADDITIONAL_LOCATION`) 직접 include 하세요. 해당 파일은 `.gitignore`에 추가해 **커밋 금지**합니다.

---

## 🚦 Quality Gates (Code Style & Static Analysis)

아래 명령으로 코드 포맷/정적분석을 실행합니다.

```bash
./gradlew spotlessApply       # 코드 포맷팅 적용
./gradlew spotlessCheck       # 포맷 검사
./gradlew checkstyleMain      # Checkstyle
./gradlew pmdMain             # PMD
./gradlew test                # 단위 테스트
```

CI에서도 동일 명령을 사용하도록 구성하면 PR 품질을 보장할 수 있습니다.

---

## 📚 API

> 실제 컨트롤러/DTO에 맞춰 수치·필드명을 조정하세요. 아래는 **구체 예시**입니다.

### 인증 & 프로필

* **OAuth 시작**: `GET /oauth2/authorization/{provider}` (provider: `google|kakao|naver`)
* **내 프로필**: `GET /api/me` → 로그인 필요

### 로또

| Method | Path                   | Auth     | 설명                         |
| ------ | ---------------------- | -------- | -------------------------- |
| GET    | `/api/lotto/recommend` | Optional | 추천 번호 `count`개(기본 5) 세트 반환 |
| POST   | `/api/lotto/purchase`  | Required | 추천/선택 번호를 구매 기록으로 저장       |
| GET    | `/api/lotto/records`   | Required | 내 구매 기록 목록 조회              |

#### 요청/응답 예시

**추천 번호**

```http
GET /api/lotto/recommend?count=5 HTTP/1.1
```

```json
[
  {"numbers": [1, 3, 12, 24, 33, 41]},
  {"numbers": [2, 11, 16, 20, 29, 44]}
]
```

**구매 요청**

```http
POST /api/lotto/purchase HTTP/1.1
Content-Type: application/json
```

```json
{
  "source": "recommendation", // 또는 "manual"
  "sets": [ {"numbers": [1,3,12,24,33,41]} ]
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
    "numbers": [1,3,12,24,33,41]
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

### Postman / cURL

* Postman Collection은 `docs/postman/LotteryService.json`에 제공(예정)
* cURL로는 위 HTTP 예시를 그대로 사용하면 됩니다.

---

## 🧭 Branch & Commit (권장)

* 브랜치: `feature/*`, `fix/*`, `chore/*` 등 prefix 사용
* 커밋 메시지: Conventional Commits 권장

    * `feat(lotto): 추천 번호 생성 규칙 추가`
    * `fix(auth): OAuth redirect-uri 인코딩 문제 수정`

---

## ❗ Troubleshooting

* H2 콘솔 접속 불가 → `spring.h2.console.enabled=true` 확인
* MySQL 연결 실패 → 포트/계정/권한, `serverTimezone`, `characterEncoding` 파라미터 확인
* OAuth 로그인 X → 제공자 대시보드의 **Redirect URI**가 앱 베이스 URL과 일치하는지 확인

---

## 📄 License

MIT

---

## Credits

* Author: aelleek@gmail.com
* Stack: Spring Boot, Java 17, Gradle
