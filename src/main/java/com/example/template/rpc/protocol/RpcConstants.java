package com.example.template.rpc.protocol;

/**
 * 自定义二进制协议的帧结构常量。帧格式依次为：
 * 魔数(4B) + 版本(1B) + 消息类型(1B) + 序列化方式(1B) + requestId(8B) + 消息体长度(4B) + 消息体。
 */
public final class RpcConstants {

    /** 协议魔数，用于快速识别/拒绝非本协议的字节流；两端必须保持一致。 */
    public static final int MAGIC_NUMBER = 0xCAFEBABE;

    /** 当前协议版本号，预留给未来协议升级做兼容性判断。 */
    public static final byte VERSION = 1;

    /** 消息体长度字段相对帧起始位置的偏移量：魔数(4)+版本(1)+消息类型(1)+序列化方式(1)+requestId(8)=15。 */
    public static final int LENGTH_FIELD_OFFSET = 15;

    /** 消息体长度字段本身占用的字节数。 */
    public static final int LENGTH_FIELD_LENGTH = 4;

    /** 定长消息头总字节数：长度字段偏移量 + 长度字段自身长度。 */
    public static final int HEADER_LENGTH = LENGTH_FIELD_OFFSET + LENGTH_FIELD_LENGTH;

    /** 单帧最大长度，按设计文档采用默认值；生产环境接入具体业务时应按消息体实际大小收紧。 */
    public static final int MAX_FRAME_LENGTH = Integer.MAX_VALUE;

    private RpcConstants() {
    }

}
