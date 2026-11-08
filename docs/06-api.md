# 06 API

요청·응답 필드의 정본은 [contracts/openapi.yaml](contracts/openapi.yaml)이다. 이 문서는 공통 규칙과 API별 동작, 실패했을 때 화면이 할 일을 설명한다.

## 1. 공통 규칙

- 새 API는 `/api/v1` 아래에 둔다. 아래 경로는 이 접두어를 뺀 것이다.
- 주체는 로그인 세션이나 비회원 쿠키에서 구한다. 본문의 회원 ID를 믿지 않는다.
- 쿠키 인증을 쓰므로 변경 요청은 `X-CSRF-TOKEN` 헤더가 필요하다. 토큰은 회원이면 `GET /session`, 비회원이면 `POST /guest-credentials` 응답 본문으로 받는다. 회원 토큰은 그 세션에, 비회원 토큰은 그 자격에 묶이고 발급 뒤 24시간 유효하다. 거부(403 `CSRF_REJECTED`)되면 화면은 토큰을 다시 받아 한 번 다시 보낸다(D-051).
- 시각은 오프셋이 있는 ISO-8601, 번호는 `[1,2,3,4,5,6]` 같은 정수 배열, 버전은 십진 문자열이다.
- 금액은 1게임 기준이다. 실제 구매 수량이나 총 수령액으로 표시하지 않는다.
- 명령 응답은 `operationId, status, data, serverTime`이다. 오류는 `code, message, retryable, requestId, details`다. `details`에는 입력 위치나 재시도 시각 같은 안전한 정보만 넣는다.
- 오류 코드 목록과 뜻은 openapi의 `Error.code`에 있다 **[미확인]**. 화면은 `message`가 아니라 `code`로 동작을 나눈다.
- 다른 회원의 자원은 존재 여부도 알 수 없게 404로 답한다.
- 조회 실패를 빈 목록으로 바꾸지 않는다. 429와 503에는 `Retry-After`를 준다. 브라우저는 입력 내용을 유지한다.

### 1.1 같은 요청의 반복

번호 저장은 성공했는데 화면이 응답을 못 받는 일이 생긴다. 이때 같은 요청이라는 것을 알아보고 처음 결과를 돌려준다.

