package com.cloudgallery.manager.cache;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudgallery.model.dto.picture.PictureQueryRequest;
import com.cloudgallery.model.entity.Picture;
import com.cloudgallery.model.vo.PictureVO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 图片列表多级缓存（Caffeine 本地缓存 + Redis 分布式缓存）
 * <p>
 * 一致性方案：缓存 key 中带"数据版本号"，任何写操作只需对版本号执行 INCR，
 * 旧 key 自然失效，避免使用 KEYS / SCAN 批量删除带来的性能问题。
 */
@Slf4j
@Component
public class PictureCacheManager {

    /**
     * 缓存 key 前缀
     */
    private static final String CACHE_KEY_PREFIX = "cloudgallery:pic:list";

    /**
     * 版本号 key：cloudgallery:pic:list:{scope}:version
     */
    private static final String VERSION_KEY_TEMPLATE = CACHE_KEY_PREFIX + ":%s:version";

    /**
     * 互斥重建锁 key
     */
    private static final String LOCK_KEY_TEMPLATE = CACHE_KEY_PREFIX + ":lock:%s";

    /**
     * 正常结果过期时间：300 ~ 600 秒（随机，避免同时过期导致缓存雪崩）
     */
    private static final int NORMAL_EXPIRE_SECONDS = 300;

    /**
     * 空结果过期时间：60 ~ 90 秒（缓存空值，防止缓存穿透）
     */
    private static final int EMPTY_EXPIRE_SECONDS = 60;

    /**
     * 重建锁过期时间（秒），防止持锁进程宕机后死锁
     */
    private static final long LOCK_EXPIRE_SECONDS = 5L;

    /**
     * 拿不到重建锁时，等待并重试读取缓存的次数
     */
    private static final int LOCK_RETRY_TIMES = 3;

    /**
     * 等待重建的间隔（毫秒）
     */
    private static final long LOCK_WAIT_MILLIS = 100L;

    /**
     * 释放锁时校验持有者，避免误删其他线程的锁
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 本地缓存：只做短时间的兜底，跨节点一致性依靠 Redis 版本号
     */
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .initialCapacity(1024)
            .maximumSize(10_000L)
            .expireAfterWrite(Duration.ofSeconds(15))
            .build();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 读取缓存，未命中则重建（带互斥锁，避免热点 key 被同时击穿）
     *
     * @param pictureQueryRequest 查询条件（必须已经补齐审核状态、空间条件等）
     * @param loader              缓存未命中时的数据库加载逻辑
     * @return 分页结果
     */
    public Page<PictureVO> getOrLoad(PictureQueryRequest pictureQueryRequest, Supplier<Page<PictureVO>> loader) {
        String scope = resolveScope(pictureQueryRequest);
        String version = getVersion(scope);
        String cacheKey = buildCacheKey(scope, version, pictureQueryRequest);
        Page<PictureVO> cachedPage = getFromCache(cacheKey);
        if (cachedPage != null) {
            return cachedPage;
        }
        // 热点 key 互斥重建，拿不到锁的线程等待后重读缓存，避免全部打到数据库
        String lockKey = String.format(LOCK_KEY_TEMPLATE, cacheKey);
        String lockValue = UUID.randomUUID().toString();
        if (!tryLock(lockKey, lockValue)) {
            for (int i = 0; i < LOCK_RETRY_TIMES; i++) {
                sleep(LOCK_WAIT_MILLIS);
                cachedPage = getFromCache(cacheKey);
                if (cachedPage != null) {
                    return cachedPage;
                }
            }
            // 等待超时，直接查库兜底（不写缓存，避免与持锁线程互相覆盖）
            return loader.get();
        }
        try {
            // 双重检查：可能刚拿到锁，缓存已经被上一个持锁线程重建好了
            cachedPage = getFromCache(cacheKey);
            if (cachedPage != null) {
                return cachedPage;
            }
            Page<PictureVO> page = loader.get();
            putToCache(cacheKey, page);
            return page;
        } finally {
            unlock(lockKey, lockValue);
        }
    }

    /**
     * 失效某个空间的图片列表缓存；spaceId 为空表示公共图库
     */
    public void evictBySpaceId(Long spaceId) {
        evict(resolveScope(spaceId));
    }

    /**
     * 失效某张图片对应的缓存
     */
    public void evictByPicture(Picture picture) {
        if (picture == null) {
            return;
        }
        evictBySpaceId(picture.getSpaceId());
    }

