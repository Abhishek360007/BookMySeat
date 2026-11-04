package com.abhishekraj.BookMySeat.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

	@Bean
	public PaymentGateway paymentGateway(PaymentProperties properties, RestClient.Builder restClientBuilder) {
		return new RazorpayGateway(restClientBuilder, properties.razorpay());
	}
}
