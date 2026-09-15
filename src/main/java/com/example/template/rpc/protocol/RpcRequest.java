package com.example.template.rpc.protocol;

import java.util.Arrays;

/**
 * 一次 RPC 调用请求的消息体：目标接口名、方法名、参数类型与参数值。
 * <p>
 * 参数类型用类的全限定名（含基本类型的关键字名，如 {@code int}）而不是 {@link Class} 本身表示，
 * 因为 {@link Class} 不是一个能被 Jackson 直接序列化/反序列化为稳定 JSON 结构的类型；服务端收到后
 * 再按名称还原成 {@link Class}。
 */
public class RpcRequest {

    /** 与外层协议帧 requestId 保持一致，冗余存一份方便脱离协议帧单独查看/日志排查。 */
    private long requestId;

    /** 目标服务接口的全限定名。 */
    private String interfaceName;

    /** 目标方法名。 */
    private String methodName;

    /** 方法参数类型的全限定名列表，用于服务端反射查找目标方法。 */
    private String[] parameterTypes;

    /** 方法调用的实际参数值。 */
    private Object[] parameters;

    public RpcRequest() {
    }

    public RpcRequest(long requestId, String interfaceName, String methodName,
                       String[] parameterTypes, Object[] parameters) {
        this.requestId = requestId;
        this.interfaceName = interfaceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.parameters = parameters;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(String[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    public Object[] getParameters() {
        return parameters;
    }

    public void setParameters(Object[] parameters) {
        this.parameters = parameters;
    }

    @Override
    public String toString() {
        return "RpcRequest{"
            + "requestId=" + requestId
            + ", interfaceName='" + interfaceName + '\''
            + ", methodName='" + methodName + '\''
            + ", parameterTypes=" + Arrays.toString(parameterTypes)
            + '}';
    }

}
