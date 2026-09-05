package com.fleetdeck.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotReservationsTest {

	private static final Duration TTL = Duration.ofSeconds(60);
	private static final Instant T0 = Instant.parse("2026-09-05T02:00:00Z");

	private RobotReservations newReservations() {
		return new RobotReservations(TTL);
	}

	@Test
	void reservedRobotIsExcludedUntilItReportsTheOrder() {
		RobotReservations reservations = newReservations();

		reservations.reserve("AMR-001", "M-10", T0);

		assertThat(reservations.reservedSerials(T0)).containsExactly("AMR-001");
	}

	@Test
	void reportingTheReservedOrderReleasesTheReservation() {
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);

		reservations.onRobotState(RobotStates.of("AMR-001", "M-10", true, false, 80.0, List.of()));

		assertThat(reservations.reservedSerials(T0)).isEmpty();
	}

	@Test
	void reportingAnOlderOrderKeepsTheReservation() {
		// 로봇이 아직 이전 order 를 보고 중이면 새 order 를 수령한 게 아니다.
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);

		reservations.onRobotState(RobotStates.of("AMR-001", "M-9", false, false, 80.0, List.of()));

		assertThat(reservations.reservedSerials(T0)).containsExactly("AMR-001");
	}

	@Test
	void reservationExpiresAfterTtl() {
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);

		assertThat(reservations.reservedSerials(T0.plusSeconds(59))).containsExactly("AMR-001");
		assertThat(reservations.reservedSerials(T0.plusSeconds(61))).isEmpty();
	}

	@Test
	void releaseRemovesReservationAndToleratesNull() {
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);

		reservations.release(null);
		assertThat(reservations.reservedSerials(T0)).containsExactly("AMR-001");

		reservations.release("AMR-001");
		assertThat(reservations.reservedSerials(T0)).isEmpty();
	}

	@Test
	void reservingTheSameRobotAgainReplacesTheHeldOrder() {
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);
		reservations.reserve("AMR-001", "M-11", T0.plusSeconds(5));

		assertThat(reservations.snapshot().get("AMR-001").orderId()).isEqualTo("M-11");
	}

	@Test
	void otherRobotsAreUnaffected() {
		RobotReservations reservations = newReservations();
		reservations.reserve("AMR-001", "M-10", T0);
		reservations.reserve("AMR-002", "M-11", T0);

		reservations.onRobotState(RobotStates.of("AMR-001", "M-10", true, false, 80.0, List.of()));

		assertThat(reservations.reservedSerials(T0)).containsExactly("AMR-002");
	}
}
