import React, { useState, useEffect } from 'react';
import { Card, Descriptions, Button, Modal, Form, Input, Typography, Tag, Divider } from 'antd';
import { User, Lock, Calendar, IdCard } from 'lucide-react';
import { getUserInfo, changePassword } from '../service/userService';
import { useUserStore } from '../store/userStore';
import { toast } from 'sonner';
import type { UserInfo } from '../type/entity';

const { Title, Text } = Typography;

const ProfilePage: React.FC = () => {
    const [userInfo, setUserInfo] = useState<UserInfo | null>(null);
    const [loading, setLoading] = useState(false);
    const [isPasswordModalVisible, setIsPasswordModalVisible] = useState(false);
    const [passwordForm] = Form.useForm();
    const { actions } = useUserStore();

    useEffect(() => {
        loadUserInfo();
    }, []);

    const loadUserInfo = async () => {
        setLoading(true);
        try {
            const data = await getUserInfo();
            setUserInfo(data);
            // 更新全局状态
            actions.setUserInfo(data);
        } catch (error: any) {
            toast.error('获取用户信息失败', {
                description: error.message
            });
        } finally {
            setLoading(false);
        }
    };

    const handleChangePassword = async (values: any) => {
        try {
            await changePassword({
                oldPassword: values.oldPassword,
                newPassword: values.newPassword
            });
            toast.success('密码修改成功', {
                description: '请使用新密码重新登录'
            });
            setIsPasswordModalVisible(false);
            passwordForm.resetFields();
            // 清除登录状态，跳转到登录页
            setTimeout(() => {
                actions.clearUserInfoAndToken();
                window.location.href = '/login';
            }, 1500);
        } catch (error: any) {
            toast.error('密码修改失败', {
                description: error.message || '请检查旧密码是否正确'
            });
        }
    };

    return (
        <div className="space-y-6 p-6">
            {/* 个人信息卡片 */}
            <Card className="bg-white border border-gray-200 shadow-sm" loading={loading}>
                <div className="flex items-center justify-between mb-6">
                    <div className="flex items-center gap-3">
                        <div className="w-16 h-16 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-lg">
                            <User size={32} className="text-white" />
                        </div>
                        <div>
                            <Title level={4} className="!mb-1 !text-slate-800">
                                {userInfo?.username || '加载中...'}
                            </Title>
                            <Text className="text-slate-500 text-sm">个人信息</Text>
                        </div>
                    </div>
                    <Tag color="blue" className="text-sm">活跃用户</Tag>
                </div>

                <Divider className="my-4" />

                <Descriptions column={1} labelStyle={{ fontWeight: 600, color: '#475569' }}>
                    <Descriptions.Item 
                        label={
                            <span className="flex items-center gap-2">
                                <IdCard size={16} className="text-slate-500" />
                                用户ID
                            </span>
                        }
                    >
                        <Text className="text-slate-700">{userInfo?.id || '-'}</Text>
                    </Descriptions.Item>
                    <Descriptions.Item 
                        label={
                            <span className="flex items-center gap-2">
                                <User size={16} className="text-slate-500" />
                                用户名
                            </span>
                        }
                    >
                        <Text className="text-slate-700">{userInfo?.username || '-'}</Text>
                    </Descriptions.Item>
                    <Descriptions.Item 
                        label={
                            <span className="flex items-center gap-2">
                                <Calendar size={16} className="text-slate-500" />
                                注册时间
                            </span>
                        }
                    >
                        <Text className="text-slate-700">
                            {userInfo?.createTime ? new Date(userInfo.createTime).toLocaleString('zh-CN') : '-'}
                        </Text>
                    </Descriptions.Item>
                </Descriptions>
            </Card>

            {/* 账号安全 */}
            <Card 
                className="bg-white border border-gray-200 shadow-sm"
                title={
                    <div className="flex items-center gap-2">
                        <Lock size={18} className="text-slate-600" />
                        <span className="text-slate-800">账号安全</span>
                    </div>
                }
            >
                <div className="space-y-4">
                    <div className="flex items-center justify-between p-4 bg-gray-50 rounded-lg border border-gray-200">
                        <div>
                            <Text strong className="text-slate-800 block mb-1">登录密码</Text>
                            <Text className="text-slate-500 text-sm">定期更换密码可以提高账号安全性</Text>
                        </div>
                        <Button 
                            type="primary" 
                            icon={<Lock size={16} />}
                            onClick={() => setIsPasswordModalVisible(true)}
                        >
                            修改密码
                        </Button>
                    </div>
                </div>
            </Card>

            {/* 修改密码模态框 */}
            <Modal
                title={
                    <div className="flex items-center gap-2">
                        <Lock size={18} className="text-blue-600" />
                        <span>修改密码</span>
                    </div>
                }
                open={isPasswordModalVisible}
                onCancel={() => {
                    setIsPasswordModalVisible(false);
                    passwordForm.resetFields();
                }}
                footer={null}
                width={480}
            >
                <Form
                    form={passwordForm}
                    layout="vertical"
                    onFinish={handleChangePassword}
                    className="mt-4"
                >
                    <Form.Item
                        label={<span className="text-slate-700 font-semibold">旧密码</span>}
                        name="oldPassword"
                        rules={[
                            { required: true, message: '请输入旧密码' },
                            { min: 6, message: '密码至少6个字符' }
                        ]}
                    >
                        <Input.Password
                            prefix={<Lock className="text-slate-400" size={18} />}
                            placeholder="请输入旧密码"
                            className="h-11 rounded-lg"
                        />
                    </Form.Item>

                    <Form.Item
                        label={<span className="text-slate-700 font-semibold">新密码</span>}
                        name="newPassword"
                        rules={[
                            { required: true, message: '请输入新密码' },
                            { min: 6, message: '密码至少6个字符' },
                            { max: 32, message: '密码最多32个字符' }
                        ]}
                    >
                        <Input.Password
                            prefix={<Lock className="text-slate-400" size={18} />}
                            placeholder="请输入新密码（6-32个字符）"
                            className="h-11 rounded-lg"
                        />
                    </Form.Item>

                    <Form.Item
                        label={<span className="text-slate-700 font-semibold">确认新密码</span>}
                        name="confirmPassword"
                        dependencies={['newPassword']}
                        rules={[
                            { required: true, message: '请确认新密码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value || getFieldValue('newPassword') === value) {
                                        return Promise.resolve();
                                    }
                                    return Promise.reject(new Error('两次密码输入不一致'));
                                },
                            }),
                        ]}
                    >
                        <Input.Password
                            prefix={<Lock className="text-slate-400" size={18} />}
                            placeholder="请再次输入新密码"
                            className="h-11 rounded-lg"
                        />
                    </Form.Item>

                    <Form.Item className="mb-0 mt-6">
                        <div className="flex gap-3">
                            <Button
                                type="primary"
                                htmlType="submit"
                                block
                                className="h-10 rounded-lg"
                            >
                                确认修改
                            </Button>
                            <Button
                                onClick={() => {
                                    setIsPasswordModalVisible(false);
                                    passwordForm.resetFields();
                                }}
                                block
                                className="h-10 rounded-lg"
                            >
                                取消
                            </Button>
                        </div>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default ProfilePage;
