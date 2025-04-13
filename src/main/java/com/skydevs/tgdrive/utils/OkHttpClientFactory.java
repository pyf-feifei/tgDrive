package com.skydevs.tgdrive.utils;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

@Component
public class OkHttpClientFactory {
    
    private static String activeProfile;
    
    @Value("${spring.profiles.active:prod}")
    public void setActiveProfile(String profile) {
        OkHttpClientFactory.activeProfile = profile;
    }
    
    private OkHttpClientFactory() {
        // 私有构造函数防止实例化
    }
    
    public static OkHttpClient createClient() {
        //  // 自定义连接池设置
        //  ConnectionPool connectionPool = new ConnectionPool(5, 2, TimeUnit.MINUTES);

        //  // 创建并返回 OkHttpClient 实例
        //  return new OkHttpClient.Builder()
        //          .connectionPool(connectionPool)
        //          .connectTimeout(60, TimeUnit.SECONDS)
        //          .readTimeout(60, TimeUnit.SECONDS)
        //          .writeTimeout(60, TimeUnit.SECONDS)
        //          .build();
        // 自定义连接池设置
        ConnectionPool connectionPool = new ConnectionPool(5, 2, TimeUnit.MINUTES);
        
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS);
        
        // 在开发环境中添加代理
        if ("dev".equals(activeProfile)) {
            Proxy clashProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 7890));
            builder.proxy(clashProxy);
        }
        
        // 创建并返回 OkHttpClient 实例
        return builder.build();
    }  
}