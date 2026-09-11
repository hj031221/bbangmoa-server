package kr.bbangmoa.server.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * app.upstreams.{이름}.* 을 통째로 받는다.
 *
 * 왜 상류마다 클래스를 만들지 않고 설정 하나로 묶었나
 *   다섯 상류의 차이는 로직이 아니라 데이터다 —
 *     키가 쿼리에 들어가나 헤더에 들어가나, GET 이냐 POST 냐, 어떤 경로를 여나.
 *   차이가 데이터면 코드를 복사할 게 아니라 설정으로 빼는 게 맞다.
 *   컨트롤러를 다섯 벌 두면 나중에 타임아웃 정책 하나 바꿀 때 다섯 군데를 고쳐야 하고,
 *   그중 하나를 빠뜨리는 게 이런 코드가 썩는 전형적인 방식이다.
 */
@ConfigurationProperties(prefix = "app")
public record UpstreamProperties(Map<String, Upstream> upstreams) {

    public record Upstream(
            /** 상류 주소. 요청 경로의 뒷부분이 여기 붙는다. */
            String baseUrl,

            /** QUERY = 쿼리 파라미터로, HEADER = 요청 헤더로 키를 붙인다. */
            AuthType authType,

            /** QUERY 면 파라미터 이름, HEADER 면 헤더 이름. */
            String authName,

            /** 헤더 값 앞에 붙일 접두사. 카카오는 "KakaoAK " 가 필요하고 TMAP 은 없다. */
            String authPrefix,

            /** 실제 키. 환경변수에서 온다. 로그·캐시키에 절대 넣지 않는다. */
            String authValue,

            /**
             * 열어둘 경로 목록. 여기 없는 경로는 상류를 부르지도 않고 404.
             *
             * 메서드를 상류가 아니라 경로마다 다는 이유
             *   예전에는 상류 전체에 methods 하나를 달았다. 카카오 모빌리티처럼
             *   GET 경로(v1/directions)와 POST 경로(v1/waypoints/directions)가
             *   섞인 상류가 생기자 methods: GET,POST 로 열 수밖에 없었고,
             *   그러면 POST /v1/directions 나 GET /v1/waypoints/directions 같은
             *   실재하지 않는 조합까지 통과해서 상류로 나간다.
             *   그 요청은 상류에서 실패하는데, 실패해도 쿼터는 깎인다.
             *   경로마다 붙이면 열려 있는 조합이 설정 한 줄에 그대로 보인다.
             */
            List<Route> routes,

            /** 경로가 ttl 을 따로 안 적었을 때 쓰는 기본 캐시 수명. */
            Duration ttl,

            /** 상류가 죽었을 때 내줄 예비 사본의 수명. 0 이거나 없으면 예비 사본을 안 둔다. */
            Duration staleTtl,

            /**
             * 요청에 항상 붙일 고정 헤더.
             *
             * ODSAY 가 Referer 로 등록 도메인을 검사한다 — 실측:
             *   Referer 없음                 → ApiKeyAuthFailed
             *   https://breadmoa.vercel.app/ → 정상
             *   http://localhost:5173/       → 정상
             * 서버가 부르면 Referer 가 없으니 그대로는 못 쓴다. 그래서 여기서 붙인다.
             *
             * 부수 효과: 이제 사용자가 어느 도메인에서 접속하든 상관없어진다.
             * 브라우저의 Referer 가 아니라 우리 서버가 정한 값이 나가기 때문이다.
             * breadmoa.com 을 ODSAY 에 새로 등록하지 않아도 된다.
             */
            Map<String, String> headers,

            /** 쿼터 방어. 안 적으면 제한 없음(기존 동작). */
            Quota quota
    ) {
        public enum AuthType { QUERY, HEADER }

        /**
         * 열린 경로 하나.
         *
         * @param path    /api/{상류}/ 뒤에 오는 경로. 정확히 일치해야 한다.
         * @param methods 이 경로에 허용할 HTTP 메서드.
         * @param ttl     이 경로만의 캐시 수명. 없으면 상류 기본값(ttl)을 쓴다.
         *                0 으로 두면 이 경로는 캐시하지 않는다 — 읽지도 쓰지도 않는다.
         */
        public record Route(String path, Set<String> methods, Duration ttl) {
            public boolean allowsMethod(String method) {
                return methods != null && methods.contains(method);
            }
        }

        /**
         * 쿼터 방어 두 겹.
         *
         * @param perMinutePerIp IP 하나가 이 상류에 1분 동안 보낼 수 있는 요청 수.
         *                       한 사용자가 폭주하는 걸 막는다. 캐시 적중도 포함해서 센다
         *                       (레이트 리밋은 캐시보다 앞에서 도는 인터셉터라서).
         * @param perDay         이 상류를 실제로 부른 횟수의 하루 상한 — 서버 전체 합계다.
         *                       이쪽이 진짜 쿼터 방어다. IP 당으로 걸면 IP 를 바꿔가며
         *                       부르는 순간 무의미해지고, 우리가 지키려는 건 "우리 계정의
         *                       일일 쿼터" 라는 서버 전체의 자원이기 때문이다.
         *                       캐시에 맞은 요청은 상류를 안 부르므로 세지 않는다.
         */
        public record Quota(Integer perMinutePerIp, Integer perDay) {}

        public boolean hasKey() {
            return authValue != null && !authValue.isBlank();
        }

        /** 열린 경로면 그 설정을, 아니면 null. 경로 수가 상류당 넷 이하라 선형 탐색으로 충분하다. */
        public Route route(String path) {
            if (routes == null) return null;
            for (Route r : routes) {
                if (r.path().equals(path)) return r;
            }
            return null;
        }

        /**
         * 이 경로의 실제 캐시 수명. Duration.ZERO 면 "캐시하지 않는다"는 뜻이다.
         * null 을 돌려주지 않는 이유: 호출부가 매번 null 검사를 하게 만들면
         * 그중 한 곳을 빠뜨리는 순간 NPE 로 요청이 통째로 실패한다.
         */
        public Duration ttlFor(String path) {
            Route r = route(path);
            Duration t = (r != null && r.ttl() != null) ? r.ttl() : ttl;
            return t != null ? t : Duration.ZERO;
        }

        public Duration effectiveStaleTtl() {
            return staleTtl != null ? staleTtl : Duration.ZERO;
        }
    }

    public Upstream get(String name) {
        return upstreams.get(name);
    }
}
