import type { UserInfo } from "../type/entity";
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { createJSONStorage } from "zustand/middleware";

type UserStore = {
    userInfo: UserInfo | undefined;
    token: string;
    isLoggingOut: boolean;
    actions: {
        setUserInfo: (userInfo: UserInfo) => void;
        setToken: (token: string) => void;
        clearUserInfoAndToken: () => void;
        setIsLoggingOut: (value: boolean) => void;
    }
}

export const useUserStore = create<UserStore>()(
    persist(
        (set) => ({
            userInfo: undefined,
            token: "",
            isLoggingOut: false,

            actions: {
                setUserInfo: (userInfo: UserInfo) => {
                    set({ userInfo });
                },
                setToken: (token: string) => {
                    set({ token });
                },
                clearUserInfoAndToken: () => {
                    set({ userInfo: undefined, token: "" });
                },
                setIsLoggingOut: (value: boolean) => {
                    set({ isLoggingOut: value });
                },
            }

        }),
        {
            name: "user-store",
            storage: createJSONStorage(() => localStorage),
            partialize: (state) => ({
                userInfo: state.userInfo,
                token: state.token,
            }),
        }
    )
);