package kr.bbangmoa.server.proxy;

import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 컨트롤러와 상류 호출 사이의 층.
 *
 * 왜 컨트롤러에 캐시 코드를 안 넣었나
 *   컨트롤러가 "HTTP 로 들어온 걸 확인하는 일"과 "캐시에 있나 보는 일"을 같이 하면,
 *   나중에 이 로직을 다른 데서(스케줄러, 배치) 재사용할 수 없다.
 *   컨트롤러는 HTTP 를 알고, 서비스는 HTTP 를 모른다 — 이게 3계층의 경계다.
 *
 * 캐시 방식: 캐시 어사이드(cache-aside)
 *   1) 캐시를 본다  2) 없으면 상류를 부른다  3) 결과를 캐시에 넣는다
 */
@Service
public class UpstreamService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamService.class);

    /** 예비 사본 키 접두사. 같은 내용을 수명만 길게 한 벌 더 둔다. */
    private static final String STALE_PREFIX = "stale:";

    private static final String FIELD_CONTENT_TYPE = "ct";
    private static final String FIELD_BODY = "body";

    private final UpstreamClient client;
    private final StringRedisTemplate redis;
    private final UpstreamQuota quota;

    public UpstreamService(UpstreamClient client, StringRedisTemplate redis, UpstreamQuota quota) {
        this.client = client;
        this.redis = redis;
        this.quota = quota;
    }

    /**
     * 어디서 나온 응답인지까지 알려준다. 컨트롤러가 X-Cache 헤더에 쓴다.
     *   HIT   신선한 캐시
     *   MISS  상류를 실제로 불러서 받아옴
     *   STALE 상류가 죽어서 예비 사본을 대신 내줌  ← 이게 있는지로 장애를 감지한다
     *   OFF   이 경로는 캐시를 안 쓰도록 설정돼 있어 매번 상류로 갔다
     */
    public record Result(int status, MediaType contentType, byte[] body, String cacheStatus) {}

    public Result fetch(String name, Upstream up, String method, String path,
                        MultiValueMap<String, String> params, byte[] body) {

        // 수명이 0 이면 그 경로는 캐시를 안 쓴다 — 읽지도 쓰지도 않는다.
        // TMAP 이 그렇다: 응답 하나가 50~160KB 인데 요청마다 좌표가 달라 적중률이 낮고,
        // 레디스는 128MB 라 이것만으로 관광공사·카카오 캐시가 통째로 밀려난다.
        Duration ttl = up.ttlFor(path);
        Duration staleTtl = up.effectiveStaleTtl();
        boolean useCache = isPositive(ttl);
        boolean useStale = isPositive(staleTtl);

        String key = (useCache || useStale) ? cacheKey(name, method, path, params, body) : null;
        String staleKey = useStale ? STALE_PREFIX + key : null;

        // 1) 신선한 캐시가 있으면 끝.
        if (useCache) {
            Result hit = readCache(key, "HIT");
            if (hit != null) return hit;
        }

        // 2) 오늘 이 상류를 부를 몫이 남았나. 캐시에 맞은 요청은 여기까지 안 오므로
        //    실제로 나가는 호출만 세어진다.
        if (!quota.tryConsume(name, up)) {
            Result stale = useStale ? readCache(staleKey, "STALE") : null;
            if (stale != null) {
                log.warn("하루 한도 초과 — 예비 사본으로 응답한다: {} {}", name, path);
                return stale;
            }
            throw new ProxyException(429, name + " 의 오늘 호출 한도를 모두 썼다");
        }

        // 3) 상류 호출. 실패하면 여기서 예외가 난다.
        UpstreamClient.Response up2;
        try {
            up2 = client.call(name, up, method, path, params, body);
        } catch (ProxyException e) {
            // 4) 상류가 죽었다. 예비 사본이 있으면 그걸 준다.
            //
            //    실측상 관광공사 연결 성공률이 40% 수준이다. 이 폴백이 없으면
            //    캐시가 만료되는 순간마다 사용자 6할이 빈 화면을 본다.
            //    "정확하지만 없는 화면"보다 "조금 낡았지만 있는 화면"이 낫다.
            Result stale = useStale ? readCache(staleKey, "STALE") : null;
            if (stale != null) {
                log.warn("상류 실패 — 예비 사본으로 응답한다: {} {}", name, path);
                return stale;
            }
            throw e;   // 예비 사본도 없으면 어쩔 수 없다
        }

        if (up2.status() < 200 || up2.status() >= 300) {
            // 실패는 캐시하지 않는다. 상류가 잠깐 이상했던 걸 TTL 동안 붙들고 있으면
            // 상류가 회복돼도 우리만 계속 실패를 돌려준다.
            throw new ProxyException(502, "상류 응답 코드: " + up2.status());
        }

        MediaType type = up2.contentType() != null ? up2.contentType() : MediaType.APPLICATION_JSON;
        // 같은 내용을 수명만 다르게 두 벌 쓴다.
        if (useCache) writeCache(key, type, up2.body(), ttl);
        if (useStale) writeCache(staleKey, type, up2.body(), staleTtl);
        return new Result(up2.status(), type, up2.body(), useCache ? "MISS" : "OFF");
    }

    /** null·0·음수를 한 자리에서 걸러낸다. 호출부마다 세 가지를 따로 검사하면 하나씩 빠진다. */
    private static boolean isPositive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }

    /**
     * 캐시 키 = {상류}:{메서드}:{경로}:{정렬된 쿼리}[:{바디 해시}]
     *
     * 정렬하는 이유
     *   ?a=1&b=2 와 ?b=2&a=1 은 같은 요청인데 문자열로는 다르다.
     *   정렬을 안 하면 같은 데이터가 두 벌 캐시되고, 적중률이 그만큼 떨어진다.
     *
     * 인증 파라미터를 빼는 이유 둘
     *   1) 항상 같은 값이라 구분에 아무 쓸모가 없다.
     *   2) 더 중요한 건, 비밀값이 Redis 에 문자열로 남는다는 것이다.
     *      키를 서버 뒤로 숨기려고 만든 프록시가 키를 캐시에 흘리면 앞뒤가 안 맞는다.
     *
     * POST 바디는 그대로 넣지 않고 해시로 줄인다 — TMAP 경유지가 많으면 키가 길어지고,
     * 좌표가 그대로 Redis 에 남는 것도 굳이 할 이유가 없다.
     */
    private String cacheKey(String name, String method, String path,
                            MultiValueMap<String, String> params, byte[] body) {
        List<String> parts = new ArrayList<>();
        params.forEach((pname, values) -> {
            if (pname.equalsIgnoreCase("serviceKey") || pname.equalsIgnoreCase("apiKey")) return;
            values.forEach(v -> parts.add(pname + "=" + v));
        });
        parts.sort(String::compareTo);

        StringBuilder sb = new StringBuilder()
                .append(name).append(':')
                .append(method).append(':')
                .append(path).append(':')
                .append(String.join("&", parts));

        if (body != null && body.length > 0) {
            sb.append(':').append(sha256Short(body));
        }
        return sb.toString();
    }

    private String sha256Short(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(d, 0, 12);   // 24자면 충돌 걱정은 없다
        } catch (Exception e) {
            // 해시를 못 만들면 캐시를 못 쓸 뿐이다. 요청 자체를 실패시킬 이유는 없다.
            return "nohash-" + data.length;
        }
    }

    /**
     * 값을 Redis 해시로 저장한다 — 필드 두 개(ct, body).
     * 문자열 하나에 몰아넣고 나중에 잘라 쓰는 방법도 있지만,
     * 그러면 redis-cli 로 열어봤을 때 사람이 못 읽는다.
     * 캐시는 장애 때 제일 먼저 열어보는 곳이라 읽히는 게 중요하다.
     */
    private void writeCache(String key, MediaType type, byte[] body, Duration ttl) {
        try {
            redis.opsForHash().putAll(key, Map.of(
                    FIELD_CONTENT_TYPE, type.toString(),
                    FIELD_BODY, new String(body, StandardCharsets.UTF_8)));
            redis.expire(key, ttl);
        } catch (Exception e) {
            // 캐시 쓰기 실패로 응답을 못 주면 본말전도다. 로그만 남기고 넘어간다.
            log.warn("캐시 쓰기 실패 — 응답은 그대로 준다: {}", e.toString());
        }
    }

    private Result readCache(String key, String status) {
        try {
            Map<Object, Object> e = redis.opsForHash().entries(key);
            if (e.isEmpty()) return null;
            Object ct = e.get(FIELD_CONTENT_TYPE);
            Object body = e.get(FIELD_BODY);
            if (ct == null || body == null) return null;
            return new Result(200,
                    MediaType.parseMediaType(ct.toString()),
                    body.toString().getBytes(StandardCharsets.UTF_8),
                    status);
        } catch (Exception ex) {
            // Redis 가 죽어도 상류를 직접 부르면 서비스는 계속된다(fail-open).
            log.warn("캐시 읽기 실패 — 상류로 간다: {}", ex.toString());
            return null;
        }
    }
}
