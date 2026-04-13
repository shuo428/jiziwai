import apiClient from "../api/client";
import type { UserInfo } from "../type/entity";

export const getUserInfo = async (): Promise<UserInfo> => {
    const response: UserInfo = await apiClient.get('/user/info');
    return response;
};

export const changePassword = async (data: { oldPassword: string; newPassword: string }) => {
    const response: string = await apiClient.post('/user/changePassword', data);
    return response;
};
