import re
from typing import Any, Literal, Optional, TypedDict

from google.adk.agents import LlmAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai.types import Content, Part

from config import get_settings
from tools import (
    capture_count_based_spectral_data,
    start_continuous_spectral_listener,
    stop_continuous_spectral_listener,
)


class CaptureIntent(TypedDict):
    """定量采集意图。

    字段说明：
    - `type`: 固定为 `"capture"`，表示这是一条“按数量采集”的控制意图。
    - `count`: 期望采集的光谱数据数量。
    """

    type: Literal["capture"]
    count: int


class StartContinuousListenerIntent(TypedDict):
    """启动持续监听意图。"""

    type: Literal["start_continuous_listener"]


class StopContinuousListenerIntent(TypedDict):
    """停止持续监听意图。"""

    type: Literal["stop_continuous_listener"]


ControlIntent = CaptureIntent | StartContinuousListenerIntent | StopContinuousListenerIntent


class SpectralAssistantAgent:
    """ADK 智能体运行时，负责 Agent 初始化、意图解析与回复生成。"""

    @staticmethod
    def _build_model(settings: Any) -> Any:
        """根据配置创建底层模型对象。

        规则：
        - `gemini`：直接使用 ADK 原生 Gemini 模型名字符串
        - `deepseek`：使用 `LiteLlm(model=\"deepseek/<model>\")`
        - `openai`：使用 `LiteLlm(model=\"openai/<model>\")`

        Returns:
            Any: 可被 `LlmAgent` 接受的模型配置对象

        Raises:
            RuntimeError: 选中 `deepseek/openai` 但本地未安装 `litellm` 时抛出。
        """
        if settings.model_provider == "gemini":
            return settings.selected_model_name()

        try:
            from google.adk.models.lite_llm import LiteLlm
        except (ImportError, ModuleNotFoundError) as exc:
            raise RuntimeError(
                "LiteLlm is unavailable. For provider=deepseek/openai, "
                "install dependencies with `pip install -r requirements.txt` in agent-adk."
            ) from exc

        if settings.model_provider == "deepseek":
            return LiteLlm(
                model=f"deepseek/{settings.selected_model_name()}",
                api_base=settings.deepseek_base_url,
                api_key=settings.deepseek_api_key,
            )

        return LiteLlm(
            model=f"openai/{settings.selected_model_name()}",
            api_base=settings.openai_base_url,
            api_key=settings.openai_api_key,
        )

    def __init__(self) -> None:
        """初始化 ADK 智能体运行时。

        初始化内容包括：
        - 读取配置
        - 校验 API Key 等关键配置
        - 创建 `LlmAgent`
        - 创建 `Runner`
        - 创建内存会话服务
        """
        self.settings = get_settings()
        self.settings.validate_required_settings()
        model = self._build_model(self.settings)
        self.root_agent = LlmAgent(
            model=model,
            name="spectral_assistant",
            description="用于光谱数据采集与分析的 AI 助手",
            instruction=(
                "你是光谱数据助手。"
                "当用户要求按指定数量获取光谱数据时，优先调用 capture_count_based_spectral_data。"
                "当用户要求开始持续监听时，调用 start_continuous_spectral_listener。"
                "当用户要求停止持续监听时，调用 stop_continuous_spectral_listener。"
                "如果工具返回 ok=false，请明确告知失败原因并建议检查后端监听状态。"
                "回答简洁、结构化，默认使用中文。"
            ),
            tools=[
                capture_count_based_spectral_data,
                start_continuous_spectral_listener,
                stop_continuous_spectral_listener,
            ],
        )
        self.session_service = InMemorySessionService()
        self.runner = Runner(
            agent=self.root_agent,
            app_name=self.settings.app_name,
            session_service=self.session_service,
        )

    async def run_reply(self, message: str, session_id: str) -> str:
        """执行一轮 ADK 对话并返回最终回复文本。

        Args:
            message: 发送给 Agent 的最终提示词文本。
            session_id: 会话 ID。同一会话下可复用上下文。

        Returns:
            str: Agent 的最终自然语言回复文本。如果 ADK 没有产生可用文本，则返回兜底提示。
        """
        await self.session_service.create_session(
            app_name=self.settings.app_name,
            user_id=self.settings.user_id,
            session_id=session_id,
        )
        content = Content(role="user", parts=[Part(text=message)])
        events = self.runner.run_async(
            user_id=self.settings.user_id,
            session_id=session_id,
            new_message=content,
        )

        final_text = ""
        async for event in events:
            if event.is_final_response() and event.content and event.content.parts:
                parts = []
                for part in event.content.parts:
                    text = getattr(part, "text", None)
                    if text:
                        parts.append(text)
                if parts:
                    final_text = "".join(parts)

        return final_text or "未生成可用回复。"

    @staticmethod
    def parse_control_intent(message: str) -> Optional[ControlIntent]:
        """从自然语言中提取光谱采集控制意图。

        当前支持三类与现有前端功能一一对应的控制动作：
        - 按数量采集：`capture`
        - 启动持续监听：`start_continuous_listener`
        - 停止持续监听：`stop_continuous_listener`

        这里依然采用轻量规则解析，而不是把控制动作完全交给大模型猜测，
        原因是这类操作型请求需要稳定、可预测地落到你现有的前端流程中。

        Args:
            message: 用户原始输入。

        Returns:
            Optional[ControlIntent]:
            - 命中按数量采集时，返回 `{"type": "capture", "count": N}`
            - 命中启动持续监听时，返回 `{"type": "start_continuous_listener"}`
            - 命中停止持续监听时，返回 `{"type": "stop_continuous_listener"}`
            - 未识别到明确控制动作时返回 `None`
        """
        text = message.strip()
        if not text:
            return None

        normalized_text = re.sub(r"\s+", "", text)

        if re.search(r"(停止|结束|关闭).*(持续)?(监听|接收)", normalized_text):
            return {"type": "stop_continuous_listener"}

        if re.search(r"(开始|启动|一直|持续|连续).*(监听|接收)", normalized_text):
            return {"type": "start_continuous_listener"}

        has_capture_keyword = any(keyword in normalized_text for keyword in ("获取", "采集", "接收", "来", "拿"))
        has_count_hint = bool(re.search(r"\d+\s*(条|个|次)?", normalized_text))
        has_spectral_hint = any(keyword in normalized_text for keyword in ("光谱", "数据", "图片"))

        if has_capture_keyword and (has_count_hint or has_spectral_hint):
            match = re.search(r"(\d+)\s*(条|个|次)?", normalized_text)
            count = 1
            if match:
                try:
                    count = int(match.group(1))
                except ValueError:
                    count = 1
            count = max(1, min(count, 10))
            return {"type": "capture", "count": count}
        return None


agent_runtime = SpectralAssistantAgent()


async def run_agent_reply(message: str, session_id: str) -> str:
    """模块级包装函数，便于外部服务层直接调用 Agent。"""
    return await agent_runtime.run_reply(message, session_id)


def parse_control_intent(message: str) -> Optional[ControlIntent]:
    """模块级包装函数，便于外部服务层复用控制意图解析逻辑。"""
    return agent_runtime.parse_control_intent(message)
