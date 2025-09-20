package com.lottery.lottery_service.lotto.external;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.lotto.entity.LottoWinnerData;
import com.lottery.lottery_service.lotto.external.dto.DhlotteryDrawResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 동행복권 회차별 당첨 데이터 조회 클라이언트.
 *
 * <p>역할:
 * <ul>
 *   <li>외부 API 호출: https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo={round}</li>
 *   <li>응답 파싱: {@link DhlotteryDrawResponse}로 역직렬화</li>
 *   <li>필요 시 엔티티 변환: {@link #toEntity(DhlotteryDrawResponse)}</li>
 * </ul>
 *
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DhlotteryClient {

    private static final String API = "https://www.dhlottery.co.kr/common.do?method=getLottoNumber&drwNo=";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * 지정 회차의 추첨 결과를 조회한다.
     *
     * @param round 조회할 회차(1..N)
     * @return 성공 시 응답 DTO, 미발표/없음/실패 시 Optional.empty()
     */
    public Optional<DhlotteryDrawResponse> fetchRound(int round) {
        try {
            URI uri = URI.create(API + round);
            HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("DhlotteryClient: HTTP {} for round {}", res.statusCode(), round);
                return Optional.empty();
            }

            DhlotteryDrawResponse dto = objectMapper.readValue(res.body(), DhlotteryDrawResponse.class);
            if (!"success".equalsIgnoreCase(dto.getReturnValue()) || dto.getDrwNo() == null) {
                // 미발표/해당 회차 없음
                log.info("DhlotteryClient: round {} not ready (returnValue={}, drwNo={})",
                        round, dto.getReturnValue(), dto.getDrwNo());
                return Optional.empty();
            }

            return Optional.of(dto);
        } catch (Exception e) {
            log.error("DhlotteryClient error for round {}: {}", round, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 응답 DTO를 {@link LottoWinnerData} 엔티티로 변환한다.
     * <p>번호는 오름차순 공백 문자열(winnerData)로 저장한다.
     *
     * @throws IllegalArgumentException 필수 필드 누락 시
     */
    public LottoWinnerData toEntity(DhlotteryDrawResponse dto) {
        if (dto.getDrwNo() == null ||
                dto.getDrwtNo1() == null || dto.getDrwtNo2() == null || dto.getDrwtNo3() == null ||
                dto.getDrwtNo4() == null || dto.getDrwtNo5() == null || dto.getDrwtNo6() == null ||
                dto.getBonusNo() == null ||
                dto.getTotSellamnt() == null || dto.getFirstWinamnt() == null ||
                dto.getFirstAccumamnt() == null || dto.getFirstPrzwnerCo() == null) {
            throw new IllegalArgumentException("필수 필드가 비어 있습니다: " + dto);
        }

        List<Integer> nums = Arrays.asList(
                dto.getDrwtNo1(), dto.getDrwtNo2(), dto.getDrwtNo3(),
                dto.getDrwtNo4(), dto.getDrwtNo5(), dto.getDrwtNo6()
        );
        String canonical = nums.stream().sorted().map(String::valueOf).collect(Collectors.joining(" "));

        return LottoWinnerData.builder()
                .round(dto.getDrwNo())
                .winnerData(canonical)
                .bonusNo(dto.getBonusNo())
                .totSellamnt(dto.getTotSellamnt())
                .firstWinamnt(dto.getFirstWinamnt())
                .firstPrzwnerCo(dto.getFirstPrzwnerCo())
                .firstAccumamnt(dto.getFirstAccumamnt())
                .drwNoDate(parseDate(dto.getDrwNoDate()))
                .build();
    }

    private LocalDate parseDate(String yyyyMMdd) {
        try { return yyyyMMdd == null ? null : LocalDate.parse(yyyyMMdd); }
        catch (Exception e) { return null; }
    }
}