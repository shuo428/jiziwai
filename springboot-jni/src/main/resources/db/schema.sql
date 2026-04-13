-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
-- 创建索引
CREATE INDEX IF NOT EXISTS idx_username ON t_user(username);
CREATE INDEX IF NOT EXISTS idx_created_at ON t_user(created_at);
-- 创建触发器自动更新 updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column() RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = CURRENT_TIMESTAMP;
RETURN NEW;
END;
$$ language 'plpgsql';
CREATE TRIGGER update_user_updated_at BEFORE
UPDATE ON t_user FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
-- 添加表注释
COMMENT ON TABLE t_user IS '用户表';
COMMENT ON COLUMN t_user.id IS '用户ID';
COMMENT ON COLUMN t_user.username IS '用户名';
COMMENT ON COLUMN t_user.password IS '密码(BCrypt加密)';
COMMENT ON COLUMN t_user.created_at IS '创建时间';
COMMENT ON COLUMN t_user.updated_at IS '更新时间';
COMMENT ON COLUMN t_user.deleted IS '逻辑删除标记(false:未删除, true:已删除)';