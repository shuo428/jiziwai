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
            // Sa-Token 的路由拦截校验器
            StpUtil.checkLogin(); // 校验是否登录
        }))
                .addPathPatterns("/**") // 拦截所有请求
                .excludePathPatterns("/api/user/login","/api/user/register"); // 排除接口
    }
}
