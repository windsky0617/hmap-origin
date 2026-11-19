package com.hmdp.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * 对照 Plus 的 JacksonCustom，统一 Java 时间类型的 JSON 格式。
 * 通过增量注册保留 Spring Boot 的其他 Jackson 配置。
 */
@Component
public class JacksonCustom implements Jackson2ObjectMapperBuilderCustomizer, Ordered {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void customize(Jackson2ObjectMapperBuilder builder) {
        builder.serializers(
                new LocalDateTimeSerializer(DATE_TIME_FORMATTER),
                new LocalDateSerializer(DATE_FORMATTER),
                new LocalTimeSerializer(TIME_FORMATTER)
        );
        builder.deserializers(
                new LocalDateTimeDeserializer(DATE_TIME_FORMATTER),
                new LocalDateDeserializer(DATE_FORMATTER),
                new LocalTimeDeserializer(TIME_FORMATTER)
        );
    }

    @Override
    public int getOrder() {
        return 1;
    }
}