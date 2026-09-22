package com.fleetdeck.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.equipment.EquipmentSource;
import com.fleetdeck.equipment.EquipmentTelemetryService;
import com.fleetdeck.fdc.SensorSample;
import com.fleetdeck.fdc.SensorTelemetryService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * OPC UA 설비 수집기.
 *
 * <p>MQTT 수집(Spring Integration) 과 대칭인 자리다. 서버에 붙어 Equipment 폴더를 탐색하고,
 * 구독(SUBSCRIBE) 또는 주기 읽기(POLL) 로 값을 받아 {@link EquipmentTelemetryService} 에 넘긴다.
 * 두 모드가 같은 매핑·같은 캐시를 쓰므로 지연과 부하를 나란히 잴 수 있다.
 *
 * <p>연결은 백그라운드에서 재시도한다. OPC UA 서버가 늦게 뜨거나 끊겨도
 * 관제 서버 자체가 기동에 실패하면 안 된다.
 */
@Component
public class OpcUaCollector implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(OpcUaCollector.class);

	/** 구독이 살아있음을 서버가 알리는 주기. publishing 주기의 몇 배로 둘지. */
	private static final double KEEP_ALIVE_FACTOR = 3.0;

	private final FleetdeckProperties.OpcUa props;
	private final EquipmentTelemetryService equipment;
	private final SensorTelemetryService sensors;
	private final EquipmentValueCache cache = new EquipmentValueCache();

	private final AtomicBoolean running = new AtomicBoolean(false);
	private ScheduledExecutorService worker;
	private volatile OpcUaClient client;
	private volatile OpcUaSubscription subscription;
	private volatile List<OpcUaNodeSet> nodeSets = List.of();

	public OpcUaCollector(FleetdeckProperties props, EquipmentTelemetryService equipment,
			SensorTelemetryService sensors) {
		this.props = props.opcua();
		this.equipment = equipment;
		this.sensors = sensors;
	}

	@Override
	public void start() {
		if (props == null || !props.enabled()) {
			log.info("OPC UA 수집 비활성 (fleetdeck.opcua.enabled=false)");
			return;
		}
		if (!running.compareAndSet(false, true)) {
			return;
		}
		worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "opcua-collector");
			t.setDaemon(true);
			return t;
		});
		worker.execute(this::connectWithRetry);
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (worker != null) {
			worker.shutdownNow();
		}
		closeQuietly();
		cache.clear();
		log.info("OPC UA 수집기 정지");
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	/** MQTT 어댑터보다 늦게 띄운다. 기동 로그에서 두 수집 경로가 순서대로 보인다. */
	@Override
	public int getPhase() {
		return 100;
	}

	// --- 연결 ---

	private void connectWithRetry() {
		while (running.get()) {
			try {
				connectOnce();
				return;
			}
			catch (Exception e) {
				log.warn("OPC UA 연결 실패 ({}): {} - {}s 뒤 재시도", props.endpointUrl(),
						e.getMessage(), props.reconnectDelay().toSeconds());
			}
			try {
				TimeUnit.MILLISECONDS.sleep(props.reconnectDelay().toMillis());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void connectOnce() throws Exception {
		OpcUaClient connected = OpcUaClient.create(props.endpointUrl(), this::selectEndpoint,
				transport -> {
				}, config -> {
				});
		connected.connect();
		this.client = connected;

		nodeSets = OpcUaBrowser.discover(connected, props.namespaceUri(), props.folderName());
		if (nodeSets.isEmpty()) {
			throw new IllegalStateException("Equipment 폴더에서 설비를 찾지 못했다: " + props.folderName());
		}
		log.info("OPC UA 연결 {} - 설비 {}대, 변수 {}개, 모드 {}", props.endpointUrl(), nodeSets.size(),
				totalVariables(), props.mode());

		if (props.mode() == FleetdeckProperties.OpcUaMode.POLL) {
			startPolling();
		}
		else {
			startSubscription();
		}
	}

	/**
	 * 서버가 GetEndpoints 로 돌려주는 URL 은 서버가 자기를 부르는 이름이다.
	 * 컨테이너·NAT·다중 NIC 환경에서는 그 이름이 우리 쪽에서 안 풀린다
	 * (시뮬레이터가 0.0.0.0 으로 광고하면 그대로 0.0.0.0 에 붙으러 간다).
	 * 보안 설정만 서버가 말한 것을 따르고, 접속 주소는 우리가 설정한 것으로 되돌린다.
	 */
	private Optional<EndpointDescription> selectEndpoint(List<EndpointDescription> endpoints) {
		String host = EndpointUtil.getHost(props.endpointUrl());
		int port = EndpointUtil.getPort(props.endpointUrl());
		return endpoints.stream()
				.filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
				.findFirst()
				.or(() -> endpoints.stream().findFirst())
				.map(e -> host == null ? e : EndpointUtil.updateUrl(e, host, port));
	}

	private int totalVariables() {
		return nodeSets.stream().mapToInt(n -> n.variables().size()).sum();
	}

	// --- 구독 모드 ---

	private void startSubscription() throws Exception {
		double publishingMillis = props.publishingInterval().toMillis();
		OpcUaSubscription sub = new OpcUaSubscription(client, publishingMillis);
		sub.setTargetKeepAliveInterval(publishingMillis * KEEP_ALIVE_FACTOR);
		sub.setSubscriptionListener(new SubscriptionHandler());
		sub.create();

		List<OpcUaMonitoredItem> items = new ArrayList<>();
		for (OpcUaNodeSet set : nodeSets) {
			for (Map.Entry<String, NodeId> variable : set.variables().entrySet()) {
				OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(variable.getValue());
				item.setSamplingInterval(publishingMillis);
				item.setQueueSize(uint(1));
				item.setUserObject(new VariableRef(set.equipmentId(), variable.getKey()));
				items.add(item);
			}
		}
		sub.addMonitoredItems(items);
		sub.synchronizeMonitoredItems();
		this.subscription = sub;
		log.info("OPC UA 구독 생성 - 모니터 항목 {}개, publishing {}ms", items.size(), publishingMillis);
	}

	/** 변수 하나를 어느 설비의 무엇으로 되돌릴지. 모니터 항목에 붙여 둔다. */
	private record VariableRef(String equipmentId, String variable) {
	}

	private final class SubscriptionHandler implements OpcUaSubscription.SubscriptionListener {

		/**
		 * 한 publish 에 담겨 온 값들을 통째로 받는다. 변수별 콜백을 쓰면 한 틱에
		 * 변수 수만큼 적재가 일어나므로, 여기서 설비 단위로 모아 한 번만 내보낸다.
		 */
		@Override
		public void onDataReceived(OpcUaSubscription sub, List<OpcUaMonitoredItem> items,
				List<DataValue> values) {
			Set<String> touched = new HashSet<>();
			for (int i = 0; i < items.size() && i < values.size(); i++) {
				if (items.get(i).getUserObject().orElse(null) instanceof VariableRef ref) {
					record(ref, values.get(i));
					touched.add(ref.equipmentId());
				}
			}
			touched.forEach(OpcUaCollector.this::emit);
		}

		@Override
		public void onStatusChanged(OpcUaSubscription sub, StatusCode status) {
			log.warn("OPC UA 구독 상태 변경: {}", status);
		}

		@Override
		public void onNotificationDataLost(OpcUaSubscription sub) {
			// 알림 유실. MQTT QoS 0 유실과 같은 성격이라 같은 방식으로 기록한다.
			log.warn("OPC UA 알림 유실 - publishing 주기 또는 큐 크기를 의심할 것");
		}

		@Override
		public void onWatchdogTimerElapsed(OpcUaSubscription sub) {
			log.warn("OPC UA 워치독 만료 - 구독을 다시 만든다");
			recreateSubscription();
		}

		@Override
		public void onTransferFailed(OpcUaSubscription sub, StatusCode status) {
			log.warn("OPC UA 구독 이관 실패 ({}) - 구독을 다시 만든다", status);
			recreateSubscription();
		}
	}

	/**
	 * 세션이 재수립돼도 구독이 살아 넘어오지 못하는 경우가 있다.
	 * 그때 조용히 값이 끊기는 것이 가장 나쁘므로 새로 만든다.
	 */
	private void recreateSubscription() {
		if (!running.get() || worker == null) {
			return;
		}
		worker.execute(() -> {
			try {
				deleteSubscriptionQuietly();
				startSubscription();
			}
			catch (Exception e) {
				log.error("OPC UA 구독 재생성 실패: {} - 연결부터 다시 시도한다", e.getMessage());
				closeQuietly();
				connectWithRetry();
			}
		});
	}

	// --- 폴링 모드 ---

	private void startPolling() {
		long periodMillis = props.pollInterval().toMillis();
		worker.scheduleAtFixedRate(this::pollOnce, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
		log.info("OPC UA 폴링 시작 - 주기 {}ms", periodMillis);
	}

	private void pollOnce() {
		OpcUaClient current = client;
		if (current == null) {
			return;
		}
		List<NodeId> nodeIds = new ArrayList<>();
		List<VariableRef> refs = new ArrayList<>();
		for (OpcUaNodeSet set : nodeSets) {
			set.variables().forEach((name, id) -> {
				nodeIds.add(id);
				refs.add(new VariableRef(set.equipmentId(), name));
			});
		}
		try {
			// maxAge 0 - 서버 캐시가 아니라 현재값을 요구한다.
			List<DataValue> values = current.readValues(0.0, TimestampsToReturn.Both, nodeIds);
			Set<String> touched = new HashSet<>();
			for (int i = 0; i < refs.size() && i < values.size(); i++) {
				record(refs.get(i), values.get(i));
				touched.add(refs.get(i).equipmentId());
			}
			touched.forEach(this::emit);
		}
		catch (Exception e) {
			log.warn("OPC UA 폴링 실패: {}", e.getMessage());
		}
	}

	// --- 공통 ---

	private void record(VariableRef ref, DataValue value) {
		if (value == null) {
			return;
		}
		StatusCode status = value.getStatusCode();
		if (status != null && status.isBad()) {
			return;
		}
		Object raw = value.getValue() == null ? null : value.getValue().getValue();
		cache.put(ref.equipmentId(), ref.variable(), raw, sourceTime(value));
	}

	/** 장비가 값을 만든 시각. 없으면 서버 시각, 그것도 없으면 수집 시각. */
	private static Instant sourceTime(DataValue value) {
		if (value.getSourceTime() != null) {
			return value.getSourceTime().getJavaInstant();
		}
		if (value.getServerTime() != null) {
			return value.getServerTime().getJavaInstant();
		}
		return Instant.now();
	}

	private void emit(String equipmentId) {
		cache.snapshot(equipmentId).ifPresent(snapshot -> {
			equipment.accept(
					OpcUaValueMapper.toMessage(equipmentId, snapshot.values(), snapshot.sourceTime()),
					EquipmentSource.OPC_UA);
			sensors.accept(sensorSamples(equipmentId, snapshot));
		});
	}

	/**
	 * 상태 변수가 아닌 수치 변수를 센서 표본으로 넘긴다. 채널 이름을 코드에 박지 않으므로
	 * 설비가 채널을 늘리면 브라우징이 찾아오고 여기로 그대로 흘러간다.
	 */
	private static List<SensorSample> sensorSamples(String equipmentId,
			EquipmentValueCache.Snapshot snapshot) {
		Map<String, Double> channels = OpcUaValueMapper.sensorChannels(snapshot.values());
		if (channels.isEmpty()) {
			return List.of();
		}
		OffsetDateTime at = snapshot.sourceTime() == null ? OffsetDateTime.now()
				: snapshot.sourceTime().atOffset(ZoneOffset.UTC);
		List<SensorSample> samples = new ArrayList<>(channels.size());
		channels.forEach((channel, value) -> samples
				.add(new SensorSample(equipmentId, channel, value, at, EquipmentSource.OPC_UA)));
		return samples;
	}

	private void deleteSubscriptionQuietly() {
		OpcUaSubscription current = subscription;
		subscription = null;
		if (current == null) {
			return;
		}
		try {
			current.delete();
		}
		catch (Exception e) {
			log.debug("OPC UA 구독 삭제 실패(무시): {}", e.getMessage());
		}
	}

	private void closeQuietly() {
		deleteSubscriptionQuietly();
		OpcUaClient current = client;
		client = null;
		if (current == null) {
			return;
		}
		try {
			current.disconnect();
		}
		catch (Exception e) {
			log.debug("OPC UA 연결 종료 실패(무시): {}", e.getMessage());
		}
	}
}
