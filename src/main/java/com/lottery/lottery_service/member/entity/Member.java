package com.lottery.lottery_service.member.entity;

import com.lottery.lottery_service.member.oauth.entity.MemberOAuthAccount;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 서비스 고유 사용자 엔티티.
 * - 소셜 제공자와 무관한 '우리 서비스의 프로필'을 표현한다.
 * - email은 카카오처럼 미제공/동의거부일 수 있으므로 nullable 허용.
 * - emailVerified는 서비스 관점에서 검증 완료 여부.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
//@ToString(exclude = "oauthAccounts")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "member", indexes = {
        @Index(name = "idx_member_last_login_at", columnList = "lastLoginAt")
})
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)      // nullable 허용
    private String email;

    private boolean emailVerified = false;

    private String name;
    private String nickname;
    private String profileImageUrl;

    @Column(nullable = false)
    private String status = "ACTIVE"; // ACTIVE/BANNED/DELETED 등 정책에 맞게 사용

    private Instant lastLoginAt;

    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MemberOAuthAccount> oauthAccounts = new ArrayList<>();

    /**
     * Member를 생성할 때 필수값을 한 번에 설정하는 정적 팩토리.
     * email은 null 가능(카카오 동의 거부 대비), emailVerified는 기본 false.
     */
    public static Member newMember(String email, String nickname, String profileImageUrl) {
        Member m = new Member();  // @NoArgsConstructor(PROTECTED) 이므로 클래스 내부에서만 new 허용
        m.email = email;
        m.emailVerified = false;
        m.nickname = nickname;
        m.profileImageUrl = profileImageUrl;
        return m;
    }

    /**
     * (provider, providerUserId)로 소셜 링크를 생성하고,
     * 양방향 연관관계를 한 번에 맞춰준다.
     */
    public MemberOAuthAccount addOAuthLink(String provider,
                                           String providerUserId,
                                           String emailOnProvider,
                                           String displayName,
                                           String profileImageUrl) {
        MemberOAuthAccount link = MemberOAuthAccount.newLink(
                this, provider, providerUserId, emailOnProvider, displayName, profileImageUrl
        );
        this.oauthAccounts.add(link); // 양쪽 일관성 유지
        return link;
    }

}


/*
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    롬복이 “파라미터 없는 생성자”를 protected로 만들어줌.

    왜? JPA가 프록시 생성/리플렉션을 위해 기본 생성자를 요구해요.
    public 으로 열어두면 어디서나 new가 가능해서 의도치 않은 생성이 생길 수 있으니 protected가 관례.


    @ToString(exclude = "oauthAccounts")
    toString() 자동 생성하되 oauthAccounts 필드는 빼라는 뜻.

    왜? 양방향 연관관계/LAZY 필드가 들어가면 toString 호출 시
    무한 순환(toString → 상대 toString → 다시 나…)
    N+1/LazyInitializationException
    같은 문제를 일으킬 수 있어요. 무겁고 위험한 필드는 제외!

    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    equals/hashCode를 만들 때 명시적으로 포함한 필드만 쓰겠다는 뜻.
    보통 @EqualsAndHashCode.Include를 id에 붙여서 식별자 기반으로 동등성 판단을 해요.

    왜? 기본값으로 전 필드를 다 넣으면 연관관계/가변 필드까지 끌려들어와
    성능·무한루프·버그 위험↑. 엔티티는 ID만 기준이 가장 안전한 편입니다.

    주의: ID가 아직 null(영속화 전)인 두 엔티티는 서로 다른 객체로 간주됩니다.
    영속화 전에 Set/Map 키로 쓰는 건 피하세요.

    @Table(name = "member", indexes = { @Index(name = "idx_member_last_login_at", columnList = "last_login_at") })
    이 엔티티가 매핑될 테이블 이름을 member로 지정.

    @Index는 DB 인덱스 생성 지시.
    columnList는 DB 컬럼명 목록(쉼표로 복수 지정 가능).

    왜? 로그인 최근 시각으로 정렬/조회할 때 성능을 위해 last_login_at 인덱스를 추가.

    🔎 중요 팁 – columnList 표기

    Spring Boot 기본 네이밍 전략을 쓰면 필드 lastLoginAt → 컬럼 last_login_at로 변환돼요.

    그런데 @Index(columnList = "...")는 변환 안 해주고 문자열을 그대로 씁니다.
    그래서 "last_login_at" 처럼 실제 컬럼명을 적는 게 안전해요.
    (확실히 하려면 필드에 @Column(name = "last_login_at")도 붙여 두면 더 명시적)
 */