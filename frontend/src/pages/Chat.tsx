import React, { useEffect, useMemo, useRef, useState } from "react";
import { Button, Card, Input, Spin, Typography } from "antd";
import { Bot, Send, User } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";

import { agentApi } from "../service/agentService";
import { jniBridgeService } from "../service/jniBridgeService";
import { useJNIStore } from "../store/jniStore";
import { useUserStore } from "../store/userStore";

const { Title, Text } = Typography;

type MessageRole = "user" | "assistant";

interface ChatMessage {
    id: string;
    role: MessageRole;
    content: string;
    time: number;
}

interface ExamplePrompt {
    title: string;
    description: string;
    prompt: string;
}

const EXAMPLE_PROMPTS: ExamplePrompt[] = [
    {
        title: "连接设备",
        description: "从对话中提取 host 和两个端口",
        prompt: "连接设备 host 192.168.1.10，controlPort 9000，imagePort 9001。",
    },
    {
        title: "获取一帧",
        description: "连接后触发一次图像采集",
        prompt: "获取一帧图片，并告诉我历史图片保存在哪里。",
    },
    {
        title: "查询状态",
        description: "读取原始状态位",
        prompt: "查询一次 FPGA 状态，把状态位按二进制显示。",
    },
];

/**
 * 将时间戳格式化为 `HH:mm`，用于消息头展示。
 *
 * @param timestamp 消息生成时间戳
 * @returns string 用户可直接识别的小时分钟文本
 */
const formatMessageTime = (timestamp: number): string =>
    new Date(timestamp).toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
    });

const renderMarkdownContent = (content: string, role: MessageRole): React.ReactNode => (
    <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
            h1: ({ children }) => <h1 className="text-lg font-semibold leading-8">{children}</h1>,
            h2: ({ children }) => <h2 className="text-base font-semibold leading-8">{children}</h2>,
            h3: ({ children }) => (
                <h3 className="text-sm font-semibold uppercase tracking-[0.12em] opacity-80">{children}</h3>
            ),
            p: ({ children }) => <p className="text-sm leading-7">{children}</p>,
            ul: ({ children }) => <ul className="list-disc space-y-2 pl-5 text-sm leading-7 marker:text-current">{children}</ul>,
            ol: ({ children }) => <ol className="list-decimal space-y-2 pl-5 text-sm leading-7 marker:text-current">{children}</ol>,
            li: ({ children }) => <li className="ml-1">{children}</li>,
            a: ({ href, children }) => (
                <a
                    href={href}
                    target="_blank"
                    rel="noreferrer"
                    className={role === "user" ? "underline underline-offset-2 text-white" : "text-sky-600 underline underline-offset-2"}
                >
                    {children}
                </a>
            ),
            table: ({ children }) => (
                <div className="overflow-x-auto rounded-xl border border-slate-200">
                    <table className="min-w-full border-collapse text-left text-sm">{children}</table>
                </div>
            ),
            thead: ({ children }) => (
                <thead className={role === "user" ? "bg-white/10" : "bg-slate-50"}>{children}</thead>
            ),
            th: ({ children }) => <th className="border-b border-slate-200 px-3 py-2 font-semibold">{children}</th>,
            td: ({ children }) => <td className="border-b border-slate-100 px-3 py-2 align-top last:border-b-0">{children}</td>,
            code: ({ inline, className, children }) => {
                if (inline) {
                    return (
                        <code
                            className={
                                role === "user"
                                    ? "rounded bg-white/14 px-1.5 py-0.5 font-mono text-[0.84em] text-white"
                                    : "rounded bg-slate-200/70 px-1.5 py-0.5 font-mono text-[0.84em] text-slate-700"
                            }
                        >
                            {children}
                        </code>
                    );
                }

                const language = className?.replace("language-", "") || "";
                return (
                    <div
                        className={`overflow-hidden rounded-xl border ${
                            role === "user"
                                ? "border-blue-300/60 bg-blue-600/40"
                                : "border-slate-200 bg-slate-950"
                        }`}
                    >
                        {language ? (
                            <div
                                className={`border-b px-3 py-2 text-[11px] uppercase tracking-[0.18em] ${
                                    role === "user"
                                        ? "border-blue-300/50 text-blue-100"
                                        : "border-slate-800 text-slate-400"
                                }`}
                            >
                                {language}
                            </div>
                        ) : null}
                        <pre
                            className={`overflow-x-auto px-4 py-3 text-[13px] leading-6 ${
                                role === "user" ? "text-white" : "text-slate-100"
                            }`}
                        >
                            <code>{children}</code>
                        </pre>
                    </div>
                );
            },
        }}
    >
        {content}
    </ReactMarkdown>
);