    /**
     * 通过版本号自增使该作用域下的所有缓存失效
     */
    public void evict(String scope) {
        try {
            stringRedisTemplate.opsForValue().increment(String.format(VERSION_KEY_TEMPLATE, scope));
        } catch (Exception e) {
            log.error("图片列表缓存版本号更新失败，scope = {}", scope, e);
        }
    }

    private String resolveScope(PictureQueryRequest pictureQueryRequest) {
        return resolveScope(pictureQueryRequest == null ? null : pictureQueryRequest.getSpaceId());
    }

    private String resolveScope(Long spaceId) {
        return spaceId == null ? "public" : "space:" + spaceId;
    }

    private String getVersion(String scope) {
        try {
            String version = stringRedisTemplate.opsForValue().get(String.format(VERSION_KEY_TEMPLATE, scope));
            // 版本号不存在时从 0 开始，第一次 evict 会将其自增为 1
            return StrUtil.blankToDefault(version, "0");
        } catch (Exception e) {
            log.error("读取图片列表缓存版本号失败，scope = {}", scope, e);
            return "0";
        }
    }

    private String buildCacheKey(String scope, String version, PictureQueryRequest pictureQueryRequest) {
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes(StandardCharsets.UTF_8));
        return String.format("%s:%s:%s:%s", CACHE_KEY_PREFIX, scope, version, hashKey);
    }

    private Page<PictureVO> getFromCache(String cacheKey) {
        // 1. 本地缓存
        String cachedValue = localCache.getIfPresent(cacheKey);
        if (cachedValue != null) {
            return deserialize(cachedValue);
        }
        // 2. Redis 分布式缓存
        try {
            cachedValue = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.error("读取 Redis 图片列表缓存失败，cacheKey = {}", cacheKey, e);
            return null;
        }
        if (cachedValue == null) {
            return null;
        }
        // 回填本地缓存
        localCache.put(cacheKey, cachedValue);
        return deserialize(cachedValue);
    }

    private void putToCache(String cacheKey, Page<PictureVO> page) {
        if (page == null) {
            return;
        }
        String cacheValue = serialize(page);
        // 空结果也要缓存（短过期），防止大量不存在的查询条件穿透到数据库
        int expireSeconds = CollUtil.isEmpty(page.getRecords())
                ? EMPTY_EXPIRE_SECONDS + RandomUtil.randomInt(0, 30)
                : NORMAL_EXPIRE_SECONDS + RandomUtil.randomInt(0, 300);
        try {
            ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
            opsForValue.set(cacheKey, cacheValue, expireSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("写入 Redis 图片列表缓存失败，cacheKey = {}", cacheKey, e);
            return;
        }
        localCache.put(cacheKey, cacheValue);
    }

    private boolean tryLock(String lockKey, String lockValue) {
        try {
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockValue, LOCK_EXPIRE_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            // Redis 异常时放弃互斥，退化为直接查库，保证接口可用性
            log.error("获取图片列表缓存重建锁失败，lockKey = {}", lockKey, e);
            return false;
        }
    }

    private void unlock(String lockKey, String lockValue) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("释放图片列表缓存重建锁失败，lockKey = {}", lockKey, e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String serialize(Page<PictureVO> page) {
        CachedPage cachedPage = new CachedPage();
        cachedPage.setRecords(page.getRecords());
        cachedPage.setCurrent(page.getCurrent());
        cachedPage.setSize(page.getSize());
        cachedPage.setTotal(page.getTotal());
        return JSONUtil.toJsonStr(cachedPage);
    }

    private Page<PictureVO> deserialize(String cacheValue) {
        try {
            CachedPage cachedPage = JSONUtil.toBean(cacheValue, CachedPage.class);
            Page<PictureVO> page = new Page<>(cachedPage.getCurrent(), cachedPage.getSize(), cachedPage.getTotal());
            page.setRecords(cachedPage.getRecords());
            return page;
        } catch (Exception e) {
            log.error("解析图片列表缓存失败，直接按未命中处理", e);
            return null;
        }
    }

    /**
     * 缓存的分页结构：只保留分页需要的字段，避免直接反序列化 Page 时泛型丢失
     */
    @Data
    public static class CachedPage implements Serializable {

        private List<PictureVO> records;

        private long current;

        private long size;

        private long total;

        private static final long serialVersionUID = 1L;
    }
}
