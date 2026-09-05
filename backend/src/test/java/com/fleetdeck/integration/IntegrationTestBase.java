package com.fleetdeck.integration;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Mosquitto·TimescaleDB 컨테이너 위에서 도는 통합 테스트의 바탕.
 *
 * 지금까지 찾은 결함(중복 배정, 유령 로봇, 회수 누락)은 모두 단위 테스트를 통과하고
 * 통합 구간에서만 드러났다. MQTT -> 적재 -> 미션 전이 -> 브로드캐스트를 실제로 흘려본다.
 *
 * 컨테이너는 static 이라 테스트 클래스 사이에서 공유된다 (Testcontainers 싱글턴 패턴).
 * Flyway 가 빈 DB 에 V1 을 적용하므로 마이그레이션 자체도 함께 검증된다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

	private static final String MOSQUITTO_CONF = """
			listener 1883
			allow_anonymous true
			""";

	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			DockerImageName.parse("timescale/timescaledb:latest-pg16")
					.asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("fleetdeck")
			.withUsername("fleetdeck")
			.withPassword("test");

	protected static final GenericContainer<?> MOSQUITTO = new GenericContainer<>(
			DockerImageName.parse("eclipse-mosquitto:2"))
			.withExposedPorts(1883)
			.withCopyToContainer(Transferable.of(MOSQUITTO_CONF), "/mosquitto/config/mosquitto.conf")
			.waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*\\n", 1)
					.withStartupTimeout(Duration.ofSeconds(60)));

	static {
		POSTGRES.start();
		MOSQUITTO.start();
	}

	protected static String mqttUrl() {
		return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("fleetdeck.mqtt.url", IntegrationTestBase::mqttUrl);

		// 빈 DB 이므로 baseline 없이 V1 부터 정상 적용되어야 한다.
		registry.add("spring.flyway.baseline-on-migrate", () -> false);

		// 모의 WMS 가 끼어들면 검증 대상 미션과 섞인다.
		registry.add("fleetdeck.wms.enabled", () -> false);
		// 회수·오프라인 전이를 테스트에서 유도하려면 주기가 짧아야 한다.
		registry.add("fleetdeck.dispatch.interval", () -> "1s");
		registry.add("fleetdeck.dispatch.reclaim-interval", () -> "1s");
		registry.add("fleetdeck.robot.evict-interval", () -> "1s");
	}
}
