# 데이터 이행 스크립트

`docs/08-delivery.md` 4절의 데이터 이행에 쓰는 스크립트다.

## legacy-survey.sql

기존 DB를 읽기 전용으로 조사한다(4절 1번). 결과는 `metric`, `value` 두 열이고 모두 개수나 설정값이다. 이메일, 이름, 번호 조합 같은 원문은 내보내지 않는다.

```bash
mysql --batch -u <읽기 전용 계정> -p <DB 이름> < scripts/migration/legacy-survey.sql
mysqldump --no-data -u <읽기 전용 계정> -p <DB 이름> > ddl.sql   # DDL은 따로 뜬다
```

| metric | 뜻 |
|---|---|
| `db_version`, `*_time_zone` | MySQL 버전과 시간대 설정. 기존 `recommended_at`이 어느 시간대로 저장됐는지 판단하는 근거 |
| `record_total` | `lotto_record` 행 수 |
| `record_member_null` | 회원 ID가 없는 행(비회원 기록) |
| `record_guest_flag_mismatch` | `guest` 표시와 회원 ID 유무가 어긋나는 행 |
| `record_numbers_null`, `record_numbers_bad_format` | 번호가 없거나 "숫자 6개를 공백 하나로" 형식이 아닌 행 |
| `record_numbers_out_of_range`, `record_numbers_repeated_value` | 형식은 맞지만 1~45 밖의 번호가 있거나 같은 번호가 반복된 행 |
| `record_numbers_valid` | 정규 형식으로 바꿀 수 있는 행 |
| `record_numbers_unsorted` | 바꿀 수 있지만 오름차순으로 저장되지 않은 행 |
| `record_round_not_positive`, `record_round_1112` | 회차가 0 이하인 행, 고정값 1112인 행(실제 회차를 알 수 없음, 4절 3번) |
| `record_recommended_at_null` | 추천 시각이 없는 행 |
| `record_purchased`, `record_manual`, `record_source:*` | 구매 표시, 수동 표시, 출처별 행 수 |
| `record_duplicate_groups_member`, `record_duplicate_extra_rows_member` | 같은 회원·회차·조합(순서 무시)이 여러 행인 묶음 수와 합치면 줄어드는 행 수 |
| `record_duplicate_groups_no_owner` | 회원 ID 없이 같은 회차·조합이 여러 행인 묶음 수. 주인을 증명할 수 없어 합치지 않는다 |
| `member_*` | 회원 수, 이메일이 있는 회원, 이메일 검증 표시, 상태별 수 |
| `oauth_*` | 소셜 계정 수, 제공자별 수, 계정이 둘 이상인 회원, 다른 회원의 이메일과 같은 제공자 이메일(이메일 연결 충돌) |
| `winner_*` | 과거 결과 행 수, 최대 회차, 빠진 회차 수 |

`src/test/resources/legacy/`의 표본 DB로 `LegacySurveyMySqlTest`가 각 지표를 확인한다(`./gradlew mysqlTest`).
