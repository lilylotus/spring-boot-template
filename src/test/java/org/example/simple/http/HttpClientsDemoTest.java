package org.example.simple.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class HttpClientsDemoTest {

    @Test
    void testHttpGet() {
        String url = "http://127.0.0.1:54100/welcome";
        System.out.println(HttpClients.get(url).bodyAsString());
        HttpRequestData hrd = HttpRequestData.get(url).queryParameter("key", "value").header("key1", "value1").build();
        System.out.println(HttpClients.execute(hrd).bodyAsString());
    }

    @Test
    void testHttpPost() {
        String url = "http://127.0.0.1:54100/test/post/echo";
        Map<String, Object> body = new HashMap<>();
        body.put("key", "value");
        body.put("key1", "value1");
        body.put("key2", "value2");
        body.put("key3", LocalDateTime.now());
        HttpRequestData hrd = HttpRequestData.post(url).timeout(Duration.ofSeconds(10)).body(new JsonRequestBody(body)).build();
        System.out.println(HttpClients.execute(hrd).bodyAsString());
    }

    @Test
    void testHttpPut() {
        String url = "http://127.0.0.1:54100/test/put/echo";
        Map<String, Object> body = new HashMap<>();
        body.put("key", "value");
        body.put("key1", "value1");
        body.put("key2", "value2");
        body.put("key3", LocalDateTime.now());
        HttpRequestData hrd = HttpRequestData.put(url).body(new JsonRequestBody(body)).build();
        System.out.println(HttpClients.execute(hrd).bodyAsString());
    }

    @Test
    void testHttpDelete() {
        String url = "http://127.0.0.1:54100/test/delete/echo";
        Map<String, Object> body = new HashMap<>();
        body.put("key", "value");
        body.put("key1", "value1");
        body.put("key2", "value2");
        body.put("key3", LocalDateTime.now());
        HttpRequestData hrd = HttpRequestData.delete(url).body(new JsonRequestBody(body)).build();
        System.out.println(HttpClients.execute(hrd).bodyAsString());
    }

}
