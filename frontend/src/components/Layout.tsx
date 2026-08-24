import React, { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, Outlet } from 'react-router-dom';
import { Layout as AntLayout, Menu, ConfigProvider, Button, Dropdown, Segmented, Tag } from 'antd';
import type { MenuProps } from 'antd';
import { Home, Cpu, Radio, LogOut, User, MessageSquare, ChevronLeft, ChevronRight, Database, Settings2, Layers } from 'lucide-react';
import { useUserStore } from '../store/userStore';
import { useJNIStore, type WorkMode } from '../store/jniStore';
import { toast } from 'sonner';

const { Sider, Content, Header } = AntLayout;

const LayoutContent: React.FC = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const { userInfo, actions } = useUserStore();
    const { workMode, actions: jniActions } = useJNIStore();
    const [collapsed, setCollapsed] = useState(false);

    const routeMode = useMemo<WorkMode | null>(() => {
        if (['/hdr-capture', '/hdr-management', '/hdr-calibration', '/hdr-dark-capture'].includes(location.pathname)) {
            return 'HDR';
        }
        if (['/spectral-data', '/spectral-management', '/calibration'].includes(location.pathname)) {
            return 'NORMAL';
        }
        return null;
    }, [location.pathname]);

    useEffect(() => {
        if (routeMode && routeMode !== workMode) {
            jniActions.setWorkMode(routeMode);
        }
    }, [jniActions, routeMode, workMode]);

    const handleLogout = () => {
        actions.setIsLoggingOut(true);
        actions.clearUserInfoAndToken();
        navigate('/login');
        setTimeout(() => {
            actions.setIsLoggingOut(false);
            toast.success('退出登录成功');
        }, 100);
    };

    const normalMenuItems: MenuProps['items'] = [
        {
            key: '/dashboard',
            icon: <Home size={18} />,
            label: <Link to="/dashboard">设备总览</Link>,
        },
        {
            key: '/spectral-data',
            icon: <Radio size={18} />,
            label: <Link to="/spectral-data">普通图像采集</Link>,
        },
        {
            key: '/calibration',
            icon: <Settings2 size={18} />,
            label: <Link to="/calibration">普通校准与缺陷地图</Link>,
        },
        {
            key: '/spectral-management',
            icon: <Database size={18} />,
            label: <Link to="/spectral-management">普通图像管理</Link>,
        },
    ];

    const hdrMenuItems: MenuProps['items'] = [
        {
            key: '/dashboard',
            icon: <Home size={18} />,
            label: <Link to="/dashboard">设备总览</Link>,
        },
        {
            key: '/hdr-capture',
            icon: <Layers size={18} />,
            label: <Link to="/hdr-capture">HDR图像采集</Link>,
        },
        {
            key: '/hdr-calibration',
            icon: <Settings2 size={18} />,
            label: <Link to="/hdr-calibration">HDR校准与缺陷地图</Link>,
        },
        {
            key: '/hdr-management',
            icon: <Database size={18} />,
            label: <Link to="/hdr-management">HDR图像管理</Link>,
        },
    ];

    const commonMenuItems: MenuProps['items'] = [
        {
            key: '/config-management',
            icon: <Cpu size={18} />,
            label: <Link to="/config-management">配置管理</Link>,
        },
        {
            key: '/chat',
            icon: <MessageSquare size={18} />,
            label: <Link to="/chat">AI 助手</Link>,
        },
    ];

    const menuItems: MenuProps['items'] = [
        ...(workMode === 'HDR' ? hdrMenuItems : normalMenuItems),
        ...commonMenuItems,
    ];

    const titleMap: Record<string, string> = {
        '/dashboard': '设备总览',
        '/spectral-data': '普通图像采集',
        '/hdr-capture': 'HDR图像采集',
        '/hdr-calibration': 'HDR校准与缺陷地图',
        '/hdr-management': 'HDR图像管理',
        '/hdr-dark-capture': 'HDR暗场采集',
        '/config-management': '配置管理',
        '/spectral-management': '普通图像管理',
        '/calibration': '普通校准与缺陷地图',
        '/profile': '个人信息',
        '/chat': 'AI 助手',
    };

    const selectedMenuKey = useMemo(() => {
        if (location.pathname === '/hdr-dark-capture') {
            return '/hdr-calibration';
        }
        return location.pathname;
    }, [location.pathname]);

    const handleModeChange = (nextMode: WorkMode) => {
        if (nextMode === workMode) {
            return;
        }
        jniActions.setWorkMode(nextMode);
        const counterpartMap: Record<string, string> = {
            '/spectral-data': '/hdr-capture',
            '/calibration': '/hdr-calibration',
            '/spectral-management': '/hdr-management',
            '/hdr-capture': '/spectral-data',
            '/hdr-calibration': '/calibration',
            '/hdr-management': '/spectral-management',
            '/hdr-dark-capture': '/calibration',
        };
        const nextPath = counterpartMap[location.pathname] ?? location.pathname;
        if (nextPath !== location.pathname) {
            navigate(nextPath, { replace: true });
        }
    };

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
                        {!collapsed && (
                            <div className="mx-4 mb-4 rounded-xl border border-slate-200 bg-slate-50 p-3">
                                <div className="mb-2 flex items-center justify-between gap-2">
                                    <span className="text-xs font-medium text-slate-500">工作模式</span>
                                    <Tag color={workMode === 'HDR' ? 'purple' : 'blue'} className="m-0">
                                        {workMode === 'HDR' ? 'HDR' : '普通'}
                                    </Tag>
                                </div>
                                <Segmented
                                    block
                                    size="small"
                                    value={workMode}
                                    options={[
                                        { label: '普通', value: 'NORMAL' },
                                        { label: 'HDR', value: 'HDR' },
                                    ]}
                                    onChange={(value) => handleModeChange(value as WorkMode)}
                                />
                            </div>
                        )}
                        <Menu
                            mode="inline"
                            selectedKeys={[selectedMenuKey]}
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
                            {titleMap[location.pathname] ?? 'AI 助手'}
                        </h1>
                        <p className="text-xs text-slate-500 m-0">
                            {workMode === 'HDR' ? 'HDR双增益工作流' : '普通单帧工作流'} · 物理实验数据处理系统
                        </p>
                    </div>

                    <div className="flex items-center gap-3">
                        <Segmented
                            size="small"
                            value={workMode}
                            options={[
                                { label: '普通模式', value: 'NORMAL' },
                                { label: 'HDR模式', value: 'HDR' },
                            ]}
                            onChange={(value) => handleModeChange(value as WorkMode)}
                        />
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
                    </div>
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
