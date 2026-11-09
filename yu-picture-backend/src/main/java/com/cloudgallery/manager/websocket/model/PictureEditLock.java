package com.cloudgallery.manager.websocket.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片编辑锁：记录当前编辑者及其会话，用于实现单编辑者控制
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureEditLock {

    /**
     * 编辑者用户 id
     */
    private Long userId;

    /**
     * 编辑者会话 id（用于区分同一用户的多个连接）
     */
    private String sessionId;

    /**
     * 最近一次活跃时间（毫秒），用于释放超时锁
     * <p>
     * 写入发生在 Disruptor 消费线程，读取发生在定时任务线程，
     * volatile 保证跨线程的可见性与原子访问
     */
    private volatile Long lastActiveTime;
}
