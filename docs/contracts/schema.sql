-- Lottery Service 첫 출시 스키마 설계. MySQL 8.4 / InnoDB.
-- 설계 계약이며 제품 migration이 아니다. 기존 DB에 그대로 실행하지 않는다.
-- 시각은 UTC DATETIME(6). FK는 모두 기본값(RESTRICT)이고 삭제는 애플리케이션이 정한 순서로 한다.
-- 테이블 책임, 불변조건, 삭제 순서: ../04-data.md
-- 세션은 Spring Session JDBC 표준 테이블(SPRING_SESSION, SPRING_SESSION_ATTRIBUTES)을 따로 쓴다.

-- 계정 상태를 먼저 차단하고 개인 자료 삭제는 추적 가능한 작업으로 진행한다.
-- 탈퇴가 끝나도 행은 WITHDRAWN 묘비로 남는다. 이름·이메일 같은 개인정보 컬럼은 두지 않는다.
-- 마지막 로그인 시각은 로그인 성공 때 갱신하고 탈퇴 차단 때 비운다(D-038).
CREATE TABLE member (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  state VARCHAR(20) NOT NULL,
  auth_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  last_login_at DATETIME(6) NULL,
  withdrawn_at DATETIME(6) NULL,
  KEY ix_member_last_login (last_login_at),
  CONSTRAINT ck_member_0 CHECK (state IN ('ACTIVE','WITHDRAWING','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 이메일 대신 제공자 식별자로 계정을 구분한다.
CREATE TABLE oauth_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  provider VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_user_id VARBINARY(255) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_oauth_identity (provider, provider_user_id),
  CONSTRAINT fk_oauth_account_0 FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 회원과 비회원에 동일한 기록 소유권 및 동시 변경 잠금 기준을 둔다.
CREATE TABLE actor_subject (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  member_id BIGINT NULL UNIQUE,
  kind VARCHAR(8) NOT NULL,
  state VARCHAR(20) NOT NULL,
  claim_version BIGINT NOT NULL DEFAULT 0,
  generation_fence BIGINT NOT NULL DEFAULT 0,
  generation_lease_until DATETIME(6) NULL,
  next_generation_at DATETIME(6) NULL,
  CONSTRAINT fk_actor_subject_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT ck_actor_subject_0 CHECK (kind IN ('MEMBER','GUEST')),
  CONSTRAINT ck_actor_subject_1 CHECK (state IN ('ACTIVE','CLAIMED','WITHDRAWING','WITHDRAWN')),
  CONSTRAINT ck_actor_subject_2 CHECK ((kind='MEMBER' AND member_id IS NOT NULL) OR (kind='GUEST' AND member_id IS NULL)),
  CONSTRAINT ck_actor_subject_3 CHECK ((kind='MEMBER' AND state IN ('ACTIVE','WITHDRAWING','WITHDRAWN')) OR (kind='GUEST' AND state IN ('ACTIVE','CLAIMED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 쿠키 원문 없이 유효기간과 회수 여부를 검증한다. 만료·폐기 7일 뒤 삭제한다.
CREATE TABLE guest_credential (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  subject_id BIGINT NOT NULL,
  token_hash BINARY(32) NOT NULL UNIQUE,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  KEY ix_guest_expiry (expires_at),
  CONSTRAINT fk_guest_credential_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 한 비회원 자료는 한 회원만 가져올 수 있다. imported_at이 NULL이면 귀속만 된 상태다.
CREATE TABLE subject_claim (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  guest_subject_id BIGINT NOT NULL UNIQUE,
  member_subject_id BIGINT NOT NULL,
  claimed_at DATETIME(6) NOT NULL,
  imported_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_subject_claim_0 FOREIGN KEY (guest_subject_id) REFERENCES actor_subject (id),
  CONSTRAINT fk_subject_claim_1 FOREIGN KEY (member_subject_id) REFERENCES actor_subject (id),
  CONSTRAINT ck_subject_claim_0 CHECK (guest_subject_id <> member_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 판매 일정과 결과 수집을 독립 관리한다.
-- publication_version은 published_result_id가 바뀔 때마다 1씩 늘어난다. 승인·직접 등록 요청은 화면이 본 값을 함께 보내고, 다르면 거부한다.
-- draw_result.revision은 결과 행의 회차 안 순번이라 이 값과 다르다.
-- notification_plan_closed_at의 설정 조건은 ../05-flows.md 8.7.
CREATE TABLE lotto_round (
  round_no INT NOT NULL PRIMARY KEY,
  sales_open_at DATETIME(6) NOT NULL,
  sales_close_at DATETIME(6) NOT NULL,
  draw_at DATETIME(6) NOT NULL,
  schedule_version BIGINT NOT NULL,
  publication_version BIGINT NOT NULL DEFAULT 0,
  published_result_id BIGINT NULL,
  cutoff_state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  cutoff_sealed_at DATETIME(6) NULL,
  notification_plan_closed_at DATETIME(6) NULL,
  cleanup_held BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_lotto_round_0 CHECK (round_no > 0),
  CONSTRAINT ck_lotto_round_1 CHECK (sales_open_at < sales_close_at),
  CONSTRAINT ck_lotto_round_2 CHECK (cutoff_state IN ('OPEN','SEALED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 완료 응답을 받지 못한 요청을 같은 업무로 확인하고 재실행을 막는다.
CREATE TABLE command_operation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  subject_id BIGINT NOT NULL,
  operation VARCHAR(40) NOT NULL,
  token_id BINARY(16) NOT NULL,
  client_key BINARY(16) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  state VARCHAR(20) NOT NULL,
  token_expires_at DATETIME(6) NOT NULL,
  marker_expires_at DATETIME(6) NOT NULL,
  resource_public_id BINARY(16) NULL,
  completion_code VARCHAR(40) NULL,
  completed_at DATETIME(6) NULL,
  lease_token BINARY(16) NULL,
  lease_until DATETIME(6) NULL,
  UNIQUE KEY uq_command_key (subject_id, operation, token_id, client_key),
  KEY ix_command_expiry (marker_expires_at),
  CONSTRAINT fk_command_operation_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id),
  CONSTRAINT ck_command_operation_0 CHECK (state IN ('PROCESSING','SUCCEEDED','RETRYABLE','FAILED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 동일 사용자·회차·조합은 한 행이다. 추천 이력과 구매 상태를 따로 둔다.
CREATE TABLE lotto_record (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  owner_subject_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  numbers_key CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recommended_at DATETIME(6) NULL,
  purchased BOOLEAN NOT NULL DEFAULT FALSE,
  purchased_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  expires_at DATETIME(6) NULL,
  correction_protected_until DATETIME(6) NULL,
  UNIQUE KEY uq_record_combination (owner_subject_id, round_no, numbers_key),
  UNIQUE KEY uq_record_round (id, round_no),
  KEY ix_record_history (owner_subject_id, round_no, deleted_at, id),
  KEY ix_record_round (round_no, id),
  KEY ix_record_expiry (expires_at, id),
  CONSTRAINT fk_lotto_record_0 FOREIGN KEY (owner_subject_id) REFERENCES actor_subject (id),
  CONSTRAINT fk_lotto_record_1 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no),
  CONSTRAINT ck_lotto_record_0 CHECK (purchased IN (0,1)),
  CONSTRAINT ck_lotto_record_1 CHECK ((purchased=1 AND purchased_at IS NOT NULL) OR (purchased=0 AND purchased_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 최초 생성과 모든 실제 변경을 남겨 마감 당시 상태를 복원한다.
-- 변경 후 상태만 저장한다. 변경 전 상태는 같은 기록의 직전 버전 행에서 읽는다.
CREATE TABLE record_transition (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  record_id BIGINT NOT NULL,
  record_version BIGINT NOT NULL,
  owner_subject_id BIGINT NOT NULL,
  action VARCHAR(24) NOT NULL,
  accepted_at DATETIME(6) NOT NULL,
  after_recommended BOOLEAN NOT NULL,
  after_purchased BOOLEAN NOT NULL,
  after_deleted BOOLEAN NOT NULL,
  UNIQUE KEY uq_transition_version (record_id, record_version),
  KEY ix_transition_cutoff (record_id, accepted_at, id),
  CONSTRAINT fk_record_transition_0 FOREIGN KEY (record_id) REFERENCES lotto_record (id),
  CONSTRAINT fk_record_transition_1 FOREIGN KEY (owner_subject_id) REFERENCES actor_subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 성공한 발행 사건이다. 정리 뒤에도 발행 수는 집계에서 유지한다.
-- 기본 추천은 생성 필터 버전을, 조건 추천은 적용한 조건을 남긴다.
CREATE TABLE recommendation_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  subject_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  key_digest BINARY(32) NOT NULL,
  request_hash BINARY(32) NOT NULL,
  accepted_at DATETIME(6) NOT NULL,
  policy_version BIGINT NOT NULL,
  generation_mode VARCHAR(16) NOT NULL,
  filter_version VARCHAR(32) NULL,
  condition_json JSON NULL,
  response_expires_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_recommendation_key (subject_id, round_no, key_digest),
  KEY ix_recommendation_owner (subject_id, round_no, id),
  CONSTRAINT fk_recommendation_request_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id),
  CONSTRAINT fk_recommendation_request_1 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no),
  CONSTRAINT ck_recommendation_request_0 CHECK ((generation_mode='BASIC' AND filter_version IS NOT NULL AND condition_json IS NULL) OR (generation_mode='CONDITION' AND filter_version IS NULL AND condition_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 최초 다섯 항목을 원자 저장한다. 원본 정리 때 개별 항목도 삭제한다.
CREATE TABLE recommendation_item (
  request_id BIGINT NOT NULL,
  ordinal TINYINT NOT NULL,
  record_id BIGINT NULL,
  numbers_key CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (request_id, ordinal),
  UNIQUE KEY uq_item_numbers (request_id, numbers_key),
  CONSTRAINT fk_recommendation_item_0 FOREIGN KEY (request_id) REFERENCES recommendation_request (id),
  CONSTRAINT fk_recommendation_item_1 FOREIGN KEY (record_id) REFERENCES lotto_record (id),
  CONSTRAINT ck_recommendation_item_0 CHECK (ordinal BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 화면 기록을 취소해도 같은 회차의 이전 추천은 배제한다.
CREATE TABLE recommendation_seen (
  subject_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  numbers_key CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  first_request_id BIGINT NOT NULL,
  PRIMARY KEY (subject_id, round_no, numbers_key),
  CONSTRAINT fk_recommendation_seen_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id),
  CONSTRAINT fk_recommendation_seen_1 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no),
  CONSTRAINT fk_recommendation_seen_2 FOREIGN KEY (first_request_id) REFERENCES recommendation_request (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 가져오기 재시도가 사용자가 삭제한 기록을 복원하지 못하게 한다.
CREATE TABLE history_import_item (
  claim_id BIGINT NOT NULL,
  source_record_id BIGINT NOT NULL,
  target_record_id BIGINT NULL,
  outcome VARCHAR(32) NOT NULL,
  PRIMARY KEY (claim_id, source_record_id),
  CONSTRAINT fk_history_import_item_0 FOREIGN KEY (claim_id) REFERENCES subject_claim (id),
  CONSTRAINT fk_history_import_item_1 FOREIGN KEY (source_record_id) REFERENCES lotto_record (id),
  CONSTRAINT fk_history_import_item_2 FOREIGN KEY (target_record_id) REFERENCES lotto_record (id),
  CONSTRAINT ck_history_import_item_0 CHECK (outcome IN ('CREATED','MERGED','SKIPPED_TARGET_DELETED','SKIPPED_SOURCE_EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 공식 번호의 의미가 바뀌면 기존 행을 덮지 않고 새 버전을 만든다.
-- source=MANUAL은 결과 API가 결과를 주지 않아 ADMIN이 직접 등록한 결과다(D-034). 나중에 API 결과가 같으면 api_confirmed_at을 채운다.
CREATE TABLE draw_result (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  round_no INT NOT NULL,
  revision BIGINT NOT NULL,
  numbers_key CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  bonus_number TINYINT NOT NULL,
  semantic_hash BINARY(32) NOT NULL,
  source_hash BINARY(32) NOT NULL,
  state VARCHAR(20) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'API',
  registered_by_member_id BIGINT NULL,
  api_confirmed_at DATETIME(6) NULL,
  fetched_at DATETIME(6) NOT NULL,
  published_at DATETIME(6) NULL,
  UNIQUE KEY uq_result_revision (round_no, revision),
  UNIQUE KEY uq_result_round (id, round_no),
  CONSTRAINT fk_draw_result_0 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no),
  CONSTRAINT ck_draw_result_0 CHECK (state IN ('VERIFIED','QUARANTINED','SUPERSEDED')),
  CONSTRAINT ck_draw_result_1 CHECK (bonus_number BETWEEN 1 AND 45),
  CONSTRAINT fk_draw_result_1 FOREIGN KEY (registered_by_member_id) REFERENCES member (id),
  CONSTRAINT ck_draw_result_2 CHECK (source IN ('API','MANUAL')),
  CONSTRAINT ck_draw_result_3 CHECK ((source='API' AND registered_by_member_id IS NULL) OR (source='MANUAL' AND registered_by_member_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE lotto_round ADD CONSTRAINT fk_round_published FOREIGN KEY (published_result_id, round_no) REFERENCES draw_result (id, round_no);

-- 미확보 금액은 NULL로 두어 0원과 구별한다.
CREATE TABLE draw_prize (
  result_id BIGINT NOT NULL,
  rank_no TINYINT NOT NULL,
  prize_per_game BIGINT NULL,
  winner_count BIGINT NULL,
  PRIMARY KEY (result_id, rank_no),
  CONSTRAINT fk_draw_prize_0 FOREIGN KEY (result_id) REFERENCES draw_result (id),
  CONSTRAINT ck_draw_prize_0 CHECK (rank_no BETWEEN 1 AND 5),
  CONSTRAINT ck_draw_prize_1 CHECK (prize_per_game IS NULL OR prize_per_game >= 0),
  CONSTRAINT ck_draw_prize_2 CHECK (winner_count IS NULL OR winner_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 다른 회차 결과와 연결하지 않고 조회 때 최신 기록/게시 버전을 확인한다.
CREATE TABLE winning_evaluation (
  record_id BIGINT NOT NULL,
  result_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  record_version BIGINT NOT NULL,
  rank_no TINYINT NULL,
  matched_count TINYINT NOT NULL,
  bonus_matched BOOLEAN NOT NULL,
  evaluated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (record_id, result_id, record_version),
  CONSTRAINT fk_winning_evaluation_0 FOREIGN KEY (record_id, round_no) REFERENCES lotto_record (id, round_no),
  CONSTRAINT fk_winning_evaluation_1 FOREIGN KEY (result_id, round_no) REFERENCES draw_result (id, round_no),
  CONSTRAINT ck_winning_evaluation_0 CHECK (rank_no IS NULL OR rank_no BETWEEN 1 AND 5),
  CONSTRAINT ck_winning_evaluation_1 CHECK (matched_count BETWEEN 0 AND 6),
  CONSTRAINT ck_winning_evaluation_2 CHECK (bonus_matched IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 작업 위치를 저장하고 오래된 결과 기준 작업을 중단한다.
CREATE TABLE evaluation_run (
  result_id BIGINT NOT NULL PRIMARY KEY,
  watermark BIGINT NOT NULL,
  cursor_id BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(20) NOT NULL,
  CONSTRAINT fk_evaluation_run_0 FOREIGN KEY (result_id) REFERENCES draw_result (id),
  CONSTRAINT ck_evaluation_run_0 CHECK (state IN ('READY','RUNNING','SUCCEEDED','SUPERSEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 연락처는 암호화하고 번호 변경마다 버전을 올린다.
CREATE TABLE member_contact (
  member_id BIGINT NOT NULL PRIMARY KEY,
  address_ciphertext VARBINARY(1024) NULL,
  key_version INT NOT NULL,
  verified_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_member_contact_0 FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- OTP 추측/동시 소비 방지는 이 행 잠금과 시도 수로 처리한다.
CREATE TABLE verification_challenge (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  member_id BIGINT NOT NULL,
  target_hmac BINARY(32) NOT NULL,
  target_ciphertext VARBINARY(1024) NULL,
  code_hmac BINARY(32) NULL,
  key_version INT NOT NULL,
  expected_contact_version BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  attempts TINYINT NOT NULL DEFAULT 0,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  KEY ix_challenge_expiry (expires_at),
  CONSTRAINT fk_verification_challenge_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT ck_verification_challenge_0 CHECK (state IN ('ACTIVE','CONSUMED','EXPIRED','REVOKED','LOCKED')),
  CONSTRAINT ck_verification_challenge_1 CHECK (attempts BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 계정/정규화 번호 한도를 각각 원자 검사하며 번호 원문을 남기지 않는다.
CREATE TABLE verification_rate_bucket (
  scope_type VARCHAR(8) NOT NULL,
  scope_hmac BINARY(32) NOT NULL,
  business_date DATE NOT NULL,
  send_count SMALLINT NOT NULL DEFAULT 0,
  next_send_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (scope_type, scope_hmac, business_date),
  CONSTRAINT ck_verification_rate_bucket_0 CHECK (scope_type IN ('MEMBER','PHONE')),
  CONSTRAINT ck_verification_rate_bucket_1 CHECK (send_count BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 철회 후 재동의를 이전 동의와 구별한다.
CREATE TABLE consent_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  granted BOOLEAN NOT NULL,
  policy_version VARCHAR(40) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_consent_member (id, member_id),
  CONSTRAINT fk_consent_event_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT ck_consent_event_0 CHECK (granted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 현재 연락처와 동의에 맞는 발송만 허용한다.
CREATE TABLE notification_preference (
  member_id BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL,
  contact_version BIGINT NOT NULL,
  consent_event_id BIGINT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_notification_preference_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_notification_preference_1 FOREIGN KEY (consent_event_id, member_id) REFERENCES consent_event (id, member_id),
  CONSTRAINT ck_notification_preference_0 CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 0건 완료도 저장하며 회차당 초기 계획을 다시 만들지 않는다.
CREATE TABLE notification_cycle (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  member_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'ALIMTALK',
  cutoff_version BIGINT NOT NULL,
  evaluation_result_id BIGINT NULL,
  state VARCHAR(24) NOT NULL,
  next_sequence BIGINT NOT NULL DEFAULT 1,
  UNIQUE KEY uq_cycle (member_id, round_no, channel),
  UNIQUE KEY uq_cycle_round (id, round_no),
  CONSTRAINT fk_notification_cycle_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_notification_cycle_1 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no),
  CONSTRAINT fk_notification_cycle_2 FOREIGN KEY (evaluation_result_id, round_no) REFERENCES draw_result (id, round_no),
  CONSTRAINT ck_notification_cycle_0 CHECK (state IN ('WAITING_EVALUATION','PLANNED','NO_TARGET','CLOSED')),
  CONSTRAINT ck_notification_cycle_1 CHECK (channel='ALIMTALK')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 판매 마감 전에 표시 기록으로 귀속된 번호만 계획에 참여한다.
CREATE TABLE notification_cutoff_item (
  cycle_id BIGINT NOT NULL,
  record_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  record_version BIGINT NOT NULL,
  was_recommended BOOLEAN NOT NULL,
  was_purchased BOOLEAN NOT NULL,
  PRIMARY KEY (cycle_id, record_id),
  CONSTRAINT fk_notification_cutoff_item_0 FOREIGN KEY (cycle_id, round_no) REFERENCES notification_cycle (id, round_no),
  CONSTRAINT fk_notification_cutoff_item_1 FOREIGN KEY (record_id, round_no) REFERENCES lotto_record (id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 초기 1건과 기발송 내용의 정정 사건을 구분한다.
CREATE TABLE notification_intent (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cycle_id BIGINT NOT NULL,
  round_no INT NOT NULL,
  kind VARCHAR(16) NOT NULL,
  sequence_no BIGINT NOT NULL,
  previous_intent_id BIGINT NULL,
  result_id BIGINT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  UNIQUE KEY uq_intent_sequence (cycle_id, kind, sequence_no),
  UNIQUE KEY uq_intent_correction (cycle_id, previous_intent_id, result_id),
  UNIQUE KEY uq_intent_cycle (id, cycle_id),
  CONSTRAINT fk_notification_intent_0 FOREIGN KEY (cycle_id, round_no) REFERENCES notification_cycle (id, round_no),
  CONSTRAINT fk_notification_intent_1 FOREIGN KEY (result_id, round_no) REFERENCES draw_result (id, round_no),
  CONSTRAINT fk_notification_intent_2 FOREIGN KEY (previous_intent_id, cycle_id) REFERENCES notification_intent (id, cycle_id),
  CONSTRAINT ck_notification_intent_0 CHECK ((kind='INITIAL' AND sequence_no=0 AND previous_intent_id IS NULL) OR (kind='CORRECTION' AND sequence_no>0 AND previous_intent_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 본문을 지워도 어떤 보존 기록에 대한 안내인지 비교한다.
CREATE TABLE notification_item (
  intent_id BIGINT NOT NULL,
  record_id BIGINT NOT NULL,
  rank_no TINYINT NULL,
  explicit_prize BIGINT NULL,
  PRIMARY KEY (intent_id, record_id),
  CONSTRAINT fk_notification_item_0 FOREIGN KEY (intent_id) REFERENCES notification_intent (id),
  CONSTRAINT fk_notification_item_1 FOREIGN KEY (record_id) REFERENCES lotto_record (id),
  CONSTRAINT ck_notification_item_0 CHECK (rank_no IS NULL OR rank_no BETWEEN 1 AND 5),
  CONSTRAINT ck_notification_item_1 CHECK (explicit_prize IS NULL OR explicit_prize>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 본문/수신자와 별개로 마지막 안내 사실만 개인 기록 수명 내 보존한다.
CREATE TABLE notification_communicated_fact (
  cycle_id BIGINT NOT NULL,
  record_id BIGINT NOT NULL,
  last_intent_id BIGINT NOT NULL,
  result_id BIGINT NOT NULL,
  rank_no TINYINT NULL,
  explicit_prize BIGINT NULL,
  communicated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (cycle_id, record_id),
  CONSTRAINT fk_notification_communicated_fact_0 FOREIGN KEY (cycle_id) REFERENCES notification_cycle (id),
  CONSTRAINT fk_notification_communicated_fact_1 FOREIGN KEY (record_id) REFERENCES lotto_record (id),
  CONSTRAINT fk_notification_communicated_fact_2 FOREIGN KEY (last_intent_id, cycle_id) REFERENCES notification_intent (id, cycle_id),
  CONSTRAINT fk_notification_communicated_fact_3 FOREIGN KEY (result_id) REFERENCES draw_result (id),
  CONSTRAINT ck_notification_communicated_fact_0 CHECK (rank_no IS NULL OR rank_no BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 결과와 번호 인증이 발송 기반만 공유한다. 두 업무의 동의/기한은 다르다.
CREATE TABLE delivery_message (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  purpose VARCHAR(16) NOT NULL,
  intent_id BIGINT NULL UNIQUE,
  challenge_id BIGINT NULL UNIQUE,
  state VARCHAR(20) NOT NULL,
  provider_request_key BINARY(16) NOT NULL UNIQUE,
  recipient_version BIGINT NULL,
  preference_version BIGINT NULL,
  consent_event_id BIGINT NULL,
  payload_ciphertext MEDIUMBLOB NULL,
  recipient_ciphertext VARBINARY(1024) NULL,
  payload_hash BINARY(32) NULL,
  key_version INT NULL,
  template_version VARCHAR(80) NULL,
  communicated_summary JSON NULL,
  expires_at DATETIME(6) NOT NULL,
  next_attempt_at DATETIME(6) NULL,
  finalized_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  KEY ix_delivery_due (state, next_attempt_at, id),
  CONSTRAINT fk_delivery_message_0 FOREIGN KEY (intent_id) REFERENCES notification_intent (id),
  CONSTRAINT fk_delivery_message_1 FOREIGN KEY (challenge_id) REFERENCES verification_challenge (id),
  CONSTRAINT fk_delivery_message_2 FOREIGN KEY (consent_event_id) REFERENCES consent_event (id),
  CONSTRAINT ck_delivery_message_0 CHECK ((purpose='RESULT' AND intent_id IS NOT NULL AND challenge_id IS NULL) OR (purpose='VERIFICATION' AND intent_id IS NULL AND challenge_id IS NOT NULL)),
  CONSTRAINT ck_delivery_message_1 CHECK (state IN ('PENDING','IN_FLIGHT','ACCEPTED','DELIVERED','RETRY_WAIT','UNKNOWN','HALTED','FAILED','SUPPRESSED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 외부 요청 응답과 실제 전달 상태를 나누어 대조한다.
CREATE TABLE delivery_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  provider_message_id VARCHAR(128) NULL,
  outcome VARCHAR(24) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  error_code VARCHAR(80) NULL,
  UNIQUE KEY uq_attempt (message_id, attempt_no),
  KEY ix_attempt_provider (provider_message_id),
  CONSTRAINT fk_delivery_attempt_0 FOREIGN KEY (message_id) REFERENCES delivery_message (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 중복/역순 콜백을 접수하고 인증된 제공자 조회와 대조한다.
CREATE TABLE provider_callback (
  provider VARCHAR(16) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  message_id BIGINT NULL,
  provider_message_id VARCHAR(128) NULL,
  normalized_status VARCHAR(24) NOT NULL,
  state VARCHAR(16) NOT NULL,
  received_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6) NULL,
  PRIMARY KEY (provider, event_id),
  CONSTRAINT fk_provider_callback_0 FOREIGN KEY (message_id) REFERENCES delivery_message (id),
  CONSTRAINT ck_provider_callback_0 CHECK (state IN ('UNMATCHED','PENDING','PROCESSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 금액은 0.1원 정수 단위. 미확정 요청의 예약도 월 상한에 포함한다.
CREATE TABLE message_budget (
  provider VARCHAR(16) NOT NULL,
  business_month DATE NOT NULL,
  limit_units BIGINT NOT NULL,
  reserved_units BIGINT NOT NULL DEFAULT 0,
  spent_units BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (provider, business_month),
  CONSTRAINT ck_message_budget_0 CHECK (limit_units>=0),
  CONSTRAINT ck_message_budget_1 CHECK (reserved_units>=0),
  CONSTRAINT ck_message_budget_2 CHECK (spent_units>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 메시지별 예약을 한 번만 만들고 예산 분류를 보존한다.
CREATE TABLE budget_reservation (
  message_id BIGINT NOT NULL PRIMARY KEY,
  provider VARCHAR(16) NOT NULL,
  business_month DATE NOT NULL,
  category VARCHAR(16) NOT NULL,
  amount_units BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_budget_reservation_0 FOREIGN KEY (message_id) REFERENCES delivery_message (id),
  CONSTRAINT fk_budget_reservation_1 FOREIGN KEY (provider, business_month) REFERENCES message_budget (provider, business_month),
  CONSTRAINT ck_budget_reservation_0 CHECK (category IN ('INITIAL','CORRECTION','VERIFICATION')),
  CONSTRAINT ck_budget_reservation_1 CHECK (state IN ('RESERVED','ACCEPTED','RELEASED','UNKNOWN')),
  CONSTRAINT ck_budget_reservation_2 CHECK (amount_units>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 업무 저장과 후속 작업 등록을 한 트랜잭션에 묶는다.
CREATE TABLE outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BINARY(16) NOT NULL UNIQUE,
  event_type VARCHAR(64) NOT NULL,
  aggregate_public_id BINARY(16) NOT NULL,
  aggregate_version BIGINT NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  dispatched_at DATETIME(6) NULL,
  UNIQUE KEY uq_outbox_version (event_type, aggregate_public_id, aggregate_version),
  KEY ix_outbox_pending (dispatched_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- DB 안의 작업 권한은 매 저장 때 lease를 검증한다. 외부 발송은 별도 대조한다.
-- 발송 슬롯(중복 키: 회차+슬롯 시각)과 운영자 알림(중복 키: 사건 종류+대상+슬롯)도 job으로 처리한다.
CREATE TABLE job (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  job_type VARCHAR(64) NOT NULL,
  dedupe_key VARBINARY(160) NOT NULL,
  state VARCHAR(16) NOT NULL,
  payload JSON NOT NULL,
  cursor_json JSON NULL,
  available_at DATETIME(6) NOT NULL,
  lease_token BINARY(16) NULL,
  lease_until DATETIME(6) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  last_error_code VARCHAR(80) NULL,
  finished_at DATETIME(6) NULL,
  UNIQUE KEY uq_job_key (job_type, dedupe_key),
  KEY ix_job_due (state, available_at, id),
  KEY ix_job_lease (state, lease_until),
  CONSTRAINT ck_job_0 CHECK (state IN ('READY','RUNNING','SUCCEEDED','RETRY_WAIT','DEAD','CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 같은 사건의 내부 업무 효과가 중복 반영되지 않도록 한다.
CREATE TABLE consumer_receipt (
  consumer VARCHAR(64) NOT NULL,
  event_id BINARY(16) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (consumer, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 숫자/전화번호를 복제하지 않고 발행량 사건을 보관한다.
CREATE TABLE usage_event (
  event_id BINARY(16) NOT NULL PRIMARY KEY,
  subject_id BIGINT NULL,
  round_no INT NULL,
  business_date DATE NOT NULL,
  metric VARCHAR(64) NOT NULL,
  delta BIGINT NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_usage_event_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id),
  CONSTRAINT fk_usage_event_1 FOREIGN KEY (round_no) REFERENCES lotto_round (round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 개인 식별 차원 없이 일·월·회차 집계 결과를 장기 보존한다.
CREATE TABLE usage_aggregate (
  period_type VARCHAR(8) NOT NULL,
  period_key VARCHAR(24) NOT NULL,
  metric VARCHAR(64) NOT NULL,
  dimension_key VARCHAR(80) NOT NULL,
  calculation_version BIGINT NOT NULL,
  value_count BIGINT NOT NULL,
  as_of DATETIME(6) NOT NULL,
  PRIMARY KEY (period_type, period_key, metric, dimension_key, calculation_version),
  CONSTRAINT ck_usage_aggregate_0 CHECK (period_type IN ('DAY','MONTH','ROUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 일별 이용자 수를 더하지 않고 각 기간의 중복 사용자를 제거한다.
CREATE TABLE period_membership (
  period_type VARCHAR(8) NOT NULL,
  period_key VARCHAR(24) NOT NULL,
  subject_id BIGINT NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (period_type, period_key, subject_id),
  CONSTRAINT fk_period_membership_0 FOREIGN KEY (subject_id) REFERENCES actor_subject (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 원본을 지우기 전에 집계 반영/대조가 끝났음을 확인한다.
CREATE TABLE aggregate_seal (
  scope_type VARCHAR(24) NOT NULL,
  scope_key VARCHAR(80) NOT NULL,
  calculation_version BIGINT NOT NULL,
  source_watermark BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  sealed_at DATETIME(6) NULL,
  PRIMARY KEY (scope_type, scope_key, calculation_version),
  CONSTRAINT ck_aggregate_seal_0 CHECK (state IN ('BUILDING','VERIFIED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 접근 차단과 실제 삭제 완료 및 외부 journal 저장 확인을 구분한다.
CREATE TABLE account_deletion (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL UNIQUE,
  member_id BIGINT NULL,
  subject_public_id BINARY(16) NOT NULL,
  state VARCHAR(24) NOT NULL,
  journal_receipt VARCHAR(160) NULL,
  requested_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  CONSTRAINT fk_account_deletion_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT ck_account_deletion_0 CHECK (state IN ('BLOCKED','JOURNAL_PENDING','DELETING','COMPLETE','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 관리자 권한. 역할마다 할 수 있는 일은 ../06-api.md 4절.
CREATE TABLE admin_grant (
  member_id BIGINT NOT NULL PRIMARY KEY,
  role VARCHAR(16) NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  granted_by_member_id BIGINT NULL,
  revoked_at DATETIME(6) NULL,
  CONSTRAINT fk_admin_grant_0 FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_admin_grant_1 FOREIGN KEY (granted_by_member_id) REFERENCES member (id),
  CONSTRAINT ck_admin_grant_0 CHECK (role IN ('OPERATOR','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 관리자 변경 감사. 1년 보관. 비밀값과 개인정보 원문은 넣지 않는다.
CREATE TABLE admin_audit_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_member_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_key VARCHAR(80) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  outcome VARCHAR(16) NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  KEY ix_audit_target (target_type, target_key, occurred_at),
  KEY ix_audit_expiry (expires_at),
  CONSTRAINT fk_admin_audit_event_0 FOREIGN KEY (actor_member_id) REFERENCES member (id),
  CONSTRAINT ck_admin_audit_event_0 CHECK (outcome IN ('SUCCEEDED','REJECTED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
