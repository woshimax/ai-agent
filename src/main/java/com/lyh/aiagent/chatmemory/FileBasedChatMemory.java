package com.lyh.aiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话通过本地文件持久化，核心在于序列化反序列化（message<->实体）
 * 使用 Kryo 序列化库，天然支持多态类型（UserMessage/AssistantMessage 等），无需手写类型配置
 */
public class FileBasedChatMemory implements ChatMemory {

    private static final String FILE_SUFFIX = ".kryo";

    // Kryo 不是线程安全的，用 ThreadLocal 保证每个线程独立实例
    private static final ThreadLocal<Kryo> KRYO_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false); // 不需要预注册，自动处理多态子类
        // 使用 Objenesis 作为降级策略，支持反序列化没有无参构造器的类（如 UserMessage）
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        return kryo;
    });

    private final String storePath;

    public FileBasedChatMemory(String storePath) {
        this.storePath = storePath;
        try {
            Files.createDirectories(Paths.get(storePath));
        } catch (IOException e) {
            throw new RuntimeException("创建对话存储目录失败: " + storePath, e);
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> all = loadMessages(conversationId);
        all.addAll(messages);
        saveMessages(conversationId, all);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> all = loadMessages(conversationId);
        int size = all.size();
        if (size <= lastN) {
            return all;
        }
        return all.subList(size - lastN, size);
    }

    @Override
    public void clear(String conversationId) {
        try {
            Files.deleteIfExists(getFilePath(conversationId));
        } catch (IOException e) {
            throw new RuntimeException("清除对话失败: " + conversationId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Message> loadMessages(String conversationId) {
        Path filePath = getFilePath(conversationId);
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try (Input input = new Input(new FileInputStream(filePath.toFile()))) {
            return (List<Message>) KRYO_THREAD_LOCAL.get().readClassAndObject(input);
        } catch (FileNotFoundException e) {
            return new ArrayList<>();
        }
    }

    private void saveMessages(String conversationId, List<Message> messages) {
        Path filePath = getFilePath(conversationId);
        try (Output output = new Output(new FileOutputStream(filePath.toFile()))) {
            KRYO_THREAD_LOCAL.get().writeClassAndObject(output, messages);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("保存对话失败: " + conversationId, e);
        }
    }

    private Path getFilePath(String conversationId) {
        return Paths.get(storePath, conversationId + FILE_SUFFIX);
    }
}
