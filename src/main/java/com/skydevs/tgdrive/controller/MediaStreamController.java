package com.skydevs.tgdrive.controller;

import com.skydevs.tgdrive.service.DownloadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/ts")
@Slf4j
public class MediaStreamController {

    @Autowired
    private DownloadService downloadService;

    @GetMapping("/{fileID}/{segmentName}")
    public ResponseEntity<StreamingResponseBody> getMediaSegment(
            @PathVariable String fileID,
            @PathVariable String segmentName) {
        log.info("接收到媒体分片请求，fileID: {}, segment: {}", fileID, segmentName);
        return downloadService.downloadFile(fileID, true);
    }
}