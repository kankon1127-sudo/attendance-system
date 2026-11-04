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

    // 管理者権限が必要なパス（DB バックアップ /admin/database-backup と復元 /admin/database-restore）
    private static final List<String> ADMIN_PATHS = Arrays.asList(
        "/admin/"
    );

    // 部長・総務・管理者権限が必要なパス（Controller 内で部署とロールによる詳細チェックも行う）
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

        // リクエストごとに DB から再取得する：停止・削除されたアカウントは即座に無効になり、ロールと部署の変更も即座に反映される
        User user = sessionUser.getId() == null ? null
                : userRepository.findById(sessionUser.getId()).orElse(null);
        if (user == null || "INACTIVE".equals(user.getStatus())) {
            session.invalidate();
            response.sendRedirect("/login");
            return false;
        }
        session.setAttribute(SESSION_USER, user);

        String requestURI = request.getRequestURI();
        
        // 管理者権限が必要かチェック
        if (requiresAdminPermission(requestURI)) {
            if (!"ADMIN".equals(user.getRole())) {
                response.sendRedirect("/home?error=permission");
                return false;
            }
        }
        
        // 部長・総務・管理者権限が必要かチェック
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
