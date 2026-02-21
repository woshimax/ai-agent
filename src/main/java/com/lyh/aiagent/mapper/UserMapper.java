package com.lyh.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyh.aiagent.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
