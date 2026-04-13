import React, { useState } from 'react';
import { Form, Input, Button, Card, Typography } from 'antd';
import { User, Lock, LogIn, Cpu } from 'lucide-react';
import { useNavigate, useLocation } from 'react-router-dom';
import { login } from '../service/authService';
import { toast } from 'sonner';
import { useUserStore } from '../store/userStore';

const { Title, Text } = Typography;

const Login: React.FC = () => {
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();
    const location = useLocation();
    const { actions } = useUserStore();

    // 获取从哪个页面跳转过来的，登录后跳回去
    const from = (location.state as any)?.from?.pathname || '/';

    const onFinish = async (values: any) => {
        setLoading(true);
        const formData = {
            username: values.username,
            password: values.password,
        };

        try {
            const response = await login(formData);
            toast.success("欢迎回来!", {
                description: "登录成功"
            });

            actions.setUserInfo(response.userInfo);
            actions.setToken(response.token);

            // 跳转到之前想访问的页面，或者首页
            navigate(from, { replace: true }  );
        } catch (error: any) {
            toast.error("登录失败");
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen flex bg-gradient-to-br from-slate-50 via-blue-50 to-slate-50">
            {/* 左侧装饰区域 */}
            <div className="hidden lg:flex lg:w-2/5 bg-gradient-to-br from-blue-100 via-blue-50 to-indigo-100 p-8 flex-col justify-center relative overflow-hidden">
                {/* 简洁装饰 */}
                <div className="absolute inset-0 opacity-30">
                    <div className="absolute top-10 right-10 w-40 h-40 bg-blue-200 rounded-full blur-3xl"></div>
                    <div className="absolute bottom-10 left-10 w-32 h-32 bg-indigo-200 rounded-full blur-3xl"></div>
                </div>
                
                <div className="relative z-10 max-w-md mx-auto">
                    <div className="flex items-center gap-2.5 mb-8">
                        <div className="bg-blue-500 p-2 rounded-lg shadow-md">
                            <Cpu size={24} className="text-white" />
                        </div>
                        <div>
                            <span className="text-slate-800 font-bold text-xl block">Lab System</span>
                            <span className="text-slate-600 text-xs">v2.0</span>
                        </div>
                    </div>
                    
                    <div className="space-y-4">
                        <h1 className="text-3xl font-bold text-slate-800 leading-tight">
                            智能实验数据处理平台
                        </h1>
                        <p className="text-slate-600 text-base leading-relaxed">
                            集成AI助手和JNI桥接控制的物理实验数据处理系统
                        </p>
                        <div className="grid grid-cols-3 gap-3 pt-2">
                            <div className="bg-white/80 backdrop-blur-sm border border-blue-200 rounded-lg p-3 text-center shadow-sm">
                                <div className="text-xl font-bold text-blue-600 mb-1">AI</div>
                                <div className="text-slate-600 text-xs">智能分析</div>
                            </div>
                            <div className="bg-white/80 backdrop-blur-sm border border-blue-200 rounded-lg p-3 text-center shadow-sm">
                                <div className="text-xl font-bold text-blue-600 mb-1">JNI</div>
                                <div className="text-slate-600 text-xs">FPGA控制</div>
                            </div>
                            <div className="bg-white/80 backdrop-blur-sm border border-blue-200 rounded-lg p-3 text-center shadow-sm">
                                <div className="text-xl font-bold text-blue-600 mb-1">RAG</div>
                                <div className="text-slate-600 text-xs">知识检索</div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* 右侧登录表单 */}
            <div className="flex-1 flex items-center justify-center p-8">
                <div className="w-full max-w-md">
                    <Card className="bg-white/80 backdrop-blur-xl border border-white/60 shadow-2xl rounded-2xl p-8">
                        <div className="text-center mb-8">
                            <Title level={2} className="!mb-2 !text-slate-800 !font-bold">欢迎回来</Title>
                            <Text className="text-slate-500 text-base">请登录以继续使用系统</Text>
                        </div>

                        <Form
                            name="login"
                            initialValues={{ remember: true }}
                            onFinish={onFinish}
                            layout="vertical"
                            size="large"
                        >
                            <Form.Item
                                label={<span className="text-slate-700 font-semibold text-sm">用户名</span>}
                                name="username"
                                rules={[{ required: true, message: '请输入用户名！' }]}
                            >
                                <Input
                                    prefix={<User className="text-slate-400" size={18} />}
                                    placeholder="请输入用户名"
                                    className="h-12 rounded-xl border-slate-200 hover:border-blue-400 focus:border-blue-500"
                                />
                            </Form.Item>

                            <Form.Item
                                label={<span className="text-slate-700 font-semibold text-sm">密码</span>}
                                name="password"
                                rules={[{ required: true, message: '请输入密码！' }]}
                            >
                                <Input.Password
                                    prefix={<Lock className="text-slate-400" size={18} />}
                                    placeholder="请输入密码"
                                    className="h-12 rounded-xl border-slate-200 hover:border-blue-400 focus:border-blue-500"
                                />
                            </Form.Item>

                            <Form.Item className="mb-6">
                                <Button
                                    type="primary"
                                    htmlType="submit"
                                    block
                                    loading={loading}
                                    className="h-12 rounded-xl text-base font-semibold bg-gradient-to-r from-blue-500 to-indigo-600 border-0 shadow-lg shadow-blue-500/40 hover:shadow-xl hover:shadow-blue-500/50 transition-all"
                                >
                                    {!loading && <LogIn size={20} />}
                                    登录
                                </Button>
                            </Form.Item>

                            <div className="text-center">
                                <Text className="text-slate-500 text-sm">
                                    还没有账号？
                                    <a href="/register" className="text-blue-600 hover:text-blue-700 font-medium ml-1">
                                        立即注册
                                    </a>
                                </Text>
                            </div>
                        </Form>
                    </Card>
                </div>
            </div>
        </div>
    );
};

export default Login;
