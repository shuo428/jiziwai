import os
from functools import lru_cache
from urllib.parse import urlparse

from dotenv import load_dotenv
from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings

load_dotenv(override=False)


class Settings(BaseSettings):
    """应用配置。

    这个类统一描述 ADK 服务运行所需的所有配置项，包括：
    - Agent 基础信息
    - Gemini 模型与 API Key
    - 后端 HTTP / WebSocket 地址
    - FastAPI 服务启动参数
    - 采集请求超时时间

    配置来源：
    - `.env`
    - 系统环境变量

    字段命名规则：
    - 类内部使用 Python 风格字段名，例如 `model_name`
    - 外部环境变量使用兼容官方示例的名字，例如 `GEMINI_MODEL`
    """

    app_name: str = Field(default="jiziwai_ai_assistant", validation_alias="ADK_APP_NAME")
    user_id: str = Field(default="web_user", validation_alias="ADK_USER_ID")
    model_provider: str = Field(
        default="gemini",
        validation_alias=AliasChoices("MODEL_PROVIDER", "LLM_PROVIDER"),
    )
    gemini_model_name: str = Field(default="gemini-2.5-flash-lite", validation_alias="GEMINI_MODEL")
    gemini_api_key: str = Field(
        default="",
        validation_alias=AliasChoices("GOOGLE_API_KEY", "GEMINI_API_KEY"),
    )
    deepseek_model_name: str = Field(default="deepseek-chat", validation_alias="DEEPSEEK_MODEL")
    deepseek_api_key: str = Field(default="", validation_alias="DEEPSEEK_API_KEY")
    deepseek_base_url: str = Field(default="https://api.deepseek.com", validation_alias="DEEPSEEK_BASE_URL")
    openai_model_name: str = Field(default="gpt-4o-mini", validation_alias="OPENAI_MODEL")
    openai_api_key: str = Field(default="", validation_alias="OPENAI_API_KEY")
    openai_base_url: str = Field(default="https://api.openai.com/v1", validation_alias="OPENAI_BASE_URL")

    backend_base_url: str = Field(default="http://127.0.0.1:8080", validation_alias="BACKEND_BASE_URL")
    backend_ws_url: str = Field(default="ws://127.0.0.1:8080/ws", validation_alias="BACKEND_WS_URL")

    agent_host: str = Field(default="0.0.0.0", validation_alias="AGENT_HOST")
    agent_port: int = Field(default=8001, ge=1, le=65535, validation_alias="AGENT_PORT")

    capture_request_timeout_seconds: int = Field(
        default=10,
        ge=1,
        le=120,
        validation_alias="CAPTURE_REQUEST_TIMEOUT_SECONDS",
    )

    class Config:
        env_file = ".env"
        case_sensitive = False
        extra = "ignore"

    @field_validator("backend_base_url")
    @classmethod
    def validate_backend_base_url(cls, value: str) -> str:
        """校验后端 HTTP 基地址是否合法。"""
        parsed = urlparse(value)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("BACKEND_BASE_URL must be a valid http/https URL")
        return value

    @field_validator("backend_ws_url")
    @classmethod
    def validate_backend_ws_url(cls, value: str) -> str:
        """校验后端 WebSocket 地址是否合法。"""
        parsed = urlparse(value)
        if parsed.scheme not in ("ws", "wss") or not parsed.netloc:
            raise ValueError("BACKEND_WS_URL must be a valid ws/wss URL")
        return value

    @field_validator("deepseek_base_url", "openai_base_url")
    @classmethod
    def validate_provider_base_urls(cls, value: str) -> str:
        """校验第三方模型服务 HTTP 基地址是否合法。"""
        parsed = urlparse(value)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("provider base URL must be a valid http/https URL")
        return value

    @field_validator("model_provider")
    @classmethod
    def validate_model_provider(cls, value: str) -> str:
        """校验模型提供商标识是否合法。"""
        normalized = value.strip().lower()
        if normalized not in ("gemini", "deepseek", "openai"):
            raise ValueError("MODEL_PROVIDER must be one of: gemini, deepseek, openai")
        return normalized

    def apply_runtime_environment(self) -> None:
        """将第三方 SDK 依赖的配置写入进程环境。

        当前主要用于 Gemini/Google SDK 的 API Key 注入。
        这样即使下游 SDK 只识别某一个固定环境变量名，
        也能从统一配置类中拿到相同的值。
        """
        if self.gemini_api_key:
            os.environ["GOOGLE_API_KEY"] = self.gemini_api_key
            os.environ["GEMINI_API_KEY"] = self.gemini_api_key
        if self.deepseek_api_key:
            os.environ["DEEPSEEK_API_KEY"] = self.deepseek_api_key
        if self.openai_api_key:
            os.environ["OPENAI_API_KEY"] = self.openai_api_key
        if self.openai_base_url:
            os.environ["OPENAI_API_BASE"] = self.openai_base_url

    def validate_required_settings(self) -> None:
        """校验运行 ADK 所必需的关键配置。

        Raises:
            ValueError: 当前所选提供商缺失必需配置时抛出。
        """
        if self.model_provider == "gemini" and not self.gemini_api_key.strip():
            raise ValueError("GOOGLE_API_KEY or GEMINI_API_KEY is required for provider=gemini")
        if self.model_provider == "deepseek" and not self.deepseek_api_key.strip():
            raise ValueError("DEEPSEEK_API_KEY is required for provider=deepseek")
        if self.model_provider == "openai" and not self.openai_api_key.strip():
            raise ValueError("OPENAI_API_KEY is required for provider=openai")

    def selected_model_name(self) -> str:
        """返回当前提供商对应的模型名。"""
        if self.model_provider == "gemini":
            return self.gemini_model_name
        if self.model_provider == "deepseek":
            return self.deepseek_model_name
        return self.openai_model_name


@lru_cache()
def get_settings() -> Settings:
    """获取应用配置单例。

    Returns:
        Settings: 已完成 `.env`/环境变量读取，并且已将关键配置同步到运行时环境的配置对象。
    """
    settings = Settings()
    settings.apply_runtime_environment()
    return settings
