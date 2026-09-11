package kr.bbangmoa.server.proxy;

import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 상류별 하루 호출 한도.
 *
 * 레이트 리밋(RateLimitInterceptor)과 무엇이 다른가
 *   레이트 리밋은 "IP 하나가 1분에 몇 번" 이다 — 한 사람의 폭주를 막는다.
 *   여기는 "우리 서버 전체가 하루에 몇 번" 이다 — 우리 계정의 일일 쿼터를 막는다.
 *   둘은 지키는 대상이 다르다. IP 당으로 아무리 조여도 IP 를 바꿔가며 부르면
 *   쿼터는 그대로 녹고, 쿼터가 녹으면 그날 하루 전 사용자가 같이 막힌다.
 *
 * 왜 인터셉터가 아니라 여기(서비스 안)에 있나
 *   인터셉터는 캐시보다 앞이라 캐시에 맞은 요청까지 세게 된다. 그런데 캐시 적중은
 *   상류를 안 부르므로 쿼터를 깎지 않는다. 세야 하는 건 "실제로 나간 호출" 이고,
 *   그걸 아는 자리는 상류를 부르기 직전인 여기뿐이다.
 *
 * 왜 상류가 끊기 전에 우리가 먼저 끊나
 *   상류가 쿼터 초과로 막으면 언제 풀리는지 우리가 모르고, 계정 단위 제재로
 *   번지면 복구가 하루 단위다. 우리가 먼저 끊으면 남은 예비 사본(stale)으로
 *   화면을 계속 그릴 수 있고, 한도를 넘긴 사실이 로그에 남는다.
 */
@Component
public class UpstreamQuota {

    private static final Logger log = LoggerFactory.getLogger(UpstreamQuota.class);

    /**
     * 상류들의 쿼터가 리셋되는 기준 시각. 컨테이너 TZ 에 맡기지 않고 못 박는다 —
     * TZ 설정 한 줄이 빠지면 UTC 로 돌아서 리셋 시점이 9시간 어긋나는데,
     * 그건 장애가 나기 전까지 아무도 모른다.
     */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final StringRedisTemplate redis;

    public UpstreamQuota(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 오늘 몫이 남았으면 한 칸 쓰고 true, 다 썼으면 false.
     *
     * 세고 나서 비교하는 이유(먼저 보고 세는 게 아니라)
     *   "읽고 → 비교하고 → 쓰기" 는 그 사이에 다른 요청이 끼어들 수 있다.
     *   INCR 은 레디스가 원자적으로 처리하므로 동시에 100개가 들어와도
     *   100개가 각자 다른 번호를 받는다. 그 번호가 한도를 넘었는지만 보면 된다.
     *
     * 실패한 호출도 한 칸으로 센다. 상류에 닿지 못한 요청은 쿼터를 안 깎으므로
     * 엄밀히는 과잉 집계지만, 쿼터 방어에서 틀릴 방향은 "더 적게 쓰는 쪽"이 맞다.
     */
    public boolean tryConsume(String name, Upstream up) {
        Integer max = up.quota() != null ? up.quota().perDay() : null;
        if (max == null) return true;   // 한도를 안 적은 상류는 제한 없음

        String key = "quota:" + name + ":" + LocalDate.now(SEOUL);
        try {
            Long used = redis.opsForValue().increment(key);
            if (used == null) return true;
            if (used == 1L) {
                // 첫 호출일 때만 만료를 건다. 매번 걸면 창이 계속 밀려 영원히 안 지워진다.
                // 이틀을 주는 이유: 자정 직전 값이 자정 직후에도 남아 있어야
                // 어제 얼마나 썼는지 redis-cli 로 확인할 수 있다.
                redis.expire(key, Duration.ofDays(2));
            }
            if (used > max) {
                log.warn("상류 하루 한도 초과 — 호출을 막는다: {} ({}/{})", name, used, max);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 레디스가 죽었다고 서비스를 멈출 수는 없다. 통과시키되 조용히 뚫리지 않게 남긴다.
            // (레이트 리밋·캐시와 같은 fail-open 원칙)
            log.warn("쿼터 확인 실패 — 통과시킨다 (Redis 이상): {}", e.toString());
            return true;
        }
    }
}
