import contextvars
import threading
from typing import Any, Dict, List, Optional

from config import get_settings

settings = get_settings()
_auth_token_ctx: contextvars.ContextVar[str] = contextvars.ContextVar("auth_token", default="")


class ContinuousListenerState:
    """持续监听状态。

    这个类专门服务于 `start_continuous_spectral_listener / stop_continuous_spectral_listener`
    这对工具，目的是把“启动监听”和“停止监听”这两个动作串成一条完整链路。

    为什么这里要维护状态：
    1. `/api/jni/start` 只是启动后端 JNI 持续监听，不会把接收到的数据直接通过 HTTP 返回。
    2. 真正的数据仍然是通过 `/ws` WebSocket 持续推送出来的。
    3. 因此工具层如果要支持“开始监听，过一段时间再停止”，就必须在 Python 进程内保留：
       - 当前 WebSocket 连接
       - 后台接收线程
       - 已接收到的数据缓存
       - 当前是否处于监听中
    """

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.running = False
        self.records: List[str] = []
        self.ws: Any = None
        self.receiver_thread: Optional[threading.Thread] = None


listener_state = ContinuousListenerState()


def set_auth_token(token: str) -> contextvars.Token[str]:
    """将当前请求的鉴权 token 写入上下文变量。

    Args:
        token: 当前 HTTP 请求透传下来的 `Authorization` 值。

    Returns:
        contextvars.Token[str]: 上下文快照，用于后续 `reset_auth_token` 回滚。
    """
    return _auth_token_ctx.set(token or "")


def reset_auth_token(token_marker: contextvars.Token[str]) -> None:
    """恢复 `set_auth_token` 之前的上下文状态。

    Args:
        token_marker: `set_auth_token` 返回的上下文快照。
    """
    _auth_token_ctx.reset(token_marker)


def _load_http_dependencies() -> tuple[Any, Any]:
    """延迟加载网络依赖。

    这样做的目的不是“偷懒”，而是避免服务在导入阶段因为环境没装依赖而直接崩掉。
    只有真正调用工具时，才检查 `requests/websocket-client` 是否存在。
    """
    try:
        import requests
        import websocket
    except ModuleNotFoundError as exc:
        raise RuntimeError(f"missing dependency: {exc}") from exc
    return requests, websocket


def capture_count_based_spectral_data(count: int = 1, timeout_seconds: int = 10) -> Dict[str, Any]:
    """
    按指定数量获取光谱数据。

    这是“定量采集”模式，对应你现有系统中的 `/api/jni/capture`。

    工作流程：
    1. 先建立 WebSocket 连接，准备接收后端推送的数据。
    2. 调用 `/api/jni/capture?count=n`，通知后端执行“接收 n 条后自动停止”。
    3. 在当前函数内阻塞等待，直到收满 `count` 条数据或超时。
    4. 收完后主动关闭 WebSocket，并把本次采集结果返回给 Agent。

    这个模式的特点：
    - 调用一次，完成一次闭环。
    - 不需要额外的 stop 动作。
    - 更适合“帮我采集 3 条数据”这种自然语言请求。

    Args:
        count: 采集条数，1-10。
        timeout_seconds: 等待超时时间（秒）。

    Returns:
        Dict[str, Any]:
        - `ok: bool`：调用是否成功
        - `count: int`：实际收到的数据条数
        - `requestedCount: int`：请求采集的条数
        - `records: List[str]`：本次采集收到的光谱数据原文
        - `message: str`：失败时的错误说明
    """
    safe_count = max(1, min(int(count), 10))
    safe_timeout = max(2, min(int(timeout_seconds), 20))
    auth_token = _auth_token_ctx.get() or ""

    try:
        requests, websocket = _load_http_dependencies()
    except RuntimeError as exc:
        return {"ok": False, "message": str(exc), "records": []}

    ws = websocket.WebSocket()
    ws.settimeout(safe_timeout)
    records: List[str] = []

    try:
        ws.connect(settings.backend_ws_url)
        resp = requests.post(
            f"{settings.backend_base_url}/api/jni/capture",
            params={"count": safe_count},
            headers={"Authorization": auth_token} if auth_token else {},
            timeout=settings.capture_request_timeout_seconds,
        )
        resp.raise_for_status()
        payload = resp.json()
        if payload.get("code") != 200:
            return {"ok": False, "message": payload.get("message", "capture failed"), "records": []}

        for _ in range(safe_count):
            try:
                message = ws.recv()
                if isinstance(message, str):
                    records.append(message)
            except Exception:
                break

        return {
            "ok": True,
            "count": len(records),
            "requestedCount": safe_count,
            "records": records,
        }
    except Exception as exc:
        return {"ok": False, "message": str(exc), "records": []}
    finally:
        try:
            ws.close()
        except Exception:
            pass


