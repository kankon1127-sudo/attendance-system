package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class UserService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Autowired
    private UserRepository userRepository;

    /** パスワードは必ず BCrypt ハッシュで保存する。パスワードを設定する処理はすべてここを通すこと */
    public static String encodePassword(String rawPassword) {
        return PASSWORD_ENCODER.encode(rawPassword);
    }

    public static boolean isEncoded(String storedPassword) {
        return storedPassword != null && storedPassword.matches("^\\$2[aby]?\\$\\d{2}\\$.{53}$");
    }

    /**
     * DB に残っている平文パスワードをハッシュに変換する（起動時に実行。旧バックアップを復元して再起動した場合も処理される）。変換した件数を返す。
     */
    public int encodePlainTextPasswords() {
        int converted = 0;
        for (User user : userRepository.findAll()) {
            String stored = user.getPassword();
            if (stored != null && !stored.isEmpty() && !isEncoded(stored)) {
                user.setPassword(encodePassword(stored));
                userRepository.save(user);
                converted++;
            }
        }
        return converted;
    }

    private boolean passwordMatches(User user, String rawPassword) {
        String stored = user.getPassword();
        if (rawPassword == null || stored == null) {
            return false;
        }
        if (isEncoded(stored)) {
            return PASSWORD_ENCODER.matches(rawPassword, stored);
        }
        // 稼働中に平文パスワードを含む旧バックアップを復元した場合：一度認証した時点でハッシュに変換する
        if (stored.equals(rawPassword)) {
            user.setPassword(encodePassword(rawPassword));
            userRepository.save(user);
            return true;
        }
        return false;
    }

    public User login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            throw new RuntimeException("login.error.userNotFound");
        }
        
        User user = userOpt.get();
        if ("INACTIVE".equals(user.getStatus())) {
            throw new RuntimeException("login.error.inactive");
        }
        if (!passwordMatches(user, password)) {
            throw new RuntimeException("login.error.wrongPassword");
        }
        
        // null の項目にデフォルト値を設定
        if (user.getRole() == null || user.getRole().trim().isEmpty()) {
            user.setRole("GENERAL");
        }
        if (user.getDepartment() == null || user.getDepartment().trim().isEmpty()) {
            user.setDepartment("未設定");
        }
        
        return user;
    }
    
    public User createUser(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new RuntimeException("user.error.usernameRequired");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new RuntimeException("user.error.passwordRequired");
        }
        if (user.getName() == null || user.getName().isBlank()) {
            throw new RuntimeException("user.error.nameRequired");
        }
        if (user.getEmployeeId() == null || user.getEmployeeId().isBlank()) {
            throw new RuntimeException("user.error.employeeIdRequired");
        }
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new BusinessException("user.error.usernameExists", user.getUsername());
        }
        if (userRepository.existsByEmployeeId(user.getEmployeeId())) {
            throw new BusinessException("user.error.employeeIdExists", user.getEmployeeId());
        }
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("GENERAL");
        }
        if (user.getStatus() == null || user.getStatus().isBlank()) {
            user.setStatus("ACTIVE");
        }
        user.setPassword(encodePassword(user.getPassword()));
        return userRepository.save(user);
    }
    
    public User updateUser(User user) {
        return userRepository.save(user);
    }
    
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
    
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("user.error.notFound"));
    }
    
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("user.error.notFound"));
    }
    
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
    
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public void updatePassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new RuntimeException("user.error.passwordRequired");
        }
        if (newPassword.length() < 4) {
            throw new RuntimeException("user.error.passwordTooShort");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("user.error.notFound"));
        u.setPassword(encodePassword(newPassword));
        userRepository.save(u);
    }
}
