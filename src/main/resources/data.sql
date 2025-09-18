--INSERT INTO member(email_verified, id, last_login_at, nickname, status)
--VALUES (FALSE, 1, '2025-09-16 13:49:46.744806+00','이경원','ACTIVE');
--
--INSERT INTO member_oauth_account (connected_at, id, member_id, updated_at, provider_user_id, display_name,  provider )
--VALUES ('2025-09-16 13:49:46.738677+00',1,1,'2025-09-16 13:49:46.738677+00','4433749540','이경원','KAKAO');

INSERT INTO lotto_record (member_id, numbers, round, recommended_at, manual, purchased, guest, source)
VALUES
--    (1, '5 12 18 22 34 40', 1123, CURRENT_TIMESTAMP, false, false, false, 'MANUAL'),
----    (2, '3 7 15 19 25 44', 1123, CURRENT_TIMESTAMP, false, true, false, 'AUTO'),
    (null, '1 4 9 11 36 42', 1123, CURRENT_TIMESTAMP, false, false, true, 'GUEST');
