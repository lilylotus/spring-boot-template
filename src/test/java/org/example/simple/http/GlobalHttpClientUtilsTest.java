package org.example.simple.http;

import org.example.simple.util.JacksonUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class GlobalHttpClientUtilsTest {

    @Test
    void testGet() {
        String url = "http://127.0.0.1:54100/test/v5/ok";
        GlobalHttpClientUtils.HttpRequestData request = GlobalHttpClientUtils.HttpRequestData.builder(url).build();
        GlobalHttpClientUtils.HttpClientResponse clientResponse = GlobalHttpClientUtils.get(request);
        Map<String, Object> data = clientResponse.bodyAsMap();
        System.out.println(data);
        System.out.println(clientResponse.bodyAsString());
    }

    @Test
    void testPost() {
        Map<String, Object> data = new HashMap<>(8);
        data.put("key", "value");
        data.put("key2", "value2");
        data.put("key3", LocalDateTime.now());

        String url = "http://127.0.0.1:54100/test/post/echo";
        GlobalHttpClientUtils.HttpRequestData.Builder builder = GlobalHttpClientUtils.HttpRequestData.builder(url);
        builder.raw(JacksonUtils.toJson(data), "application/json");
        builder.header("Content-Type", "application/json");
        builder.header("ak", "akValue");
        GlobalHttpClientUtils.HttpRequestData requestData = builder.build();
        GlobalHttpClientUtils.HttpClientResponse clientResponse = GlobalHttpClientUtils.post(requestData);
        System.out.println(clientResponse.bodyAsString());
    }

    @Test
    void testPostUploadFile() {
        String url = "http://127.0.0.1:18888/bigfile/upload";
        GlobalHttpClientUtils.HttpRequestData.Builder builder = GlobalHttpClientUtils.HttpRequestData.builder(url);
        builder.formData(fd ->
            fd.text("FLAG", UUID.randomUUID().toString().replace("-", ""))
                .file("FILE", new File("C:\\Users\\Administrator\\Pictures\\star-face-img3.png"), "application/octet-stream")
        );
        GlobalHttpClientUtils.HttpRequestData requestData = builder.build();
        GlobalHttpClientUtils.HttpClientResponse clientResponse = GlobalHttpClientUtils.post(requestData);
        System.out.println(clientResponse.bodyAsString());
    }


    @Test
    void testPostUploadFileStream() throws FileNotFoundException {
        String url = "http://127.0.0.1:18888/bigfile/upload";
        GlobalHttpClientUtils.HttpRequestData.Builder builder = GlobalHttpClientUtils.HttpRequestData.builder(url);
        FileInputStream fis = new FileInputStream(new File("C:\\Users\\Administrator\\Pictures\\star-face-img3.png"));
        builder.formData(fd ->
            fd.text("FLAG", UUID.randomUUID().toString().replace("-", ""))
                .file("FILE", "star-face-img3.png", "application/octet-stream", fis)
        );
        GlobalHttpClientUtils.HttpRequestData requestData = builder.build();
        GlobalHttpClientUtils.HttpClientResponse clientResponse = GlobalHttpClientUtils.post(requestData);
        System.out.println(clientResponse.bodyAsString());
    }

    @Test
    void testPostUrlEncoded() {
        String url = "http://127.0.0.1:54100/test/cat/logout";
        Map<String, String> data = new HashMap<>(8);
        data.put("key", "value");
        data.put("key2", "value2");
        data.put("key3", "value3");
        GlobalHttpClientUtils.HttpRequestData requestData = GlobalHttpClientUtils.HttpRequestData.builder(url)
            .urlEncoded(data)
            .build();
        GlobalHttpClientUtils.HttpClientResponse clientResponse = GlobalHttpClientUtils.post(requestData);
        System.out.println(clientResponse.bodyAsString());
    }

}
