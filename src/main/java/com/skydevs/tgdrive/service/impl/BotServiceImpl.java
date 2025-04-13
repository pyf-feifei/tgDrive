package com.skydevs.tgdrive.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.File;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.request.DeleteMessage;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;
import com.skydevs.tgdrive.dto.ConfigForm;
import com.skydevs.tgdrive.dto.UploadFile;
import com.skydevs.tgdrive.entity.BigFileInfo;
import com.skydevs.tgdrive.entity.FileInfo;
import com.skydevs.tgdrive.exception.*;
import com.skydevs.tgdrive.mapper.FileMapper;
import com.skydevs.tgdrive.service.BotService;
import com.skydevs.tgdrive.service.ConfigService;
import com.skydevs.tgdrive.utils.StringUtil;
import com.skydevs.tgdrive.utils.UserFriendly;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;  // 添加这行
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import okhttp3.OkHttpClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit; // 如果需要设置超时
import com.skydevs.tgdrive.utils.OkHttpClientFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

@Service
@Slf4j
public class BotServiceImpl implements BotService {

    @Autowired
    private ConfigService configService;
    @Autowired
    private FileMapper fileMapper;
    @Value("${spring.profiles.active:prod}")  // 添加这行，默认为prod环境
    private String activeProfile;
    private String botToken;
    private String chatId;
    private TelegramBot bot;
    // 控制同时运行的任务数量
    private final int PERMITS = 5;
    // tg bot接口限制20MB，传10MB是最佳实践
    private final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    /*
    @Value("${server.port}")
    private int serverPort;
    private String url;
     */


    /**
     * 设置基本配置
     *
     * @param filename 配置文件名
     */
    public void setBotToken(String filename) {
        ConfigForm config = configService.get(filename);
        if (config == null) {
            log.error("配置文件不存在");
            throw new ConfigFileNotFoundException();
        }
        try {
            botToken = config.getToken();
            chatId = config.getTarget();
        } catch (Exception e) {
            log.error("获取Bot Token失败: {}", e.getMessage());
            throw new GetBotTokenFailedException();
        }
        /*
        if (appConfig.getUrl() == null || appConfig.getUrl().isEmpty()) {
            url = "http://localhost:" + serverPort;
        } else {
            url = appConfig.getUrl();
        }
         */
    

               // --- 配置代理 ---
        // 1. 定义 Clash 代理地址和端口 (请根据你的 Clash 设置修改)
        final String proxyHost = "127.0.0.1";
        final int proxyPort = 7890; // Clash 默认 HTTP/SOCKS 混合端口，通常用 HTTP 类型即可
        log.info("配置 Telegram Bot 使用代理: {}:{}", proxyHost, proxyPort);
        if ("dev".equals(activeProfile)) {
            // try {
            //     log.info("配置 Telegram Bot 使用代理: {}:{}", proxyHost, proxyPort);
            //     Proxy clashProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));

            //     // 2. 创建配置了代理的 OkHttpClient
            //     OkHttpClient customClient = new OkHttpClient.Builder() 
            //             .proxy(clashProxy)
            //             // 可选：设置更长的超时时间，以防代理网络慢
            //             .connectTimeout(60, TimeUnit.SECONDS)
            //             .writeTimeout(120, TimeUnit.SECONDS)
            //             .readTimeout(120, TimeUnit.SECONDS)
            //             .build();

            //     // 3. 使用配置了代理的 Client 初始化 TelegramBot
            //     //    注意：因为不再使用自定义 apiUrl，所以不需要 .apiUrl()
            //     this.bot = new TelegramBot.Builder(this.botToken)
            //                  .okHttpClient(customClient)
            //                  .build();

            //     log.info("Telegram Bot 使用自定义 OkHttpClient (带 Clash 代理) 初始化完成。");

            // } catch (Exception e) { 
            //      log.error("初始化 Telegram Bot (带代理) 时出错: {}", e.getMessage(), e);
            //      // 根据需要处理初始化失败的情况，例如抛出异常
            //      throw new RuntimeException("无法初始化带代理的 Telegram Bot", e);
            // }
            try {
                // 使用工厂类创建OkHttpClient
                OkHttpClient customClient = OkHttpClientFactory.createClient();
                // 使用创建的Client初始化TelegramBot
                this.bot = new TelegramBot.Builder(this.botToken)
                             .okHttpClient(customClient)
                             .build();
                log.info("Telegram Bot 初始化完成。");
            } catch (Exception e) { 
                log.error("初始化 Telegram Bot 时出错: {}", e.getMessage(), e);
                throw new RuntimeException("无法初始化 Telegram Bot", e);
            }
        } else {
            this.bot = new TelegramBot(botToken);
        }
    }

