package com.fleetdeck.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * MQTT 인바운드(로봇 state, 설비 state 구독)와 아웃바운드(order, instantActions 발행) 채널.
 */
@Configuration
public class MqttConfig {

	public static final String INBOUND_CHANNEL = "mqttInbound";
	public static final String OUTBOUND_CHANNEL = "mqttOutbound";

	static final String ROBOT_STATE_TOPIC = "uagv/v2/+/+/state";
	static final String EQUIPMENT_STATE_TOPIC = "fleetdeck/equipment/+/state";

	@Bean
	public MqttPahoClientFactory mqttClientFactory(FleetdeckProperties props) {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setServerURIs(new String[] { props.mqtt().url() });
		options.setAutomaticReconnect(true);
		options.setCleanSession(true);
		options.setConnectionTimeout(10);
		options.setKeepAliveInterval(30);

		DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
		factory.setConnectionOptions(options);
		return factory;
	}

	@Bean(name = INBOUND_CHANNEL)
	public MessageChannel mqttInbound() {
		return new DirectChannel();
	}

	@Bean(name = OUTBOUND_CHANNEL)
	public MessageChannel mqttOutbound() {
		return new DirectChannel();
	}

	@Bean
	public MessageProducer mqttInboundAdapter(MqttPahoClientFactory factory, FleetdeckProperties props) {
		MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
				props.mqtt().clientId() + "-in", factory, ROBOT_STATE_TOPIC, EQUIPMENT_STATE_TOPIC);
		adapter.setConverter(new DefaultPahoMessageConverter());
		adapter.setQos(0);
		adapter.setOutputChannel(mqttInbound());
		return adapter;
	}

	@Bean
	@ServiceActivator(inputChannel = OUTBOUND_CHANNEL)
	public MessageHandler mqttOutboundHandler(MqttPahoClientFactory factory, FleetdeckProperties props) {
		MqttPahoMessageHandler handler = new MqttPahoMessageHandler(props.mqtt().clientId() + "-out", factory);
		handler.setAsync(true);
		handler.setDefaultQos(1);
		return handler;
	}
}
