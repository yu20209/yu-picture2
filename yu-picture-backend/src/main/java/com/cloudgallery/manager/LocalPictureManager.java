package com.cloudgallery.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.cloudgallery.config.LocalStorageConfig;
import com.cloudgallery.exception.BusinessException;
import com.cloudgallery.exception.ErrorCode;
import com.cloudgallery.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

/**
 * 本地图片存储管理器
 * <p>
 * 替代原先的腾讯云 COS：把图片保存到本地磁盘、本地生成缩略图、本地解析图片信息（宽高、格式、主色调），
 * 对外访问地址通过 {@link LocalStorageConfig} 的静态资源映射提供。
 */
@Slf4j
@Component
public class LocalPictureManager {

    /**
     * 缩略图最大边长度（与原 COS 处理规则保持一致）
     */
    private static final int THUMBNAIL_MAX_SIZE = 256;

    /**
     * 生成缩略图的最小文件体积（超过该大小才生成缩略图，与原 COS 逻辑保持一致）
     */
    private static final long THUMBNAIL_MIN_SIZE = 2 * 1024;

    /**
     * 图片信息无法解析时使用的兜底主色调（避免 ColorTransformUtils 空指针）
     */
    private static final String DEFAULT_COLOR = "0xffffff";

    @Resource
    private LocalStorageConfig localStorageConfig;

    /**
     * 保存图片到本地，并生成缩略图、解析图片信息
     *
     * @param key  相对路径（形如 /public/1/2026-10-16_xxx.jpg）
     * @param file 待保存的临时文件
     * @return 上传结果
     */
      //key：图片准备存放的相对路径。file：之前创建的临时图片文件。
    public UploadPictureResult savePicture(String key, File file) {
        // StrUtil.removePrefix() 是 Hutool 的字符串工具方法，用于删除指定前缀
        String relativePath = StrUtil.removePrefix(key, "/");
        // 兜底：按 URL 上传时原始文件名可能不带后缀，此时从图片内容推断格式，保证落盘文件名带后缀
        if (StrUtil.isBlank(FileUtil.getSuffix(relativePath))) {
            String detectedFormat = detectFormat(file);
            if (StrUtil.isNotBlank(detectedFormat)) {
                relativePath = StrUtil.removeSuffix(relativePath, ".") + "." + detectedFormat;
            }
        }
        //localStorageConfig.getRootPath() 获取本地存储的根目录。
        File targetFile = new File(localStorageConfig.getRootPath(), relativePath);
        // 如果图片的上级文件夹不存在，就先创建。
        FileUtil.mkParentDirs(targetFile);
        // 从临时文件复制到本地存储目录（原临时文件由调用方负责清理）
        try {
            //这才是把图片实际保存到本地目录的操作。
            FileUtil.copy(file, targetFile, true);
        } catch (Exception e) {
            log.error("图片保存到本地失败，key = {}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }
        // 封装返回结果
        UploadPictureResult result = new UploadPictureResult();
        result.setUrl(buildUrl(relativePath));
        result.setPicSize(FileUtil.size(targetFile));
        result.setPicFormat(FileUtil.getSuffix(targetFile).toLowerCase());
        // 解析图片信息；webp 等 ImageIO 不支持的格式会解析失败，此时走兜底值，保证上传不被阻塞
        BufferedImage image = readImage(targetFile);
        if (image != null && image.getHeight() > 0) {
            int width = image.getWidth();
            int height = image.getHeight();
            result.setPicWidth(width);
            result.setPicHeight(height);
            result.setPicScale(NumberUtil.round(width * 1.0 / height, 2).doubleValue());
            result.setPicColor(getAverageColor(image));
        } else {
            result.setPicColor(DEFAULT_COLOR);
        }
        // 生成缩略图（仅对超过 2KB 且能被解析的图片生成）
        String thumbnailUrl = result.getUrl();
        if (image != null && FileUtil.size(targetFile) > THUMBNAIL_MIN_SIZE) {
            String thumbnailUrlGenerated = generateThumbnail(relativePath, image);
            if (thumbnailUrlGenerated != null) {
                thumbnailUrl = thumbnailUrlGenerated;
            }
        }
        // 未生成缩略图时，缩略图兜底为原图
        result.setThumbnailUrl(thumbnailUrl);
        return result;
    }

    /**
     * 删除本地图片文件
     *
     * @param url 图片访问地址（形如 http://localhost:8123/api/pictures/public/1/xxx.jpg）
     */
    public void deletePictureFile(String url) {
        String relativePath = parseRelativePath(url);
        if (relativePath == null) {
            return;
        }
        File file = new File(localStorageConfig.getRootPath(), relativePath);
        if (file.exists()) {
            FileUtil.del(file);
        }
    }

    /**
     * 把相对路径拼成可访问的 URL
     */
    private String buildUrl(String relativePath) {
        return StrUtil.removeSuffix(localStorageConfig.getUrlPrefix(), "/") + "/" + relativePath;
    }

    /**
     * 通过访问地址反推本地相对路径
     */
    private String parseRelativePath(String url) {
        String prefix = localStorageConfig.getUrlPrefix();
        if (StrUtil.isBlank(url) || StrUtil.isBlank(prefix) || !url.startsWith(prefix)) {
            return null;
        }
        return StrUtil.removePrefix(url.substring(prefix.length()), "/");
    }

    /**
     * 读取图片对象，读取失败（如 webp）时返回 null，不中断上传流程
     */
    private BufferedImage readImage(File file) {
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            log.warn("图片信息解析失败，file = {}", file.getAbsolutePath());
            return null;
        }
    }

    /**
     * 从图片内容推断文件格式（用于原始文件名缺少后缀的场景），推断失败返回 null
     */
    private String detectFormat(File file) {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(file)) {
            if (imageInputStream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return null;
            }
            String formatName = readers.next().getFormatName().toLowerCase();
            return "jpeg".equals(formatName) ? "jpg" : formatName;
        } catch (Exception e) {
            log.warn("图片格式推断失败，file = {}", file.getAbsolutePath());
            return null;
        }
    }

