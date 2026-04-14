package com.lyh.aiagent.agent;

import com.lyh.aiagent.advisors.LoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
/**
 * Manus 全能助手
 * 通过 ManusFactory 创建实例，不再使用 @Component
 */
public class Manus extends ToolCallAgent {

    public Manus(Object[] allTools, ChatModel dashscopeChatModel) {
        super(allTools);
        this.setName("Manus");
        String SYSTEM_PROMPT = """
                你是Manus, 一位全能型人工智能助手，致力于解决用户提出的任何任务。你可以调用多种工具，高效完成各类复杂需求。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具或工具组合完成任务。

                工作流程：
                1. 分析用户需求，判断是否需要使用工具
                2. 如果不需要工具（如简单问候、闲聊），使用 doTerminate 工具回复
                3. 如果需要工具，先调用工具收集信息，再使用 doTerminate 给出最终答案

                使用 doTerminate 工具的要求（非常重要！）：
                ✓ 必须将完整的最终答案作为 finalMessage 参数传递
                ✓ finalMessage 应该是用户直接看到的完整内容，包含所有必要的信息
                ✓ 不要在 finalMessage 中说"让我..."、"我将..."等过渡性语句
                ✓ 如果生成了文档内容，必须将完整文档内容放在 finalMessage 中
                ✓ 如果用户明确要求“生成 PDF / 导出 PDF / 转成 PDF”，必须调用 generatePdf 工具真正生成 PDF
                ✓ 不要用 saveFile 保存 txt/markdown 来代替 PDF
                ✓ 生成 PDF 成功后，再用 doTerminate 回复用户，并明确告知 PDF 已生成
                ✓ 不要输出 ```tool_code```、伪函数调用、或“我来帮您查询一下”这类中间过程到最终答案里
                ✓ 如果需要工具，必须走真实工具调用，不要把工具名和参数当普通文本打印出来
                ✓ 如果用户在问“从哪里到哪里怎么走 / 路线 / 导航 / 前往某地”，优先调用地图/路线工具
                ✓ 如果可用，优先调用 planRouteWithAmap，并把用户原话完整传入
                ✓ 对于路线类问题，search 只能补充说明，不能替代真实地图路线
                ✓ 不要凭常识臆造路线、站点、耗时、票价；应优先依赖地图工具结果

                finalMessage 参数错误示例：
                "让我为您准备详细的文档"  ❌

                finalMessage 参数正确示例：
                "北京周末约会指南\n\n1. 上午安排...\n2. 下午安排..."  ✓
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new LoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
