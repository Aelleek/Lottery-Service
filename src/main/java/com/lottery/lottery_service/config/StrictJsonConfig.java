package com.lottery.lottery_service.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** 요청 본문을 openapi 계약대로 엄격하게 읽는다. 모르는 필드, 정수 자리의 소수·문자열, 같은 키 반복, 본문 뒤 남은 값은 400이다. */
@Configuration(proxyBeanMethods = false)
public class StrictJsonConfig {

  /** 앱의 기본 JsonMapper(HTTP 본문 변환에 쓰임)에 엄격한 읽기 설정을 더한다. */
  @Bean
  public JsonMapperBuilderCustomizer strictJsonInput() {
    return StrictJsonConfig::apply;
  }

  /** 엄격한 읽기 설정을 builder에 적용한다. */
  public static void apply(JsonMapper.Builder builder) {
    builder
        .enable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .withCoercionConfig(
            LogicalType.Textual,
            config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
  }
}
