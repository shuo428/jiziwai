import axios from "axios";

export type AgentIntent =
    | {
          type: "connect";
          host: string;
          controlPort: number;
          imagePort: number;
          verifyCrc?: boolean;
      }
    | {
          type: "disconnect";
      }
    | {
          type: "trigger_once";
      }
    | {
          type: "query_status";
      }
    | {
          type: "reset";
      }
    | {
          type: "send_full_config";
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
