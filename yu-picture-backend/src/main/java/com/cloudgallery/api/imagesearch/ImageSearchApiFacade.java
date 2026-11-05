package com.cloudgallery.api.imagesearch;

import com.cloudgallery.api.imagesearch.model.ImageSearchResult;
import com.cloudgallery.api.imagesearch.sub.GetImageFirstUrlApi;
import com.cloudgallery.api.imagesearch.sub.GetImageListApi;
import com.cloudgallery.api.imagesearch.sub.GetImagePageUrlApi;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ImageSearchApiFacade {

    /**
     * 搜索图片
     * @param imageUrl
     * @return
     */
    public static List<ImageSearchResult> searchImage(String imageUrl) {
        String imagePageUrl = GetImagePageUrlApi.getImagePageUrl(imageUrl);
        String imageFirstUrl = GetImageFirstUrlApi.getImageFirstUrl(imagePageUrl);
        List<ImageSearchResult> imageList = GetImageListApi.getImageList(imageFirstUrl);
        return imageList;
    }

    public static void main(String[] args) {
        List<ImageSearchResult> imageList = searchImage("https://www.example.com/logo.png");
        System.out.println("结果列表" + imageList);
    }
}
