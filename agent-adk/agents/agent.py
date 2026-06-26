import re
from typing import Any, Literal, Optional, TypedDict

from google.adk.agents import LlmAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.genai.types import Content, Part

from config import get_settings
from tools import (
    connect_spectra_bridge,
    disconnect_spectra_bridge,
    query_bridge_status,
    send_full_config,
    send_reset_command,
    trigger_single_frame,
)


class ConnectIntent(TypedDict):
    type: Literal["connect"]
    host: str
    controlPort: int
    imagePort: int
    verifyCrc: bool


class SimpleIntent(TypedDict):
    type: Literal["disconnect", "trigger_once", "query_status", "reset", "send_full_config"]


ControlIntent = ConnectIntent | SimpleIntent


class SpectralAssistantAgent:
    """ADK 智能体运行时，负责 Agent 初始化、意图解析与回复生成。"""

    @staticmethod
    def _build_model(settings: Any) -> Any:
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
        self.settings = get_settings()
        self.settings.validate_required_settings()
        model = self._build_model(self.settings)
        self.root_agent = LlmAgent(
            model=model,
            name="spectral_assistant",
            description="用于 SpectraBridge 设备连接、状态查询、配置下发和单帧图像采集的 AI 助手",
            instruction=(
                "你是光谱设备控制助手。"
                "当前系统使用 SpectraBridgeNative JNI 接口，不再使用旧的持续监听或按数量采集。"
                "连接设备时需要 host、controlPort 和 imagePort。"
                "连接成功后才可以复位、触发单帧、查询状态或发送 512 字节完整配置。"
                "图像帧和状态回调通过前端 WebSocket 展示；历史图像、原始数据和完整性结果保存在服务器与 PostgreSQL。"
                "回答简洁、结构化，默认使用中文。"
            ),
            tools=[
                connect_spectra_bridge,
                disconnect_spectra_bridge,
                send_reset_command,
                trigger_single_frame,
                query_bridge_status,
                send_full_config,
            ],
        )
        self.session_service = InMemorySessionService()
        self.runner = Runner(
            agent=self.root_agent,
            app_name=self.settings.app_name,
            session_service=self.session_service,
        )

    async def run_reply(self, message: str, session_id: str) -> str:
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
        text = message.strip()
        if not text:
            return None

        normalized = re.sub(r"\s+", "", text)
        lower_text = text.lower()

        if re.search(r"(断开|关闭|停止).*(连接|设备|桥接|bridge)", normalized):
            return {"type": "disconnect"}

        if re.search(r"(复位|重置|reset)", normalized, re.IGNORECASE):
            return {"type": "reset"}

        if re.search(r"(查询|获取|读取).*(状态|status)", normalized, re.IGNORECASE):
            return {"type": "query_status"}

        if re.search(r"(获取|采集|触发|拍).*(一帧|单帧|图片|图像|frame)", normalized, re.IGNORECASE):
            return {"type": "trigger_once"}

        if re.search(r"(发送|下发).*(完整配置|配置|512)", normalized):
            return {"type": "send_full_config"}

        if re.search(r"(连接|connect)", normalized, re.IGNORECASE):
            host = SpectralAssistantAgent._extract_host(text)
            control_port = SpectralAssistantAgent._extract_port(
                lower_text,
                [r"controlport[:：=\s]*(\d+)", r"控制端口[:：=\s]*(\d+)", r"control[:：=\s]*(\d+)"],
            )
            image_port = SpectralAssistantAgent._extract_port(
                lower_text,
                [r"imageport[:：=\s]*(\d+)", r"图像端口[:：=\s]*(\d+)", r"image[:：=\s]*(\d+)"],
            )
            if host and control_port and image_port:
                return {
                    "type": "connect",
                    "host": host,
                    "controlPort": control_port,
                    "imagePort": image_port,
                    "verifyCrc": "不校验crc" not in normalized.lower(),
                }

        return None

    @staticmethod
    def _extract_host(text: str) -> Optional[str]:
        patterns = [
            r"(?:host|ip|地址|主机)[:：=\s]*([a-zA-Z0-9_.-]+)",
            r"(?:连接|connect)[:：=\s]*([a-zA-Z0-9_.-]+)",
        ]
        for pattern in patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                return match.group(1).strip().rstrip("，,。")
        return None

    @staticmethod
    def _extract_port(text: str, patterns: list[str]) -> Optional[int]:
        for pattern in patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                try:
                    port = int(match.group(1))
                except ValueError:
                    return None
                if 1 <= port <= 65535:
                    return port
        return None


agent_runtime = SpectralAssistantAgent()


async def run_agent_reply(message: str, session_id: str) -> str:
    return await agent_runtime.run_reply(message, session_id)


def parse_control_intent(message: str) -> Optional[ControlIntent]:
    return agent_runtime.parse_control_intent(message)
