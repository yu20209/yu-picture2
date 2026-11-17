package com.cloudgallery.manager;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudgallery.mapper.PictureFileCleanupMapper;
import com.cloudgallery.mapper.PictureMapper;
import com.cloudgallery.model.entity.Picture;
import com.cloudgallery.model.entity.PictureFileCleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 图片文件清理器
 * <p>
 * 负责在数据库事务提交之后删除不再被引用的图片文件：
 * 1. 正常情况下异步删除，删除前先做引用检查，避免误删其他记录仍在使用的文件；
 * 2. 文件删除失败时写入补偿表，由定时任务按 1/5/30/120/600 分钟的退避策略重试；
 * 3. 入库失败等场景需要立即回收本次新落盘的文件，使用 {@link #cleanOrphanFile(String)}。
 */
@Slf4j
@Component
public class PictureFileCleaner {

    /**
     * 待处理
     */
    public static final int STATUS_PENDING = 0;

    /**
     * 清理成功
     */
    public static final int STATUS_SUCCESS = 1;

    /**
     * 清理失败（异常任务区，超过最大重试次数后等待人工排查）
     */
    public static final int STATUS_FAILED = 2;

    /**
     * 处理中（已被某个消费者领取，避免重复处理）
     */
    public static final int STATUS_PROCESSING = 3;

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * 处理中状态超过该时长仍未结束，认为消费者异常退出，任务需重新放回待处理
     */
    private static final int PROCESSING_TIMEOUT_MINUTES = 10;

    /**
     * 每次重试的退避间隔（分钟），与 MAX_RETRY_COUNT 对应
     */
    private static final long[] RETRY_DELAY_MINUTES = {1, 5, 30, 120, 600};

    /**
     * 定时任务每批处理的记录数
     */
    private static final int BATCH_SIZE = 20;

    /**
     * 错误信息最大长度
     */
    private static final int MAX_ERROR_MSG_LENGTH = 1024;

    @Resource
    private LocalPictureManager localPictureManager;

    @Resource
    private PictureMapper pictureMapper;

    @Resource
    private PictureFileCleanupMapper pictureFileCleanupMapper;

    /**
     * 异步清理不再被引用的文件（在事务提交后调用，失败不阻塞主流程）
     *
     * @param url              文件地址
     * @param excludePictureId 需要排除的图片 id（覆盖上传时排除旧记录本身）
     */
    @Async
    public void asyncCleanIfUnreferenced(String url, Long excludePictureId) {
        try {
            cleanIfUnreferenced(url, excludePictureId);
        } catch (Exception e) {
            log.error("异步清理图片文件失败，url = {}", url, e);
        }
    }

    /**
     * 引用检查后删除文件
     *
     * @return true 表示文件已删除或无需删除；false 表示文件确实存在但删除失败（已进入补偿队列）
     */
    public boolean cleanIfUnreferenced(String url, Long excludePictureId) {
        if (StrUtil.isBlank(url)) {
            return true;
        }
        boolean referenced;
        try {
            referenced = isReferenced(url, excludePictureId);
        } catch (Exception e) {
            // 引用检查失败（如数据库异常）时无法确认文件是否仍被使用，保守起见不删除，进入补偿队列稍后重试
            log.error("图片文件引用检查失败，已加入补偿队列，url = {}", url, e);
            enqueue(url, e);
            return false;
        }
        if (referenced) {
            return true;
        }
        try {
            localPictureManager.deletePictureFile(url);
            return true;
        } catch (Exception e) {
            log.error("删除图片文件失败，已加入补偿队列，url = {}", url, e);
            enqueue(url, e);
            return false;
        }
    }

    /**
     * 直接删除本次新落盘、还没有被数据库引用的文件（用于上传入库失败的兜底回收）
     */
    public void cleanOrphanFile(String url) {
        if (StrUtil.isBlank(url)) {
            return;
        }
        try {
            localPictureManager.deletePictureFile(url);
        } catch (Exception e) {
            log.error("回收孤儿图片文件失败，已加入补偿队列，url = {}", url, e);
            enqueue(url, e);
        }
    }

    /**
     * 检查文件是否仍被图片记录引用（原图或缩略图）
     */
    public boolean isReferenced(String url, Long excludePictureId) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        LambdaQueryWrapper<Picture> wrapper = new LambdaQueryWrapper<Picture>()
                .and(w -> w.eq(Picture::getUrl, url).or().eq(Picture::getThumbnailUrl, url));
        if (excludePictureId != null && excludePictureId > 0) {
            wrapper.ne(Picture::getId, excludePictureId);
        }
        Long count = pictureMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 定时消费补偿任务：先恢复卡死的任务，再逐条领取并处理
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void retryFailedCleanups() {
        // 1. 消费者可能在上一次处理中异常退出，先把长时间停留在「处理中」的任务放回待处理
        recoverStuckTasks();
        // 2. 取出到期的待处理任务
        LambdaQueryWrapper<PictureFileCleanup> wrapper = new LambdaQueryWrapper<PictureFileCleanup>()
                .eq(PictureFileCleanup::getStatus, STATUS_PENDING)
                .le(PictureFileCleanup::getNextRetryTime, new Date())
                .orderByAsc(PictureFileCleanup::getNextRetryTime)
                .last("limit " + BATCH_SIZE);
        List<PictureFileCleanup> cleanupList;
        try {
            cleanupList = pictureFileCleanupMapper.selectList(wrapper);
        } catch (Exception e) {
            log.error("查询文件补偿记录失败", e);
            return;
        }
        if (cleanupList == null || cleanupList.isEmpty()) {
            return;
        }
        for (PictureFileCleanup cleanup : cleanupList) {
            // 3. 领取任务：抢占成功才处理，避免多个消费者（多线程/多实例）重复处理同一条任务
            if (!claim(cleanup.getId())) {
                continue;
            }
            retryOne(cleanup);
        }
    }

    /**
     * 领取任务：通过条件更新把状态从待处理改为处理中，更新成功即代表领取成功
     */
    private boolean claim(Long id) {
        LambdaUpdateWrapper<PictureFileCleanup> wrapper = new LambdaUpdateWrapper<PictureFileCleanup>()
                .eq(PictureFileCleanup::getId, id)
                .eq(PictureFileCleanup::getStatus, STATUS_PENDING)
                .set(PictureFileCleanup::getStatus, STATUS_PROCESSING)
                .set(PictureFileCleanup::getUpdateTime, new Date());
        try {
            return pictureFileCleanupMapper.update(null, wrapper) > 0;
        } catch (Exception e) {
            log.error("领取文件补偿任务失败，id = {}", id, e);
            return false;
        }
    }

    /**
     * 恢复卡死任务：处理中超时说明消费者已异常退出，放回待处理并计入重试次数，避免无限循环
     */
    private void recoverStuckTasks() {
        Date staleTime = new Date(System.currentTimeMillis()
                - TimeUnit.MINUTES.toMillis(PROCESSING_TIMEOUT_MINUTES));
        LambdaUpdateWrapper<PictureFileCleanup> wrapper = new LambdaUpdateWrapper<PictureFileCleanup>()
                .eq(PictureFileCleanup::getStatus, STATUS_PROCESSING)
                .le(PictureFileCleanup::getUpdateTime, staleTime)
                .set(PictureFileCleanup::getStatus, STATUS_PENDING)
                .set(PictureFileCleanup::getUpdateTime, new Date())
                .setSql("retryCount = retryCount + 1");
        try {
            int count = pictureFileCleanupMapper.update(null, wrapper);
            if (count > 0) {
                log.warn("发现 {} 条处理中超时的文件补偿任务，已重新放回待处理", count);
            }
        } catch (Exception e) {
            log.error("恢复超时文件补偿任务失败", e);
        }
    }

    /**
     * 重试单条补偿记录
     */
    private void retryOne(PictureFileCleanup cleanup) {
        int retryCount = cleanup.getRetryCount() == null ? 0 : cleanup.getRetryCount() + 1;
        cleanup.setRetryCount(retryCount);
        try {
            // 文件可能已被重新上传并被新记录引用，此时直接视为处理完成，避免误删
            if (isReferenced(cleanup.getUrl(), null)) {
                cleanup.setStatus(STATUS_SUCCESS);
                cleanup.setErrorMsg("文件已被重新引用，跳过删除");
                updateCleanup(cleanup);
                return;
            }
            localPictureManager.deletePictureFile(cleanup.getUrl());
            cleanup.setStatus(STATUS_SUCCESS);
            cleanup.setErrorMsg(null);
        } catch (Exception e) {
            // 引用检查失败或文件删除失败：无法确认/完成清理，按退避策略稍后重试
            log.error("重试清理图片文件失败，url = {}", cleanup.getUrl(), e);
            cleanup.setErrorMsg(StrUtil.maxLength(e.getMessage(), MAX_ERROR_MSG_LENGTH));
            if (retryCount >= MAX_RETRY_COUNT) {
                // 转入异常任务区：不再自动重试，保留记录与最后一次错误信息，等待人工排查
                cleanup.setStatus(STATUS_FAILED);
                log.error("文件补偿任务超过最大重试次数，已转入异常任务区等待人工排查，id = {}, url = {}, 错误 = {}",
                        cleanup.getId(), cleanup.getUrl(), cleanup.getErrorMsg());
            } else {
                long delayMinutes = RETRY_DELAY_MINUTES[Math.min(retryCount, RETRY_DELAY_MINUTES.length - 1)];
                cleanup.setNextRetryTime(new Date(System.currentTimeMillis()
                        + TimeUnit.MINUTES.toMillis(delayMinutes)));
            }
        }
        updateCleanup(cleanup);
    }

    /**
     * 写入补偿记录（同一地址在待处理/处理中时不再重复排队）
     */
    private void enqueue(String url, Exception e) {
        try {
            Long pendingCount = pictureFileCleanupMapper.selectCount(new LambdaQueryWrapper<PictureFileCleanup>()
                    .eq(PictureFileCleanup::getUrl, url)
                    .in(PictureFileCleanup::getStatus, STATUS_PENDING, STATUS_PROCESSING));
            if (pendingCount != null && pendingCount > 0) {
                return;
            }
            PictureFileCleanup cleanup = new PictureFileCleanup();
            cleanup.setUrl(url);
            cleanup.setRetryCount(0);
            cleanup.setStatus(STATUS_PENDING);
            cleanup.setNextRetryTime(new Date(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1)));
            cleanup.setErrorMsg(StrUtil.maxLength(e == null ? "未知错误" : e.getMessage(), MAX_ERROR_MSG_LENGTH));
            pictureFileCleanupMapper.insert(cleanup);
        } catch (Exception ex) {
            log.error("写入文件补偿记录失败，url = {}", url, ex);
        }
    }

    private void updateCleanup(PictureFileCleanup cleanup) {
        try {
            pictureFileCleanupMapper.updateById(cleanup);
        } catch (Exception e) {
            log.error("更新文件补偿记录失败，id = {}", cleanup.getId(), e);
        }
    }
}
