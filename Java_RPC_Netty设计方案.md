# Java RPC框架设计（基于Netty，支持负载均衡/请求超时/异步请求）

## 设计重点一览

1. **协议层**——自定义二进制协议 + `LengthFieldBasedFrameDecoder`，解决TCP粘包半包。
2. **异步核心**——`requestId` + `CompletableFuture` + 全局映射表，是整个框架异步能力的地基。
3. **超时机制**——`HashedWheelTimer` + 原子`remove`防止正常响应和超时任务竞态导致重复处理。
4. **负载均衡**——策略可插拔（轮询/加权/一致性哈希），候选连接列表由服务发现动态维护。
5. **服务端线程模型**——IO线程和业务线程池严格分离，避免耗时业务逻辑阻塞Netty的EventLoop。
6. **生产收尾**——心跳保活、优雅停机、限流、分级超时、谨慎重试、全链路监控。

---

## 一、通信协议设计——解决粘包半包

```java
// 协议格式: 魔数(4B) + 版本(1B) + 消息类型(1B) + 序列化方式(1B) + requestId(8B) + 消息长度(4B) + 消息体
public class RpcMessage {
    public static final byte REQUEST = 1;
    public static final byte RESPONSE = 2;
    public static final byte HEARTBEAT = 3;

    private long requestId;
    private byte messageType;
    private Object data;   // 请求时是RpcRequest，响应时是RpcResponse
}
```

编解码器用 `LengthFieldBasedFrameDecoder` 处理粘包半包：

```java
public class RpcFrameDecoder extends LengthFieldBasedFrameDecoder {
    public RpcFrameDecoder() {
        // maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip
        super(Integer.MAX_VALUE, 15, 4, 0, 0);
    }
}
```

---

## 二、客户端核心——动态代理 + requestId + CompletableFuture

动态代理把接口调用转成RPC请求，是客户端对业务代码"透明化"的关键：

```java
public class RpcClientProxy implements InvocationHandler {
    private final NettyRpcClient rpcClient;

    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[]{interfaceClass},
            this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        RpcRequest request = RpcRequest.builder()
            .requestId(IdGenerator.nextId())
            .interfaceName(method.getDeclaringClass().getName())
            .methodName(method.getName())
            .parameters(args)
            .paramTypes(method.getParameterTypes())
            .build();

        CompletableFuture<Object> future = rpcClient.sendRequest(request);
        try {
            return future.get(3000, TimeUnit.MILLISECONDS);  // 同步调用等待Future完成
        } catch (TimeoutException e) {
            throw new RpcException("请求超时: " + request.getRequestId());
        }
    }
}
```

如果要提供真正的异步调用能力（不阻塞等待），额外暴露一个直接返回Future的接口，让业务代码自己决定要不要`.get()`阻塞或者`.thenAccept()`异步处理：

```java
public interface AsyncRpcCall {
    CompletableFuture<Object> callAsync(String interfaceName, String methodName, Object[] args);
}
```

核心的请求-响应匹配机制——用`requestId`把"发出去的请求"和"异步收到的响应"关联起来：

```java
public class NettyRpcClient {
    // 全局映射表：requestId -> 等待中的Future
    private final Map<Long, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    // Netty自带的时间轮，专门用来做大量定时任务(超时检测)，性能远好于给每个请求都new一个ScheduledExecutorService任务
    private final HashedWheelTimer timeoutTimer = new HashedWheelTimer();

    public CompletableFuture<Object> sendRequest(RpcRequest request) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingRequests.put(request.getRequestId(), future);

        // 注册超时任务：到时候如果Future还没被响应完成，主动标记超时失败
        Timeout timeout = timeoutTimer.newTimeout(t -> {
            CompletableFuture<Object> f = pendingRequests.remove(request.getRequestId());
            if (f != null) {
                f.completeExceptionally(new TimeoutException("请求超时 requestId=" + request.getRequestId()));
            }
        }, 3, TimeUnit.SECONDS);

        Channel channel = loadBalancer.selectChannel();  // 负载均衡选一个连接
        channel.writeAndFlush(request).addListener(f -> {
            if (!f.isSuccess()) {
                // 写入失败(连接断开等)，立即让Future失败，不用等超时
                CompletableFuture<Object> failed = pendingRequests.remove(request.getRequestId());
                if (failed != null) failed.completeExceptionally(f.cause());
            }
        });

        return future;
    }

    // 客户端Handler收到服务端响应时调用这个方法完成匹配
    public void handleResponse(RpcResponse response) {
        CompletableFuture<Object> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            if (response.isSuccess()) {
                future.complete(response.getResult());
            } else {
                future.completeExceptionally(new RpcException(response.getErrorMessage()));
            }
        }
        // future为null说明已经超时被移除了，响应来迟了，直接丢弃，不需要额外处理
    }
}
```

