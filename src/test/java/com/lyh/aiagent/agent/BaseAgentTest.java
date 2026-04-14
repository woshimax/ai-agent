package com.lyh.aiagent.agent;

import com.lyh.aiagent.model.AgentState;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;

class BaseAgentTest {

    @Test
    void shouldResetRuntimeStateBetweenRuns() {
        RepeatableTestAgent agent = new RepeatableTestAgent();
        agent.setMaxSteps(2);

        String firstResult = assertDoesNotThrow(() -> agent.run("第一个问题"));
        assertTrue(firstResult.contains("共执行了 2 个步骤"));
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals(2, agent.getCurrentStep());

        String secondResult = assertDoesNotThrow(() -> agent.run("第二个问题"));
        assertTrue(secondResult.contains("共执行了 2 个步骤"));
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals(2, agent.getCurrentStep());
    }

    @Test
    void shouldPersistAndRestoreConversationMemory() {
        MemoryAwareTestAgent agent = new MemoryAwareTestAgent();
        agent.setChatMemory(new InMemoryChatMemory());
        agent.setConversationId("chat-1");

        String firstResult = assertDoesNotThrow(() -> agent.run("第一个问题"));
        assertEquals("messageCount=1", firstResult);

        String secondResult = assertDoesNotThrow(() -> agent.run("第二个问题"));
        assertEquals("messageCount=3", secondResult);
    }

    private static class RepeatableTestAgent extends BaseAgent {
        @Override
        public String step() {
            return "继续执行";
        }
    }

    private static class MemoryAwareTestAgent extends BaseAgent {
        @Override
        public String step() {
            int messageCount = getMessageList().size();
            getMessageList().add(new AssistantMessage("messageCount=" + messageCount));
            setState(AgentState.FINISHED);
            return "完成";
        }
    }

    private static class InMemoryChatMemory implements ChatMemory {
        private final Map<String, List<Message>> store = new HashMap<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            store.computeIfAbsent(conversationId, key -> new ArrayList<>()).addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId, int lastN) {
            List<Message> messages = store.getOrDefault(conversationId, new ArrayList<>());
            if (messages.size() <= lastN) {
                return new ArrayList<>(messages);
            }
            return new ArrayList<>(messages.subList(messages.size() - lastN, messages.size()));
        }

        @Override
        public void clear(String conversationId) {
            store.remove(conversationId);
        }
    }
}