def start_continuous_spectral_listener(control_message: str = "") -> Dict[str, Any]:
    """
    启动持续监听模式。

    这是“持续监听”模式，对应你现有系统中的 `/api/jni/start`。

    它和 `capture_count_based_spectral_data` 的根本区别：
    - `capture_count_based_spectral_data` 是“采够指定数量后自动结束”的短事务。
    - `start_continuous_spectral_listener` 是“开始后持续接收，直到显式 stop”的长连接事务。

    这里的实现步骤：
    1. 打开 WebSocket，作为持续接收通道。
    2. 启动后台线程，不断从 WebSocket 读取消息并放入内存缓存。
    3. 调用 `/api/jni/start`，通知后端进入持续监听状态。
    4. 函数本身只返回“监听已启动”，不会阻塞等待数据结束。

    为什么要起后台线程：
    - 因为持续监听本身没有天然结束点。
    - 如果在当前函数里同步 `recv()`，工具调用会一直卡住，Agent 无法继续对话。
    - 所以必须把“数据接收”放到后台线程，把“控制动作”留在工具调用里。

    Args:
        control_message: 可选的控制说明文本。
            这个参数不会改变启动逻辑，主要作用是为 Tool Schema 显式生成参数定义，
            以兼容 DeepSeek/OpenAI 通过 LiteLLM 进行函数调用时对 `parameters` 字段的要求。

    Returns:
        Dict[str, Any]:
        - `ok: bool`：启动是否成功
        - `message: str`：启动结果说明
        - `recordCount: int`：若已经处于监听状态，则返回当前缓存条数
        - `records: List[str]`：失败时可带回部分缓存或空列表
    """
    auth_token = _auth_token_ctx.get() or ""

    try:
        requests, websocket = _load_http_dependencies()
    except RuntimeError as exc:
        return {"ok": False, "message": str(exc), "records": []}

    with listener_state.lock:
        if listener_state.running:
            return {
                "ok": True,
                "message": "continuous listener already running",
                "recordCount": len(listener_state.records),
            }

        ws = websocket.WebSocket()
        ws.settimeout(1)
        ws.connect(settings.backend_ws_url)

        listener_state.ws = ws
        listener_state.records = []
        listener_state.running = True

        # 后台线程持续消费 WebSocket 推送。
        # 这里故意只做“接收并缓存”，不做复杂业务逻辑，保证线程职责单一。
        def receive_loop() -> None:
            while listener_state.running and listener_state.ws is not None:
                try:
                    message = listener_state.ws.recv()
                    if isinstance(message, str):
                        with listener_state.lock:
                            listener_state.records.append(message)
                except Exception:
                    continue

        listener_state.receiver_thread = threading.Thread(
            target=receive_loop,
            name="spectral-listener-thread",
            daemon=True,
        )
        listener_state.receiver_thread.start()

    try:
        resp = requests.get(
            f"{settings.backend_base_url}/api/jni/start",
            headers={"Authorization": auth_token} if auth_token else {},
            timeout=settings.capture_request_timeout_seconds,
        )
        resp.raise_for_status()
        payload = resp.json()
        if payload.get("code") != 200:
            raise RuntimeError(payload.get("message", "start listener failed"))
        return {
            "ok": True,
            "message": payload.get("message", "continuous listener started"),
        }
    except Exception as exc:
        stop_continuous_spectral_listener()
        return {"ok": False, "message": str(exc), "records": []}


def stop_continuous_spectral_listener(control_message: str = "") -> Dict[str, Any]:
    """
    停止持续监听模式，并返回本轮监听期间累积的数据。

    工作流程：
    1. 调用 `/api/jni/stop`，通知后端停止 JNI 持续监听。
    2. 停掉 Python 侧后台接收线程所依赖的运行标记。
    3. 关闭 WebSocket。
    4. 返回这一轮持续监听期间已经接收到的全部数据。

    这样设计的目的：
    - 让 Agent 能把“开始监听 -> 过一会儿再停止 -> 汇总结果”串成完整操作。
    - 保证持续监听模式和前端页面中的 start/stop 语义一致。

    Args:
        control_message: 可选的控制说明文本。
            这个参数不会参与停止逻辑，仅用于为 Tool Schema 提供稳定的参数结构，
            避免 LiteLLM 在处理“无参工具”时生成 `parameters=None`。

    Returns:
        Dict[str, Any]:
        - `ok: bool`：停止动作是否成功
        - `message: str`：停止结果说明
        - `records: List[str]`：本轮持续监听期间累计收到的数据
        - `recordCount: int`：`records` 的数量
    """
    auth_token = _auth_token_ctx.get() or ""

    try:
        requests, _ = _load_http_dependencies()
    except RuntimeError as exc:
        return {"ok": False, "message": str(exc), "records": []}

    errors: List[str] = []
    try:
        resp = requests.get(
            f"{settings.backend_base_url}/api/jni/stop",
            headers={"Authorization": auth_token} if auth_token else {},
            timeout=settings.capture_request_timeout_seconds,
        )
        resp.raise_for_status()
        payload = resp.json()
        if payload.get("code") != 200:
            errors.append(payload.get("message", "stop listener failed"))
    except Exception as exc:
        errors.append(str(exc))

    with listener_state.lock:
        listener_state.running = False
        records = list(listener_state.records)
        ws = listener_state.ws
        listener_state.records = []
        listener_state.ws = None
        listener_state.receiver_thread = None

    if ws is not None:
        try:
            ws.close()
        except Exception:
            pass

    if errors:
        return {
            "ok": False,
            "message": "; ".join(errors),
            "records": records,
            "recordCount": len(records),
        }

    return {
        "ok": True,
        "message": "continuous listener stopped",
        "records": records,
        "recordCount": len(records),
    }
