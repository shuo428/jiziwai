from typing import Any, Optional

from agents import parse_control_intent, run_agent_reply
from tools import reset_auth_token, set_auth_token


class ChatService:
    """负责对话编排，桥接 HTTP 层与 ADK 运行时。"""

    async def handle_chat(self, message: str, session_id: str, authorization: str) -> dict[str, Any]:
        """处理一次聊天请求。

        这个方法位于服务层，职责是：
        - 调用意图解析
        - 注入鉴权 token 到工具上下文
        - 根据意图调整送给 ADK 的提示词
        - 调用 ADK 生成最终回复

        Args:
            message: 用户原始输入。
            session_id: 当前会话 ID。
            authorization: HTTP 层透传下来的鉴权 token。

        Returns:
            dict[str, Any]:
            - `reply: str`：ADK 返回的最终回复
            - `intent: dict | None`：解析到的结构化意图；未命中时为 `None`
        """
        intent = parse_control_intent(message)
        token_marker = set_auth_token(authorization)
        try:
            prompt = message.strip()
            if intent:
                intent_type = intent.get("type")
                if intent_type == "connect":
                    prompt = (
                        "用户希望连接 SpectraBridge 设备。"
                        "前端将使用解析出的 host、controlPort 和 imagePort 建立连接。"
                        "请给出一句简短确认，并提醒连接成功后才能发送设备命令。"
                    )
                elif intent_type == "disconnect":
                    prompt = (
                        "用户希望断开 SpectraBridge 设备连接。"
                        "前端将执行断开连接。请给出一句简短确认。"
                    )
                elif intent_type == "trigger_once":
                    prompt = (
                        "用户希望获取一帧光谱图像。"
                        "前端将发送单帧触发命令，图像通过回调显示，历史帧写入 localStorage。"
                        "请给出一句简短确认。"
                    )
                elif intent_type == "query_status":
                    prompt = (
                        "用户希望查询 FPGA 状态。"
                        "前端将发送状态查询命令，并把回调中的原始状态位显示为二进制。"
                        "请给出一句简短确认。"
                    )
                elif intent_type == "reset":
                    prompt = (
                        "用户希望发送 FPGA 复位命令。"
                        "前端将执行复位命令。请给出一句简短确认。"
                    )
                elif intent_type == "send_full_config":
                    prompt = (
                        "用户希望发送 512 字节完整配置。"
                        "前端将使用当前页面中维护的 512 个配置字节下发。"
                        "请给出一句简短确认。"
                    )

            reply = await run_agent_reply(prompt, session_id)
            return {
                "reply": reply,
                "intent": intent,
            }
        finally:
            reset_auth_token(token_marker)


chat_service = ChatService()
