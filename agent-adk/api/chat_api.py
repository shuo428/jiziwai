import uuid
from typing import Any, Optional

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from services.chat_service import chat_service

app = FastAPI(title="Jiziwai ADK Agent")


class ChatRequest(BaseModel):
    """聊天请求模型。

    字段说明：
    - `message`: 用户发送给 AI 助手的原始文本。
    - `sessionId`: 前端维护的会话 ID；为空时服务端会自动生成。
    """

    message: str
    sessionId: Optional[str] = None


class ChatResponse(BaseModel):
    """聊天响应模型。

    字段说明：
    - `sessionId`: 当前对话使用的会话 ID。
    - `reply`: AI 助手生成的自然语言回复。
    - `intent`: 解析出的结构化控制意图；如果只是普通问答则为 `None`。
    """

    sessionId: str
    reply: str
    intent: Optional[dict[str, Any]] = None


@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, authorization: Optional[str] = Header(default=None)) -> ChatResponse:
    """聊天接口。

    Args:
        req: 请求体，包含用户消息和可选会话 ID。
        authorization: HTTP 请求头中的鉴权 token，会透传给工具层访问后端接口。

    Returns:
        ChatResponse: 包含会话 ID、AI 回复以及可选结构化意图。

    Raises:
        HTTPException: 当 `message` 为空时抛出 400。
    """
    if not req.message or not req.message.strip():
        raise HTTPException(status_code=400, detail="message is required")

    session_id = req.sessionId or str(uuid.uuid4())
    result = await chat_service.handle_chat(
        message=req.message,
        session_id=session_id,
        authorization=authorization or "",
    )
    return ChatResponse(
        sessionId=session_id,
        reply=result["reply"],
        intent=result["intent"],
    )
