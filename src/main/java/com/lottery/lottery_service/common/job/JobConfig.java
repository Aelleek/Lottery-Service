package com.lottery.lottery_service.common.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** job 설정({@code lottery.job.*})을 등록한다. */
@Configuration
@EnableConfigurationProperties(JobProperties.class)
public class JobConfig {}
