package com.lottery.lottery_service.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "lottery.api-v1.enabled=true")
@AutoConfigureMockMvc
class RequestRejectedTest {

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("방화벽이 거른 경로(;, 인코딩한 /)도 400 VALIDATION_FAILED JSON으로 답한다")
  void rejectedPathsUseErrorFormat() throws Exception {
    for (String path :
        new String[] {"/api/v1/lotto/context;jsessionid=1", "/api/v1/lotto/rounds/1%2F2/result"}) {
      mvc.perform(get(path))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.requestId").isString());
    }
  }
}