    /**
     * 获取图片平均色，返回形如 0x08af9a 的格式（与前端解析规则一致）
     */
    private String getAverageColor(BufferedImage image) {
        // 缩放到 1x1，得到的像素值即为整张图的平均色
        BufferedImage onePixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = onePixel.createGraphics();
        graphics.drawImage(image, 0, 0, 1, 1, null);
        graphics.dispose();
        return String.format("0x%06x", onePixel.getRGB(0, 0) & 0xFFFFFF);
    }

    /**
     * 生成缩略图（等比缩放，最大边不超过 256）
     *
     * @return 缩略图访问地址，生成失败返回 null
     */
    private String generateThumbnail(String relativePath, BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int maxSide = Math.max(width, height);
        if (maxSide <= THUMBNAIL_MAX_SIZE) {
            // 原图本身就不大，无需缩放
            return null;
        }
        double scale = THUMBNAIL_MAX_SIZE * 1.0 / maxSide;
        int thumbWidth = Math.max(1, (int) (width * scale));
        int thumbHeight = Math.max(1, (int) (height * scale));
        // png 保留透明通道，其余统一用 jpg
        boolean keepAlpha = image.getColorModel().hasAlpha();
        String format = keepAlpha ? "png" : "jpg";
        // 缩略图和原图放在同一目录下
        String directory = StrUtil.subBefore(relativePath, "/", true);
        String thumbnailKey = (StrUtil.isBlank(directory) ? "" : directory + "/")
                + FileUtil.mainName(relativePath) + "_thumbnail." + format;
        File thumbnailFile = new File(localStorageConfig.getRootPath(), thumbnailKey);
        try {
            BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight,
                    keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, thumbWidth, thumbHeight, null);
            graphics.dispose();
            FileUtil.mkParentDirs(thumbnailFile);
            if (!ImageIO.write(thumbnail, format, thumbnailFile)) {
                log.warn("缩略图写出失败，key = {}", thumbnailKey);
                return null;
            }
            return buildUrl(thumbnailKey);
        } catch (Exception e) {
            log.warn("缩略图生成失败，key = {}", thumbnailKey, e);
            return null;
        }
    }
}
