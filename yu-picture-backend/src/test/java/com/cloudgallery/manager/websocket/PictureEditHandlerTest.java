package com.cloudgallery.manager.websocket;

import cn.hutool.json.JSONUtil;
import com.cloudgallery.manager.websocket.model.PictureEditLock;
import com.cloudgallery.manager.websocket.model.PictureEditMessageTypeEnum;
import com.cloudgallery.manager.websocket.model.PictureEditRequestMessage;
import com.cloudgallery.model.entity.User;
import com.cloudgallery.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 图片编辑锁单元测试：验证并发抢占、会话隔离、条件释放与超时释放
 * <p>
 * 不依赖 Spring 容器，仅通过反射注入 UserService。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PictureEditHandlerTest {

    @Mock
    private UserService userService;

    private PictureEditHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PictureEditHandler();
        ReflectionTestUtils.setField(handler, "userService", userService);
    }

    /**
     * 20 个线程同时申请编辑同一张图片，只能有 1 个线程抢到锁，其余 19 个收到 ERROR
     */
    @Test
    void concurrentEnterEditShouldHaveOnlyOneOwner() throws Exception {
        int threadCount = 20;
        Long pictureId = 100L;
        List<WebSocketSession> sessions = new ArrayList<>();
        List<User> users = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            sessions.add(mockSession("session-" + i));
            users.add(mockUser((long) i + 1));
        }
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(1, TimeUnit.SECONDS);
                handler.handleEnterEditMessage(new PictureEditRequestMessage(), sessions.get(index),
                        users.get(index), pictureId);
                return null;
            }));
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        Map<Long, PictureEditLock> locks = getLocks();
        assertEquals(1, locks.size(), "并发抢占后应当只有一个编辑者");
        PictureEditLock lock = locks.get(pictureId);
        assertNotNull(lock);

        // 统计失败者收到的消息，并确认消息类型都是 ERROR
        int sentCount = 0;
        int errorCount = 0;
        for (WebSocketSession session : sessions) {
            int sendTimes = countSendMessage(session);
            assertTrue(sendTimes <= 1, "每个会话最多收到一条消息");
            sentCount += sendTimes;
            if (sendTimes == 1) {
                String payload = firstSendPayload(session);
                assertEquals(PictureEditMessageTypeEnum.ERROR.getValue(),
                        JSONUtil.parseObj(payload).getStr("type"));
                errorCount++;
            }
        }
        assertEquals(threadCount - 1, sentCount, "只有一个赢家，其余 19 个申请者应收到失败提示");
        assertEquals(threadCount - 1, errorCount);
    }

    /**
     * 同一用户的另一个会话不能抢占已持有的锁
     */
    @Test
    void sameUserDifferentSessionCannotPreemptLock() throws Exception {
        Long pictureId = 200L;
        User user = mockUser(1L);
        WebSocketSession firstSession = mockSession("session-a");
        WebSocketSession secondSession = mockSession("session-b");

        handler.handleEnterEditMessage(new PictureEditRequestMessage(), firstSession, user, pictureId);
        handler.handleEnterEditMessage(new PictureEditRequestMessage(), secondSession, user, pictureId);

        PictureEditLock lock = getLocks().get(pictureId);
        assertNotNull(lock);
        assertEquals("session-a", lock.getSessionId());
        Mockito.verify(secondSession).sendMessage(Mockito.any());
    }

    /**
     * 非持锁者（即使同一用户的其他会话）退出不会释放锁；持锁者退出才会释放
     */
    @Test
    void onlyLockOwnerCanRelease() throws Exception {
        Long pictureId = 201L;
        User user = mockUser(1L);
        WebSocketSession ownerSession = mockSession("owner");
        WebSocketSession otherSession = mockSession("other");

        handler.handleEnterEditMessage(new PictureEditRequestMessage(), ownerSession, user, pictureId);
        handler.handleExitEditMessage(new PictureEditRequestMessage(), otherSession, user, pictureId);
        assertNotNull(getLocks().get(pictureId), "非持锁会话不能释放锁");

        handler.handleExitEditMessage(new PictureEditRequestMessage(), ownerSession, user, pictureId);
        assertNull(getLocks().get(pictureId), "持锁者退出后应释放锁");
    }

    /**
     * 超过空闲时间的锁会被定时任务自动释放
     */
    @Test
    void expiredLockShouldBeReleased() throws Exception {
        Long pictureId = 202L;
        User user = mockUser(1L);
        WebSocketSession session = mockSession("s1");

        handler.handleEnterEditMessage(new PictureEditRequestMessage(), session, user, pictureId);
        PictureEditLock lock = getLocks().get(pictureId);
        assertNotNull(lock);
        // 模拟 6 分钟没有编辑动作（锁超时阈值为 5 分钟）
        lock.setLastActiveTime(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(6));

        handler.releaseExpiredEditLocks();
        assertNull(getLocks().get(pictureId));
    }

    @SuppressWarnings("unchecked")
    private Map<Long, PictureEditLock> getLocks() {
        return (Map<Long, PictureEditLock>) ReflectionTestUtils.getField(handler, "pictureEditingUsers");
    }

    private User mockUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setUserName("user-" + userId);
        return user;
    }

    private WebSocketSession mockSession(String sessionId) {
        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private int countSendMessage(WebSocketSession session) {
        return (int) Mockito.mockingDetails(session).getInvocations().stream()
                .filter(invocation -> "sendMessage".equals(invocation.getMethod().getName()))
                .count();
    }

    private String firstSendPayload(WebSocketSession session) {
        return Mockito.mockingDetails(session).getInvocations().stream()
                .filter(invocation -> "sendMessage".equals(invocation.getMethod().getName()))
                .map(invocation -> (TextMessage) invocation.getArgument(0))
                .findFirst()
                .map(TextMessage::getPayload)
                .orElse("");
    }
}
