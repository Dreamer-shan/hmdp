package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Objects;

/**
 * @author hongyuan.shan
 * @date 2023/02/11 17:55
 * @description
 */
public class LoginInterceptor implements HandlerInterceptor {
    //controller执行之前拦截,每次请求之前校验session,后面就不用在业务中校验登录状态了
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取session
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if (Objects.isNull(user)) {
            response.setStatus(401);
            return false;
        }
        //拦截到了请求，把用户信息放到ThreadLocal,在后面的controller就可以拿到了
        // UserHolder.saveUser((User) user);

        UserHolder.saveUser((UserDTO) user);
        //放行
        return true;
    }

    //业务执行完毕,销毁信息,避免内存泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
