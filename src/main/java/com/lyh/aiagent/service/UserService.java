package com.lyh.aiagent.service;

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

    public User register(String username) {
        User user = new User();
        user.setUsername(username);
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        userMapper.insert(user);
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
