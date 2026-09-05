package com.fleetdeck.integration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** 통합 테스트에서 로봇 역할을 하는 MQTT 클라이언트. 상태를 올리고 order 를 받아본다. */
final class TestMqtt implements AutoCloseable {

	record Received(String topic, String payload) {
	}

	private final MqttClient client;
	private final List<Received> received = new CopyOnWriteArrayList<>();

	TestMqtt(String url, String clientId) throws MqttException {
		this.client = new MqttClient(url, clientId, new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(true);
		options.setConnectionTimeout(10);
		client.setCallback(new MqttCallback() {
			@Override
			public void connectionLost(Throwable cause) {
			}

			@Override
			public void messageArrived(String topic, MqttMessage message) {
				received.add(new Received(topic, new String(message.getPayload())));
			}

			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
			}
		});
		client.connect(options);
	}

	void subscribe(String topicFilter) throws MqttException {
		client.subscribe(topicFilter, 1);
	}

	void publish(String topic, String payload) throws MqttException {
		client.publish(topic, new MqttMessage(payload.getBytes()));
	}

	List<Received> received() {
		return List.copyOf(received);
	}

	void clearReceived() {
		received.clear();
	}

	@Override
	public void close() throws MqttException {
		if (client.isConnected()) {
			client.disconnect();
		}
		client.close();
	}
}