    /**
     * 分块上传文件
     *
     * @param inputStream
     * @param filename
     * @return
     */
    private List<String> sendFileStreamInChunks(InputStream inputStream, String filename) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(PERMITS); // 线程池大小
        Semaphore semaphore = new Semaphore(PERMITS); // 控制同时运行的任务数量

        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {
            byte[] buffer = new byte[MAX_FILE_SIZE]; // 10MB 缓冲区
            int partIndex = 0;

            while (true) {
                // 用offset追踪buffer读了多少字节
                int offset = 0;
                while(offset < MAX_FILE_SIZE) {
                    int byteRead = bufferedInputStream.read(buffer, offset, MAX_FILE_SIZE - offset);
                    if (byteRead == -1) {
                        break;
                    }
                    offset += byteRead;
                }

                if (offset == 0) {
                    break;
                }
                semaphore.acquire(); // 获取许可，若没有可用许可则阻塞

                // 当前块的文件名
                String partName = filename + "_part" + partIndex;
                partIndex++;

                // 取当前分块数据
                byte[] chunkData = Arrays.copyOf(buffer, offset);

                // 提交上传任务，使用CompletableFuture
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String fileId = uploadChunk(chunkData, partName);
                        if (fileId == null) {
                            throw new RuntimeException("分块 " + partName + " 上传失败");
                        }
                        return fileId;
                    } finally {
                        semaphore.release(); // 在任务完成后释放信号量
                    }
                }, executorService);
                futures.add(future);
            }

            // 等待所有任务完成并按顺序获取结果
            List<String> fileIds = new ArrayList<>();
            try {
                for (CompletableFuture<String> future : futures) {
                    fileIds.add(future.join()); // 按顺序等待结果
                }
                return fileIds;
            } catch (CompletionException e) {
                for (CompletableFuture<String> future : futures) {
                    future.cancel(true);
                }
                executorService.shutdown();
                throw new RuntimeException("分块上传失败: " + e.getCause().getMessage(), e);
            }
        } catch (IOException | InterruptedException e) {
            log.error("文件流读取失败或上传失败：{}", e.getMessage());
            throw new RuntimeException("文件流读取失败或上传");
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * 上传块
     *
     * @param chunkData
     * @param partName
     * @return
     * @throws EOFException
     */
    private String uploadChunk(byte[] chunkData, String partName) {
        SendDocument sendDocument = new SendDocument(chatId, chunkData).fileName(partName);
        int retryCount = 3;
        int baseDelay = 1000; // 基础延迟时间（毫秒）

        for (int i = 0; i < retryCount; i++) {
            try {
                SendResponse response = bot.execute(sendDocument);

                // 检查响应
                if (response != null && response.isOk() && response.message() != null) {
                    // 安全地获取fileId
                    String fileID;
                    fileID = extractFileId(response.message());
                    if (fileID != null) {
                        log.info("分块上传成功，File ID：{}， 文件名：{}", fileID, partName);
                        return fileID;
                    }
                }

                // 如果到这里，说明上传没有成功，需要重试
                int exponentialDelay = baseDelay * (int)Math.pow(2, i); // 指数退避策略
                log.warn("上传失败，正在准备第{}次重试，等待{}毫秒", (i+1), exponentialDelay);
                Thread.sleep(exponentialDelay);

            } catch (NullPointerException e) {
                log.error("Bot未设置或其他空指针异常", e);
                throw new BotNotSetException("上传过程中发生空指针异常");
            } catch (InterruptedException e) {
                log.error("线程被中断", e);
                Thread.currentThread().interrupt(); // 重置中断状态
                throw new RuntimeException("上传过程被中断", e);
            } catch (Exception e) {
                // 捕获所有其他异常
                log.error("上传过程中发生未预期的异常: {}", e.getMessage(), e);

                try {
                    int exponentialDelay = baseDelay * (int)Math.pow(2, i); // 指数退避策略
                    log.warn("发生异常，正在准备第{}次重试，等待{}毫秒", (i+1), exponentialDelay);
                    Thread.sleep(exponentialDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("重试等待被中断", ie);
                }

                // 如果是最后一次重试，则抛出异常
                if (i == retryCount - 1) {
                    throw new RuntimeException("上传失败，已达到最大重试次数", e);
                }
            }
        }

        // 如果所有重试都失败
        log.error("分块上传失败，已重试{}次，文件名：{}", retryCount, partName);
        throw new NoConnectionException("无法上传文件分块，已达到最大重试次数");
    }

    public String extractFileId(Message message) {
        if (message == null) {
            return null;
        }

        // 按优先级检查可能的文件类型
        if (message.document() != null) {
            return message.document().fileId();
        } else if (message.sticker() != null) {
            return message.sticker().fileId();
        } else if (message.video() != null) {
            return message.video().fileId();
        } else if (message.photo() != null && message.photo().length > 0) {
            return message.photo()[message.photo().length - 1].fileId(); // 取最后一张（通常是最高分辨率）
        } else if (message.audio() != null) {
            return message.audio().fileId();
        } else if (message.animation() != null) {
            return message.animation().fileId();
        } else if (message.voice() != null) {
            return message.voice().fileId();
        } else if (message.videoNote() != null) {
            return message.videoNote().fileId();
        }

        return null; // 没有找到 fileId
    }

    /**
     * 上传单文件（为了使gif能正常显示，gif上传到tg后，会被转换为MP4）
     * @param inputStream
     * @param filename
     * @return
     */
    private String uploadOneFile(InputStream inputStream, String filename) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int byteRead;
            while ((byteRead = inputStream.read(data)) != -1) {
                buffer.write(data, 0, byteRead);
            }
            byte[] chunkData = buffer.toByteArray();
            return uploadChunk(chunkData, filename);
        } catch (IOException e) {
            log.error("文件上传失败 :" + e.getMessage());
            return null;
        }
    }

    /**
     * 生成上传文件
     *
     * @param multipartFile
     * @param request
     * @return
     */
    @Override
    public UploadFile getUploadFile(MultipartFile multipartFile, HttpServletRequest request) {
        UploadFile uploadFile = new UploadFile();
        if (!multipartFile.isEmpty()) {
            String downloadUrl = uploadFile(multipartFile, request);
            uploadFile.setFileName(multipartFile.getOriginalFilename());
            uploadFile.setDownloadLink(downloadUrl);
        } else {
            uploadFile.setFileName("文件不存在");
        }

        return uploadFile;
    }

    /**
     * 上传文件
     *
     * @param multipartFile
     * @param request
     * @return 文件下载地址
     */
    private String uploadFile(MultipartFile multipartFile, HttpServletRequest request) {
        try {
            String prefix = StringUtil.getPrefix(request);
            InputStream inputStream = multipartFile.getInputStream();
            String filename = multipartFile.getOriginalFilename();
            long size = multipartFile.getSize();
            if (size > MAX_FILE_SIZE) {
                List<String> fileIds = sendFileStreamInChunks(inputStream, filename);
                String fileID = createRecordFile(filename, size, fileIds);
                FileInfo fileInfo = FileInfo.builder()
                        .fileId(fileID)
                        .size(UserFriendly.humanReadableFileSize(size))
                        .fullSize(size)
                        .uploadTime(LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC))
                        .downloadUrl(prefix + "/d/" + fileID)
                        .fileName(filename)
                        .build();
                fileMapper.insertFile(fileInfo);
                return prefix + "/d/" + fileID;
            } else {
                // 小于10MB的GIF会被TG转换为MP4，对文件后缀进行处理
                String uploadFilename = filename;
                if (filename != null && filename.endsWith(".gif")) {
                    uploadFilename = filename.substring(0, filename.lastIndexOf(".gif"));
                }
                String fileID = uploadOneFile(inputStream, uploadFilename);
                FileInfo fileInfo = FileInfo.builder()
                        .fileId(fileID)
                        .size(UserFriendly.humanReadableFileSize(size))
                        .fullSize(size)
                        .uploadTime(LocalDateTime.now(ZoneOffset.UTC).toEpochSecond(ZoneOffset.UTC))
                        .downloadUrl(prefix + "/d/" + fileID)
                        .fileName(filename)
                        .build();
                fileMapper.insertFile(fileInfo);
                return prefix + "/d/" + fileID;
            }
        } catch (IOException e) {
            log.error("文件上传失败，响应信息：{}", e.getMessage());
            throw new RuntimeException("文件上传失败");
        }
    }

    /**
     * 生成recordFile
     *
     * @param originalFileName
     * @param fileSize
     * @param fileIds
     * @return
     * @throws IOException
     */
    private String createRecordFile(String originalFileName, long fileSize, List<String> fileIds) throws IOException {
        BigFileInfo record = new BigFileInfo();
        record.setFileName(originalFileName);
        record.setFileSize(fileSize);
        record.setFileIds(fileIds);
        record.setRecordFile(true);

        // 创建一个系统临时文件，不依赖特定路径
        Path tempDir = Files.createTempDirectory("tempDir");
        String hashString = DigestUtil.sha256Hex(originalFileName);
        Path tempFile = tempDir.resolve(hashString + ".record.json");
        Files.createFile(tempFile);
        try {
            String jsonString = JSON.toJSONString(record, true);
            Files.write(Paths.get(tempFile.toUri()), jsonString.getBytes());
        } catch (IOException e) {
            log.error("上传记录文件生成失败" + e.getMessage());
            throw new RuntimeException("上传文件生成失败");
        }

        // 上传记录文件到 Telegram
        byte[] fileBytes = Files.readAllBytes(tempFile);
        SendDocument sendDocument = new SendDocument(chatId, fileBytes)
                .fileName(tempFile.getFileName().toString());

        SendResponse response = bot.execute(sendDocument);
        Message message = response.message();
        String recordFileId = message.document().fileId();

        log.info("记录文件上传成功，File ID: " + recordFileId);

        // 删除本地临时文件
        Files.deleteIfExists(tempFile);

        return recordFileId;
    }

    /**
     * 获取完整下载路径
     *
     * @param file
     * @return
     */
    public String getFullDownloadPath(File file) {
        log.info("获取完整的下载路径: " + bot.getFullFilePath(file));
        return bot.getFullFilePath(file);
    }

    /**
     * 根据fileId获取文件
     *
     * @param fileId
     * @return
     */
    public File getFile(String fileId) {
        GetFile getFile = new GetFile(fileId);
        try {
            GetFileResponse getFileResponse = bot.execute(getFile);
            return getFileResponse.file();
        } catch (NullPointerException e) {
            log.error("当前未加载配置文件！" + e.getMessage());
            throw new NoConfigException("当前未加载配置文件！");
        }
    }

    /**
     * 根据文件id获取文件名
     *
     * @param fileID
     * @return
     */
    @Override
    public String getFileNameByID(String fileID) {
        GetFile getFile = new GetFile(fileID);
        GetFileResponse getFileResponse = bot.execute(getFile);
        File file = getFileResponse.file();
        return file.filePath();
    }

    /**
     * 发送消息
     *
     * @param m
     */
    public boolean sendMessage(String m) {
        // TelegramBot bot = new TelegramBot(botToken);
        if (this.bot == null) {
            if ("dev".equals(activeProfile)) {
                // // 开发环境使用代理配置
                // try {
                //     Proxy clashProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 7890));
                //     OkHttpClient customClient = new OkHttpClient.Builder() 
                //             .proxy(clashProxy)
                //             .connectTimeout(60, TimeUnit.SECONDS)
                //             .writeTimeout(120, TimeUnit.SECONDS)
                //             .readTimeout(120, TimeUnit.SECONDS)
                //             .build();
                //     this.bot = new TelegramBot.Builder(this.botToken)
                //                  .okHttpClient(customClient)
                //                  .build();
                // } catch (Exception e) {
                //     log.error("初始化代理Bot失败", e);
                //     this.bot = new TelegramBot(botToken);
                // }
                try {
                    // 使用工厂类创建OkHttpClient
                    OkHttpClient customClient = OkHttpClientFactory.createClient();
                    // 使用创建的Client初始化TelegramBot
                    this.bot = new TelegramBot.Builder(this.botToken)
                                 .okHttpClient(customClient)
                                 .build();
                    log.info("Telegram Bot 初始化完成。");
                } catch (Exception e) { 
                    log.error("初始化 Telegram Bot 时出错: {}", e.getMessage(), e);
                    throw new RuntimeException("无法初始化 Telegram Bot", e);
                }
            } else {
                this.bot = new TelegramBot(botToken);
            }
        }
        try {
            this.bot.execute(new SendMessage(chatId, m));
        } catch (Exception e) {
            log.error("消息发送失败", e);
            return false;
        }
        log.info("消息发送成功");
        return true;
    }


    /**
     * 获取bot token
     *
     * @return
     */
    @Override
    public String getBotToken() {
        return botToken;
    }

    /**
     * 上传文件
     * @param inputStream 文件输入流
     * @param path 文件路径
     * @return
     */
    @Override
    public String uploadFile(InputStream inputStream, String path) {
        try {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            long size = inputStream.available();

            return getUploadedFileID(inputStream, filename, size);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }


    @Override
    public String uploadFile(InputStream inputStream, String path, HttpServletRequest request) {
        try {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            long size = request.getContentLengthLong();

            return getUploadedFileID(inputStream, filename, size);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @Nullable
    private String getUploadedFileID(InputStream inputStream, String filename, long size) throws IOException {
        if (size > MAX_FILE_SIZE) {
            List<String> fileIds = sendFileStreamInChunks(inputStream, filename);
            String fileID = createRecordFile(filename, size, fileIds);
            return fileID;
        } else {
            String uploadFilename = filename;
            if (filename.endsWith(".gif")) {
                uploadFilename = filename.substring(0, filename.lastIndexOf(".gif"));
            }
            return uploadOneFile(inputStream, uploadFilename);
        }
    }

    @Override
    public InputStream downloadFile(String fileId) {
        try {
            File file = getFile(fileId);
            String fileUrl = bot.getFullFilePath(file);
            return new URL(fileUrl).openStream();
        } catch (IOException e) {
            log.error("文件下载失败", e);
            throw new RuntimeException("文件下载失败", e);
        }
    }

    @Override
    public void deleteFile(String fileId) {
        try {
            bot.execute(new DeleteMessage(chatId, Integer.parseInt(fileId)));
            log.info("文件删除成功，File ID: {}", fileId);
        } catch (Exception e) {
            log.error("文件删除失败", e);
            throw new RuntimeException("文件删除失败", e);
        }
    }
}