**关键的竞态条件处理**：正常响应和超时任务可能几乎同时触发，都想对同一个Future做`complete`。解决方式是用`pendingRequests.remove()`的返回值做判断——`ConcurrentHashMap.remove()`是原子操作，只有第一个成功`remove`到非null值的那一方才能真正complete这个Future，另一方拿到的是`null`，自然跳过，不会出现"Future被重复complete"的问题。

---

## 三、负载均衡——可插拔策略

```java
public interface LoadBalancer {
    Channel select(List<Channel> availableChannels, RpcRequest request);
}

// 轮询
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger index = new AtomicInteger(0);
    @Override
    public Channel select(List<Channel> channels, RpcRequest request) {
        if (channels.isEmpty()) throw new RpcException("没有可用的服务端连接");
        int i = Math.abs(index.getAndIncrement() % channels.size());
        return channels.get(i);
    }
}

// 一致性哈希（同一个key的请求尽量落到同一个服务端实例，适合有状态缓存场景）
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private final TreeMap<Long, Channel> ring = new TreeMap<>();
    // 按channel对应的地址做hash环，请求按某个业务key(比如userId)算hash找最近的节点
}
```

可用连接列表`availableChannels`从哪来——生产环境要接**服务注册与发现**（Nacos/ZooKeeper），客户端订阅某个服务名下的实例列表变化，实例上线/下线时动态更新这份list，而不是写死配置文件里的固定地址列表：

```java
public class ServiceDiscovery {
    private final List<Channel> channelPool = new CopyOnWriteArrayList<>();

    public void onServiceListChanged(List<InetSocketAddress> newAddresses) {
        // 1. 建立新增地址的连接
        // 2. 关闭已下线地址对应的连接
        // 3. 更新channelPool，供负载均衡器使用
    }
}
```

---

## 四、服务端——IO线程与业务线程池分离

解码之后的业务方法调用（可能涉及数据库、外部调用，耗时不可控）绝对不能在Netty的`EventLoop`线程里直接跑，否则会阻塞该线程上其他所有连接的IO处理：

```java
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {
    private final ExecutorService businessThreadPool = new ThreadPoolExecutor(
        16, 32, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
        new ThreadFactoryBuilder().setNameFormat("rpc-business-%d").build(),
        new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时让调用者线程跑，做限流保护
    );

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcRequest request) {
        businessThreadPool.submit(() -> {
            RpcResponse response;
            try {
                Object result = invokeServiceMethod(request);  // 真正执行业务逻辑
                response = RpcResponse.success(request.getRequestId(), result);
            } catch (Exception e) {
                response = RpcResponse.fail(request.getRequestId(), e.getMessage());
            }
            ctx.writeAndFlush(response);  // 写回操作会自动切回IO线程执行，不用手动处理线程切换
        });
    }
}
```

---

## 五、连接保活与优雅停机

**心跳**：客户端定期发心跳，服务端配合`IdleStateHandler`检测连接是否存活。

**优雅停机**：服务端收到停机信号后，不能立即粗暴断连接，要先停止接收新请求，等当前正在处理中的请求全部处理完（或者超时强制结束），再真正关闭：

```java
public void shutdownGracefully() {
    isShuttingDown = true;  // 标记，配合服务发现主动从注册中心下线，不再有新流量进来
    businessThreadPool.shutdown();
    try {
        if (!businessThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
            businessThreadPool.shutdownNow();  // 超时还没跑完，强制中断
        }
    } catch (InterruptedException ignored) {}
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
}
```

---

## 六、生产环境要点清单

1. **序列化选型**：JDK原生序列化性能差、有安全风险，生产环境用**Protostuff**或**Kryo**（性能好）或Jackson Json格式，跨语言场景考虑Protobuf。
2. **心跳+断线重连**：客户端检测到连接断开要自动重连，并触发一次服务发现刷新（防止重连到已经下线的地址）。
3. **超时时间要分级**：不同接口方法允许配置不同的超时阈值（比如轻量查询3秒、重量级聚合计算10秒），不要所有请求共用一个硬编码超时值。
4. **失败重试要谨慎**：超时/网络异常可以做有限次重试，但幂等性没保证的写操作不能随便重试，重试前要判断这个方法是不是标记为可安全重试的。
5. **限流保护**：服务端业务线程池的队列要设上限，配合`CallerRunsPolicy`或直接拒绝策略，防止突发流量把服务端拖垮。
6. **监控指标**：每个接口的QPS、平均延迟、超时率、失败率，是排查生产问题的第一手资料，建议每次请求完成时（无论成功/超时/异常）都记录一条指标数据。
