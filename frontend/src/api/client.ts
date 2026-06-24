import axios, { type AxiosError, type AxiosResponse } from "axios";
import type { Result } from '../type/api';
import { toast } from "sonner";
import { useUserStore } from "../store/userStore";

const AUTH_EXPIRED_CODES = new Set([401, 402]);
const AUTH_REDIRECT_MESSAGE_KEY = "auth-redirect-message";
let isRedirectingToLogin = false;

const apiClient = axios.create({
    baseURL: '/api',
    headers: {
        'Content-Type': 'application/json',
    },
});

const isAuthExpiredCode = (code?: number): boolean => (
    typeof code === "number" && AUTH_EXPIRED_CODES.has(code)
);

const redirectToLogin = (message?: string): void => {
    const { actions } = useUserStore.getState();
    actions.clearUserInfoAndToken();
    actions.setIsLoggingOut(false);

    if (typeof window === "undefined" || window.location.pathname === "/login" || isRedirectingToLogin) {
        return;
    }

    isRedirectingToLogin = true;
    const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    const loginUrl = `/login?redirect=${encodeURIComponent(currentPath)}`;
    window.sessionStorage.setItem(AUTH_REDIRECT_MESSAGE_KEY, message || "登录已过期，请重新登录");
    window.location.replace(loginUrl);
};

apiClient.interceptors.request.use(
    (config) => {
        const accessToken = useUserStore.getState().token;
        if (accessToken) {
            config.headers.Authorization = `${accessToken}`;
        }
        return config;
    },
    (error) => Promise.reject(error),
);


// Response interceptor for error handling
apiClient.interceptors.response.use(
    (res: AxiosResponse<Result<any>>) => {
        if (!res.data) throw new Error("sys.api.apiRequestFailed");
        const { code, data, message } = res.data;
        if (code === 200) {
            return data;
        }
        if (isAuthExpiredCode(code)) {
            redirectToLogin(message);
        }
        throw new Error(message || "sys.api.apiRequestFailed");
    },
    (error: AxiosError<Result>) => {
        const { response, message } = error || {};
        const errMsg = response?.data?.message || message || "sys.api.errorMessage";
        if (response?.status === 401 || isAuthExpiredCode(response?.data?.code)) {
            redirectToLogin(errMsg);
            return Promise.reject(error);
        }

        toast.error(errMsg, { position: "top-center" });
        return Promise.reject(error);
    },
);

export default apiClient;
