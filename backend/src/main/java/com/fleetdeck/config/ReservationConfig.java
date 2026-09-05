package com.fleetdeck.config;

import com.fleetdeck.robot.RobotReservations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReservationConfig {

	/** 예약 TTL 은 미션 회수 기준과 같은 값을 쓴다. 예약만 먼저 풀려 중복 배정되는 일을 막는다. */
	@Bean
	public RobotReservations robotReservations(FleetdeckProperties props) {
		return new RobotReservations(props.dispatch().staleAfter());
	}
}
