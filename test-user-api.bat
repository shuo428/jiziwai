@echo off
echo ================================
echo 用户表 API 测试脚本
echo ================================
echo.

set BASE_URL=http://localhost:8080/api/user

echo [1] 测试用户注册
curl -X POST %BASE_URL%/register ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"testuser\",\"password\":\"123456\"}"
echo.
echo.

echo [2] 测试用户登录
curl -X POST %BASE_URL%/login ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"testuser\",\"password\":\"123456\"}" > login_response.json
echo.
echo.

echo [3] 从响应中提取 token（需要手动复制）
type login_response.json
echo.
echo.

echo ================================
echo 测试完成！
echo 请手动复制 token，然后测试其他接口
echo ================================
pause
