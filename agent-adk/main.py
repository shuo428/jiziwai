import uvicorn

from api import app
from config import get_settings


def main() -> None:
    """启动 FastAPI 服务。

    启动参数来自统一配置类 `Settings`：
    - `agent_host`
    - `agent_port`
    """
    settings = get_settings()
    uvicorn.run(app, host=settings.agent_host, port=settings.agent_port)


if __name__ == "__main__":
    main()
