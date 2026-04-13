import { z } from "zod";
import apiClient from "../api/client";
import type { UserInfo } from "../type/entity";

export const LoginScheam = z.object({
    username: z.string().min(3).max(32),
    password: z.string().min(6).max(32),
});

export type LoginData = z.infer<typeof LoginScheam>;

export type LoginResponse = {
    userInfo: UserInfo,
    token: string,
}

export const login = async (data: LoginData) => {
    const response: LoginResponse = await apiClient.post('/user/login', data);
    return response;
};

export const RegisterSchema = z.object({
    username: z.string().min(3, '用户名至少3个字符').max(32, '用户名最多32个字符'),
    password: z.string().min(6, '密码至少6个字符').max(32, '密码最多32个字符'),
    confirmPassword: z.string(),
}).refine((data) => data.password === data.confirmPassword, {
    message: "两次密码输入不一致",
    path: ["confirmPassword"],
});

export type RegisterData = z.infer<typeof RegisterSchema>;

export const register = async (data: { username: string; password: string }) => {
    const response = await apiClient.post('/user/register', data);
    return response;
};
