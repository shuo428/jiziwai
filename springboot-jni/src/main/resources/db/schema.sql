-- PostgreSQL schema for jiziwai.
--
-- Minimal image-storage design:
-- 1. Raw images are stored on disk/object storage, not as BYTEA in PostgreSQL.
-- 2. PostgreSQL stores only acquisition metadata, file URIs, essential metrics and actions.
-- 3. Optional or future metrics are kept in JSONB instead of creating many columns/tables.

BEGIN;

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_user_username ON t_user(username);

DROP TRIGGER IF EXISTS update_user_updated_at ON t_user;
DROP TRIGGER IF EXISTS trg_user_updated_at ON t_user;
CREATE TRIGGER trg_user_updated_at
BEFORE UPDATE ON t_user
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ---------------------------------------------------------------------------
-- One independent trigger/acquisition
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_spectral_capture (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT REFERENCES t_user(id) ON DELETE SET NULL,

    capture_scene VARCHAR(24) NOT NULL DEFAULT 'NORMAL'
        CHECK (capture_scene IN (
            'NORMAL',
            'DARK',
            'FLAT',
            'HALF_SATURATION',
            'SATURATION',
            'NARROW_LINE',
            'POINT_SOURCE'
        )),
    capture_status VARCHAR(16) NOT NULL DEFAULT 'WAITING'
        CHECK (capture_status IN (
            'WAITING',
            'RECEIVED',
            'ANALYZING',
            'PASS',
            'WARNING',
            'FAILED',
            'TIMEOUT',
            'DISCARDED'
        )),

    trigger_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    sensor_mode VARCHAR(24),
    exposure_us NUMERIC(16, 3),
    analog_gain NUMERIC(10, 4),
    sensor_temperature_c NUMERIC(8, 3),

    fpga_error_code INTEGER,
    error_message TEXT,
    config_snapshot JSONB NOT NULL DEFAULT '{}'::JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_capture_time
    ON t_spectral_capture(trigger_time DESC);
CREATE INDEX IF NOT EXISTS idx_capture_status
    ON t_spectral_capture(capture_status, trigger_time DESC);
CREATE INDEX IF NOT EXISTS idx_capture_user
    ON t_spectral_capture(user_id, trigger_time DESC);

DROP TRIGGER IF EXISTS trg_spectral_capture_updated_at ON t_spectral_capture;
CREATE TRIGGER trg_spectral_capture_updated_at
BEFORE UPDATE ON t_spectral_capture
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE t_spectral_capture IS '一次独立的单帧触发、接收和分析事务';
COMMENT ON COLUMN t_spectral_capture.request_id IS '上位机生成的触发编号，不要求FPGA提供frameId';
COMMENT ON COLUMN t_spectral_capture.config_snapshot IS '触发时需要保留的设备配置JSON';

-- ---------------------------------------------------------------------------
-- Image metadata and storage locations
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_spectral_image (
    id BIGSERIAL PRIMARY KEY,
    capture_id BIGINT NOT NULL UNIQUE
        REFERENCES t_spectral_capture(id) ON DELETE CASCADE,

    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    pixel_format VARCHAR(32) NOT NULL DEFAULT 'RAW16_LOW12',
    payload_length BIGINT NOT NULL CHECK (payload_length > 0),

    raw_storage_uri TEXT NOT NULL,
    preview_storage_uri TEXT,
    processed_storage_uri TEXT,
    raw_sha256 CHAR(64),

    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_image_raw_sha256
        CHECK (raw_sha256 IS NULL OR raw_sha256 ~ '^[0-9a-fA-F]{64}$')
);

CREATE INDEX IF NOT EXISTS idx_image_received_at
    ON t_spectral_image(received_at DESC);
CREATE INDEX IF NOT EXISTS idx_image_raw_sha256
    ON t_spectral_image(raw_sha256)
    WHERE raw_sha256 IS NOT NULL;

DROP TRIGGER IF EXISTS trg_spectral_image_updated_at ON t_spectral_image;
CREATE TRIGGER trg_spectral_image_updated_at
BEFORE UPDATE ON t_spectral_image
FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE t_spectral_image IS '图像格式、原始文件、预览图和处理结果地址';
COMMENT ON COLUMN t_spectral_image.raw_storage_uri IS '原始12-bit数据文件地址';
COMMENT ON COLUMN t_spectral_image.preview_storage_uri IS '前端显示用8-bit预览图地址';
COMMENT ON COLUMN t_spectral_image.processed_storage_uri IS '校正或处理后的图像地址';

-- ---------------------------------------------------------------------------
-- Essential reception-integrity result
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_image_integrity_analysis (
    id BIGSERIAL PRIMARY KEY,
    capture_id BIGINT NOT NULL UNIQUE
        REFERENCES t_spectral_capture(id) ON DELETE CASCADE,
    image_id BIGINT UNIQUE
        REFERENCES t_spectral_image(id) ON DELETE CASCADE,

    passed BOOLEAN NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    result_message TEXT,

    crc_ok BOOLEAN,
    size_ok BOOLEAN,
    format_ok BOOLEAN,
    expected_bytes BIGINT,
    received_bytes BIGINT,
    elapsed_ms BIGINT,

    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_integrity_result
    ON t_image_integrity_analysis(passed, result_code, created_at DESC);

COMMENT ON TABLE t_image_integrity_analysis IS 'FPGA状态、超时、尺寸、格式和CRC完整性结论';
COMMENT ON COLUMN t_image_integrity_analysis.details IS
    '魔数、协议版本、实际CRC、高位异常位置等按需扩展信息';

-- ---------------------------------------------------------------------------
-- Essential image-quality metrics
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_image_quality_analysis (
    id BIGSERIAL PRIMARY KEY,
    image_id BIGINT NOT NULL UNIQUE
        REFERENCES t_spectral_image(id) ON DELETE CASCADE,

    quality_status VARCHAR(16) NOT NULL
        CHECK (quality_status IN ('PASS', 'WARNING', 'FAIL', 'NOT_EVALUATED')),
    analysis_version VARCHAR(32) NOT NULL,

    pixel_min INTEGER,
    pixel_max INTEGER,
    pixel_mean NUMERIC(20, 8),
    pixel_stddev NUMERIC(20, 8),
    black_pixel_ratio NUMERIC(16, 12),
    saturation_pixel_ratio NUMERIC(16, 12),

    abnormal_row_count INTEGER,
    abnormal_column_count INTEGER,
    bad_pixel_count INTEGER,

    smile_rms_pixels NUMERIC(20, 8),
    spectral_fwhm_pixels NUMERIC(20, 8),
    halo_ratio NUMERIC(20, 12),

    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    analyzed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_quality_black_ratio
        CHECK (black_pixel_ratio IS NULL OR black_pixel_ratio BETWEEN 0 AND 1),
    CONSTRAINT chk_quality_saturation_ratio
        CHECK (saturation_pixel_ratio IS NULL OR saturation_pixel_ratio BETWEEN 0 AND 1),
    CONSTRAINT chk_quality_abnormal_rows
        CHECK (abnormal_row_count IS NULL OR abnormal_row_count >= 0),
    CONSTRAINT chk_quality_abnormal_columns
        CHECK (abnormal_column_count IS NULL OR abnormal_column_count >= 0),
    CONSTRAINT chk_quality_bad_pixels
        CHECK (bad_pixel_count IS NULL OR bad_pixel_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quality_status
    ON t_image_quality_analysis(quality_status, analyzed_at DESC);

COMMENT ON TABLE t_image_quality_analysis IS '每张图像最必要的基础、行列、坏点、弯曲、清晰度和光晕指标';
COMMENT ON COLUMN t_image_quality_analysis.details IS
    '分位数、直方图、异常坐标、Keystone、鬼影等非核心或未来指标';

-- ---------------------------------------------------------------------------
-- Decision/action after analysis
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_image_action_log (
    id BIGSERIAL PRIMARY KEY,
    capture_id BIGINT NOT NULL
        REFERENCES t_spectral_capture(id) ON DELETE CASCADE,
    image_id BIGINT
        REFERENCES t_spectral_image(id) ON DELETE SET NULL,

    action_type VARCHAR(24) NOT NULL
        CHECK (action_type IN (
            'ACCEPT',
            'CORRECT',
            'REACQUIRE',
            'DISCARD',
            'ALERT',
            'RESET_FPGA',
            'RECONNECT'
        )),
    action_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
        CHECK (action_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    reason TEXT,
    output_storage_uri TEXT,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_action_capture
    ON t_image_action_log(capture_id, created_at DESC);

COMMENT ON TABLE t_image_action_log IS '接受、校正、重拍、丢弃、报警、复位或重连记录';

-- ---------------------------------------------------------------------------
-- Frontend/report summary view
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_spectral_image_summary AS
SELECT
    c.id AS capture_id,
    c.request_id,
    c.user_id,
    c.capture_scene,
    c.capture_status,
    c.trigger_time,
    c.completed_at,
    c.sensor_mode,
    c.exposure_us,
    c.analog_gain,
    c.sensor_temperature_c,
    c.fpga_error_code,
    c.error_message,
    i.id AS image_id,
    i.width,
    i.height,
    i.pixel_format,
    i.raw_storage_uri,
    i.preview_storage_uri,
    i.processed_storage_uri,
    ia.passed AS integrity_passed,
    ia.result_code AS integrity_result_code,
    qa.quality_status,
    qa.pixel_mean,
    qa.pixel_stddev,
    qa.black_pixel_ratio,
    qa.saturation_pixel_ratio,
    qa.bad_pixel_count,
    qa.smile_rms_pixels,
    qa.spectral_fwhm_pixels,
    qa.halo_ratio,
    qa.analyzed_at
FROM t_spectral_capture c
LEFT JOIN t_spectral_image i ON i.capture_id = c.id
LEFT JOIN t_image_integrity_analysis ia ON ia.capture_id = c.id
LEFT JOIN t_image_quality_analysis qa ON qa.image_id = i.id;

COMMIT;
