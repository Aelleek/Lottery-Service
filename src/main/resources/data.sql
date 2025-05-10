INSERT INTO member (id) VALUES (1);
INSERT INTO member (id) VALUES (2);

INSERT INTO lotto_record (member_id, numbers, round, recommended_at, is_manual, is_purchased, is_guest, source)
VALUES
    (1, '5 12 18 22 34 40', 1123, CURRENT_TIMESTAMP, false, false, false, 'MANUAL'),
    (2, '3 7 15 19 25 44', 1123, CURRENT_TIMESTAMP, false, true, false, 'AUTO'),
    (null, '1 4 9 11 36 42', 1123, CURRENT_TIMESTAMP, false, false, true, 'GUEST');
