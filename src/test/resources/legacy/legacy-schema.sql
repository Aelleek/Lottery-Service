-- 현재 코드(엔티티)가 Hibernate ddl-auto로 MySQL 8.4에 만드는 테이블. 2026-11-05 bootRun 결과를 mysqldump --no-data로 떴다.
-- 기존 DB 조사(docs/08-delivery.md 4절 1번)를 시험하는 표본 DB의 바탕이다. 실제 운영 DB와 다를 수 있다.

CREATE TABLE `member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `email` varchar(255) DEFAULT NULL,
  `email_verified` bit(1) NOT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `nickname` varchar(255) DEFAULT NULL,
  `profile_image_url` varchar(255) DEFAULT NULL,
  `status` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmbmcqelty0fbrvxp1q58dn57t` (`email`),
  KEY `idx_member_last_login_at` (`last_login_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member_oauth_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `access_token_enc` varchar(255) DEFAULT NULL,
  `connected_at` datetime(6) DEFAULT NULL,
  `display_name` varchar(255) DEFAULT NULL,
  `email_on_provider` varchar(255) DEFAULT NULL,
  `profile_image_url` varchar(255) DEFAULT NULL,
  `provider` enum('GOOGLE','KAKAO','NAVER') NOT NULL,
  `provider_user_id` varchar(191) NOT NULL,
  `refresh_token_enc` varchar(255) DEFAULT NULL,
  `scopes` varchar(255) DEFAULT NULL,
  `token_expires_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_user` (`provider`,`provider_user_id`),
  KEY `idx_oauth_member` (`member_id`),
  KEY `idx_oauth_provider_email` (`provider`,`email_on_provider`),
  CONSTRAINT `FKnhipml9fr0p71fnmhdgqtsfwo` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `lotto_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `guest` bit(1) NOT NULL,
  `manual` bit(1) NOT NULL,
  `numbers` varchar(255) DEFAULT NULL,
  `purchased` bit(1) NOT NULL,
  `recommended_at` datetime(6) DEFAULT NULL,
  `round` int NOT NULL,
  `source` varchar(255) DEFAULT NULL,
  `member_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKt66nlwwgi2n8fx4su4hrrthbw` (`member_id`),
  CONSTRAINT `FKt66nlwwgi2n8fx4su4hrrthbw` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `lotto_winner_data` (
  `round` int NOT NULL,
  `bnus_no` int NOT NULL,
  `drw_no_date` date DEFAULT NULL,
  `first_accumamnt` bigint NOT NULL,
  `first_przwner_co` int NOT NULL,
  `first_winamnt` bigint NOT NULL,
  `tot_sellamnt` bigint NOT NULL,
  `winner_data` varchar(30) NOT NULL,
  PRIMARY KEY (`round`),
  KEY `idx_lotto_winner_canonical` (`winner_data`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
