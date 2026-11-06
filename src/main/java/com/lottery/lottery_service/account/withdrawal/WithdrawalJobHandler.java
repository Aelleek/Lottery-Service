package com.lottery.lottery_service.account.withdrawal;

import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobHandler;
import com.lottery.lottery_service.common.job.JobLease;
import com.lottery.lottery_service.common.job.JobStep;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.notification.budget.MessageBudgetStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴 삭제 job(04 6.2절 2~10번). 단계를 차례로 하나씩 처리하고 진행 위치({@code phase})와 귀속 비회원 주체 목록을 job에 남긴다(귀속 행은 중간
 * 단계에서 지워진다). 각 단계는 다시 실행해도 결과가 같다. 개인을 식별하지 않는 통계와 결과 알림 메시지의 비용 기록은 남긴다.
 */
@Component
public class WithdrawalJobHandler implements JobHandler {

  /** 기록 단계의 한 걸음 크기. */
  static final int RECORD_BATCH = 500;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final DeletionJournal journal;
  private final MessageBudgetStore budget;
  private final JobStore jobs;
  private final TransactionTemplate independent;
  private final Clock clock;

  /** 처리기를 만든다. */
  public WithdrawalJobHandler(
      JdbcTemplate jdbc,
      DeletionJournal journal,
      MessageBudgetStore budget,
      JobStore jobs,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.budget = budget;
    this.jobs = jobs;
    this.independent = new TransactionTemplate(transactionManager);
    this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  @Override
  public String type() {
    return WithdrawalService.JOB;
  }

  private void alertJournalFailure(JobLease lease) {
    independent.executeWithoutResult(
        status -> {
          byte[] requestId =
              jdbc.queryForObject(
                  "SELECT public_id FROM job WHERE id = ?", byte[].class, lease.id());
          jobs.enqueue(
              JobStore.OPERATOR_ALERT,
              "deletion-journal-failed:" + lease.id(),
              "{\"kind\":\"DELETION_JOURNAL_FAILED\",\"requestId\":\"" + uuid(requestId) + "\"}",
              clock.instant());
        });
  }

  @Override
  public JobStep step(JobLease lease, String cursorJson) {
    long memberId = read(lease.payload(), "memberId");
    int phase = cursorJson == null ? 0 : (int) read(cursorJson, "phase");
    long subject =
        jdbc.queryForObject(
            "SELECT id FROM actor_subject WHERE member_id = ?", Long.class, memberId);
    List<Long> guests = guests(cursorJson, subject);
    List<Long> subjects = new ArrayList<>(List.of(subject));
    subjects.addAll(guests);
    String in = String.join(",", Collections.nCopies(subjects.size(), "?"));
    Object[] ids = subjects.toArray();
    switch (phase) {
      case 0 -> {
        byte[] publicId =
            jdbc.queryForObject(
                "SELECT public_id FROM member WHERE id = ?", byte[].class, memberId);
        try {
          journal.append(uuid(publicId), clock.instant());
        } catch (RuntimeException e) {
          alertJournalFailure(lease);
          throw e;
        }
      }
      case 1 -> {
        if (deleteRecords(in, ids) == RECORD_BATCH) {
          return next(1, guests);
        }
      }
      case 2 -> {
        jdbc.update("DELETE FROM recommendation_seen WHERE subject_id IN (" + in + ")", ids);
        jdbc.update(
            "DELETE i FROM recommendation_item i JOIN recommendation_request r ON r.id = i.request_id"
                + " WHERE r.subject_id IN ("
                + in
                + ")",
            ids);
        jdbc.update("DELETE FROM recommendation_request WHERE subject_id IN (" + in + ")", ids);
      }
      case 3 -> {
        jdbc.update(
            "DELETE h FROM history_import_item h JOIN subject_claim c ON c.id = h.claim_id"
                + " WHERE c.member_subject_id = ?",
            subject);
        jdbc.update("DELETE FROM subject_claim WHERE member_subject_id = ?", subject);
        jdbc.update("DELETE FROM guest_credential WHERE subject_id IN (" + in + ")", ids);
      }
      case 4 -> scrubMessages(memberId);
      case 5 -> {
        jdbc.update("DELETE FROM member_contact WHERE member_id = ?", memberId);
        jdbc.update("DELETE FROM consent_event WHERE member_id = ?", memberId);
        jdbc.update(
            "DELETE FROM delivery_message WHERE member_id = ? AND purpose = 'VERIFICATION'",
            memberId);
        jdbc.update("DELETE FROM verification_challenge WHERE member_id = ?", memberId);
      }
      case 6 -> {
        jdbc.update("DELETE FROM command_operation WHERE subject_id IN (" + in + ")", ids);
        jdbc.update(
            "UPDATE usage_event SET subject_id = NULL WHERE subject_id IN (" + in + ")", ids);
      }
      case 7 -> jdbc.update("DELETE FROM oauth_account WHERE member_id = ?", memberId);
      default -> {
        jdbc.update(
            "UPDATE member SET state = 'WITHDRAWN', withdrawn_at = NOW(6) WHERE id = ?", memberId);
        jdbc.update("UPDATE actor_subject SET state = 'WITHDRAWN' WHERE id = ?", subject);
        if (!guests.isEmpty()) {
          jdbc.update(
              "DELETE FROM actor_subject WHERE id IN ("
                  + String.join(",", Collections.nCopies(guests.size(), "?"))
                  + ")",
              guests.toArray());
        }
        return new JobStep.Done();
      }
    }
    return next(phase + 1, guests);
  }

  private int deleteRecords(String in, Object[] subjects) {
    List<Long> records =
        jdbc.queryForList(
            "SELECT id FROM lotto_record WHERE owner_subject_id IN ("
                + in
                + ") ORDER BY id LIMIT "
                + RECORD_BATCH
                + " FOR UPDATE",
            Long.class,
            subjects);
    if (records.isEmpty()) {
      return 0;
    }
    String ids = String.join(",", Collections.nCopies(records.size(), "?"));
    Object[] args = records.toArray();
    jdbc.update("DELETE FROM recommendation_item WHERE record_id IN (" + ids + ")", args);
    jdbc.update("DELETE FROM history_import_item WHERE source_record_id IN (" + ids + ")", args);
    jdbc.update(
        "UPDATE history_import_item SET target_record_id = NULL WHERE target_record_id IN ("
            + ids
            + ")",
        args);
    jdbc.update("DELETE FROM winning_evaluation WHERE record_id IN (" + ids + ")", args);
    jdbc.update("DELETE FROM lotto_record WHERE id IN (" + ids + ")", args);
    return records.size();
  }

  private void scrubMessages(long memberId) {
    List<Long> unknownWithoutId =
        jdbc.queryForList(
            "SELECT id FROM delivery_message WHERE member_id = ? AND purpose = 'RESULT'"
                + " AND state = 'UNKNOWN' AND provider_message_id IS NULL",
            Long.class,
            memberId);
    for (long id : unknownWithoutId) {
      budget.spend(id);
      jdbc.update(
          "UPDATE delivery_message SET state = 'FAILED', finalized_at = NOW(6) WHERE id = ?", id);
    }
    jdbc.update(
        "UPDATE delivery_message SET payload_ciphertext = NULL, recipient_ciphertext = NULL,"
            + " payload_hash = NULL, communicated_summary = NULL, consent_event_id = NULL,"
            + " version = version + 1"
            + " WHERE member_id = ? AND purpose = 'RESULT'",
        memberId);
  }

  private List<Long> guests(String cursorJson, long subject) {
    if (cursorJson == null) {
      return jdbc.queryForList(
          "SELECT guest_subject_id FROM subject_claim WHERE member_subject_id = ? ORDER BY 1",
          Long.class,
          subject);
    }
    try {
      List<Long> guests = new ArrayList<>();
      MAPPER.readTree(cursorJson).path("guests").forEach(n -> guests.add(n.asLong()));
      return guests;
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }

  private static JobStep next(int phase, List<Long> guests) {
    return new JobStep.Continue("{\"phase\":" + phase + ",\"guests\":" + guests + "}");
  }

  private static long read(String json, String field) {
    try {
      return MAPPER.readTree(json).path(field).asLong();
    } catch (Exception e) {
      throw new IllegalStateException("invalid job json", e);
    }
  }
}
