package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    public User login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            throw new RuntimeException("ユーザーが存在しません。");
        }
        
        User user = userOpt.get();
        if ("INACTIVE".equals(user.getStatus())) {
            throw new RuntimeException("このアカウントは無効化されています。管理者に連絡してください。");
        }
        if (!user.getPassword().equals(password)) {
            throw new RuntimeException("パスワードが正しくありません。");
        }
        
        // 补全 null 字段，给予默认值
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
            throw new RuntimeException("ユーザー名を入力してください。");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new RuntimeException("パスワードを入力してください。");
        }
        if (user.getName() == null || user.getName().isBlank()) {
            throw new RuntimeException("氏名を入力してください。");
        }
        if (user.getEmployeeId() == null || user.getEmployeeId().isBlank()) {
            throw new RuntimeException("社員番号を入力してください。");
        }
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new RuntimeException("ユーザー名は既に存在します: " + user.getUsername());
        }
        if (userRepository.existsByEmployeeId(user.getEmployeeId())) {
            throw new RuntimeException("社員番号は既に存在します: " + user.getEmployeeId());
        }
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("GENERAL");
        }
        if (user.getStatus() == null || user.getStatus().isBlank()) {
            user.setStatus("ACTIVE");
        }
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
                .orElseThrow(() -> new RuntimeException("用户不存在，ID: " + id));
    }
    
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));
    }
    
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
    
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public void updatePassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new RuntimeException("パスワードが空です。");
        }
        if (newPassword.length() < 4) {
            throw new RuntimeException("パスワードは4文字以上必要です。");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("ユーザーが存在しません ID=" + userId));
        u.setPassword(newPassword);
        userRepository.save(u);
    }
}
