package com.lyh.aiagent.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lyh.aiagent.exception.BusinessException;
import com.lyh.aiagent.mapper.UserMapper;
import com.lyh.aiagent.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    public User register(String username, String password) {
        // 用户名已存在则不允许重复注册
        User existing = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", username)
        );
        if (existing != null) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password));
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        userMapper.insert(user);
        return user;
    }

    public User login(String username, String password) {
        User user = userMapper.selectOne(
                new QueryWrapper<User>().eq("username", username)
        );
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!BCrypt.checkpw(password, user.getPassword())) {
            throw new BusinessException("密码错误");
        }
        return user;
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }
}
