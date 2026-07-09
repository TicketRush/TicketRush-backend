
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `booking` (
  `booking_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `booking_number` varchar(50) NOT NULL,
  `booking_status` enum('CANCELED','CONFIRMED','EXPIRED','PENDING','REFUNDED','REFUNDING','REFUND_FAILED') NOT NULL,
  `confirmed_at` datetime(6) DEFAULT NULL,
  `performance_id` bigint NOT NULL,
  `seat_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`booking_id`),
  UNIQUE KEY `UK6j74n7w8mp19sixr5272028mk` (`booking_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dead_letter_record` (
  `dead_letter_record_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `event_id` varchar(36) DEFAULT NULL,
  `event_type` varchar(150) DEFAULT NULL,
  `exception_fqcn` varchar(500) DEFAULT NULL,
  `exception_message` text,
  `message_key` varchar(200) DEFAULT NULL,
  `original_offset` bigint NOT NULL,
  `original_partition` int NOT NULL,
  `original_topic` varchar(200) NOT NULL,
  `payload` text,
  PRIMARY KEY (`dead_letter_record_id`),
  UNIQUE KEY `uk_dlr_topic_partition_offset` (`original_topic`,`original_partition`,`original_offset`),
  KEY `idx_dlr_event_type_created_at` (`event_type`,`created_at`),
  KEY `idx_dlr_original_topic_created_at` (`original_topic`,`created_at`),
  KEY `idx_dlr_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `expired_booking` (
  `expired_booking_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `booking_id` bigint NOT NULL,
  `expired_at` datetime(6) NOT NULL,
  PRIMARY KEY (`expired_booking_id`),
  UNIQUE KEY `UKh7idet7j8jvxast89621h9tjn` (`booking_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbox` (
  `inbox_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `consumer_group` varchar(100) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(150) DEFAULT NULL,
  PRIMARY KEY (`inbox_id`),
  UNIQUE KEY `uk_inbox_group_event` (`consumer_group`,`event_id`),
  KEY `idx_inbox_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbox` (
  `outbox_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `aggregate_id` varchar(100) NOT NULL,
  `aggregate_type` varchar(100) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(150) NOT NULL,
  `last_error` text,
  `message_key` varchar(200) DEFAULT NULL,
  `payload` text NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `retry_count` int NOT NULL,
  `status` enum('DEAD','FAILED','PENDING','SENT') NOT NULL,
  `topic` varchar(200) NOT NULL,
  `trace_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`outbox_id`),
  UNIQUE KEY `uk_outbox_event_id` (`event_id`),
  KEY `idx_outbox_status_created_at` (`status`,`created_at`),
  KEY `idx_outbox_aggtype_status_published` (`aggregate_type`,`status`,`published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment` (
  `payment_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `amount` bigint NOT NULL,
  `approval_number` varchar(100) DEFAULT NULL,
  `booking_id` bigint NOT NULL,
  `completed_booking_id` bigint DEFAULT NULL,
  `failure_code` varchar(50) DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `payment_key` varchar(200) DEFAULT NULL,
  `pg_failure_code` varchar(50) DEFAULT NULL,
  `pg_failure_reason` varchar(255) DEFAULT NULL,
  `provider` enum('KAKAO','NAVER','TOSS') NOT NULL,
  `seat_id` bigint DEFAULT NULL,
  `status` enum('CANCELED','COMPLETED','FAILED','PENDING') NOT NULL,
  `user_id` bigint DEFAULT NULL,
  PRIMARY KEY (`payment_id`),
  UNIQUE KEY `UK6vew52c3hm7vwaiwfvt498x3` (`payment_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `performance` (
  `performance_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `description` text,
  `duration_minutes` int NOT NULL,
  `genre` enum('BALLET','CLASSIC','CONCERT','FANMEETING','FESTIVAL','JAZZ','MUSICAL') NOT NULL,
  `image3d_url` varchar(255) DEFAULT NULL,
  `image_main_url` varchar(255) DEFAULT NULL,
  `performance_status` enum('CANCELED','CLOSED','ON_SALE','UPCOMING') NOT NULL,
  `performer` varchar(200) DEFAULT NULL,
  `price` bigint NOT NULL,
  `show_date` date NOT NULL,
  `show_time` time NOT NULL,
  `title` varchar(200) NOT NULL,
  `total_seats` int NOT NULL,
  PRIMARY KEY (`performance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `performance_facilities` (
  `performance_id` bigint NOT NULL,
  `facility_name` varchar(255) DEFAULT NULL,
  `facility_order` int NOT NULL,
  PRIMARY KEY (`performance_id`,`facility_order`),
  CONSTRAINT `FKhcy9bu41n6ukrevxoox2bj74q` FOREIGN KEY (`performance_id`) REFERENCES `performance` (`performance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `performance_images` (
  `performance_id` bigint NOT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `image_url_order` int NOT NULL,
  PRIMARY KEY (`performance_id`,`image_url_order`),
  CONSTRAINT `FK5e7x52fj982qqu64uw9esuxdb` FOREIGN KEY (`performance_id`) REFERENCES `performance` (`performance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund` (
  `refund_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `booking_id` bigint NOT NULL,
  `confirmed_at` datetime(6) DEFAULT NULL,
  `payment_id` bigint NOT NULL,
  `pg_refund_key` varchar(200) DEFAULT NULL,
  `price` bigint NOT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `requested_at` datetime(6) DEFAULT NULL,
  `status` enum('COMPLETED','FAILED','PENDING') DEFAULT NULL,
  PRIMARY KEY (`refund_id`),
  UNIQUE KEY `UKqwu73qgmbrsnysqx67oewyj5d` (`payment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat` (
  `seat_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `booking_number` varchar(50) DEFAULT NULL,
  `hold_expired_at` datetime(6) DEFAULT NULL,
  `performance_id` bigint NOT NULL,
  `seat_layout_id` bigint NOT NULL,
  `seat_number` varchar(10) NOT NULL,
  `seat_status` enum('AVAILABLE','HOLD','SOLD') NOT NULL,
  PRIMARY KEY (`seat_id`),
  KEY `idx_seat_performance_id` (`performance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `seat_layout` (
  `seat_layout_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `max_cols` int NOT NULL,
  `performance_id` bigint NOT NULL,
  `total_rows` int NOT NULL,
  PRIMARY KEY (`seat_layout_id`),
  UNIQUE KEY `uk_seat_layout_performance_id` (`performance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `social_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `provider_user_id` varchar(100) NOT NULL,
  `social_provider` enum('GOOGLE','KAKAO','NAVER') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_social_provider_provider_user_id` (`social_provider`,`provider_user_id`),
  UNIQUE KEY `UKc1w2fxw43mk5g4bwhykj393h6` (`user_id`),
  CONSTRAINT `FKe077f5rlmayycish4itikihul` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ticket` (
  `ticket_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `booking_id` bigint NOT NULL,
  `ticket_status` enum('CANCELED','UNUSED','USED') NOT NULL,
  `ticket_token_hash` varchar(64) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`ticket_id`),
  UNIQUE KEY `UKgco27k8cbs8j67db3oadbna6o` (`booking_id`),
  UNIQUE KEY `UKgbou3cclxytcn7k5xlag9s0n8` (`ticket_token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `name` varchar(50) DEFAULT NULL,
  `user_role` enum('ADMIN','MEMBER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKob8kqyqqgmefl0aco34akdtpe` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKmy17tf2l7ojod9fu836oxcobn` (`user_id`),
  CONSTRAINT `FK4qaqge5ewvmfuwsp5eddfr4r2` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

