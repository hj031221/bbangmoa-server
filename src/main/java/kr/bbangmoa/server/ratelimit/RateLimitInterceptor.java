package kr.bbangmoa.server.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.bbangmoa.server.proxy.UpstreamProperties;
import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;

/**
 * IP 당 분당 요청 수 제한.
 *
 * 왜 캐시(Phase 2)만으로는 부족한가
 *   캐시는 "같은 요청"이 반복될 때만 상류를 막는다. 좌표나 페이지 번호를
 *   조금씩 바꿔가며 부르면 매번 캐시 미스가 나서 그대로 상류로 나간다.
 *   그러면 관광공사 일일 쿼터가 하루 만에 소진된다. 둘은 막는 대상이 다르다.
 *
 * 왜 Filter 가 아니라 Interceptor 인가
 *   Filter 는 서블릿 레벨이라 더 앞단이지만, 경로를 문자열로 직접 비교해야 한다.
 *   Interceptor 는 스프링 MVC 의 경로 패턴(/api/tour/**)을 그대로 쓸 수 있어서
 *   "어디에 걸려 있는지"가 WebConfig 한 곳에 보인다.
 *
 * 여기서 막지 못하는 것 — IP 를 바꿔가며 부르는 경우.
 *   그건 IP 당 제한으로는 구조적으로 못 막는다. 우리 계정의 일일 쿼터는
 *   서버 전체 합계로 세는 UpstreamQuota 가 따로 지킨다. 둘은 역할이 다르다.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final UpstreamProperties upstreams;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                RateLimitProperties props,
                                UpstreamProperties upstreams) {
        this.redis = redis;
        this.props = props;
        this.upstreams = upstreams;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.enabled()) return true;

        // 프리플라이트(OPTIONS)는 세지 않는다.
        //   스프링은 프리플라이트에도 인터셉터 체인을 그대로 태운다. 그런데 이건
        //   브라우저가 본 요청 전에 자동으로 보내는 것이라 사용자의 "요청 횟수"가 아니고,
        //   상류로 나가지도 않는다. 세면 한 번의 실제 호출이 두 번으로 계산된다.
        if ("OPTIONS".equals(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null) {
            return true;
        }

        // getRemoteAddr() 를 그냥 써도 되는 이유:
        // application.yaml 의 server.forward-headers-strategy: framework 가 켜져 있어서
        // 스프링이 Nginx 가 붙인 X-Forwarded-For 를 이미 반영해준다.
        // 이게 없으면 모든 요청의 IP 가 Nginx 주소 하나로 보여서
        // 전체 사용자가 한 사람 취급을 받는다.
        String ip = request.getRemoteAddr();

        // 키에 "몇 번째 분"을 넣는다 = 고정 창(fixed window) 방식.
        // 분이 바뀌면 키가 통째로 바뀌므로 카운터가 저절로 0 부터 시작한다.
        // 단점: 창 경계에서 순간적으로 두 배까지 통과할 수 있다(59초에 N번, 60초에 N번).
        // 슬라이딩 윈도우가 정확하지만 구현이 복잡하다. 쿼터 방어가 목적이라
        // 이 정도 오차는 감수한다.
        long minute = Instant.now().getEpochSecond() / 60;

        // 1) 전체 한도. 상류를 가리지 않고 이 IP 가 보낸 모든 요청을 센다.
        //    (예전 키 이름이 "rl:tour:" 였는데 실제로는 전체를 세고 있었다 —
        //     이름이 사실과 달라서 로그·redis-cli 로 볼 때 오해를 준다)
        if (!allow("rl:all:" + ip + ":" + minute, props.requestsPerMinute(), response)) {
            return false;
        }

        // 2) 상류별 한도. 쿼터가 빠듯한 상류(카카오 모빌리티·TMAP)만 따로 조인다.
        //    전체 한도만 있으면 그 300 회를 전부 TMAP 에 쏟아붓는 것도 통과한다.
        String name = upstreamName(request);
        Upstream up = name != null ? upstreams.get(name) : null;
        Integer perIp = (up != null && up.quota() != null) ? up.quota().perMinutePerIp() : null;
        if (perIp != null && !allow("rl:" + name + ":" + ip + ":" + minute, perIp, response)) {
            return false;
        }

        return true;
    }

    /** 카운터 하나를 올리고 한도 안인지 본다. 넘었으면 429 를 직접 써서 false. */
    private boolean allow(String key, int limit, HttpServletResponse response) throws Exception {
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 첫 요청일 때만 만료를 건다. 매번 걸면 창이 계속 밀려나서
                // 카운터가 영원히 안 리셋된다.
                redis.expire(key, Duration.ofSeconds(90));
            }
        } catch (Exception e) {
            // Redis 가 죽었을 때 통과시킬 것인가(fail-open), 막을 것인가(fail-closed).
            // 통과 선택: 레이트 리밋은 부가 기능인데 이게 죽었다고 사이트 전체가
            // 멈추면 손해가 더 크다. 대신 로그를 남겨서 조용히 뚫리지 않게 한다.
            log.warn("레이트 리밋 확인 실패 — 통과시킨다 (Redis 이상): {}", e.toString());
            return true;
        }

        if (count != null && count > limit) {
            response.setStatus(429);
            // 클라이언트에게 언제 다시 오면 되는지 알려주는 표준 헤더.
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"message\":\"분당 "
                    + limit + "회를 넘었다\"}");
            return false;   // false = 컨트롤러로 넘기지 않는다
        }
        return true;
    }

    /**
     * /api/{이름}/... 에서 {이름} 만 꺼낸다. 형태가 안 맞으면 null.
     *
     * 컨트롤러의 @PathVariable 을 못 쓰는 이유: 인터셉터는 컨트롤러보다 먼저 돌아서
     * 아직 경로 변수가 풀려 있지 않다. 그래서 여기서 직접 자른다.
     */
    private String upstreamName(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/")) return null;
        int start = "/api/".length();
        int end = uri.indexOf('/', start);
        if (end < 0) return null;
        String name = uri.substring(start, end);
        return name.isEmpty() ? null : name;
    }
}
