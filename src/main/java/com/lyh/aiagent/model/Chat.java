package com.lyh.aiagent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("chat")
public class Chat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String chatId;

    private String chatName;

    private Boolean pinned;

    private Date createTime;

    private Date updateTime;
}
