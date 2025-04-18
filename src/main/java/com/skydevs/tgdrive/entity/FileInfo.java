package com.skydevs.tgdrive.entity;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FileInfo {
    private String fileName;         // 文件名
    private String downloadUrl;      // 文件下载URL
    private String size;
    private Long fullSize;
    private String fileId;

    // 用于存储上传时间的 UNIX 时间戳
    private Long uploadTime;

    // WebDAV文件路径
    private String webdavPath;

    private boolean dir;
    
    // 新增媒体相关字段
    private String mediaType;        // 媒体类型
    private boolean isHLS;           // 是否为HLS媒体
}

