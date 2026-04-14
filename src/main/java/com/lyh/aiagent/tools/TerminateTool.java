package com.lyh.aiagent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 终止工具 - 用于 Agent 主动结束对话
 */
@Slf4j
@Component
public class TerminateTool {

    /**
     * 结束当前对话并返回最终回复给用户
     *
     * @param finalMessage 要返回给用户的最终消息
     * @return 确认信息
     */
    @Tool(name = "doTerminate", description = """
            当你已经完成了用户的任务，或者准备好直接回复用户时，使用此工具来结束对话。
            这个工具会将你的最终回复传递给用户，并终止当前的任务执行流程。

            使用场景：
            1. 用户的问题不需要使用其他工具，可以直接回答时
            2. 已经使用工具完成了用户的任务，准备总结回复时
            3. 任何你认为可以给出最终答案的时候

            参数说明：
            - finalMessage: 你要返回给用户的最终回复内容，应该清晰、完整地回答用户的问题或说明任务完成情况
            """)
    public String terminate(String finalMessage) {
        log.info("Agent 调用 terminate 工具，最终消息：{}", finalMessage);
        return "任务已终止，最终回复：" + finalMessage;
    }
}
