package com.arpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })
@EnableScheduling
@EnableJpaAuditing
@EntityScan(basePackages = "com.arpay.entity")
public class ArpayNotificationsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ArpayNotificationsApplication.class, args);
	}

}