- 변경 요청은 `Idempotency-Key`(UUID)와 서버가 서명한 명령 토큰(`X-Command-Token`)을 함께 보낸다.
- 명령 토큰은 주체, 명령 종류, 발급 시각, 만료 시각(발급 후 24시간)을 묶는다. 추천은 회차와 일정 버전도 묶는다. 민감한 원문은 넣지 않는다.
- 같은 사용자 행동을 다시 시도할 때는 같은 토큰, 같은 키, 같은 본문을 쓴다. 화면이 자동 재시도마다 새 키를 만들지 않는다.
- 같은 키에 다른 본문이 오면 409 `IDEMPOTENCY_CONFLICT`다.
- 처리 중인 같은 키는 202 `PROCESSING`과 조회 경로(`GET /operations/{id}`)를 준다.
- 완료된 요청을 다시 받으면 현재 소유권과 원본 보관 상태를 확인한 뒤 처음 결과를 준다. 추천 원본이 이미 지워졌으면 410 `RESPONSE_EXPIRED`다. 새 번호를 만들거나 지난 결과를 되살리지 않는다.
- 토큰이 만료됐고 완료 결과도 복구할 수 없으면 410 `COMMAND_EXPIRED`다. 새 행동은 새 토큰과 새 키로 한다.
- 멱등 기록의 보관 기간은 [02 8](02-policies.md#8-보관과-삭제)에 있다. 탈퇴하면 개인 연결을 지운다.
- 확정 실패한 명령(FAILED)을 조회하면 200이 아니라 오류 응답을 준다.

## 2. 사용자 API

| API | 하는 일 | 주요 실패와 화면 동작 |
|---|---|---|
| `GET /session` | 로그인 상태(회원·비회원·없음)와 그 주체에 묶은 CSRF 토큰 | 실패 없음. 주체가 없으면 토큰 없이 NONE |
| `GET /lotto/context` | 현재 회차, 일정 버전, 추천 가능 여부, 다음 개시 시각 | 일정 미확인은 503. 결과 수집 지연과는 별개 |
| `POST /guest-credentials` | 비회원 자격 발급. 쿠키와 CSRF 토큰을 준다 | Origin·Fetch Metadata 검사 실패 403, 발급 속도 초과 429. 429에는 다시 시도할 시각을 주고 화면이 그 시각을 보여 준다. 회원 세션 중이면 발급하지 않는다 |
| `POST /command-tokens` | 명령 토큰 발급 | 허용되지 않은 명령 403. 발급 자체는 업무 실행이 아니다 |
| `POST /lotto/recommendations` | 5세트 추천. 넣을 번호·뺄 번호는 비우면 기본 추천 | 409 회차 변경·후보 부족, 422 조건 오류, 429 생성 중·간격, 503 계산 지연 |
| `GET /guest/lotto/records` | 비회원의 보존 중인 본인 추천 | 회원 API와 같은 회차·페이지 규칙. 귀속된 뒤에는 비회원 자격으로 회원 자료를 볼 수 없다 |
| `GET /operations/{id}` | 이전 명령의 처리 결과 | 404 타인, 410 원본 만료 |
| `GET /me/lotto/records` | 내 기록. 회차·구매 여부·등수 필터, 10개씩 cursor | 아래 2.1 |
| `POST /me/lotto/purchases` | 구매 등록. 한 회차, 최대 100세트 | 전부 검증 후 한 번에 저장. 입력별 오류 위치를 준다. 판매 마감 뒤 등록이면 결과에 그 사실을 담아 화면이 알림 제외를 안내한다 |
| `POST /me/lotto/records/{id}/cancel-purchase` | 구매 등록 취소 또는 직접 등록 기록 삭제 | 409 `VERSION_CONFLICT`면 다시 조회. 추천·직접 등록에 맞는 확인 문구를 쓴다 |
| `GET /lotto/rounds/{round}/result` | 회차 결과, 게시본, 등수별 금액, 확인 시각 | 미발표, 수집 지연, 잘못된 원천을 구분한다 |
| `POST /lotto/check` | 저장 없는 즉시 확인. 최대 5세트 | 입력 번호를 로그나 통계에 남기지 않는다 |
| `POST /me/guest-claims/{id}/imports` | 비회원 기록 가져오기. 로그인 직후와 나중의 내 기록 화면에서 같은 API를 쓴다(D-036) | 202와 진행 상태. 다른 계정에 귀속됐거나 만료된 자료는 가져올 수 없다. 결과에 판매 마감 뒤에 가져와 알림 대상이 아닌 회차를 담는다 |
| `POST /me/notification-verifications` | 수신 번호 인증 코드 발송 | 발송 간격·하루 횟수([02 6.4](02-policies.md#64-수신-번호와-동의)) 초과 429. 실패해도 사이트는 그대로 쓴다 |
| `POST /me/notification-verifications/{id}/verify` | 인증 코드 확인 | 만료 410, 시도 초과 429, 연락처 버전이 바뀌었으면 409 |
| `GET`, `PUT /me/notification-preference` | 알림 수신 설정 조회와 변경 | 켜려면 현재 번호 인증이 필요하다. 끄면 새 발송을 바로 막는다 |
| `DELETE /me/account` | 탈퇴 | 최근 5분 재인증이 없으면 403. 202로 삭제 접수 |

### 2.1 내역 조회

- 기본(필터 없음, 현재 회차) 조회가 0건이면 `emptyReason=NO_CURRENT_RECORDS`다. 화면은 이것을 보고 추천 화면으로 옮긴다. HTTP 리디렉션이 아니다.
- 필터 결과 0건은 `NO_MATCH`, 사용자가 고른 과거 회차 0건은 `NO_RECORDS`다. 둘 다 이동하지 않는다.
- cursor는 (회차, ID)와 필터 해시를 서명한 값이다. 변조하거나 다른 필터에 재사용할 수 없다.
- 첫 페이지 시점을 기준으로 이어서 보여주므로, 새 기록이 생겨도 페이지가 흔들리지 않는다. 다만 수정·삭제까지 과거 시점으로 고정하지는 않는다.
- 등수 필터에서 최신 판정이 없는 기록은 낙첨에 넣지 않는다.

## 3. 로그인 경로

Spring Security OAuth2 기본 경로를 쓴다. `/api/v1` 밖이다.

| 경로 | 하는 일 |
|---|---|
| `GET /oauth2/authorization/{provider}` | 카카오·구글·네이버 로그인 시작 |
| `GET /login/oauth2/code/{provider}` | 제공자 콜백. 로그인 후 비회원 귀속 확인 화면으로 보낸다 |
| `POST /logout` | 로그아웃. 서버 세션 회수 |

재인증과 관리자 다중 요소 인증 방식은 [03 6.3](03-architecture.md#63-인증과-관리자)에 있다. 재인증은 서버 세션에 적은 재인증 시각으로 확인한다(C-31). 화면은 로그인 시작 주소에 `reauth=true`를 붙여(예: `/oauth2/authorization/kakao?reauth=true`) 다시 인증하고 돌아온다. 다른 소셜 계정으로 돌아오거나 취소하면 `/?reauth=failed`로 돌아오고 세션은 그대로다. openapi의 `X-Reauthentication-Proof` 헤더는 뺐다.

## 4. 관리자 API

첫 출시에 필요한 최소 범위다. 모두 관리자 세션과 다중 요소 인증이 필요하고, 변경은 `admin_audit_event`에 남는다.

| API | 하는 일 | 권한 |
|---|---|---|
| `GET /admin/rounds/{round}/notification-status` | 회차 알림 현황. 슬롯별 결과와 원인, 상태별 메시지 수 | OPERATOR 이상 |
| `POST /admin/rounds/{round}/notification-dispatches` | 수동 다시 보내기. 못 보낸 기본 알림과, 정정으로 새로 대상이 된 회원의 기본 알림. 기한은 [02 6.2](02-policies.md#62-발송-시각과-재시도). 사유 필수, `Idempotency-Key` 필요 | OPERATOR 이상 |
| `POST /admin/draw-results/{resultId}/publication` | 격리된 공식 결과를 새 게시본으로 승인. 현재 게시 버전(`lotto_round.publication_version`)과 사유 필수 | ADMIN |
| `POST /admin/rounds/{round}/draw-results` | 결과 API가 주지 않는 회차의 결과 직접 등록(D-034). 조건은 [02 5](02-policies.md#5-결과와-판정). 사유, 최근 5분 재인증, 현재 게시 버전 필수. 이미 게시됐으면 409 `RESULT_ALREADY_PUBLISHED`, 그 밖에 게시 버전이 다르면 409 `VERSION_CONFLICT` | ADMIN |
| `GET /admin/stats/daily` | 기간별 기본 지표 | OPERATOR 이상 |
| `GET /admin/stats/rounds/{round}` | 회차별 기본 지표 | OPERATOR 이상 |

관리자 권한 부여 절차는 [03 6.3](03-architecture.md#63-인증과-관리자)에 있다(C-33). 관리자 화면 구성은 아직 정하지 않았다.

## 5. 기존 API 전환

- 지금 있는 `/api/lotto/*`는 정적 화면의 호출 위치를 확인한 뒤 같은 릴리스에서 새 경로로 바꾼다.
- 회원과 비회원의 추천 경로는 하나의 처리로 합친다.
- `source=AD`처럼 의미가 있는 입력을 몰래 BASIC으로 바꾸는 호환 계층은 만들지 않는다. 외부 사용자가 확인되면 전환 기간을 따로 계획한다.
