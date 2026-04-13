import axios, { type AxiosError, type AxiosResponse } from "axios";
import type { Result } from '../type/api';
import { toast } from "sonner";
import { useUserStore } from "../store/userStore";

const apiClient = axios.create({
    baseURL: '/api',
    headers: {
        'Content-Type': 'application/json',
    },
});

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
        throw new Error(message || "sys.api.apiRequestFailed");
    },
    (error: AxiosError<Result>) => {
        const { response, message } = error || {};
        const errMsg = response?.data?.message || message || "sys.api.errorMessage";
        toast.error(errMsg, { position: "top-center" });
        if (response?.status === 401) {
            // userStore.getState().actions.clearUserInfoAndToken();
        }
        return Promise.reject(error);
    },
);

export default apiClient;
