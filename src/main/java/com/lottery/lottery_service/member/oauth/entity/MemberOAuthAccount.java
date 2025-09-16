package com.lottery.lottery_service.member.oauth.entity;

import com.lottery.lottery_service.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 소셜 제공자 계정과 Member를 연결하는 엔티티.
 * (provider, provider_user_id) 조합이 전역적으로 유일하다.
 *
 * 지금 단계에선 provider를 String으로 둔다. (STEP 4에서 enum으로 교체)
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(exclude = "member")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(
        name = "member_oauth_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_provider_user",
                columnNames = {"provider", "provider_user_id"}
        ),
        indexes = {
                @Index(name = "idx_oauth_member", columnList = "member_id"),
                @Index(name = "idx_oauth_provider_email", columnList = "provider,email_on_provider")
        }
)
public class MemberOAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** 소유자 Member (FK) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Setter(AccessLevel.NONE)
    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Setter(AccessLevel.NONE)
    @Column(name = "provider_user_id", nullable = false, updatable = false, length = 191)
    private String providerUserId;

    /** 제공자 측 이메일(있을 때만) */
    @Column(name = "email_on_provider")
    private String emailOnProvider;

    /** 표시 이름/닉네임(있을 때만) */
    @Column(name = "display_name")
    private String displayName;

    /** 제공자 측 프로필 이미지 URL(있을 때만) */
    @Column(name = "profile_image_url")
    private String profileImageUrl;

    /** (선택) 토큰 저장 시엔 반드시 암호화 고려 */
    @Column(name = "access_token_enc")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc")
    private String refreshTokenEnc;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    private String scopes;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.connectedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * 링크 엔티티를 생성하는 정적 팩토리.
     * - 정체성 필드(provider, providerUserId)는 생성 시에만 세팅되고 이후 변경 불가.
     * - member 역참조까지 세팅한다.
     */
    public static MemberOAuthAccount newLink(Member owner,
                                             String provider,
                                             String providerUserId,
                                             String emailOnProvider,
                                             String displayName,
                                             String profileImageUrl) {
        MemberOAuthAccount m = new MemberOAuthAccount(); // @NoArgsConstructor(PROTECTED)
        m.member = owner;
        m.provider = provider;
        m.providerUserId = providerUserId;
        m.emailOnProvider = emailOnProvider;
        m.displayName = displayName;
        m.profileImageUrl = profileImageUrl;
        return m;
    }
}