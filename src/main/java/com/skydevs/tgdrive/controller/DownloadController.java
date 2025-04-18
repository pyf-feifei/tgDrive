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

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/d")
@Slf4j
public class DownloadController {

    @Autowired
    private DownloadService downloadService;

    // 移除了未使用的依赖注入
    // @Autowired
    // private FileMapper fileMapper;
    // 
    // @Autowired
    // private BotService botService;

    @GetMapping("/{fileID}")
    public CompletableFuture<ResponseEntity<StreamingResponseBody>> downloadFile(@PathVariable String fileID) {
        log.info("接收到下载请求，fileID: " + fileID);
        return CompletableFuture.supplyAsync(() -> downloadService.downloadFile(fileID));
    }
}
