package com.cloudgallery.manager.websocket.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.cloudgallery.manager.websocket.model.PictureEditRequestMessage;
import com.cloudgallery.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

/**
 * 图片编辑事件生产者
 */
@Component
@Slf4j
public class PictureEditEventProducer {

    @Resource
    //Disruptor一个负责管理事件队列、生产者和消费者的工具。
    private Disruptor<PictureEditEvent> pictureEditEventDisruptor;

    /**
     * 发布事件
     *
     * @param pictureEditRequestMessage
     * @param session
     * @param user
     * @param pictureId
     */
    public void publishEvent(PictureEditRequestMessage pictureEditRequestMessage, WebSocketSession session, User user, Long pictureId) {
        //RingBuffer，环形缓冲区。理解成一个循环使用的事件存放区域。
        RingBuffer<PictureEditEvent> ringBuffer = pictureEditEventDisruptor.getRingBuffer();
        // 获取到可以防止事件的位置向—— RingBuffer 申请下一个可用的事件位置，并返回该位置对应的序号。
        long next = ringBuffer.next();
        //根据这个位置，获取对应的 PictureEditEvent 对象。
        PictureEditEvent pictureEditEvent = ringBuffer.get(next);
        //给事件对象赋值
        pictureEditEvent.setPictureEditRequestMessage(pictureEditRequestMessage);
        pictureEditEvent.setSession(session);
        pictureEditEvent.setUser(user);
        pictureEditEvent.setPictureId(pictureId);
        // 正式发布这个事件，通知 Disruptor 这个位置的数据已经准备好，可以被消费者处理。
        ringBuffer.publish(next);
    }

    /**
     * 优雅停机
     */
    @PreDestroy
    public void destroy() {
        pictureEditEventDisruptor.shutdown();
    }
}

