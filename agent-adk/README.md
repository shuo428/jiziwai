# ADK Agent Service

该目录提供项目 AI 助手的 Python 服务，使用 Google ADK 实现智能体。

## 目录结构

- `config.py`: 统一配置项（模型、后端地址、端口等）
- `main.py`: 服务启动入口
- `api/chat_api.py`: HTTP 接口层
- `services/chat_service.py`: 对话编排服务层
- `tools/spectral_data_tool.py`: 智能体工具（当前包含连接、断开、复位、单帧触发、状态查询和 512 字节配置下发）
- `agents/agent.py`: ADK 智能体运行时（Agent 初始化、意图解析、回复生成）

说明：当前设备控制使用 `SpectraBridgeNative + BridgeListener`，前端负责执行结构化意图并通过 WebSocket 接收回调结果。

## 1. 安装依赖

```bash
cd agent-adk
pip install -r requirements.txt
```

## 2. 配置环境变量

支持三个模型提供商：
- `gemini`
- `deepseek`
- `openai`

推荐通过 `MODEL_PROVIDER` 切换当前运行商。

Gemini 示例：

```bash
set MODEL_PROVIDER=gemini
set GOOGLE_API_KEY=你的GeminiKey
set GEMINI_MODEL=gemini-2.5-flash-lite
```

DeepSeek 示例：

```bash
set MODEL_PROVIDER=deepseek
set DEEPSEEK_API_KEY=你的DeepSeekKey
set DEEPSEEK_MODEL=deepseek-chat
set DEEPSEEK_BASE_URL=https://api.deepseek.com
```

OpenAI 示例：

```bash
set MODEL_PROVIDER=openai
set OPENAI_API_KEY=你的OpenAIKey
set OPENAI_MODEL=gpt-4o-mini
set OPENAI_BASE_URL=https://api.openai.com/v1
```

通用后端/服务配置：

```bash
set BACKEND_BASE_URL=http://127.0.0.1:8080
set BACKEND_WS_URL=ws://127.0.0.1:8080/ws
set AGENT_PORT=8001
```

说明：
- `gemini` 优先使用 `GOOGLE_API_KEY` 与 `GEMINI_MODEL`
- `deepseek` 使用 `DEEPSEEK_API_KEY`
- `openai` 使用 `OPENAI_API_KEY`
- `deepseek/openai` 依赖 `litellm`

## 3. 启动服务

```bash
python main.py
```

默认监听 `http://127.0.0.1:8001`，前端 AI 助手页面会请求 `POST /chat`。