const ChatPage: React.FC = () => {
    const [messages, setMessages] = useState<ChatMessage[]>([]);
    const [input, setInput] = useState("");
    const [loading, setLoading] = useState(false);
    const [sessionId, setSessionId] = useState<string | null>(null);
    const token = useUserStore((state) => state.token);
    const configBytes = useJNIStore((state) => state.configBytes);
    const listRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        if (listRef.current) {
            listRef.current.scrollTop = listRef.current.scrollHeight;
        }
    }, [messages, loading]);

    const canSend = useMemo(() => input.trim().length > 0 && !loading, [input, loading]);

    const pushMessage = (role: MessageRole, content: string) => {
        setMessages((prev) => [
            ...prev,
            {
                id: `${role}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
                role,
                content,
                time: Date.now(),
            },
        ]);
    };

    const handleSend = async () => {
        const text = input.trim();
        if (!text || loading) {
            return;
        }

        setInput("");
        pushMessage("user", text);
        setLoading(true);

        try {
            const result = await agentApi.chat(text, sessionId, token);
            setSessionId(result.sessionId);
            pushMessage("assistant", result.reply);

            if (result.intent?.type === "connect") {
                const state = await jniBridgeService.connect({
                    host: result.intent.host,
                    controlPort: result.intent.controlPort,
                    imagePort: result.intent.imagePort,
                    verifyCrc: result.intent.verifyCrc ?? true,
                });
                pushMessage(
                    "assistant",
                    [
                        "### 连接完成",
                        "",
                        `- Host：\`${state.host}\``,
                        `- Control Port：\`${state.controlPort}\``,
                        `- Image Port：\`${state.imagePort}\``,
                    ].join("\n"),
                );
            } else if (result.intent?.type === "disconnect") {
                await jniBridgeService.disconnect();
                pushMessage(
                    "assistant",
                    ["### 已断开连接", "", "- 当前状态：**未连接**"].join("\n"),
                );
            } else if (result.intent?.type === "trigger_once") {
                const frame = await jniBridgeService.triggerOnceAndWaitForFrame();
                pushMessage(
                    "assistant",
                    [
                        "### 已获取一帧图片",
                        "",
                        `- 尺寸：**${frame.width} x ${frame.height}**`,
                        "- 当前图片已显示在 **获取光谱数据** 页面",
                        "- 原始图片、预览图和完整性结果已保存到 **服务器与PostgreSQL**",
                    ].join("\n"),
                );
            } else if (result.intent?.type === "query_status") {
                const status = await jniBridgeService.queryStatusAndWait();
                pushMessage(
                    "assistant",
                    [
                        "### 状态查询完成",
                        "",
                        `- 状态位：\`${status.statusBinary}\``,
                        `- errorCode：\`${status.errorCode}\``,
                    ].join("\n"),
                );
            } else if (result.intent?.type === "reset") {
                await jniBridgeService.sendReset();
                pushMessage("assistant", ["### 复位命令已发送", "", "- 请等待设备侧回调或状态变化"].join("\n"));
            } else if (result.intent?.type === "send_full_config") {
                const ack = await jniBridgeService.sendFullConfigAndWait(configBytes);
                pushMessage(
                    "assistant",
                    [
                        "### 完整配置已发送",
                        "",
                        `- resultCode：\`${ack.resultCode}\``,
                        `- failedAddr：\`${ack.failedAddr}\``,
                    ].join("\n"),
                );
            }
        } catch (error: any) {
            const message = error?.response?.data?.detail || error?.message || "AI 助手调用失败";
            toast.error(message);
            pushMessage(
                "assistant",
                ["### 调用失败", "", `- 错误原因：\`${message}\``, "- 建议：检查后端服务、WebSocket 和鉴权状态后重试"].join(
                    "\n",
                ),
            );
        } finally {
            setLoading(false);
        }
    };

    const handleReset = () => {
        setSessionId(null);
        setMessages([]);
        toast.success("已重置对话");
    };

    return (
        <div className="space-y-6 p-6">
            <Card className="bg-white border border-gray-200 shadow-sm">
                <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="p-2.5 bg-indigo-500 rounded-lg">
                            <Bot size={22} className="text-white" />
                        </div>
                        <div>
                            <div className="flex items-center gap-2">
                                <Title level={4} className="!mb-0 !text-slate-800">
                                    AI 助手
                                </Title>
                            </div>
                        </div>
                    </div>
                    <Button onClick={handleReset}>重置会话</Button>
                </div>
            </Card>

            <Card className="bg-white border border-gray-200 shadow-sm">
                <div
                    ref={listRef}
                    className="h-[500px] overflow-y-auto rounded-2xl border border-slate-200 bg-[linear-gradient(180deg,#f8fbff_0%,#f6f8fb_100%)] p-4"
                >
                    {messages.length === 0 && !loading ? (
                        <div className="flex h-full flex-col justify-center">
                            <div className="mx-auto max-w-2xl text-center">
                                <Text className="text-slate-500">
                                    点击下面的示例可直接填入输入框，然后按回车或点击发送。
                                </Text>
                            </div>

                            <div className="mx-auto mt-8 grid w-full max-w-3xl gap-3 md:grid-cols-3">
                                {EXAMPLE_PROMPTS.map((example) => (
                                    <button
                                        key={example.title}
                                        type="button"
                                        onClick={() => setInput(example.prompt)}
                                        className="rounded-2xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:-translate-y-0.5 hover:border-indigo-300 hover:shadow-md"
                                    >
                                        <div className="text-sm font-semibold text-slate-800">{example.title}</div>
                                        <div className="mt-1 text-xs leading-6 text-slate-500">{example.description}</div>
                                        <div className="mt-3 rounded-xl bg-slate-50 px-3 py-2 text-xs leading-6 text-slate-600">
                                            {example.prompt}
                                        </div>
                                    </button>
                                ))}
                            </div>
                        </div>
                    ) : (
                        <div className="space-y-4">
                            {messages.map((msg) => (
                            <div
                                key={msg.id}
                                className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}
                            >
                                <div
                                    className={`max-w-[85%] rounded-2xl px-4 py-3 shadow-sm ${
                                        msg.role === "user"
                                            ? "bg-[linear-gradient(135deg,#2563eb_0%,#1d4ed8_100%)] text-white"
                                            : "border border-slate-200 bg-white text-slate-800"
                                    }`}
                                >
                                    <div className="mb-3 flex items-center justify-between gap-4">
                                        <div className="flex min-w-0 items-center gap-2 leading-none">
                                            <span
                                                className={`flex h-6 w-6 items-center justify-center rounded-full ${
                                                    msg.role === "user"
                                                        ? "bg-white/18 text-white"
                                                        : "bg-indigo-50 text-indigo-600"
                                                }`}
                                            >
                                                {msg.role === "user" ? <User size={13} /> : <Bot size={13} />}
                                            </span>
                                            <span className="text-xs font-medium opacity-85">
                                                {msg.role === "user" ? "你" : "AI 助手"}
                                            </span>
                                        </div>
                                        <span className="shrink-0 text-[11px] tabular-nums opacity-60">
                                            {formatMessageTime(msg.time)}
                                        </span>
                                    </div>
                                    <div className={msg.role === "user" ? "text-white" : "text-slate-700"}>
                                        {renderMarkdownContent(msg.content, msg.role)}
                                    </div>
                                </div>
                            </div>
                            ))}
                        </div>
                    )}

                    {loading && (
                        <div className="flex justify-start">
                            <div className="mt-4 rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm">
                                <div className="flex items-center gap-2 text-sm text-slate-600">
                                    <Spin size="small" />
                                    <span>AI 正在处理...</span>
                                </div>
                            </div>
                        </div>
                    )}
                </div>

                <div className="mt-4 flex flex-wrap gap-2">
                    {EXAMPLE_PROMPTS.map((example) => (
                        <Button key={example.title} onClick={() => setInput(example.prompt)}>
                            {example.title}
                        </Button>
                    ))}
                </div>

                <div className="mt-4 flex gap-3">
                            <Input.TextArea
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        autoSize={{ minRows: 2, maxRows: 4 }}
                        placeholder="输入你的问题，例如：连接设备 host 192.168.1.10 controlPort 9000 imagePort 9001，或获取一帧图片"
                        onPressEnter={(e) => {
                            if (!e.shiftKey) {
                                e.preventDefault();
                                handleSend();
                            }
                        }}
                    />
                    <Button
                        type="primary"
                        icon={<Send size={16} />}
                        disabled={!canSend}
                        onClick={handleSend}
                        className="h-auto min-w-24 rounded-xl px-5"
                    >
                        发送
                    </Button>
                </div>
                <div className="mt-2 text-xs text-slate-500">按 `Enter` 发送，按 `Shift + Enter` 换行。</div>
            </Card>
        </div>
    );
};

export default ChatPage;
