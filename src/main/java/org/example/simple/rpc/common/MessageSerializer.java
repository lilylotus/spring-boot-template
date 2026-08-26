package org.example.simple.rpc.common;

import tools.jackson.databind.JsonNode;

/**
 * RPC 消息与参数的序列化边界。
 */
public interface MessageSerializer {

    /**
     * 将对象序列化为消息字节。
     *
     * @param value 待序列化对象
     * @return 消息字节
     */
    byte[] serialize(Object value);

    /**
     * 将消息字节反序列化为指定类型。
     *
     * @param bytes 消息字节
     * @param type 目标类型
     * @param <T> 目标类型参数
     * @return 反序列化结果
     */
    <T> T deserialize(byte[] bytes, Class<T> type);

    /**
     * 将 Java 值转换为 JSON 树。
     *
     * @param value Java 值
     * @return JSON 树节点
     */
    JsonNode toTree(Object value);

    /**
     * 将 JSON 树转换为指定 Java 类型。
     *
     * @param node JSON 树节点
     * @param type 目标类型
     * @param <T> 目标类型参数
     * @return Java 值
     */
    <T> T fromTree(JsonNode node, Class<T> type);
}
