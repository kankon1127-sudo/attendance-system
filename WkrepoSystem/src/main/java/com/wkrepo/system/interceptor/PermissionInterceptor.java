package com.wkrepo.system.interceptor;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final String SESSION_USER = "user";

    // 需要管理员权限的路径
    private static final List<String> ADMIN_PATHS = Arrays.asList(
        "/default-attendance-setting",
        "/admin/database-backup"
    );

    // 需要部長・総務・管理员权限的路径（Controller 内还会按部门和角色做细分检查）
    private static final List<String> MANAGER_PATHS = Arrays.asList(
        "/user-management"
    );

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.sendRedirect("/login");
            return false;
        }

        User sessionUser = (User) session.getAttribute(SESSION_USER);
        if (sessionUser == null) {
            response.sendRedirect("/login");
            return false;
        }

        // 每次请求都从数据库重新读取：被停用或删除的账号立即失效，角色和部门的变更立即生效
        User user = sessionUser.getId() == null ? null
                : userRepository.findById(sessionUser.getId()).orElse(null);
        if (user == null || "INACTIVE".equals(user.getStatus())) {
            session.invalidate();
            response.sendRedirect("/login");
            return false;
        }
        session.setAttribute(SESSION_USER, user);

        String requestURI = request.getRequestURI();
        
        // 检查是否需要管理员权限
        if (requiresAdminPermission(requestURI)) {
            if (!"ADMIN".equals(user.getRole())) {
                response.sendRedirect("/home?error=permission");
                return false;
            }
        }
        
        // 检查是否需要部長・総務・管理员权限
        if (requiresManagerPermission(requestURI)) {
            if (!"MANAGER".equals(user.getRole()) && !"SOUMU".equals(user.getRole())
                    && !"ADMIN".equals(user.getRole())) {
                response.sendRedirect("/home?error=permission");
                return false;
            }
        }
        
        return true;
    }
    
    private boolean requiresAdminPermission(String requestURI) {
        return ADMIN_PATHS.stream().anyMatch(requestURI::startsWith);
    }
    
    private boolean requiresManagerPermission(String requestURI) {
        return MANAGER_PATHS.stream().anyMatch(requestURI::startsWith);
    }
}
