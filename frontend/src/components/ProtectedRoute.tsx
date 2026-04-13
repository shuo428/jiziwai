import React, { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useUserStore } from '../store/userStore';
import { toast } from 'sonner';

interface ProtectedRouteProps {
    children: React.ReactNode;
}

const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ children }) => {
    const { token, isLoggingOut } = useUserStore();
    const location = useLocation();
    const isAuthenticated = !!token;

    useEffect(() => {
        // 只在真正的未授权访问时显示提示（排除主动退出登录的情况）
        if (!isAuthenticated && !isLoggingOut) {
            toast.error('请先登录', {
                description: '您需要登录才能访问此页面'
            });
        }
    }, [isAuthenticated, isLoggingOut]);

    if (!isAuthenticated) {
        return <Navigate to="/login" state={{ from: location }} replace />;
    }

    return <>{children}</>;
};

export default ProtectedRoute;
