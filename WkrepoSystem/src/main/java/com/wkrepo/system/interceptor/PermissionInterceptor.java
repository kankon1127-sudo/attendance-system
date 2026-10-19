package com.wkrepo.system.interceptor;

import com.wkrepo.system.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

@Component
public class PermissionInterceptor implements HandlerInterceptor {
    
    private static final String SESSION_USER = "user";
    
    // 需要管理员权限的路径
    private static final List<String> ADMIN_PATHS = Arrays.asList(
        "/default-attendance-setting"
    );
    
    // 需要主管或管理员权限的路径
    private static final List<String> MANAGER_PATHS = Arrays.asList(
        // 审批相关路径后续添加
    );
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.sendRedirect("/login");
            return false;
        }
        
        User user = (User) session.getAttribute(SESSION_USER);
        if (user == null) {
            response.sendRedirect("/login");
            return false;
        }
        
        String requestURI = request.getRequestURI();
        
        // 检查是否需要管理员权限
        if (requiresAdminPermission(requestURI)) {
            if (!"ADMIN".equals(user.getRole())) {
                response.sendRedirect("/home?error=permission");
                return false;
            }
        }
        
        // 检查是否需要主管权限
        if (requiresManagerPermission(requestURI)) {
            if (!"MANAGER".equals(user.getRole()) && !"ADMIN".equals(user.getRole())) {
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
