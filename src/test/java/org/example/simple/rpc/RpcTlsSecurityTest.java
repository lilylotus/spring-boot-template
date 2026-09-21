package org.example.simple.rpc;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.loadbalance.RoundRobinLoadBalancer;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS 安全边界测试。
 * <p>
 * 覆盖服务端证书不被客户端信任时的握手拒绝，以及对端不推进握手时的握手超时，
 * 确保不会默认信任任意证书，也不会无限等待握手完成。
 */
class RpcTlsSecurityTest {

    private static final String SERVICE_NAME = "安全服务";
    private static final String PASSWORD = "rpc-test-password";

    private static Path directory;
    private static KeyStore trusted;
    private static KeyStore rogue;

    @BeforeAll
    static void generateKeyStores() throws Exception {
        directory = Files.createTempDirectory("rpc-tls-security-");
        trusted = load(generate("trusted"));
        rogue = load(generate("rogue"));
    }

    @AfterAll
    static void deleteKeyStores() throws Exception {
        if (directory == null) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    /* 临时目录清理失败不影响测试结论。 */
                }
            });
        }
    }

    @Test
    void rejectsServerCertificateFromUnknownIssuer() throws Exception {
        // 服务端使用客户端信任库之外的证书，握手必须失败而不是默认信任。
        SslContext serverTls = SslContextBuilder.forServer(keyManager(rogue)).build();
        SslContext clientTls = SslContextBuilder.forClient().trustManager(trustManager(trusted)).build();
        RpcConfig config = config(3000);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, SecureService.class, (SecureService) () -> "安全");

        try (RpcServer server =
                 new RpcServer(registry, config, SerializerRegistry.defaults(), serverTls);
             RpcClient client = client(config, clientTls)) {
            server.start("127.0.0.1", 0);
            int port = server.getPort();

            assertThrows(RuntimeException.class, () -> client.connect("localhost", port));
        }
    }

    @Test
    void failsWhenTlsHandshakeExceedsTimeout() throws Exception {
        SslContext clientTls = SslContextBuilder.forClient().trustManager(trustManager(trusted)).build();
        RpcConfig config = config(500);

        try (SilentAcceptor acceptor = new SilentAcceptor();
             RpcClient client = client(config, clientTls)) {
            long started = System.nanoTime();

            assertThrows(RuntimeException.class, () -> client.connect("localhost", acceptor.port()));

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 5000, "握手超时未生效，耗时：" + elapsedMillis + " 毫秒");
        }
    }

    private static RpcConfig config(int handshakeTimeoutMillis) {
        return RpcConfig.builder()
            .handshakeTimeoutMillis(handshakeTimeoutMillis)
            .connectTimeoutMillis(500)
            .drainTimeoutMillis(500)
            .shutdownTimeoutMillis(1000)
            .build();
    }

    private static RpcClient client(RpcConfig config, SslContext tls) {
        return new RpcClient(
            config, SerializerRegistry.defaults(), (byte) 1, tls, RoundRobinLoadBalancer::new);
    }

    private static Path generate(String name) throws Exception {
        Path file = directory.resolve(name + ".p12");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "rpc", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", PASSWORD, "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve(name + ".log").toFile())
            .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return file;
    }

    private static KeyStore load(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) {
            store.load(input, PASSWORD.toCharArray());
        }
        return store;
    }

    private static KeyManagerFactory keyManager(KeyStore store) throws Exception {
        KeyManagerFactory keys =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD.toCharArray());
        return keys;
    }

    private static TrustManagerFactory trustManager(KeyStore store) throws Exception {
        TrustManagerFactory trust =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        return trust;
    }

    /**
     * 只接受连接但从不推进 TLS 握手的对端。
     */
    private static final class SilentAcceptor implements AutoCloseable {

        private final ServerSocket acceptor = new ServerSocket(0);
        private final List<Socket> accepted = new ArrayList<>();

        SilentAcceptor() throws IOException {
            Thread worker = new Thread(() -> {
                try {
                    while (!acceptor.isClosed()) {
                        accepted.add(acceptor.accept());
                    }
                } catch (IOException ignored) {
                    /* 关闭监听端口即结束接收循环。 */
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return acceptor.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            acceptor.close();
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    /**
     * 测试用安全服务。
     */
    interface SecureService {

        /** 返回固定内容。 */
        String echo();
    }
}
