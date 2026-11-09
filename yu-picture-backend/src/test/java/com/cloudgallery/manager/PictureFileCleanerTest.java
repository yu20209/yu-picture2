package com.cloudgallery.manager;

import com.cloudgallery.mapper.PictureFileCleanupMapper;
import com.cloudgallery.mapper.PictureMapper;
import com.cloudgallery.model.entity.PictureFileCleanup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片文件补偿清理单元测试：引用检查、失败入队、重试成功、超过最大次数失败
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PictureFileCleanerTest {

    private static final String URL = "http://localhost:8123/api/pictures/public/1/test.jpg";

    @Mock
    private LocalPictureManager localPictureManager;

    @Mock
    private PictureMapper pictureMapper;

    @Mock
    private PictureFileCleanupMapper pictureFileCleanupMapper;

    private PictureFileCleaner pictureFileCleaner;

    @BeforeEach
    void setUp() {
        pictureFileCleaner = new PictureFileCleaner();
        ReflectionTestUtils.setField(pictureFileCleaner, "localPictureManager", localPictureManager);
        ReflectionTestUtils.setField(pictureFileCleaner, "pictureMapper", pictureMapper);
        ReflectionTestUtils.setField(pictureFileCleaner, "pictureFileCleanupMapper", pictureFileCleanupMapper);
    }

    /**
     * 文件仍被图片记录引用时不能删除
     */
    @Test
    void shouldNotDeleteReferencedFile() {
        when(pictureMapper.selectCount(any())).thenReturn(1L);

        assertTrue(pictureFileCleaner.cleanIfUnreferenced(URL, null));
        verify(localPictureManager, never()).deletePictureFile(any());
    }

    /**
     * 没有引用时直接删除文件
     */
    @Test
    void shouldDeleteUnreferencedFile() {
        when(pictureMapper.selectCount(any())).thenReturn(0L);

        assertTrue(pictureFileCleaner.cleanIfUnreferenced(URL, null));
        verify(localPictureManager).deletePictureFile(URL);
        verify(pictureFileCleanupMapper, never()).insert(any(PictureFileCleanup.class));
    }

    /**
     * 删除文件抛异常时写入补偿记录，状态为待处理
     */
    @Test
    void shouldEnqueueWhenDeleteFails() {
        when(pictureMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("磁盘文件被占用")).when(localPictureManager).deletePictureFile(URL);

        assertFalse(pictureFileCleaner.cleanIfUnreferenced(URL, null));

        ArgumentCaptor<PictureFileCleanup> captor = ArgumentCaptor.forClass(PictureFileCleanup.class);
        verify(pictureFileCleanupMapper).insert(captor.capture());
        PictureFileCleanup cleanup = captor.getValue();
        assertEquals(URL, cleanup.getUrl());
        assertEquals(PictureFileCleaner.STATUS_PENDING, cleanup.getStatus());
        assertEquals(0, cleanup.getRetryCount());
        assertNotNull(cleanup.getNextRetryTime());
        assertTrue(cleanup.getErrorMsg().contains("磁盘文件被占用"));
    }

    /**
     * 重试成功后将记录置为成功
     */
    @Test
    void retrySuccessShouldMarkSuccess() {
        PictureFileCleanup cleanup = newCleanup(0);
        when(pictureFileCleanupMapper.selectList(any())).thenReturn(Collections.singletonList(cleanup));
        when(pictureMapper.selectCount(any())).thenReturn(0L);

        pictureFileCleaner.retryFailedCleanups();

        verify(localPictureManager).deletePictureFile(URL);
        ArgumentCaptor<PictureFileCleanup> captor = ArgumentCaptor.forClass(PictureFileCleanup.class);
        verify(pictureFileCleanupMapper).updateById(captor.capture());
        assertEquals(PictureFileCleaner.STATUS_SUCCESS, captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getRetryCount());
    }

    /**
     * 重试时文件已被重新引用，直接视为成功，避免误删
     */
    @Test
    void retryReferencedFileShouldMarkSuccessWithoutDeleting() {
        PictureFileCleanup cleanup = newCleanup(0);
        when(pictureFileCleanupMapper.selectList(any())).thenReturn(Collections.singletonList(cleanup));
        when(pictureMapper.selectCount(any())).thenReturn(1L);

        pictureFileCleaner.retryFailedCleanups();

        verify(localPictureManager, never()).deletePictureFile(any());
        ArgumentCaptor<PictureFileCleanup> captor = ArgumentCaptor.forClass(PictureFileCleanup.class);
        verify(pictureFileCleanupMapper).updateById(captor.capture());
        assertEquals(PictureFileCleaner.STATUS_SUCCESS, captor.getValue().getStatus());
    }

    /**
     * 第 5 次重试仍失败时置为 FAILED，不再继续重试
     */
    @Test
    void retryOverMaxTimesShouldMarkFailed() {
        PictureFileCleanup cleanup = newCleanup(4);
        when(pictureFileCleanupMapper.selectList(any())).thenReturn(Collections.singletonList(cleanup));
        when(pictureMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("仍然删除失败")).when(localPictureManager).deletePictureFile(URL);

        pictureFileCleaner.retryFailedCleanups();

        ArgumentCaptor<PictureFileCleanup> captor = ArgumentCaptor.forClass(PictureFileCleanup.class);
        verify(pictureFileCleanupMapper).updateById(captor.capture());
        assertEquals(PictureFileCleaner.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals(5, captor.getValue().getRetryCount());
    }

    /**
     * 重试失败后应按退避策略安排下一次重试时间
     */
    @Test
    void retryFailedShouldReschedule() {
        PictureFileCleanup cleanup = newCleanup(1);
        when(pictureFileCleanupMapper.selectList(any())).thenReturn(Collections.singletonList(cleanup));
        when(pictureMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RuntimeException("删除失败")).when(localPictureManager).deletePictureFile(URL);

        pictureFileCleaner.retryFailedCleanups();

        ArgumentCaptor<PictureFileCleanup> captor = ArgumentCaptor.forClass(PictureFileCleanup.class);
        verify(pictureFileCleanupMapper).updateById(captor.capture());
        PictureFileCleanup updated = captor.getValue();
        assertEquals(PictureFileCleaner.STATUS_PENDING, updated.getStatus());
        assertEquals(2, updated.getRetryCount());
        Date retryTime = updated.getNextRetryTime();
        assertNotNull(retryTime);
        // 第 2 次重试失败后，下次重试间隔应为 30 分钟
        long delayMillis = retryTime.getTime() - System.currentTimeMillis();
        assertTrue(delayMillis > 25 * 60 * 1000 && delayMillis <= 31 * 60 * 1000,
                "下次重试应在 30 分钟左右，实际间隔毫秒数 = " + delayMillis);
    }

    private PictureFileCleanup newCleanup(int retryCount) {
        PictureFileCleanup cleanup = new PictureFileCleanup();
        cleanup.setId(1L);
        cleanup.setUrl(URL);
        cleanup.setRetryCount(retryCount);
        cleanup.setStatus(PictureFileCleaner.STATUS_PENDING);
        cleanup.setNextRetryTime(new Date());
        return cleanup;
    }
}
