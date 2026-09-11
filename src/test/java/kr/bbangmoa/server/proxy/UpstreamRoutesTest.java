package kr.bbangmoa.server.proxy;

import kr.bbangmoa.server.proxy.UpstreamProperties.Upstream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * application.yaml 의 상류 설정이 "의도한 대로" 묶였는지 본다.
 *
 * contextLoads() 로는 부족한 이유
 *   그건 바인딩이 깨지지 않았다는 것까지만 말해준다. routes 를 통째로 빠뜨리거나,
 *   POST 전용 경로에 GET 을 열어두거나, 캐시를 끄려던 상류의 ttl 이 살아 있어도
 *   컨텍스트는 멀쩡히 뜬다. 그런 건 배포된 뒤 쿼터가 녹거나 레디스가 차고 나서야 보인다.
 *
 * 그래서 여기서 검사하는 건 문법이 아니라 결정이다 —
 * "어떤 경로가 어떤 메서드로 열려 있는가"와 "무엇을 캐시하지 않기로 했는가".
 *
 * 단언은 JUnit 것만 쓴다. AssertJ 가 테스트 클래스패스에 있는지는 스타터 구성에 따라
 * 달라지는데, 그것 때문에 빌드가 깨지면 정작 보려던 걸 못 본다.
 */
@SpringBootTest
class UpstreamRoutesTest {

    @Autowired
    UpstreamProperties props;

    @Test
    @DisplayName("상류 다섯이 모두 경로 목록을 갖는다 — routes 가 비면 그 상류는 통째로 404 가 된다")
    void 모든_상류에_경로가_있다() {
        assertEquals(Set.of("tour", "kakao", "kakaonavi", "odsay", "tmap"),
                props.upstreams().keySet());

        props.upstreams().forEach((name, up) -> {
            assertNotNull(up.routes(), name + " 에 routes 가 없다");
            assertFalse(up.routes().isEmpty(), name + " 의 routes 가 비어 있다");
        });
    }

    @Test
    @DisplayName("카카오 모빌리티: GET 경로와 POST 경로가 서로 넘나들지 않는다")
    void 카카오모빌리티_경로별_메서드() {
        Upstream navi = props.get("kakaonavi");

        // 좌표가 쿼리에 실리는 단일 구간 — GET 만.
        assertTrue(navi.route("v1/directions").allowsMethod("GET"));
        assertFalse(navi.route("v1/directions").allowsMethod("POST"),
                "GET 전용 경로에 POST 가 열려 있다");

        // 좌표가 JSON 바디에 실리는 다중 경유지·매트릭스 — POST 만.
        // GET 으로도 열려 있으면 상류에서 실패할 요청이 통과해 쿼터만 깎는다.
        for (String p : new String[]{"v1/waypoints/directions", "v1/destinations/directions"}) {
            assertTrue(navi.route(p).allowsMethod("POST"), p + " 에 POST 가 막혀 있다");
            assertFalse(navi.route(p).allowsMethod("GET"), p + " 에 GET 이 열려 있다");
        }

        // 목록에 없는 경로는 route() 가 null — 컨트롤러가 404 로 끊는다.
        assertNull(navi.route("v1/futures/directions"));
    }

    @Test
    @DisplayName("TMAP: 캐시를 쓰지 않는다 — ttl 과 stale-ttl 이 모두 0")
    void tmap은_캐시하지_않는다() {
        Upstream tmap = props.get("tmap");

        // 응답이 50~160KB 인데 레디스는 128MB 다. 이게 되살아나면
        // 관광공사·카카오 캐시가 LRU 로 밀려난다.
        assertEquals(Duration.ZERO, tmap.ttlFor("tmap/routes/pedestrian"),
                "TMAP 캐시가 다시 켜졌다");
        assertEquals(Duration.ZERO, tmap.effectiveStaleTtl(),
                "TMAP 예비 사본이 다시 켜졌다");

        assertTrue(tmap.route("tmap/routes/pedestrian").allowsMethod("POST"));
        assertFalse(tmap.route("tmap/routes/pedestrian").allowsMethod("GET"));
    }

    @Test
    @DisplayName("쿼터가 빠듯한 상류에는 하루 한도가 걸려 있다")
    void 쿼터_한도가_걸려있다() {
        // 캐시를 껐으므로 TMAP 은 요청 수 = 상류 호출 수다. 하루 한도가 유일한 방어선.
        Upstream tmap = props.get("tmap");
        assertNotNull(tmap.quota(), "TMAP 에 quota 가 없다 — 캐시가 없는 상류라 필수다");
        assertNotNull(tmap.quota().perDay());
        assertTrue(tmap.quota().perDay() > 0);

        // 카카오 모빌리티는 무료 한도가 일 10,000 건이라 그보다 낮게 잡혀 있어야 한다.
        Upstream navi = props.get("kakaonavi");
        assertNotNull(navi.quota(), "카카오 모빌리티에 quota 가 없다");
        assertNotNull(navi.quota().perDay());
        assertTrue(navi.quota().perDay() < 10_000,
                "하루 한도가 상류 무료 쿼터(10,000)보다 높거나 같다 — 방어가 안 된다");
    }

    @Test
    @DisplayName("관광공사: 상세 경로만 더 긴 수명을 갖는다 — 목록은 짧게")
    void 경로별_ttl이_적용된다() {
        Upstream tour = props.get("tour");

        // 목록은 신규 등록이 반영돼야 하므로 상류 기본값(짧은 쪽).
        assertEquals(tour.ttl(), tour.ttlFor("areaBasedList2"));

        // 상세는 contentId 에 묶인 고정 정보라 경로에 따로 적은 긴 값.
        assertTrue(tour.ttlFor("detailCommon2").compareTo(tour.ttl()) > 0);
        assertTrue(tour.ttlFor("detailIntro2").compareTo(tour.ttl()) > 0);
    }
}
