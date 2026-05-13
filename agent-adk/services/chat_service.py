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
                if intent_type == "capture":
                    count = int(intent.get("count", 1))
                    prompt = (
                        f"用户希望采集 {count} 条光谱数据。"
                        "前端已经按原有流程执行按数量采集。"
                        "请给出一句简短确认，并提醒在“光谱数据管理”查看结果。"
                    )
                elif intent_type == "start_continuous_listener":
                    prompt = (
                        "用户希望启动持续监听光谱数据。"
                        "前端已经按原有流程启动持续监听，并会持续把接收到的数据存入原有数据列表。"
                        "请给出一句简短确认，并提醒用户后续可以要求停止监听。"
                    )
                elif intent_type == "stop_continuous_listener":
                    prompt = (
                        "用户希望停止持续监听光谱数据。"
                        "前端已经按原有流程停止持续监听。"
                        "请给出一句简短确认，并提醒在“光谱数据管理”查看已接收数据。"
                    )

            reply = await run_agent_reply(prompt, session_id)
            return {
                "reply": reply,
                "intent": intent,
            }
        finally:
            reset_auth_token(token_marker)


chat_service = ChatService()
