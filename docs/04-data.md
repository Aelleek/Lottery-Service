# 04 데이터

테이블과 컬럼의 정본은 [contracts/schema.sql](contracts/schema.sql)이다. 이 문서는 각 테이블이 왜 있는지, 어떤 규칙을 지키는지, 어떤 순서로 지우는지를 설명한다. 보관 기간 값은 [02 8](02-policies.md#8-보관과-삭제)에 있다.

## 1. 저장 원칙

가장 중요한 결정은 **사용자에게 보이는 기록, 이미 추천했다는 사실, 서비스 통계를 따로 저장하는 것**이다.

예를 들어 사용자가 추천 번호를 구매 등록했다가 취소하면 화면에는 추천 기록이 남는다. 하지만 취소했다고 그 조합을 처음 추천하는 것처럼 되돌리거나 전체 추천 개수를 줄이면 안 된다. 한 테이블로 모든 용도를 처리하면 삭제가 다른 기능의 의미까지 바꾼다. 그래서 화면용 기록(`lotto_record`), 같은 회차 추천 이력(`recommendation_seen`), 사용량 집계(`usage_aggregate`)를 나눈다.

- **번호.** 정렬한 6개를 `CHAR(17)` 문자열로 저장한다. `1, 2, 3, 4, 5, 6`은 `01 02 03 04 05 06`이다. 같은 조합인지는 이 값의 유일성 제약으로 막는다. API에서는 정수 배열이다.
- **ID.** 내부 ID는 `BIGINT`, 외부에 보이는 ID는 UUID(`BINARY(16)`)다. 외부 ID로 찾을 때도 소유권을 검사한다.
- **시각.** UTC `DATETIME(6)`으로 저장하고, 날짜 경계는 서울 시간으로 계산한다.
- **버전.** 동시 변경을 막는 `version`은 `BIGINT`다. API에서는 십진 문자열로 보낸다.
- **금액.** 아직 모르는 금액은 `NULL`이다. 0원과 구분한다.
- **검증.** 앱이 먼저 검증하고, DB는 NOT NULL, FK, 유일성, CHECK로 한 번 더 막는다.
- **FK.** 모두 기본값(RESTRICT)이다. `ON DELETE CASCADE`를 쓰지 않고, 애플리케이션이 6절의 순서로 지운다.

**검토했지만 쓰지 않은 방식**

| 방식 | 쓰지 않은 이유 |
|---|---|
| 화면 기록 한 테이블만 둠 | 단순하지만, 기록을 지우면 같은 번호를 다시 추천하고 통계도 사라진다 |
| 모든 원본을 영구 보관 | 정정 대응은 쉬워지지만 추천 30일 보관 정책과 개인정보 수명을 어긴다 |
| 분산 잠금이나 메시지 브로커 추가 | 초기 규모에 비해 운영할 구성과 장애 지점이 늘어난다. MySQL 트랜잭션으로 필요한 불변조건을 먼저 검증한다 |
| 완전한 이벤트 소싱 | 마감 복원에 필요한 범위만 이력으로 남기고, 현재 상태는 기록 테이블에 둔다. 모든 조회를 이벤트 재생으로 만들지 않는다 |
| 광고·한도 테이블을 미리 만듦 | 첫 출시에 쓰지 않는다. 무제한 정책 객체와 과부하 보호만 두고, 광고를 도입할 때 별도로 설계한다 |

## 2. 테이블

42개다. 이 밖에 세션 저장용 Spring Session JDBC 표준 테이블 2개를 쓴다 **[미확인]**.

| 묶음 | 테이블 | 하는 일 | 꼭 지키는 규칙 |
|---|---|---|---|
| 회원과 주체 | `member` | 회원. 개인정보 컬럼이 없다 | 상태: ACTIVE, WITHDRAWING(탈퇴 처리 중), WITHDRAWN(묘비) |
| | `oauth_account` | 소셜 계정 연결 | (제공자, 제공자 사용자 ID) 유일. 이메일은 신원 키가 아니다 |
| | `actor_subject` | 회원과 비회원을 같은 "주체"로 다룬다. 기록 변경의 잠금 기준. `generation_lease_until`은 주체당 동시에 하나만 추천을 생성하게 하는 lease, `generation_fence`는 늦게 끝난 생성의 저장을 거부하는 번호, `next_generation_at`은 다음 추천이 가능한 시각 | 회원 주체는 ACTIVE, WITHDRAWING, WITHDRAWN만, 비회원 주체는 ACTIVE, CLAIMED(귀속됨)만 가진다 |
| | `guest_credential` | 비회원 자격 | 쿠키 원문은 저장하지 않고 해시만 둔다 |
| | `subject_claim` | 비회원 자료를 회원에게 귀속 | 비회원 주체 하나는 한 번만 귀속. `imported_at`이 비었으면 귀속만 되고 아직 가져오지 않은 상태 |
| 회차 | `lotto_round` | 판매 개시·마감·추첨 시각, 일정 버전, 게시된 결과, 게시 버전, 마감 봉인 상태, 알림 계획 완료 시각 | 게시된 결과의 정본은 `published_result_id`. 게시 버전(`publication_version`)은 게시 결과가 바뀔 때마다 1 오르고 승인·직접 등록의 충돌 검사에 쓴다. 봉인 상태: OPEN, SEALED. 알림 계획 완료 조건은 [05 8.7](05-flows.md#87-알림-계획-완료) |
| 명령 | `command_operation` | 같은 요청을 다시 받으면 처음 결과를 돌려주기 위한 기록 | (주체, 명령, 토큰 ID, 클라이언트 키) 유일. 상태: PROCESSING, SUCCEEDED, RETRYABLE, FAILED, EXPIRED |
| 추천 | `recommendation_request` | 성공한 추천 요청 하나. 생성 방식과 필터 버전을 함께 기록 | 기본 추천은 필터 버전 필수, 조건 추천은 필터 버전 없음 |
| | `recommendation_item` | 그 요청의 5세트 | 요청당 정확히 5개, 서로 다른 조합 |
| | `recommendation_seen` | 같은 회차에 이미 추천한 조합 | (주체, 회차, 조합) 유일. 화면 기록과 독립 |
| 기록 | `lotto_record` | 사용자에게 보이는 번호 기록 | (주체, 회차, 조합) 유일. 추천 이력은 `recommended_at`, 구매는 `purchased`로 따로 표시 |
| | `record_transition` | 기록의 모든 변경 이력. 최초 생성 포함 | (기록, 버전) 유일. 변경 **후** 상태만 저장하고, 변경 전 상태는 직전 버전 행에서 읽는다 |
| | `history_import_item` | 비회원 기록 가져오기의 항목별 결과 | (귀속, 원본 기록) 유일. 재실행이 사용자가 지운 기록을 되살리지 않게 한다 |
| 결과와 판정 | `draw_result` | 회차 공식 결과의 게시본. 결과 API로 받았거나 ADMIN이 직접 등록한 것(`source`) | (회차, 게시본 번호) 유일. 의미가 같은 결과는 새 게시본을 만들지 않는다. 외부 ID가 있다. 상태: VERIFIED(검증을 통과함. 게시 여부는 회차의 `published_result_id`), QUARANTINED(게시본과 달라 승인 대기), SUPERSEDED(새 게시본으로 대체됨) |
| | `draw_prize` | 등수별 1게임 금액과 당첨자 수 | 모르는 값은 NULL |
| | `winning_evaluation` | 기록별 판정 | (기록, 게시본, 기록 버전) 유일. 행이 없으면 판정 대기, `rank_no`가 NULL이면 낙첨 |
| | `evaluation_run` | 게시본별 판정 작업의 진행 위치 | 상태: READY, RUNNING, SUCCEEDED, SUPERSEDED(새 게시본이 나와 중단), FAILED |
| 연락처와 동의 | `member_contact` | 암호화한 수신 번호 | 번호를 바꿀 때마다 버전 증가 |
| | `verification_challenge` | 인증 코드 요청 | 코드는 HMAC으로만 저장. 시도 상한은 [02 6.4](02-policies.md#64-수신-번호와-동의). 상태: ACTIVE, CONSUMED(사용함), EXPIRED, REVOKED(재발급으로 무효), LOCKED(시도 초과) |
| | `verification_rate_bucket` | 계정별·번호별 하루 발송 횟수 | 번호 원문 대신 HMAC |
| | `consent_event` | 동의와 철회 이력 | 재동의를 이전 동의와 구분 |
| | `notification_preference` | 현재 수신 설정 | 현재 인증된 번호 버전과 동의에 묶인다 |
| 알림 계획 | `notification_cycle` | 회원·회차별 알림 묶음 | (회원, 회차, 채널) 유일. 대상 0건도 완료 행을 남긴다. 상태: WAITING_EVALUATION(마감 대상의 판정 대기), PLANNED(보낼 알림이 있음), NO_TARGET(대상 없음으로 완료), CLOSED(회차 알림 계획 완료) |
| | `notification_cutoff_item` | 판매 마감 시점의 대상 기록 | 마감 때 그 회원에게 속한 기록만 |
| | `notification_intent` | 기본 알림 1건과 정정 알림 | 기본은 회차당 하나(순번 0), 정정은 이전 안내를 가리킨다. 정정으로 처음 대상이 된 회원은 기본 intent가 정정 뒤에 생길 수 있다([05 8.5](05-flows.md#85-정정-알림)) |
| | `notification_item` | 알림에 담긴 기록별 등수와 금액 | — |
| | `notification_communicated_fact` | 기록별로 마지막으로 접수된 안내 사실 | 본문이 정리된 뒤에도 정정 비교에 쓴다. 번호·전화번호는 복제하지 않는다 |
| 발송 | `delivery_message` | 실제로 보낼 메시지 하나. 결과 알림 또는 인증 코드 | 목적에 맞는 참조가 정확히 하나. 상태는 [05 8](05-flows.md#8-알림-발송) |
| | `delivery_attempt` | 제공자 호출 시도 | (메시지, 시도 번호) 유일 |
| | `provider_callback` | 제공자 콜백 | (제공자, 이벤트 ID) 유일. 서명 검증을 통과한 것만. 상태: UNMATCHED(메시지를 아직 못 찾음), PENDING(메시지를 찾았고 반영 전), PROCESSED. 이 PENDING은 메시지의 PENDING과 다르다 |
| | `message_budget` | 월 예산 | 0.1원 단위 정수. 예약과 확인 중 비용도 상한에 포함 |
| | `budget_reservation` | 메시지별 비용 예약 | 메시지당 하나. 분류는 기본·정정·인증. 상태: RESERVED, ACCEPTED(확정 사용), RELEASED(확정 미접수로 반환), UNKNOWN(확인 중 비용) |
| 비동기 작업 | `outbox_event` | 업무 저장과 함께 기록한 후속 사건 | 사건 ID 유일. 번호·전화번호를 payload에 넣지 않는다 |
| | `job` | worker가 처리할 일. 발송 슬롯과 운영자 알림도 job이다 | (종류, 중복 키) 유일. 마지막 오류 코드를 남긴다. 상태: READY, RUNNING(lease 보유), SUCCEEDED, RETRY_WAIT(다음 시도 시각까지 대기), DEAD(시도 상한 초과, 운영자 확인 필요), CANCELED. job의 RETRY_WAIT는 메시지의 RETRY_WAIT와 다른 상태다. lease 회수는 [03 4](03-architecture.md#4-비동기-처리) |
| | `consumer_receipt` | 소비자별로 처리한 사건 | (소비자, 사건 ID) 유일 |
| 통계 | `usage_event` | 활동 원천 이벤트 | 번호·연락처 없음 |
| | `usage_aggregate` | 일·월·회차 집계 | 개인 차원 없음. 기간 만료 없음 |
| | `period_membership` | 기간별 고유 이용자 계산용 | (기간, 주체) 유일 |
| | `aggregate_seal` | 범위별 집계 완료 증거 | 날짜가 지났다는 이유만으로 완료로 보지 않는다. 상태: BUILDING, VERIFIED(원천과 대조를 마침), FAILED |
| 탈퇴 | `account_deletion` | 접근 차단, 삭제 기록 저장, 실제 삭제 완료를 구분해 추적 | 상태: BLOCKED, JOURNAL_PENDING, DELETING, COMPLETE, FAILED. 순서는 6.2 |
| 관리자 | `admin_grant` | 관리자 권한 | 역할: OPERATOR, ADMIN |
| | `admin_audit_event` | 관리자 변경 감사 기록 | 대상, 사유, 전후 값, 결과. 비밀값과 개인정보 원문 제외 |

## 3. 꼭 지키는 불변조건

1. 성공한 추천 요청은 정확히 5세트를 가지고, 5세트는 서로 다르다.
2. 한 주체의 같은 회차 추천 이력에 같은 조합이 두 번 들어가지 않는다.
3. 한 주체의 같은 회차 기록에 같은 조합이 두 번 들어가지 않는다.
4. 같은 요청은 한 번만 처리된다.
5. 기본 알림은 회원·회차당 최대 하나다.
6. 같은 메시지를 제공자에게 두 번 접수시키지 않는다. 확실하지 않으면 보내지 않고 대조한다.
7. 기록을 지우거나 합치거나 가져와도 이미 집계한 생성량은 바뀌지 않는다.

## 4. 잠금과 트랜잭션

- 입력 검증, 후보 계산, 외부 호출은 트랜잭션 밖에서 한다. 최종 권한·시간·중복 확인과 상태 변경만 짧은 트랜잭션 안에서 한다.
- 잠금 순서는 항상 같다. job(있으면) → 주체 ID 오름차순 → 회차 → 기록 ID 오름차순 → 알림 주기·intent → 예산. 어떤 경로도 이 순서를 거꾸로 잡지 않는다.
- 같은 회원의 기록 변경, 추천, 귀속, 탈퇴는 주체 행 잠금으로 줄을 세운다. 다른 회원까지 한 잠금에 묶지 않는다.
- 일반 기록 저장은 회차 행을 공유 잠금하고, 일정 변경과 마감 봉인은 배타 잠금한다. 결과 게시 트랜잭션은 회차만 잠그고, 회원별 판정은 outbox 뒤에 따로 한다.
- 격리 수준은 READ COMMITTED를 기준으로 하고, 불변조건은 명시 잠금, 유일성, 버전으로 지킨다.
- 교착이나 잠금 시간 초과가 나면 전체를 rollback하고 최대 2회 다시 시도한다. 다시 시도할 때 회차, 동의, 버전을 다시 확인한다. 외부 발송은 이 재시도에 넣지 않는다.

## 5. 보관 정리

- 정리 worker는 하루 한 번 돈다([07 1](07-operations.md#1-작업-일정)). 주체 → 회차 → 기록 순서로 잠근 뒤, 현재 구매 상태, 최신 게시본 판정, 보호 조건을 다시 확인하고 지운다.
- 보호 조건: 판정이 안 끝났거나, 정정 후보가 격리돼 있거나(`lotto_round.cleanup_held`), 해당 범위의 집계가 끝나지 않았으면 지우지 않는다.
- 정리 단위는 **주체·회차**다. 한 주체의 한 회차에서 보관 기간이 지난 낙첨 기록을 지울 때, 그 회차의 추천 이력과 추천 요청·항목도 함께 지운다. 당첨이나 구매 기록은 남고, 기록 자체가 번호를 가지므로 추천 항목이 없어도 된다. 예를 들어 5세트 중 1세트가 4등이면 그 1개 기록만 남고 나머지 4개와 요청·항목·이력은 지워진다.
- 사용자가 지운 직접 등록 기록은 `deleted_at`으로 먼저 숨긴다. 실제 삭제는 그 회차의 알림 계획이 끝나고(`notification_plan_closed_at`, [05 8.7](05-flows.md#87-알림-계획-완료)), 그 기록을 가리키는 가져오기·멱등 기록이 모두 만료된 뒤에 한다.
- 오래 정리되지 못한 데이터는 운영 문제로 보고 운영자에게 알린다. 몰래 영구 보관하지 않는다.

## 6. 삭제 순서

### 6.1 기록 하나를 지울 때

`lotto_record`를 가리키는 테이블을 먼저 처리한다.

| 순서 | 테이블 | 처리 | 전제 |
|---|---|---|---|
| 1 | `recommendation_item` | 삭제. 주체·회차 정리라면 그 회차의 요청 항목 전부 | — |
| 2 | `history_import_item` | 이 기록이 원본이면 삭제. 대상이면 `target_record_id`를 NULL로 | — |
| 3 | `notification_communicated_fact` | 삭제 | 그 회차의 알림 계획 완료 |
| 4 | `notification_item` | 삭제 | 같음 |
| 5 | `notification_cutoff_item` | 삭제 | 같음 |
| 6 | `winning_evaluation` | 삭제 | 최신 판정 완료 |
| 7 | `record_transition` | 삭제 | 그 회차의 마감 봉인과 대상 고정 완료 |
| 8 | `lotto_record` | 삭제 | 1~7 완료 |

주체·회차 정리라면 이어서 `recommendation_seen`(그 회차) → `recommendation_request`(그 회차)를 지운다.

마감 시점 상태를 이력에서 되살릴 수 있다는 보증은 **그 회차의 알림 계획이 끝날 때까지**다. 기록의 보관 기간은 알림 계획 완료보다 길어서, 정상 정리는 이 보증과 부딪치지 않는다. 탈퇴는 그 회원을 알림에서 먼저 빼므로 기다리지 않고 지운다.

### 6.2 탈퇴할 때

| 순서 | 대상 | 처리 |
|---|---|---|
| 1 | 차단 | `member`와 회원 주체를 WITHDRAWING으로, `auth_version` 증가, 세션 회수, 대기 중 메시지 SUPPRESSED |
| 2 | 삭제 기록 | DB 밖 저장소에 삭제 기록(journal)을 쓰고 저장 확인을 받는다. 실패하면 차단을 유지하고 다시 시도하며 운영자에게 알린다 |
| 3 | 기록 | 회원 주체와 그가 귀속한 비회원 주체의 기록 전부를 6.1 순서로 삭제 |
| 4 | 추천 | 두 주체의 `recommendation_seen` → `recommendation_item` → `recommendation_request` |
| 5 | 귀속 | `history_import_item` → `subject_claim` → 비회원 주체의 `guest_credential` |
| 6 | 알림 | `delivery_attempt`, 확정되거나 해제된 `budget_reservation`, `provider_callback`(메시지 참조 NULL) → `delivery_message` → `notification_intent`(정정부터) → `notification_cycle`. 접수 확인 중(UNKNOWN)인 메시지는 본문·수신자 사본만 즉시 지운다. 그 메시지와 메시지가 가리키는 intent·cycle은 대조가 끝난 뒤 지운다 |
| 7 | 연락처와 동의 | `notification_preference` → `consent_event` → 인증 메시지 → `verification_challenge` → `member_contact` |
| 8 | 명령과 통계 | `command_operation` 삭제, `period_membership` 삭제, `usage_event.subject_id`를 NULL로 |
| 9 | 소셜 계정 | `oauth_account` 삭제. 이제 같은 소셜 계정으로 새로 가입할 수 있다 |
| 10 | 묘비 | `member`와 회원 주체는 WITHDRAWN으로 남긴다. 비회원 주체는 삭제. `account_deletion`을 COMPLETE로 |

회원 행을 지우지 않고 남기는 이유는 여러 테이블이 회원을 가리키기 때문이다 **[미확인]**. 남는 행에는 외부 ID, 상태, 시각만 있고 사람과 이어지는 정보는 없다. 다시 가입하면 새 외부 ID를 가진 새 회원이 된다. 이전 외부 ID는 재사용하지 않는다.

### 6.3 비회원을 정리할 때

- 만료되거나 폐기된 `guest_credential`은 [02 8](02-policies.md#8-보관과-삭제)의 기간이 지나면 삭제한다.
- 기록, 추천 이력, 귀속, 유효한 자격이 하나도 없는 비회원 주체는 `command_operation`, `period_membership`을 지우고 `usage_event.subject_id`를 NULL로 한 뒤 삭제한다.

## 7. 집계

- 원천 이벤트를 소비할 때 `consumer_receipt`와 집계 증가를 같은 트랜잭션에 저장한다. 하나가 실패하면 둘 다 rollback된다.
- 생성 세트 수와 추천 성공 수는 추천 요청·항목이 생긴 시점 기준이다. 구매 현재 조합 수와 최초 등록 수는 다른 지표다.
- 일·월·회차 고유 이용자는 `period_membership`에서 기간마다 따로 센다. 보조 자료는 원천 이벤트의 보관 기간 안에 유지하고, 기간 집계가 봉인되면 익명 숫자만 남긴다.
- 원본을 지우기 전에 해당 범위의 `aggregate_seal`이 VERIFIED인지 확인한다.
- 탈퇴 전에 확정된 생성량을 아직 집계하지 못했으면, 개인 연결을 먼저 끊고 숫자 사건만 남겨 집계한다.
- 재집계는 새 계산 버전을 만들어 검증한 뒤 바꾼다. 원천을 지운 뒤 다시 계산할 수 없는 지표는 그렇다고 표시한다.

## 8. 정정 근거

- 알림 본문과 수신자 사본은 보관 기간이 지나면 지운다. 그 뒤에도 정정이 필요한지 비교하려고 `notification_communicated_fact`에 기록별로 "마지막으로 접수된 안내의 등수와 명시한 금액"만 남긴다.
- 이 사실은 접수(ACCEPTED)나 전달(DELIVERED)이 확인된 안내로만 갱신한다. 대기, 확인 중, 확정 미접수는 보낸 것으로 기록하지 않는다.
- 기록이 지워지거나 탈퇴하면 함께 지운다. 기록이 지워져 비교 범위가 줄어든 것을 정정으로 알리지 않는다.

## 9. 삭제 기록과 복원

- 삭제 기록(journal)은 복원 대상 DB와 함께 되돌아가지 않는 별도 저장소에 둔다. DB를 되돌리면 같이 사라지는 outbox만으로는 저장을 보장할 수 없다.
- journal에는 복원 후 다시 지우는 데 필요한 최소 정보(주체 외부 ID, 요청 시각, 범위)만 둔다. 보관 기간은 [02 8](02-policies.md#8-보관과-삭제)에 있다.
- 백업으로 복원할 때는 외부 발송, 로그인, 쓰기를 멈춘 상태에서 한다. 최신 journal을 다시 적용하고, 세션을 회수하고, 발송 상태를 대조한 뒤에 서비스를 연다. 최신 journal을 확보하지 못하면 열지 않는다. 절차는 [07 4](07-operations.md#4-백업과-복원)에 있다.
