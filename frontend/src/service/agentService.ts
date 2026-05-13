import axios from "axios";

export type AgentIntent =
    | {
          type: "capture";
          count: number;
      }
    | {
          type: "start_continuous_listener";
      }
    | {
          type: "stop_continuous_listener";
      };

export interface AgentChatResult {
    sessionId: string;
    reply: string;
    intent?: AgentIntent | null;
}

const agentClient = axios.create({
    baseURL: "/agent-api",
    headers: {
        "Content-Type": "application/json",
    },
});

export const agentApi = {
    chat: async (message: string, sessionId: string | null, token: string): Promise<AgentChatResult> => {
        const response = await agentClient.post<AgentChatResult>(
            "/chat",
            { message, sessionId },
            {
                headers: token ? { Authorization: token } : {},
            },
        );
        return response.data;
    },
};
