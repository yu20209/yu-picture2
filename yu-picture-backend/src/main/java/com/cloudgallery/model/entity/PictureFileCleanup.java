package com.cloudgallery.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片文件补偿清理记录
 * <p>
 * 文件删除失败（例如磁盘被占用、权限不足）时写入该表，由定时任务按退避策略重试，
 * 保证最终能够把数据库已经不再引用的图片文件清理掉。
 */
@TableName(value = "picture_file_cleanup")
@Data
public class PictureFileCleanup implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 待清理的文件地址
     */
    private String url;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 状态：0-待处理 1-清理成功 2-清理失败（超过最大重试次数）
     */
    private Integer status;

    /**
     * 下次重试时间
     */
    private Date nextRetryTime;

    /**
     * 失败原因
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
