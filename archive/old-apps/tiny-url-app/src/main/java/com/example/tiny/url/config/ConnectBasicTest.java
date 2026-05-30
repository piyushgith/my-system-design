package com.example.tiny.url.config;

/*
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

public class ConnectBasicTest {
    static void main(String[] args) {
        connectBasic();
    }
    public static void connectBasic() {
        RedisURI uri = RedisURI.Builder
                .redis("redis-12810.crce179.ap-south-1-1.ec2.redns.redis-cloud.com", 12810)
                .withAuthentication("default", "1rubwknBEJiCybGRyYF83pt41EeWro16")
                .build();
        RedisClient client = RedisClient.create(uri);
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> commands = connection.sync();

        commands.set("foo", "bar");
        String result = commands.get("foo");
        System.out.println(result); // >>> bar

        connection.close();

        client.shutdown();
    }
}*/
