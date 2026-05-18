import contextvars
from typing import Any, Dict, List, Optional

from config import get_settings

settings = get_settings()
_auth_token_ctx: contextvars.ContextVar[str] = contextvars.ContextVar("auth_token", default="")


def set_auth_token(token: str) -> contextvars.Token[str]:
    """将当前请求的鉴权 token 写入上下文变量。"""
    return _auth_token_ctx.set(token or "")


def reset_auth_token(token_marker: contextvars.Token[str]) -> None:
    """恢复 `set_auth_token` 之前的上下文状态。"""
    _auth_token_ctx.reset(token_marker)


def _load_requests() -> Any:
    try:
        import requests
    except ModuleNotFoundError as exc:
        raise RuntimeError(f"missing dependency: {exc}") from exc
    return requests


def _headers() -> Dict[str, str]:
    token = _auth_token_ctx.get() or ""
    return {"Authorization": token} if token else {}


def _post(path: str, payload: Optional[dict[str, Any]] = None) -> Dict[str, Any]:
    requests = _load_requests()
    response = requests.post(
        f"{settings.backend_base_url}{path}",
        json=payload,
        headers=_headers(),
        timeout=settings.capture_request_timeout_seconds,
    )
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 200:
        return {"ok": False, "message": body.get("message", "backend request failed")}
    return {"ok": True, "message": body.get("message", "ok"), "data": body.get("data")}


def connect_spectra_bridge(
    host: str,
    control_port: int,
    image_port: int,
    verify_crc: bool = True,
) -> Dict[str, Any]:
    """连接 SpectraBridgeNative 设备通道。"""
    if not host or not host.strip():
        return {"ok": False, "message": "host is required"}
    if control_port < 1 or control_port > 65535:
        return {"ok": False, "message": "control_port must be in range 1..65535"}
    if image_port < 1 or image_port > 65535:
        return {"ok": False, "message": "image_port must be in range 1..65535"}

    try:
        return _post(
            "/api/jni/connect",
            {
                "host": host.strip(),
                "controlPort": control_port,
                "imagePort": image_port,
                "verifyCrc": verify_crc,
            },
        )
    except Exception as exc:
        return {"ok": False, "message": str(exc)}


def disconnect_spectra_bridge(control_message: str = "") -> Dict[str, Any]:
    """断开 SpectraBridgeNative 连接。"""
    try:
        return _post("/api/jni/disconnect")
    except Exception as exc:
        return {"ok": False, "message": str(exc)}


def send_reset_command(control_message: str = "") -> Dict[str, Any]:
    """发送 FPGA 复位命令。"""
    try:
        return _post("/api/jni/commands/reset")
    except Exception as exc:
        return {"ok": False, "message": str(exc)}


def trigger_single_frame(control_message: str = "") -> Dict[str, Any]:
    """触发一次图像采集。图像数据会通过后端 WebSocket 回调给前端。"""
    try:
        return _post("/api/jni/commands/trigger-once")
    except Exception as exc:
        return {"ok": False, "message": str(exc)}


def query_bridge_status(control_message: str = "") -> Dict[str, Any]:
    """查询 FPGA 原始状态位。状态结果会通过后端 WebSocket 回调给前端。"""
    try:
        return _post("/api/jni/commands/query-status")
    except Exception as exc:
        return {"ok": False, "message": str(exc)}


def send_full_config(config_bytes: Optional[List[int]] = None) -> Dict[str, Any]:
    """发送 512 字节完整配置。未传入时发送全 0 配置。"""
    payload = config_bytes if config_bytes is not None else [0] * 512
    if len(payload) != 512:
        return {"ok": False, "message": "config_bytes must contain exactly 512 bytes"}
    for index, value in enumerate(payload):
        if value is None or value < 0 or value > 255:
            return {"ok": False, "message": f"config_bytes[{index}] must be in range 0..255"}

    try:
        return _post("/api/jni/commands/full-config", {"configBytes": payload})
    except Exception as exc:
        return {"ok": False, "message": str(exc)}
