package com.example.template.file.controller;

import com.example.template.exception.GlobalExceptionHandler;
import com.example.template.file.entity.FileUpload;
import com.example.template.file.exception.FileStorageException;
import com.example.template.file.service.FileDownloadResult;
import com.example.template.file.service.FileStorageService;
import com.example.template.log4j2.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FileController} 的Spring MVC层测试，用{@link MockitoBean}把{@link FileStorageService}
 * 换成可控行为的mock，不依赖真实磁盘或数据库，只验证Controller与全局异常处理器的协作行为。
 */
@WebMvcTest(controllers = FileController.class)
@ContextConfiguration(classes = {
    FileController.class,
    GlobalExceptionHandler.class,
    TraceIdFilter.class
})
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileStorageService fileStorageService;

    @Test
    void shouldReturnFileIdAfterSuccessfulUpload() throws Exception {
        FileUpload saved = new FileUpload();
        saved.setId(1L);
        saved.setOriginalName("report.pdf");
        saved.setFileSize(4L);
        saved.setCreateTime(LocalDateTime.now());
        when(fileStorageService.upload(any())).thenReturn(saved);

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "内容".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/files/upload").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.originalName").value("report.pdf"));
    }

    @Test
    void shouldRejectUploadWhenServiceThrowsFileStorageException() throws Exception {
        when(fileStorageService.upload(any()))
            .thenThrow(new FileStorageException(HttpStatus.BAD_REQUEST, "上传文件不能为空"));
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/files/upload").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("上传文件不能为空"));
    }

    @Test
    void shouldDownloadFileWithOriginalNameInContentDisposition() throws Exception {
        FileDownloadResult result = new FileDownloadResult(
            new ByteArrayResource("文件内容".getBytes(StandardCharsets.UTF_8)), "report.pdf", "application/pdf");
        when(fileStorageService.prepareDownload(eq(1L))).thenReturn(result);

        mockMvc.perform(get("/api/files/{id}/download", 1L))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("report.pdf")))
            .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void shouldReturnNotFoundWhenRecordMissing() throws Exception {
        when(fileStorageService.prepareDownload(eq(99L)))
            .thenThrow(new FileStorageException(HttpStatus.NOT_FOUND, "文件不存在"));

        mockMvc.perform(get("/api/files/{id}/download", 99L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("文件不存在"));
    }

    @Test
    void shouldReturnGoneWhenFileDisabled() throws Exception {
        when(fileStorageService.prepareDownload(eq(2L)))
            .thenThrow(new FileStorageException(HttpStatus.GONE, "文件已失效，无法下载"));

        mockMvc.perform(get("/api/files/{id}/download", 2L))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value(410))
            .andExpect(jsonPath("$.message").value("文件已失效，无法下载"));
    }
}
