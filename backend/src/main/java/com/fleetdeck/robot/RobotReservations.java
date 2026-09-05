package com.fleetdeck.robot;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 배정했지만 아직 로봇이 수령을 보고하지 않은 order 를 들고 있는다.
 *
 * RobotRegistry 는 텔레메트리가 올 때만(초당 1회) 갱신되므로, 한 번의 디스패치 패스에서
 * 미션 여러 건을 연속 배정하면 같은 로봇이 계속 유휴로 보여 중복 배정된다.
 * 시뮬레이터/실장비 모두 나중에 온 order 가 앞 order 를 덮어쓰므로 앞 미션은 완료 보고를 못 받는다.
 * 배정 즉시 여기에 예약을 걸어 후보에서 제외하는 것으로 그 창을 없앤다.
 *
 * 예약은 다음 중 먼저 오는 쪽에서 풀린다.
 *   - 해당 로봇이 그 orderId 를 보고 (정상 수령)
 *   - TTL 경과 (order 유실. 미션은 타임아웃 회수가 PENDING 으로 되돌린다)
 */
public class RobotReservations {

	private static final Logger log = LoggerFactory.getLogger(RobotReservations.class);

	private final ConcurrentMap<String, Reservation> byRobot = new ConcurrentHashMap<>();
	private final Duration ttl;

	public record Reservation(String orderId, Instant reservedAt) {
	}

	public RobotReservations(Duration ttl) {
		this.ttl = ttl;
	}

	public void reserve(String serial, String orderId, Instant now) {
		byRobot.put(serial, new Reservation(orderId, now));
	}

	public void release(String serial) {
		if (serial != null) {
			byRobot.remove(serial);
		}
	}

	/** 아직 유효한 예약을 들고 있는 로봇 목록. TTL 지난 예약은 이 때 정리한다. */
	public Set<String> reservedSerials(Instant now) {
		byRobot.entrySet().removeIf(e -> {
			boolean expired = isExpired(e.getValue(), now);
			if (expired) {
				log.warn("예약 만료: {} 가 order {} 를 {}초 안에 수령하지 않음",
						e.getKey(), e.getValue().orderId(), ttl.toSeconds());
			}
			return expired;
		});
		return Set.copyOf(byRobot.keySet());
	}

	/** 로봇이 예약된 order 를 보고하면 수령한 것이므로 예약을 푼다. */
	public void onRobotState(RobotStateMessage state) {
		byRobot.computeIfPresent(state.serialNumber(),
				(serial, held) -> held.orderId().equals(state.orderId()) ? null : held);
	}

	public Map<String, Reservation> snapshot() {
		return Map.copyOf(byRobot);
	}

	private boolean isExpired(Reservation reservation, Instant now) {
		return reservation.reservedAt().plus(ttl).isBefore(now);
	}
}
