-- 기존 DB 조사 시험용 표본. 실제 자료가 아니다. 문제 유형마다 행을 하나 이상 둔다.
INSERT INTO member (id, email, email_verified, status) VALUES
  (1, 'a@example.com', 0, 'ACTIVE'),
  (2, 'b@example.com', 0, 'ACTIVE'),
  (3, NULL, 0, 'DELETED');

-- 회원 1은 두 제공자를 연결했다. 회원 3의 제공자 이메일은 회원 2의 이메일과 같다(충돌 1건).
INSERT INTO member_oauth_account (member_id, provider, provider_user_id, email_on_provider) VALUES
  (1, 'KAKAO', 'k1', 'a@example.com'),
  (1, 'GOOGLE', 'g1', 'A@EXAMPLE.COM'),
  (3, 'NAVER', 'n1', 'b@example.com');

INSERT INTO lotto_record (member_id, guest, manual, numbers, purchased, recommended_at, `round`, source) VALUES
  (1, 0, 0, '1 2 3 4 5 6', 0, '2026-01-01 00:00:00', 1112, 'AUTO'),
  (1, 0, 1, '6 5 4 3 2 1', 1, '2026-01-01 00:00:00', 1112, 'manual'),
  (1, 0, 0, '1 2 3 4 5 6', 0, '2026-01-01 00:00:00', 1113, 'AUTO'),
  (2, 0, 0, '01 02 03 04 05 46', 0, '2026-01-01 00:00:00', 1112, 'AUTO'),
  (2, 0, 0, '1 1 3 4 5 6', 0, NULL, 1112, 'AUTO'),
  (2, 0, 0, '1,2,3,4,5,6', 0, '2026-01-01 00:00:00', 1112, 'AUTO'),
  (NULL, 1, 0, '7 8 9 10 11 12', 0, '2026-01-01 00:00:00', 1123, 'GUEST'),
  (NULL, 1, 0, '7 8 9 10 11 12', 0, '2026-01-01 00:00:00', 1123, 'GUEST'),
  (NULL, 0, 0, NULL, 0, '2026-01-01 00:00:00', 0, NULL);

INSERT INTO lotto_winner_data (`round`, bnus_no, first_accumamnt, first_przwner_co, first_winamnt, tot_sellamnt, winner_data) VALUES
  (1, 7, 0, 0, 0, 0, '1,2,3,4,5,6'),
  (2, 7, 0, 0, 0, 0, '1,2,3,4,5,6'),
  (4, 7, 0, 0, 0, 0, '1,2,3,4,5,6');
