package com.abhishekraj.BookMySeat.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

	@Bean
	public PaymentGateway paymentGateway(PaymentProperties properties) {
		return new RazorpayGateway(properties.razorpay());
	}
}
