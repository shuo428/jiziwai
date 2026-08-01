-- One-time migration from the original detailed image schema to the minimal schema.
-- It intentionally preserves t_user.
-- Run only after confirming that image-related tables contain no required data.

BEGIN;

DROP VIEW IF EXISTS v_spectral_image_summary;

DROP TABLE IF EXISTS t_calibration_global_setting CASCADE;
DROP TABLE IF EXISTS t_calibration_session CASCADE;
DROP TABLE IF EXISTS t_spectrum_extraction CASCADE;
DROP TABLE IF EXISTS t_image_action_log CASCADE;
DROP TABLE IF EXISTS t_image_anomaly CASCADE;
DROP TABLE IF EXISTS t_image_quality_analysis CASCADE;
DROP TABLE IF EXISTS t_image_integrity_analysis CASCADE;
DROP TABLE IF EXISTS t_calibration_asset CASCADE;
DROP TABLE IF EXISTS t_calibration_profile CASCADE;
DROP TABLE IF EXISTS t_quality_rule CASCADE;
DROP TABLE IF EXISTS t_quality_rule_set CASCADE;
DROP TABLE IF EXISTS t_spectral_image CASCADE;
DROP TABLE IF EXISTS t_spectral_capture CASCADE;

COMMIT;
