package springbootjni.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // Sa-Token 的接口拦截校验器。
            // 这里只保护后端 API 和 WebSocket 握手，不拦截 React 前端页面和静态资源。
            // 否则发布包访问 http://localhost:8080/ 时，index.html 会在进入前端前被误判为未登录，
            // 浏览器只能看到 {"code":401,"message":"未授权","data":null}。
            StpUtil.checkLogin();
        }))
                .addPathPatterns("/api/**", "/ws/**")
                .excludePathPatterns("/api/user/login", "/api/user/register");
    }
}
