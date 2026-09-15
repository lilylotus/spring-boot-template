package com.example.template.rpc.server;

import com.example.template.rpc.protocol.JacksonRpcSerializer;
import com.example.template.rpc.protocol.RpcMessage;
import com.example.template.rpc.protocol.RpcRequest;
import com.example.template.rpc.protocol.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

/**
 * 服务端入站处理器：收到请求后提交到独立的业务线程池执行，不在 Netty 的 EventLoop 线程里直接跑
 * 业务逻辑，避免耗时的业务方法阻塞该 EventLoop 上其它所有连接的 IO 处理。
 */
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcMessage> {

    private static final Logger log = LoggerFactory.getLogger(RpcServerHandler.class);

    private final ServiceRegistry serviceRegistry;

    private final ExecutorService businessThreadPool;

    public RpcServerHandler(ServiceRegistry serviceRegistry, ExecutorService businessThreadPool) {
        this.serviceRegistry = serviceRegistry;
        this.businessThreadPool = businessThreadPool;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) {
        switch (msg.getMessageType()) {
            case REQUEST: {
                RpcRequest request = (RpcRequest) msg.getData();
                // 业务方法调用(可能涉及数据库/外部调用，耗时不可控)提交到独立线程池，
                // IO线程只负责解码和提交任务，写回结果时Netty会自动把写操作切回IO线程执行
                businessThreadPool.submit(() -> handleRequest(ctx, request));
                break;
            }
            case HEARTBEAT: {
                // 心跳只用于维持连接/触发IdleStateHandler读事件计时，服务端不需要额外处理
                break;
            }
            default: {
                log.warn("RPC服务端收到未预期的消息类型: {}，来自: {}", msg.getMessageType(), ctx.channel());
            }
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, RpcRequest request) {
        RpcResponse response;
        try {
            Object service = serviceRegistry.lookup(request.getInterfaceName());
            if (service == null) {
                // 接口未注册是可预期的调用方错误，返回明确的失败响应，不能让连接直接异常断开
                response = RpcResponse.fail(request.getRequestId(), "未找到服务: " + request.getInterfaceName());
            } else {
                Object result = invokeService(service, request);
                response = RpcResponse.success(request.getRequestId(), result);
            }
        } catch (InvocationTargetException e) {
            // 业务方法自身抛出的异常，把原始异常信息带回给调用方，方便排查
            response = RpcResponse.fail(request.getRequestId(), String.valueOf(e.getTargetException().getMessage()));
        } catch (Exception e) {
            log.error("处理RPC请求失败: {}", request, e);
            response = RpcResponse.fail(request.getRequestId(), e.getMessage());
        }
        ctx.writeAndFlush(RpcMessage.response(JacksonRpcSerializer.TYPE_CODE, response));
    }

    private Object invokeService(Object service, RpcRequest request) throws ReflectiveOperationException {
        String[] parameterTypeNames = request.getParameterTypes() == null ? new String[0] : request.getParameterTypes();
        Class<?>[] parameterClasses = new Class<?>[parameterTypeNames.length];
        for (int i = 0; i < parameterTypeNames.length; i++) {
            parameterClasses[i] = RpcTypeUtils.resolveClass(parameterTypeNames[i]);
        }

        Method method = service.getClass().getMethod(request.getMethodName(), parameterClasses);
        Object[] parameters = request.getParameters() == null ? new Object[0] : request.getParameters();
        Object[] coercedParameters = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            coercedParameters[i] = RpcTypeUtils.coerce(parameterClasses[i], parameters[i]);
        }

        return method.invoke(service, coercedParameters);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            // 超过配置的空闲时间没有收到任何数据(含心跳)，判定连接已失活，主动关闭释放资源
            log.info("连接读空闲超时，关闭连接: {}", ctx.channel());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("RPC服务端连接发生异常，关闭连接: {}", ctx.channel(), cause);
        ctx.close();
    }

}
