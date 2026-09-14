package org.example.simple.rpc;

import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import io.netty.handler.ssl.*;
import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.SerializerRegistry;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.loadbalance.RoundRobinLoadBalancer;
import org.example.simple.rpc.server.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcTlsTest {
    interface Service { String echo(); }
    @Test void acceptsTrustedMutualTlsAndRejectsWrongHostname() throws Exception {
        Path directory = Files.createTempDirectory("rpc-tls-");
        Path certificate = directory.resolve("localhost.p12");
        char[] password = "rpc-test-password".toCharArray();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "rpc", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore",
            certificate.toString(), "-storepass", new String(password), "-noprompt")
            .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(certificate)) { store.load(input, password); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SslContext serverTls = SslContextBuilder.forServer(keys).trustManager(trust).clientAuth(ClientAuth.REQUIRE).build();
        SslContext clientTls = SslContextBuilder.forClient().trustManager(trust).keyManager(keys).build();
        RpcConfig config = RpcConfig.builder().drainTimeoutMillis(100).build();
        ServiceRegistry registry = new ServiceRegistry(); registry.register("服务", Service.class, (Service) () -> "安全");
        try (RpcServer server = new RpcServer(registry, config, SerializerRegistry.defaults(), serverTls);
             RpcClient client = new RpcClient(config, SerializerRegistry.defaults(), (byte) 2, clientTls,
                 RoundRobinLoadBalancer::new)) {
            server.start("127.0.0.1", 0); client.connect("localhost", server.getPort());
            assertEquals("安全", client.invoke("服务", "echo", String.class, new Class<?>[0], new Object[0]));
            try (RpcClient wrongHost = new RpcClient(config, SerializerRegistry.defaults(), (byte) 1, clientTls,
                RoundRobinLoadBalancer::new)) {
                assertThrows(RuntimeException.class, () -> wrongHost.connect("127.0.0.1", server.getPort()));
            }
        } finally {
            Files.deleteIfExists(certificate); Files.deleteIfExists(directory.resolve("keytool.log"));
            Files.deleteIfExists(directory);
        }
    }
}
