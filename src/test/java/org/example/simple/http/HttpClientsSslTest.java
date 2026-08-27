package org.example.simple.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTP 客户端 SSL 安全默认值和显式跳过校验测试。
 */
class HttpClientsSslTest {

    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    private HttpsServer server;
    private String serverUrl;

    @BeforeEach
    void startHttpsServer() throws Exception {
        HttpClients.configure(HttpClientConfig.defaults());
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(createServerSslContext()));
        server.createContext("/secure", exchange -> {
            byte[] body = "安全响应".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        serverUrl = "https://localhost:" + server.getAddress().getPort() + "/secure";
    }

    @AfterEach
    void stopServer() {
        HttpClients.shutdown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void strictClientRejectsSelfSignedCertificate() {
        HttpClientException exception = assertThrows(
            HttpClientException.class,
            () -> HttpClients.get(serverUrl));

        assertEquals(HttpErrorType.TRANSPORT, exception.getErrorType());
    }

    @Test
    void explicitlyInsecureClientAcceptsSelfSignedCertificate() {
        HttpClientConfig config = HttpClientConfig.builder()
            .skipSslVerification(true)
            .build();

        HttpClients.configure(config);

        assertEquals("安全响应", HttpClients.get(serverUrl).bodyAsString());
    }

    private SSLContext createServerSslContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(readKeyStoreBytes()), STORE_PASSWORD);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, STORE_PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return sslContext;
    }

    private byte[] readKeyStoreBytes() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/http/self-signed-localhost.p12.base64")) {
            if (input == null) {
                throw new IOException("找不到 HTTPS 测试密钥库资源");
            }
            String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim();
            return Base64.getDecoder().decode(encoded);
        }
    }
}
