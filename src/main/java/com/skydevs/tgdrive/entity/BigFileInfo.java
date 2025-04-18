package com.skydevs.tgdrive.entity;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BigFileInfo {
    private String fileName;
    private long fileSize;
    private List<String> fileIds;
    private boolean isRecordFile;
    
    // 新增HLS相关字段
    private boolean isHlsFile;        // 是否为HLS媒体文件
    private String m3u8Content;       // M3U8文件内容
    private String contentType;       // 媒体内容类型
}
