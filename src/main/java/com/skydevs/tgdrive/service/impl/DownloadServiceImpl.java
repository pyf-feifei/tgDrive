package com.skydevs.tgdrive.service.impl;

import com.alibaba.fastjson.JSON;
import com.pengrad.telegrambot.model.File;
import com.skydevs.tgdrive.entity.BigFileInfo;
import com.skydevs.tgdrive.exception.BotNotSetException;
import com.skydevs.tgdrive.mapper.FileMapper;
import com.skydevs.tgdrive.service.BotService;
import com.skydevs.tgdrive.service.DownloadService;
import com.skydevs.tgdrive.utils.OkHttpClientFactory;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class DownloadServiceImpl implements DownloadService {

    @Autowired
    private BotService botService;
    @Autowired
    private FileMapper fileMapper;

    // 修改为每次获取新的客户端实例，确保使用最新的代理配置
    private OkHttpClient getOkHttpClient() {
        return OkHttpClientFactory.createClient();
    }

    /**
     * 下载文件
     * 
     * @param fileID
     * @return
     */
    @Override
    public ResponseEntity<StreamingResponseBody> downloadFile(String fileID, Boolean isTs) {
        try (InputStream inputStream = downloadFileInputStream(fileID);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int byteRead;
            while ((byteRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, byteRead);
            }

            byte[] inputData = buffer.toByteArray();
            try (InputStream inputStream1 = new ByteArrayInputStream(inputData);
                    InputStream inputStream2 = new ByteArrayInputStream(inputData)) {
                BigFileInfo record = parseBigFileInfo(inputStream1);
                if (isTs) {
                    log.info("检测到HLS媒体文件，准备处理...");
                    // 直接处理TS文件流，不需要完全下载
                    return handleTsFile(fileID, inputStream2);
                }
                if (record != null && record.isRecordFile()) {
                    return handleRecordFile(fileID, record);
                }
                return handleRegularFile(fileID, inputStream2, inputData);
            }
        } catch (IOException e) {
            log.error("下载文件失败：" + e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        } catch (NullPointerException e) {
            throw new BotNotSetException();
        }
    }

    /**
     * 处理小文件
     * 
     * @param fileID
     * @param inputStream
     * @return
     */
    private ResponseEntity<StreamingResponseBody> handleRegularFile(String fileID, InputStream inputStream,
            byte[] chunkData) {
        log.info("文件不是记录文件，直接下载文件...");

        File file = botService.getFile(fileID);
        String filename = resolveFilename(fileID, file.filePath());
        if (filename.lastIndexOf('.') == -1) {
            Tika tika = new Tika();
            try (InputStream is = new ByteArrayInputStream(chunkData)) {
                String mimeType = tika.detect(is);

                String extension = getExtensionByMimeType(mimeType);
                if (!extension.isEmpty()) {
                    filename = filename + extension;
                } else {
                    log.error("未添加扩展名，扩展名检测失败");
                }
            } catch (Exception e) {
                log.error("文件检测失败" + e.getCause().getMessage());
            }
        }
        long fullSize = file.fileSize();

        HttpHeaders headers = setHeaders(filename, fullSize);

        StreamingResponseBody streamingResponseBody = outputStream -> {
            streamData(inputStream, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(getContentTypeFromFilename(filename)))
                .body(streamingResponseBody);
    }

    private String getExtensionByMimeType(String mimeType) {
        try {
            // 使用Tika的MimeType工具获取扩展名
            MimeTypes allTypes = MimeTypes.getDefaultMimeTypes();
            MimeType type = allTypes.forName(mimeType);
            return type.getExtension();
        } catch (Exception e) {
            log.error("无法获取扩展名");
            return "";
        }
    }

    /**
     * 流数据处理
     * 
     * @param inputStream
     * @param outputStream
     */
    private void streamData(InputStream inputStream, OutputStream outputStream) {
        try (InputStream is = inputStream) {
            byte[] buffer = new byte[4096];
            int byteRead;
            while ((byteRead = is.read(buffer)) != -1) {
                outputStream.write(buffer, 0, byteRead);
            }
        } catch (IOException e) {
            handleClientAbortException(e);
        } catch (Exception e) {
            log.info("文件下载终止");
            log.info(e.getMessage(), e);
        }
    }

    /**
     * 处理大文件
     * 
     * @param fileID
     * @param record
     * @return
     */
    private ResponseEntity<StreamingResponseBody> handleRecordFile(String fileID, BigFileInfo record) {
        log.info("文件名为：" + record.getFileName());

        // // 检查是否为HLS媒体文件
        // if (record.isHlsFile()) {
        // log.info("检测到HLS媒体文件，准备处理...");
        // return handleHLSMediaFile(fileID, record);
        // }

        log.info("检测到记录文件，开始下载并合并分片文件...");

        String filename = resolveFilename(fileID, record.getFileName());
        Long fullSize = fileMapper.getFullSizeByFileId(fileID);

        HttpHeaders headers = setHeaders(filename, fullSize);

        List<String> partFileIds = record.getFileIds();

        StreamingResponseBody streamingResponseBody = outputStream -> {
            downloadAndMergeFileParts(partFileIds, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(getContentTypeFromFilename(filename)))
                .body(streamingResponseBody);
    }

    // /**
    // * 处理HLS媒体文件
    // */
    // private ResponseEntity<StreamingResponseBody> handleHLSMediaFile(String
    // fileID, BigFileInfo record) {
    // String filename = resolveFilename(fileID, record.getFileName());
    // String contentType = record.getContentType();
    // if (contentType == null) {
    // contentType = getContentTypeFromFilename(filename); // 保留 contentType
    // 获取逻辑，以备后用
    // }

    // List<String> tsFileIds = record.getFileIds();
    // String m3u8Content = record.getM3u8Content();

    // // 检查是否包含分段参数 (处理 TS 分片请求)
    // if (fileID.contains("?segment=")) {
    // // 处理TS分片请求
    // String segmentName = fileID.substring(fileID.indexOf("?segment=") + 9);
    // // 注意：这里需要使用原始的 fileID (记录文件的ID) 来构建 TS URL，而不是截断后的
    // String originalFileID = fileID.substring(0, fileID.indexOf("?segment="));

    // // 解析请求的TS文件索引
    // int tsIndex = extractTSIndex(segmentName);
    // if (tsIndex >= 0 && tsIndex < tsFileIds.size()) {
    // // 返回对应的TS文件
    // HttpHeaders headers = new HttpHeaders();
    // headers.setContentType(MediaType.parseMediaType("video/mp2t"));
    // // ContentDisposition 设为 inline 可能更适合 HLS
    // headers.setContentDisposition(
    // ContentDisposition.inline().filename("segment_" + tsIndex + ".ts").build());

    // String tsFileId = tsFileIds.get(tsIndex);
    // return ResponseEntity.ok()
    // .headers(headers)
    // .body(outputStream -> {
    // // 使用 downloadFileInputStream 下载单个 TS 文件可能更统一
    // try (InputStream tsStream = downloadFileInputStream(tsFileId)) {
    // streamData(tsStream, outputStream);
    // } catch (IOException e) {
    // handleClientAbortException(e);
    // } catch (Exception e) {
    // log.error("下载TS分片时出错: {}", tsFileId, e);
    // // 可以考虑返回一个错误状态，但这可能中断 HLS 播放
    // }
    // });
    // } else {
    // // 如果索引无效，返回 404 Not Found
    // log.warn("请求了无效的 HLS segment 索引: {}", tsIndex);
    // return ResponseEntity.notFound().build();
    // }
    // }

    // // 如果不是 TS 分片请求，则认为是请求 M3U8 清单
    // log.info("处理 M3U8 清单请求 for fileID: {}", fileID);
    // HttpHeaders headers = new HttpHeaders();
    // headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
    // // M3U8 通常是 inline
    // headers.setContentDisposition(ContentDisposition.inline().filename("playlist.m3u8").build());

    // // 替换M3U8内容中的TS文件占位符为实际下载URL
    // // 注意：传递给 replaceM3U8Placeholders 的 fileID 应该是记录文件的 ID
    // String finalM3u8Content = replaceM3U8Placeholders(m3u8Content, tsFileIds,
    // fileID);

    // return ResponseEntity.ok()
    // .headers(headers)
    // .body(outputStream -> {
    // try {
    // outputStream.write(finalM3u8Content.getBytes(StandardCharsets.UTF_8));
    // outputStream.flush();
    // } catch (IOException e) {
    // handleClientAbortException(e);
    // } catch (Exception e) {
    // log.error("写入 M3U8 内容时出错 for fileID: {}", fileID, e);
    // }
    // });

    // // 移除了原来的 "默认情况" (合并TS分片)，因为对于 HLS 的初始请求，应该返回 M3U8
    // /*
    // // 默认情况：返回完整媒体文件（合并所有TS分片） - 这不适用于 HLS 播放的初始请求
    // headers = setHeaders(filename, record.getFileSize());
    // headers.setContentType(MediaType.parseMediaType(contentType));

    // return ResponseEntity.ok()
    // .headers(headers)
    // .body(outputStream -> {
    // downloadAndMergeFileParts(tsFileIds, outputStream);
    // });
    // */
    // }

    /**
     * 检查是否为M3U8请求
     */
    private boolean isM3U8Request(String filename) {
        return filename != null && filename.toLowerCase().contains("m3u8");
    }

    /**
     * 从TS文件名中提取索引
     */
    private int extractTSIndex(String filename) {
        try {
            // 假设TS文件名格式为 segment_X.ts
            if (filename.contains("_") && filename.endsWith(".ts")) {
                String indexStr = filename.substring(filename.lastIndexOf("_") + 1, filename.lastIndexOf("."));
                return Integer.parseInt(indexStr);
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 替换M3U8内容中的TS文件占位符
     */
    private String replaceM3U8Placeholders(String m3u8Content, List<String> tsFileIds, String recordFileID) { // 参数名修改为
                                                                                                              // recordFileID
                                                                                                              // 更清晰
        String result = m3u8Content;
        for (int i = 0; i < tsFileIds.size(); i++) {
            // 占位符格式应与 BotServiceImpl 中生成时一致
            String placeholder = "{{TS_FILE_" + i + "}}";
            // URL 指向 MediaStreamController
            String tsUrl = "/ts/" + recordFileID + "/segment_" + i + ".ts"; // 使用 recordFileID
            result = result.replace(placeholder, tsUrl);
        }
        log.debug("Processed M3U8 content:\n{}", result); // 添加日志方便调试
        return result;
    }

    /**
     * 下载并合并分片文件
     * 
     * @param partFileIds
     * @param outputStream
     */
    private void downloadAndMergeFileParts(List<String> partFileIds, OutputStream outputStream) {
        int maxConcurrentDownloads = 3; // 最大并发下载数
        ExecutorService executorService = Executors.newFixedThreadPool(maxConcurrentDownloads);

        List<PipedInputStream> pipedInputStreams = new ArrayList<>(partFileIds.size());
        CountDownLatch latch = new CountDownLatch(partFileIds.size());

        try {
            for (int i = 0; i < partFileIds.size(); i++) {
                pipedInputStreams.add(new PipedInputStream());
            }

            for (int i = 0; i < partFileIds.size(); i++) {
                final int index = i;
                final String partFileId = partFileIds.get(i);
                final PipedInputStream pipedInputStream = pipedInputStreams.get(index);
                final PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

                executorService.submit(() -> {
                    try (InputStream partInputStream = downloadFileByte(partFileId).byteStream();
                            OutputStream pos = pipedOutputStream) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = partInputStream.read(buffer)) != -1) {
                            pos.write(buffer, 0, bytesRead);
                            pos.flush();
                        }
                    } catch (IOException e) {
                        log.error("分片文件下载失败：{}", partFileId, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            for (int i = 0; i < partFileIds.size(); i++) {
                try (InputStream pis = pipedInputStreams.get(i)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = pis.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                } catch (IOException e) {
                    handleClientAbortException(e);
                }
            }

            latch.await();
        } catch (Exception e) {
            log.error("文件下载终止：{}", e.getMessage(), e);
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * 处理客户端终止连接异常
     * 
     * @param e
     */
    private void handleClientAbortException(IOException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("An established connection was aborted")
                || message.contains("你的主机中的软件中止了一个已建立的连接"))) {
            log.info("客户端中止了连接：{}", message);
        } else {
            log.error("写入输出流时发生 IOException", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 处理文件名
     * 
     * @param fileID
     * @param defaultName
     * @return
     */
    private String resolveFilename(String fileID, String defaultName) {
        String filename = fileMapper.getFileNameByFileId(fileID);
        if (filename == null) {
            filename = defaultName;
        }

        return filename;
    }

    /**
     * 尝试转换为大文件的记录文件
     * 
     * @param inputStream 下载的文件的输入流
     * @return BigFilInfo
     */
    private BigFileInfo parseBigFileInfo(InputStream inputStream) {
        try {
            String fileContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return JSON.parseObject(fileContent, BigFileInfo.class);
        } catch (Exception e) {
            log.info("文件不是 BigFileInfo类型，作为普通文件处理");
            return null;
        }
    }

    /**
     * 下载文件并转换为流处理
     * 
     * @param fileID
     * @return
     * @throws IOException
     */
    private InputStream downloadFileInputStream(String fileID) throws IOException {
        File file = botService.getFile(fileID);
        String fileUrl = botService.getFullDownloadPath(file);
        log.info("下载文件URL: {}", fileUrl);

        // 使用工厂方法获取客户端
        OkHttpClient client = getOkHttpClient();

        Request request = new Request.Builder()
                .url(fileUrl)
                .get()
                .build();

        Response response = null;
        try {
            response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                log.error("无法下载文件，响应码：{}, URL: {}", response.code(), fileUrl);
                // 关闭响应体以释放资源
                if (response.body() != null) {
                    response.body().close();
                }
                throw new IOException("无法下载文件，响应码：" + response.code() + " for URL: " + fileUrl);
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                log.error("响应体为空, URL: {}", fileUrl);
                // 确保即使 body 为空也关闭 response
                response.close();
                throw new IOException("响应体为空 for URL: " + fileUrl);
            }

            // 注意：返回 byteStream 后，调用者负责关闭流，OkHttp 会在流关闭时处理 Response 的关闭
            return responseBody.byteStream();

        } catch (IOException e) {
            log.error("下载文件时发生 IO 异常, URL: {}", fileUrl, e);
            // 如果 response 不为 null，尝试关闭它
            if (response != null) {
                response.close();
            }
            throw e; // 重新抛出异常
        } catch (Exception e) {
            log.error("下载文件时发生未知异常, URL: {}", fileUrl, e);
            if (response != null) {
                response.close();
            }
            throw new IOException("下载文件时发生未知异常 for URL: " + fileUrl, e);
        }
    }

    /**
     * 设置响应头
     *
     * @param filename
     * @param size
     * @return
     */
    private HttpHeaders setHeaders(String filename, Long size) {
        HttpHeaders headers = new HttpHeaders();
        try {
            String contentType = getContentTypeFromFilename(filename);
            headers.setContentType(MediaType.parseMediaType(contentType));
            if (size != null && size > 0) {
                headers.setContentLength(size);
            }

            if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
                // 对于图片和视频，设置 Content-Disposition 为 inline
                headers.setContentDisposition(
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build());
            } else {
                // 使用 URLEncoder 编码文件名，确保支持中文
                String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString()).replace("+",
                        "%20");
                String contentDisposition = "attachment; filename*=UTF-8''" + encodedFilename;
                headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
            }
        } catch (UnsupportedEncodingException e) {
            log.error("不支持的编码");
        }
        return headers;
    }

    /**
     * 下载分片文件
     *
     * @param partFileId
     * @return
     * @throws IOException
     */
    private ResponseBody downloadFileByte(String partFileId) throws IOException {
        File partFile = botService.getFile(partFileId);
        String partFileUrl = botService.getFullDownloadPath(partFile);
        log.info("下载分片文件URL: {}", partFileUrl);

        // 使用工厂方法获取客户端
        OkHttpClient client = getOkHttpClient();

        Request partRequest = new Request.Builder()
                .url(partFileUrl)
                .get()
                .build();

        Response response = client.newCall(partRequest).execute();
        if (!response.isSuccessful()) {
            log.error("无法下载分片文件，响应码：" + response.code());
            throw new IOException("无法下载分片文件，响应码：" + response.code());
        }

        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            log.error("分片响应体为空");
            throw new IOException("分片响应体为空");
        }

        return responseBody;
    }

    /**
     * 获取文件类型
     *
     * @param filename
     * @return
     */
    private String getContentTypeFromFilename(String filename) {
        String contentType = null;
        Path path = Paths.get(filename);
        try {
            contentType = Files.probeContentType(path);
        } catch (IOException e) {
            log.warn("无法通过 Files.probeContentType 获取 MIME 类型: " + e.getMessage());
        }

        if (contentType == null) {
            // 手动映射常见的文件扩展名到 MIME 类型
            String extension = getFileExtension(filename).toLowerCase();
            contentType = switch (extension) {
                case "gif" -> "image/gif";
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "bmp" -> "image/bmp";
                case "txt" -> "text/plain";
                case "pdf" -> "application/pdf";
                case "mp4" -> "video/mp4";
                // 添加其他需要的类型
                default -> "application/octet-stream";
            };
        }
        return contentType;
    }

    /**
     * 获取文件扩展名
     *
     * @param filename
     * @return
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1);
    }

    /**
     * 处理TS文件流
     * 
     * @param fileID
     * @param inputStream
     * @return
     */
    private ResponseEntity<StreamingResponseBody> handleTsFile(String fileID, InputStream inputStream) {
        log.info("处理TS文件流: {}", fileID);

        // 设置适合TS文件的响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp2t"));
        headers.setContentDisposition(ContentDisposition.inline().filename("segment.ts").build());

        // 创建流式响应体
        StreamingResponseBody streamingResponseBody = outputStream -> {
            streamData(inputStream, outputStream);
        };

        return ResponseEntity.ok()
                .headers(headers)
                .body(streamingResponseBody);
    }
}