package com.lottery.lottery_service.member.oauth.repository;

import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.member.oauth.entity.MemberOAuthAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** (provider, providerUserId) 조합으로 OAuth 계정 링크를 조회. provider는 STEP 4에서 enum으로 교체 예정. */
public interface MemberOAuthAccountRepository extends JpaRepository<MemberOAuthAccount, Long> {
  Optional<MemberOAuthAccount> findByProviderAndProviderUserId(
      OAuthProvider provider, String providerUserId);
}
