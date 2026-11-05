-- 기존 DB 읽기 전용 조사 (docs/08-delivery.md 4절 1번). MySQL 8.0 이상.
-- 개인정보 원문과 번호 조합은 내보내지 않는다. 결과는 (metric, value) 행이고 값은 모두 개수나 설정값이다.
-- 실행 예: mysql --batch -u <읽기 전용 계정> -p <DB> < scripts/migration/legacy-survey.sql
-- 지표 뜻은 scripts/migration/README.md에 있다.
WITH
fmt AS (
  SELECT id, member_id, `round`, numbers
  FROM lotto_record
  WHERE numbers REGEXP '^[0-9]{1,2}( [0-9]{1,2}){5}$'
),
val AS (
  SELECT fmt.id, jt.n
  FROM fmt,
       JSON_TABLE(CONCAT('["', REPLACE(fmt.numbers, ' ', '","'), '"]'),
                  '$[*]' COLUMNS (n INT PATH '$')) jt
),
agg AS (
  SELECT id,
         COUNT(DISTINCT n) AS distinct_count,
         MIN(n) AS min_n,
         MAX(n) AS max_n,
         GROUP_CONCAT(n ORDER BY n SEPARATOR ' ') AS sorted_plain,
         GROUP_CONCAT(LPAD(n, 2, '0') ORDER BY n SEPARATOR ' ') AS canonical
  FROM val
  GROUP BY id
),
ok AS (
  SELECT fmt.id, fmt.member_id, fmt.`round`, fmt.numbers, agg.sorted_plain, agg.canonical
  FROM fmt JOIN agg ON agg.id = fmt.id
  WHERE agg.distinct_count = 6 AND agg.min_n >= 1 AND agg.max_n <= 45
),
dup AS (
  SELECT member_id, `round`, canonical, COUNT(*) AS c
  FROM ok
  GROUP BY member_id, `round`, canonical
  HAVING COUNT(*) > 1
)
SELECT 'db_version' AS metric, CAST(VERSION() AS CHAR) AS value
UNION ALL SELECT 'global_time_zone', CAST(@@global.time_zone AS CHAR)
UNION ALL SELECT 'session_time_zone', CAST(@@session.time_zone AS CHAR)
UNION ALL SELECT 'system_time_zone', CAST(@@system_time_zone AS CHAR)
UNION ALL SELECT 'record_total', COUNT(*) FROM lotto_record
UNION ALL SELECT 'record_member_null', COUNT(*) FROM lotto_record WHERE member_id IS NULL
UNION ALL SELECT 'record_guest_flag_mismatch', COUNT(*) FROM lotto_record
  WHERE (guest = 1 AND member_id IS NOT NULL) OR (guest = 0 AND member_id IS NULL)
UNION ALL SELECT 'record_numbers_null', COUNT(*) FROM lotto_record WHERE numbers IS NULL
UNION ALL SELECT 'record_numbers_bad_format', COUNT(*) FROM lotto_record
  WHERE numbers IS NOT NULL AND NOT numbers REGEXP '^[0-9]{1,2}( [0-9]{1,2}){5}$'
UNION ALL SELECT 'record_numbers_out_of_range', COUNT(*) FROM agg WHERE min_n < 1 OR max_n > 45
UNION ALL SELECT 'record_numbers_repeated_value', COUNT(*) FROM agg WHERE distinct_count < 6
UNION ALL SELECT 'record_numbers_valid', COUNT(*) FROM ok
UNION ALL SELECT 'record_numbers_unsorted', COUNT(*) FROM ok WHERE numbers <> sorted_plain
UNION ALL SELECT 'record_round_not_positive', COUNT(*) FROM lotto_record WHERE `round` <= 0
UNION ALL SELECT 'record_round_1112', COUNT(*) FROM lotto_record WHERE `round` = 1112
UNION ALL SELECT 'record_recommended_at_null', COUNT(*) FROM lotto_record WHERE recommended_at IS NULL
UNION ALL SELECT 'record_purchased', COUNT(*) FROM lotto_record WHERE purchased = 1
UNION ALL SELECT 'record_manual', COUNT(*) FROM lotto_record WHERE manual = 1
UNION ALL SELECT CONCAT('record_source:', COALESCE(source, 'NULL')), COUNT(*) FROM lotto_record
  GROUP BY source
UNION ALL SELECT 'record_duplicate_groups_member', COUNT(*) FROM dup WHERE member_id IS NOT NULL
UNION ALL SELECT 'record_duplicate_extra_rows_member', COALESCE(SUM(c - 1), 0) FROM dup
  WHERE member_id IS NOT NULL
UNION ALL SELECT 'record_duplicate_groups_no_owner', COUNT(*) FROM dup WHERE member_id IS NULL
UNION ALL SELECT 'member_total', COUNT(*) FROM member
UNION ALL SELECT 'member_email_present', COUNT(*) FROM member WHERE email IS NOT NULL
UNION ALL SELECT 'member_email_verified', COUNT(*) FROM member WHERE email_verified = 1
UNION ALL SELECT CONCAT('member_status:', status), COUNT(*) FROM member GROUP BY status
UNION ALL SELECT 'oauth_total', COUNT(*) FROM member_oauth_account
UNION ALL SELECT CONCAT('oauth_provider:', provider), COUNT(*) FROM member_oauth_account
  GROUP BY provider
UNION ALL SELECT 'oauth_members_with_multiple_accounts', COUNT(*) FROM (
  SELECT member_id FROM member_oauth_account GROUP BY member_id HAVING COUNT(*) > 1) m
UNION ALL SELECT 'oauth_email_conflict', COUNT(*) FROM member_oauth_account a
  JOIN member m ON LOWER(m.email) = LOWER(a.email_on_provider) AND m.id <> a.member_id
UNION ALL SELECT 'winner_total', COUNT(*) FROM lotto_winner_data
UNION ALL SELECT 'winner_max_round', COALESCE(MAX(`round`), 0) FROM lotto_winner_data
UNION ALL SELECT 'winner_round_gaps', COALESCE(MAX(`round`) - MIN(`round`) + 1 - COUNT(*), 0)
  FROM lotto_winner_data;
