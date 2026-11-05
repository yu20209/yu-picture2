package com.cloudgallery.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 本地图片存储配置
 * <p>
 * 用于替代腾讯云对象存储（COS）：图片直接落盘到本地目录，并通过静态资源映射对外提供访问地址。
 */
@Configuration
@ConfigurationProperties(prefix = "local.storage")
@Data
public class LocalStorageConfig implements WebMvcConfigurer {

    /**
     * 本地存储根目录（图片文件的落盘位置）
     */
    private String rootPath = "./upload";

    /**
     * 对外访问前缀（会和相对路径拼成浏览器可直接访问的 URL）
     */
    private String urlPrefix = "http://localhost:8123/api/pictures";

    /**
     * 静态资源访问路径
     */
    private String resourcePattern = "/pictures/**";

    /**
     * 把访问路径映射到本地磁盘目录，从而让图片能通过 HTTP 直接访问
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + new File(rootPath).getAbsolutePath() + File.separator;
        registry.addResourceHandler(resourcePattern).addResourceLocations(location);
    }
}
