package com.lottery.lottery_service.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "lottery.api-v1.enabled=true")
@AutoConfigureMockMvc
class StrictJsonWebTest {

  @Autowired private MockMvc mvc;

  @Test
  @DisplayName("앱의 HTTP 본문 변환도 계약 밖 본문(모르는 필드, 소수 번호, 문자열 회차)을 400 VALIDATION_FAILED로 답한다")
  void appRejectsOffContractBody() throws Exception {
    for (String body :
        new String[] {
          "{\"round\":1250,\"sets\":[[1,2,3,4,5,6]],\"extra\":true}",
          "{\"round\":1250,\"sets\":[[1,2,3,4,5,6.9]]}",
          "{\"round\":\"1250\",\"sets\":[[1,2,3,4,5,6]]}"
        }) {
      mvc.perform(post("/api/v1/lotto/check").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }
}
