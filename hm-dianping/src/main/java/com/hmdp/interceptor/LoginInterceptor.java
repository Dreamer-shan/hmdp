package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * @author hongyuan.shan
 * @date 2023/02/11 17:55
 * @description 拦截器链,第二个拦截器,只需要判断有无用户
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截,threadLocal是否有用户
        if (Objects.isNull(UserHolder.getUser())){
            //需要拦截
            response.setStatus(401);
            return false;
        }
        //放行
        return true;
    }
}
