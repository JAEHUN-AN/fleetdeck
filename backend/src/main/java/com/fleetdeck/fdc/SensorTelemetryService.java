package com.fleetdeck.fdc;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.config.WebSocketConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 센서 측정값의 합류 지점. 설비 상태({@code EquipmentTelemetryService})와 대칭이다.
 *
 * <p>하는 일은 셋이다 — 적재, 탐지, 경보 중계. 프로토콜은 이미 어댑터에서 끝났으므로
 * MQTT 로 왔든 OPC UA 로 왔든 여기서는 구분이 없다.
 *
 * <p>사양(공칭값·시그마)이 없는 채널은 <b>적재는 하되 탐지는 건너뛴다.</b> 설비가 새 채널을
 * 내보내기 시작해도 데이터는 남아야 하고, 기준 없이 만든 관리한계는 경보가 아니라 소음이다.
 */
@Service
public class SensorTelemetryService {

	private static final Logger log = LoggerFactory.getLogger(SensorTelemetryService.class);

	private final FleetdeckProperties.Fdc props;
	private final SensorRepository repository;
	private final SimpMessagingTemplate messaging;

	/** (설비, 채널) 마다 감시기 하나. 탐지기가 상태를 들고 있어 공유할 수 없다. */
	private final ConcurrentMap<String, ChannelMonitor> monitors = new ConcurrentHashMap<>();
	private final java.util.Set<String> unknownChannels = ConcurrentHashMap.newKeySet();

	public SensorTelemetryService(FleetdeckProperties props, SensorRepository repository,
			SimpMessagingTemplate messaging) {
		this.props = props.fdc();
		this.repository = repository;
		this.messaging = messaging;
	}

	public void accept(List<SensorSample> samples) {
		if (samples.isEmpty()) {
			return;
		}
		persist(samples);
		if (props == null || !props.enabled()) {
			return;
		}
		for (SensorSample sample : samples) {
			detect(sample);
		}
	}

	public void acceptFaultInjection(FaultInjectionMessage message) {
		if (!message.isUsable()) {
			log.warn("불완전한 주입 라벨 무시: {}", message);
			return;
		}
		try {
			repository.insertFaultInjection(message);
		}
		catch (DataAccessException e) {
			log.error("주입 라벨 적재 실패 {}/{}: {}", message.equipmentId(), message.channel(),
					e.getMostSpecificCause().getMessage());
		}
	}

	private void persist(List<SensorSample> samples) {
		try {
			repository.insertSamples(samples);
		}
		catch (DataAccessException e) {
			log.error("센서값 적재 실패 ({}건): {}", samples.size(),
					e.getMostSpecificCause().getMessage());
		}
	}

	private void detect(SensorSample sample) {
		ChannelSpec spec = specOf(sample.channel());
		if (spec == null) {
			// 채널마다 한 번만 알린다. 매 틱 찍으면 로그가 덮인다.
			if (unknownChannels.add(sample.channel())) {
				log.info("사양 없는 채널 {} - 적재만 하고 탐지는 건너뛴다", sample.channel());
			}
			return;
		}

		String key = sample.equipmentId() + "/" + sample.channel();
		ChannelMonitor monitor = monitors.computeIfAbsent(key, k -> new ChannelMonitor(spec));
		for (ChannelMonitor.Alarm alarm : monitor.accept(sample.value())) {
			raise(sample, alarm);
		}
	}

	private void raise(SensorSample sample, ChannelMonitor.Alarm alarm) {
		log.warn("FDC 경보 {} {}/{} value={} score={}", alarm.detector(), sample.equipmentId(),
				sample.channel(), sample.value(), String.format("%.2f", alarm.score()));
		try {
			repository.insertAlarm(sample, alarm);
		}
		catch (DataAccessException e) {
			log.error("경보 적재 실패: {}", e.getMostSpecificCause().getMessage());
		}
		messaging.convertAndSend(WebSocketConfig.ALARMS_TOPIC,
				new AlarmView(sample.equipmentId(), sample.channel(), alarm.detector(),
						sample.value(), alarm.score(), sample.at()));
	}

	private ChannelSpec specOf(String channel) {
		Map<String, ChannelSpec> channels = props == null ? null : props.channels();
		return channels == null ? null : channels.get(channel);
	}

	/** 대시보드로 나가는 경보. */
	public record AlarmView(String equipmentId, String channel, String detector, double value,
			double score, OffsetDateTime at) {
	}
}
