-- V1：初始 schema。三层防超卖的物理层在这里落地：
--   ① uk_activity_user 唯一索引（挡住同一用户重复报名）
--   ② chk_activity_stock CHECK (stock >= 0)（挡住任何让库存变负的 SQL）
--   ③ UPDATE ... WHERE stock > 0 的影响行数兜底在 Repository 里，不在这里

CREATE TABLE activity (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  title       VARCHAR(200)  NOT NULL,
  capacity    INT           NOT NULL,
  stock       INT           NOT NULL,
  start_time  DATETIME(3)   NOT NULL,
  status      VARCHAR(20)   NOT NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_activity_capacity CHECK (capacity >= 0),
  CONSTRAINT chk_activity_stock    CHECK (stock >= 0)
) ENGINE = InnoDB;

CREATE TABLE registration (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  activity_id BIGINT         NOT NULL,
  user_id     BIGINT         NOT NULL,
  status      VARCHAR(20)    NOT NULL,
  created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_activity_user UNIQUE (activity_id, user_id),
  CONSTRAINT fk_reg_activity FOREIGN KEY (activity_id) REFERENCES activity(id),
  INDEX idx_reg_activity_status (activity_id, status)
) ENGINE = InnoDB;

-- 用户表。报名只认 user_id，用户名密码仅用于换 token（D3）
CREATE TABLE app_user (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  username    VARCHAR(64)   NOT NULL,
  password    VARCHAR(100)  NOT NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_user_username UNIQUE (username)
) ENGINE = InnoDB;
