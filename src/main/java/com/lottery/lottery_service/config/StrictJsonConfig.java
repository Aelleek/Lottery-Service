package com.lottery.lottery_service.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 요청 본문을 openapi 계약대로 엄격하게 읽는다. 모르는 필드, 정수 자리의 소수·문자열, 같은 키 반복, 본문 뒤 남은 값은 400이다. */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfig {

  /** 앱의 기본 ObjectMapper(HTTP 본문 변환에 쓰임)에 엄격한 읽기 설정을 더한다. */
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer strictJsonInput() {
    return StrictJsonConfig::apply;
  }

  /** 엄격한 읽기 설정을 builder에 적용한다. */
  public static void apply(Jackson2ObjectMapperBuilder builder) {
    builder
        .featuresToEnable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .featuresToDisable(
            DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .postConfigurer(
            mapper ->
                mapper
                    .coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }
}
