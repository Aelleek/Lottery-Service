

INSERT INTO lotto_history (member_id, numbers, round, recommended_at, is_manual, is_purchased, source, is_guest)
VALUES
    (1, '3 11 22 33 38 44', 1112, CURRENT_TIMESTAMP, false, false, 'BASIC', false),
    (2, '4 12 27 31 40 45', 1112, CURRENT_TIMESTAMP, false, false, 'AD', true);
