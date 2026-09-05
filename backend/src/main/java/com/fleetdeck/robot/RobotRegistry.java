package com.fleetdeck.robot;

import com.fleetdeck.common.LatestStateRegistry;
import com.fleetdeck.config.FleetdeckProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 로봇의 최신 상태와 마지막 수신 시각을 함께 들고 있다.
 *
 * 수신 시각이 필요한 이유: 통신이 끊긴 로봇도 마지막 상태가 "유휴"로 남아 있어
 * 디스패처가 죽은 로봇에 미션을 배정한다. 부하 테스트에서 50대를 8대로 줄였을 때
 * 사라진 42대가 계속 후보로 잡히는 것을 확인했다.
 */
@Component
public class RobotRegistry extends LatestStateRegistry<RobotStateMessage> {

	private final ConcurrentMap<String, Instant> lastSeen = new ConcurrentHashMap<>();
	private final Duration offlineAfter;
	private final Duration evictAfter;

	public RobotRegistry(FleetdeckProperties props) {
		super(RobotStateMessage::serialNumber);
		this.offlineAfter = props.robot().offlineAfter();
		this.evictAfter = props.robot().evictAfter();
	}

	public void upsert(RobotStateMessage state, Instant receivedAt) {
		super.upsert(state);
		lastSeen.put(state.serialNumber(), receivedAt);
	}

	public boolean isOnline(String serial, Instant now) {
		Instant seen = lastSeen.get(serial);
		return seen != null && !isOlderThan(seen, offlineAfter, now);
	}

	/** 배정 후보. 오프라인 로봇은 마지막 상태가 유휴여도 제외한다. */
	public List<RobotStateMessage> onlineStates(Instant now) {
		return all().stream()
				.filter(r -> isOnline(r.serialNumber(), now))
				.toList();
	}

	/** API·브로드캐스트용. 상태에 생존 여부를 붙여 돌려준다. */
	public List<RobotView> views(Instant now) {
		return all().stream()
				.map(r -> toView(r, now))
				.sorted(Comparator.comparing(v -> v.state().serialNumber()))
				.toList();
	}

	public Optional<RobotView> view(String serial, Instant now) {
		return find(serial).map(r -> toView(r, now));
	}

	public RobotView toView(RobotStateMessage state, Instant now) {
		String serial = state.serialNumber();
		return new RobotView(state, isOnline(serial, now), lastSeen.get(serial));
	}

	/** 오래 소식 없는 로봇을 지운다. 지운 시리얼 목록을 돌려준다. */
	public List<String> evictStale(Instant now) {
		List<String> gone = lastSeen.entrySet().stream()
				.filter(e -> isOlderThan(e.getValue(), evictAfter, now))
				.map(Map.Entry::getKey)
				.toList();
		for (String serial : gone) {
			lastSeen.remove(serial);
			remove(serial);
		}
		return gone;
	}

	private static boolean isOlderThan(Instant seen, Duration limit, Instant now) {
		return seen.plus(limit).isBefore(now);
	}
}
