import React, { useState } from 'react';
import { Link, useLocation, useNavigate, Outlet } from 'react-router-dom';
import { Layout as AntLayout, Menu, ConfigProvider, Button, Dropdown } from 'antd';
import type { MenuProps } from 'antd';
import { Home, Cpu, Radio, LogOut, User, MessageSquare, ChevronLeft, ChevronRight, Database } from 'lucide-react';
import { useUserStore } from '../store/userStore';
import { toast } from 'sonner';

const { Sider, Content, Header } = AntLayout;

const LayoutContent: React.FC = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const { userInfo, actions } = useUserStore();
    const [collapsed, setCollapsed] = useState(false);

    const handleLogout = () => {
        actions.setIsLoggingOut(true);
        actions.clearUserInfoAndToken();
        navigate('/login');
        setTimeout(() => {
            actions.setIsLoggingOut(false);
            toast.success('退出登录成功');
        }, 100);
    };

    const menuItems: MenuProps['items'] = [
        {
            key: '/dashboard',
            icon: <Home size={18} />,
            label: <Link to="/dashboard">仪表盘</Link>,
        },
        {
            key: '/spectral-data',
            icon: <Radio size={18} />,
            label: <Link to="/spectral-data">获取光谱数据</Link>,
        },
        {
            key: '/spectral-management',
            icon: <Database size={18} />,
            label: <Link to="/spectral-management">光谱数据管理</Link>,
        },
        {
            key: '/chat',
            icon: <MessageSquare size={18} />,
            label: <Link to="/chat">AI 助手</Link>,
        },
    ];

    const userMenuItems: MenuProps['items'] = [
        {
            key: 'user-info',
            label: <span className="text-slate-600">{userInfo?.username || '用户'}</span>,
            disabled: true,
        },
        {
            type: 'divider',
        },
        {
            key: 'profile',
            icon: <User size={16} />,
            label: <Link to="/profile">个人信息</Link>,
        },
        {
            key: 'logout',
            icon: <LogOut size={16} />,
            label: '退出登录',
            onClick: handleLogout,
            danger: true,
        },
    ];

    return (
        <AntLayout className="min-h-screen">
            <Sider
                collapsible
                collapsed={collapsed}
                onCollapse={setCollapsed}
                width={240}
                className="bg-white border-r border-gray-200 shadow-sm"
                trigger={null}
            >
                <div className="h-full flex flex-col">
                    <div className="h-16 flex items-center justify-between px-4 border-b border-gray-200">
                        {!collapsed ? (
                            <div className="flex items-center gap-2">
                                <div className="bg-blue-500 p-1.5 rounded">
                                    <Cpu size={20} className="text-white" />
                                </div>
                                <span className="text-slate-800 font-semibold text-base">Lab System</span>
                            </div>
                        ) : (
                            <div className="bg-blue-500 p-1.5 rounded mx-auto">
                                <Cpu size={20} className="text-white" />
                            </div>
                        )}
                    </div>

                    <div className="flex-1 py-4">
                        <Menu
                            mode="inline"
                            selectedKeys={[location.pathname]}
                            items={menuItems}
                            className="bg-transparent border-0"
                            inlineCollapsed={collapsed}
                        />
                    </div>

                    <div className="border-t border-gray-200 p-2">
                        <Button
                            type="text"
                            icon={collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
                            onClick={() => setCollapsed(!collapsed)}
                            className="w-full text-slate-500 hover:text-slate-800 hover:bg-gray-100"
                        />
                    </div>
                </div>
            </Sider>

            <AntLayout>
                <Header className="bg-white border-b border-gray-200 px-6 flex items-center justify-between h-16 shadow-sm">
                    <div>
                        <h1 className="text-lg font-semibold text-slate-800 m-0">
                            {location.pathname === '/dashboard'
                                ? '仪表盘'
                                : location.pathname === '/spectral-data'
                                  ? '获取光谱数据'
                                  : location.pathname === '/spectral-management'
                                    ? '光谱数据管理'
                                    : location.pathname === '/profile'
                                      ? '个人信息'
                                      : 'AI 助手'}
                        </h1>
                        <p className="text-xs text-slate-500 m-0">物理实验数据处理系统</p>
                    </div>

                    <Dropdown menu={{ items: userMenuItems }} placement="bottomRight" trigger={['click']}>
                        <Button
                            type="text"
                            className="flex items-center gap-2 h-10 px-3 hover:bg-gray-100 rounded-lg transition-colors"
                        >
                            <div className="w-8 h-8 rounded-full bg-blue-500 flex items-center justify-center">
                                <User size={16} className="text-white" />
                            </div>
                            <span className="text-slate-700 font-medium text-sm">{userInfo?.username || '用户'}</span>
                        </Button>
                    </Dropdown>
                </Header>

                <Content className="m-6 bg-gray-50 rounded-lg">
                    <div className="min-h-[calc(100vh-120px)]">
                        <Outlet />
                    </div>
                </Content>
            </AntLayout>
        </AntLayout>
    );
};

const Layout: React.FC = () => {
    return (
        <ConfigProvider
            theme={{
                token: {
                    colorPrimary: '#3b82f6',
                    borderRadius: 6,
                    fontFamily: 'Inter, sans-serif',
                },
                components: {
                    Menu: {
                        itemBg: 'transparent',
                        itemColor: '#64748b',
                        itemSelectedBg: '#eff6ff',
                        itemSelectedColor: '#3b82f6',
                        itemHoverBg: '#f8fafc',
                        itemHoverColor: '#1e293b',
                        itemBorderRadius: 6,
                        itemMarginInline: 8,
                    },
                    Card: {
                        colorBgContainer: '#ffffff',
                    },
                    Layout: {
                        siderBg: '#ffffff',
                    },
                },
            }}
        >
            <LayoutContent />
        </ConfigProvider>
    );
};

export default Layout;
