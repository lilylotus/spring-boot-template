package com.example.template.file.service;

import com.example.template.file.config.FileStorageProperties;
import com.example.template.file.entity.FileStatus;
import com.example.template.file.entity.FileUpload;
import com.example.template.file.exception.FileStorageException;
import com.example.template.file.mapper.FileUploadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LocalFileStorageService} 的离线单元测试，用Mockito替换{@link FileUploadMapper}，
 * 不连接任何真实数据库；磁盘写入落在JUnit的临时目录，测试结束后自动清理。
 */
class LocalFileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUploadFileAndPersistRecord() {
        FileUploadMapper mapper = mockMapperThatAssignsId(1L);
        LocalFileStorageService service = createService(mapper);
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "内容".getBytes(StandardCharsets.UTF_8));

        FileUpload saved = service.upload(file);

        assertEquals(1L, saved.getId());
        assertEquals("report.pdf", saved.getOriginalName());
        assertNotEquals("report.pdf", saved.getStoredName());
        assertTrue(saved.getStoredName().endsWith(".pdf"));
        assertEquals(FileStatus.NORMAL, saved.getStatus());
        assertTrue(Files.exists(tempDir.resolve(saved.getStoredName())));
    }

    @Test
    void shouldGenerateStoredNameWithoutExtensionWhenOriginalNameHasNone() {
        FileUploadMapper mapper = mockMapperThatAssignsId(2L);
        LocalFileStorageService service = createService(mapper);
        MultipartFile file = new MockMultipartFile("file", "README", "text/plain", "内容".getBytes(StandardCharsets.UTF_8));

        FileUpload saved = service.upload(file);

        assertFalse(saved.getStoredName().contains("."));
    }

    @Test
    void shouldRejectEmptyFile() {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        LocalFileStorageService service = createService(mapper);
        MultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        FileStorageException exception = assertThrows(FileStorageException.class, () -> service.upload(emptyFile));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
    }

    @Test
    void shouldDeleteWrittenFileWhenDatabaseInsertFails() {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        when(mapper.insert(any(FileUpload.class))).thenThrow(new RuntimeException("模拟数据库写入失败"));
        LocalFileStorageService service = createService(mapper);
        MultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "内容".getBytes(StandardCharsets.UTF_8));

        assertThrows(RuntimeException.class, () -> service.upload(file));

        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        } catch (IOException e) {
            throw new UncheckedIOExceptionForTest(e);
        }
    }

    @Test
    void shouldDownloadNormalStatusFile() throws IOException {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        Path storedFile = tempDir.resolve("abc123.pdf");
        Files.writeString(storedFile, "文件内容");
        FileUpload record = normalRecord(10L, "report.pdf", "abc123.pdf");
        when(mapper.selectById(10L)).thenReturn(record);
        LocalFileStorageService service = createService(mapper);

        FileDownloadResult result = service.prepareDownload(10L);

        assertEquals("report.pdf", result.originalName());
        assertTrue(result.resource().exists());
    }

    @Test
    void shouldRejectDownloadForMissingRecord() {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        when(mapper.selectById(99L)).thenReturn(null);
        LocalFileStorageService service = createService(mapper);

        FileStorageException exception = assertThrows(FileStorageException.class, () -> service.prepareDownload(99L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
    }

    @Test
    void shouldRejectDownloadForDisabledStatus() {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        FileUpload record = normalRecord(11L, "report.pdf", "def456.pdf");
        record.setStatus(FileStatus.DISABLED);
        when(mapper.selectById(11L)).thenReturn(record);
        LocalFileStorageService service = createService(mapper);

        FileStorageException exception = assertThrows(FileStorageException.class, () -> service.prepareDownload(11L));

        assertEquals(HttpStatus.GONE, exception.getHttpStatus());
    }

    @Test
    void shouldRejectDownloadWhenDiskFileMissing() {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        FileUpload record = normalRecord(12L, "report.pdf", "missing.pdf");
        when(mapper.selectById(12L)).thenReturn(record);
        LocalFileStorageService service = createService(mapper);

        FileStorageException exception = assertThrows(FileStorageException.class, () -> service.prepareDownload(12L));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getHttpStatus());
    }

    private LocalFileStorageService createService(FileUploadMapper mapper) {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setBaseDir(tempDir.toString());
        return new LocalFileStorageService(mapper, properties);
    }

    private FileUploadMapper mockMapperThatAssignsId(long id) {
        FileUploadMapper mapper = mock(FileUploadMapper.class);
        when(mapper.insert(any(FileUpload.class))).thenAnswer(invocation -> {
            FileUpload argument = invocation.getArgument(0);
            argument.setId(id);
            return 1;
        });
        return mapper;
    }

    private FileUpload normalRecord(Long id, String originalName, String storedName) {
        FileUpload record = new FileUpload();
        record.setId(id);
        record.setOriginalName(originalName);
        record.setStoredName(storedName);
        record.setStatus(FileStatus.NORMAL);
        return record;
    }

    /**
     * 把测试内部的{@link IOException}包装成非受检异常，避免lambda内的try-catch污染断言逻辑。
     */
    private static final class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(IOException cause) {
            super(cause);
        }
    }
}
