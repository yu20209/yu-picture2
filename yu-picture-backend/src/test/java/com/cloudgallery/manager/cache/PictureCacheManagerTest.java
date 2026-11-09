package com.cloudgallery.manager.cache;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudgallery.model.dto.picture.PictureQueryRequest;
import com.cloudgallery.model.vo.PictureVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多级缓存单元测试：用 ConcurrentHashMap 模拟 Redis，不依赖真实中间件
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PictureCacheManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PictureCacheManager pictureCacheManager;

    private Map<String, String> redis;

    @BeforeEach
    void setUp() {
        redis = new ConcurrentHashMap<>();
        pictureCacheManager = new PictureCacheManager();
        ReflectionTestUtils.setField(pictureCacheManager, "stringRedisTemplate", stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        when(valueOperations.setIfAbsent(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)))
                .thenAnswer(invocation -> redis.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        // set(key, value, timeout, unit)：模拟写入 Redis
        org.mockito.Mockito.doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long next = Long.parseLong(redis.getOrDefault(key, "0")) + 1;
            redis.put(key, String.valueOf(next));
            return next;
        });
    }

    /**
     * 第一次回源，第二次命中本地/Redis 缓存
     */
    @Test
    void secondCallShouldHitCache() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSpaceId(10L);
        AtomicInteger loadCount = new AtomicInteger();
        Page<PictureVO> page = new Page<>(1, 10, 0);

        Page<PictureVO> first = pictureCacheManager.getOrLoad(request, () -> {
            loadCount.incrementAndGet();
            return page;
        });
        Page<PictureVO> second = pictureCacheManager.getOrLoad(request, () -> {
            loadCount.incrementAndGet();
            return page;
        });

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(1, loadCount.get(), "第二次请求应命中缓存，不再回源");
    }

    /**
     * 版本号自增后旧缓存失效，需要重新回源
     */
    @Test
    void evictShouldInvalidateByVersion() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSpaceId(20L);
        AtomicInteger loadCount = new AtomicInteger();
        Page<PictureVO> page = new Page<>(1, 10, 0);

        pictureCacheManager.getOrLoad(request, () -> {
            loadCount.incrementAndGet();
            return page;
        });
        pictureCacheManager.evictBySpaceId(20L);
        pictureCacheManager.getOrLoad(request, () -> {
            loadCount.incrementAndGet();
            return page;
        });

        assertEquals(2, loadCount.get());
        assertEquals("1", redis.get("cloudgallery:pic:list:space:20:version"));
    }

    /**
     * spaceId 为空时失效公共图库作用域
     */
    @Test
    void evictByPictureWithoutSpaceShouldInvalidatePublicScope() {
        // spaceId 为空，代表公共图库
        com.cloudgallery.model.entity.Picture picture = new com.cloudgallery.model.entity.Picture();
        pictureCacheManager.evictByPicture(picture);
        assertEquals("1", redis.get("cloudgallery:pic:list:public:version"));
    }

    /**
     * 空结果使用较短的过期时间，避免大量不存在的查询穿透到数据库
     */
    @Test
    void emptyResultShouldUseShortTtl() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSpaceId(30L);

        pictureCacheManager.getOrLoad(request, () -> new Page<>(1, 10, 0));

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> unitCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(valueOperations).set(anyString(), anyString(), ttlCaptor.capture(), unitCaptor.capture());
        assertEquals(TimeUnit.SECONDS, unitCaptor.getValue());
        assertTrue(ttlCaptor.getValue() >= 60 && ttlCaptor.getValue() <= 90,
                "空结果缓存过期时间应在 60~90 秒之间，实际为 " + ttlCaptor.getValue());
    }

    /**
     * 缓存内容能正确序列化/反序列化（分页字段与记录都不丢失）
     */
    @Test
    void cachedPageShouldKeepRecords() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSpaceId(40L);
        Page<PictureVO> page = new Page<>(2, 10, 25);
        PictureVO pictureVO = new PictureVO();
        pictureVO.setId(1L);
        pictureVO.setName("test");
        List<PictureVO> records = new ArrayList<>();
        records.add(pictureVO);
        page.setRecords(records);

        pictureCacheManager.getOrLoad(request, () -> page);
        // 清空本地缓存，强制走 Redis 反序列化路径
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<String, String> localCache =
                (com.github.benmanes.caffeine.cache.Cache<String, String>)
                        ReflectionTestUtils.getField(pictureCacheManager, "localCache");
        localCache.invalidateAll();

        Page<PictureVO> cached = pictureCacheManager.getOrLoad(request, () -> {
            throw new AssertionError("不应回源");
        });
        assertEquals(2, cached.getCurrent());
        assertEquals(10, cached.getSize());
        assertEquals(25, cached.getTotal());
        assertEquals(1, cached.getRecords().size());
        assertEquals("test", cached.getRecords().get(0).getName());
    }

    /**
     * 热点 key 并发访问时只允许一个线程回源，其余线程等待后读取缓存
     */
    @Test
    void concurrentRequestsShouldLoadOnce() throws Exception {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSpaceId(50L);
        AtomicInteger loadCount = new AtomicInteger();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(1, TimeUnit.SECONDS);
                pictureCacheManager.getOrLoad(request, () -> {
                    loadCount.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new Page<>(1, 10, 0);
                });
                return null;
            }));
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, loadCount.get(), "并发场景下只应有一个线程回源，其余等待缓存");
    }
}
